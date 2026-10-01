import os
import sys
import json
import base64
import io
import re
import time
import datetime
import threading
import requests
from collections import deque
from flask import Flask, request, jsonify
from pypdf import PdfReader

app = Flask(__name__)

# --- DYNAMIC CONFIGURATION VIA ENVIRONMENT VARIABLES ---
LIFERAY_BASE_URL = os.getenv("LIFERAY_BASE_URL", "https://webserver-lctdemoibaihub-prd.lfr.cloud")
VERIFY_SSL_ENV = os.getenv("LIFERAY_VERIFY_SSL", "False").lower()
LIFERAY_VERIFY_SSL = VERIFY_SSL_ENV in ("true", "1", "yes")
AIHUB_AGENT_ERC = os.getenv("AIHUB_AGENT_ERC", "JMR_AGENT_PRESS_RELEASES")
AIHUB_SERVICE_URL = os.getenv("AIHUB_SERVICE_URL", "https://ai.hub.liferay.com")

if not LIFERAY_VERIFY_SSL:
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# --- IN-MEMORY TRACE BUFFER (LAST 100 ACTIVITIES) ---
RECENT_LOGS = deque(maxlen=100)

def trace(level, category, message, data=None):
    """
    Records traces with ISO timestamp, immediate stdout flush, and in-memory buffer accessible via HTTP.
    """
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    log_entry = {
        "timestamp": timestamp,
        "level": level,
        "category": category,
        "message": message,
        "data": data
    }
    RECENT_LOGS.append(log_entry)
    
    icon = {
        "INFO": "ℹ️",
        "WARN": "⚠️",
        "ERROR": "❌",
        "SUCCESS": "✅",
        "STEP": "🚀"
    }.get(level, "🔹")
    
    formatted_msg = f"[{timestamp}] {icon} [{category}] {message}"
    print(formatted_msg, flush=True)
    if data and level in ("ERROR", "WARN"):
        print(f"    Detail: {json.dumps(data, ensure_ascii=False)}", flush=True)

def extract_entry_id(payload):
    """
    Dynamically extracts the document identifier from the Object Action payload.
    No hardcoded IDs: inspects primaryKey, id, or Liferay container wrappers.
    """
    if not payload or not isinstance(payload, dict):
        return None

    # 1. Direct attributes
    if payload.get("primaryKey"):
        return str(payload["primaryKey"])
    if payload.get("id"):
        return str(payload["id"])

    # 2. Common Object Action containers (objectEntry, entryDTO, dto)
    for container_key in ("objectEntry", "entryDTO", "dto"):
        container = payload.get(container_key)
        if isinstance(container, str):
            try:
                container = json.loads(container)
            except Exception:
                continue
        if isinstance(container, dict):
            if container.get("id"):
                return str(container["id"])
            if container.get("primaryKey"):
                return str(container["primaryKey"])

    return None

def format_press_release_for_llm(raw_text):
    """
    Normalizes press release text into continuous semantic HTML strictly on a single line
    (no literal '\n' or '\r' line breaks and no unescaped double quotes).
    This ensures safe interpolation into JSON templates such as:
    {"title": "...", "body": "{{text}}"}
    """
    if not raw_text:
        return ""

    # 1. Collapse all lines into a single text flow
    lines = [l.strip() for l in raw_text.splitlines() if l.strip()]
    joined = " ".join(lines)

    # 2. Replace double quotes with chevron quotation marks (« ») to avoid breaking JSON strings
    joined = re.sub(r'\"([^\"]*)\"', r'«\1»', joined)
    joined = joined.replace('\"', '«')

    # 3. Structure metadata and headers with HTML tags
    joined = re.sub(r'\s*((?:AGENCIA|AGENCY)\s*:\s*)', r'<p><strong>\1</strong> ', joined, flags=re.IGNORECASE)
    joined = re.sub(r'\s*((?:FECHA|DATE)\s*:\s*)', r'</p><p><strong>\1</strong> ', joined, flags=re.IGNORECASE)
    joined = re.sub(r'\s*((?:ENLACE|LINK|URL)\s*:\s*)', r'</p><p><strong>\1</strong> ', joined, flags=re.IGNORECASE)

    # 4. Bullet points and lists
    joined = re.sub(r'\s*((?:Aspectos destacados|Highlights)[^:]*:?)\s*●\s*', r'</p><p><strong>\1</strong></p><ul><li>', joined, flags=re.IGNORECASE)
    joined = re.sub(r'\s*●\s*', r'</li><li>', joined)
    if '<ul><li>' in joined and '</li></ul>' not in joined:
        if re.search(r'(Contacto de prensa|Press contact)', joined, re.IGNORECASE):
            joined = re.sub(r'\s*((?:Contacto de prensa|Press contact):?)', r'</li></ul><p><strong>\1</strong> ', joined, flags=re.IGNORECASE)
        else:
            joined += '</li></ul>'

    # 5. Punctuation cleanup and removal of control characters
    joined = re.sub(r'\s+([,.:;])', r'\1', joined)
    joined = joined.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    joined = re.sub(r' {2,}', ' ', joined).strip()

    # Ensure enclosing tags
    if not joined.endswith('</p>') and not joined.endswith('</ul>'):
        joined += '</p>'
    if not joined.startswith('<p>'):
        joined = '<p>' + joined

    return joined

def split_press_releases(full_text):
    """
    Splits the document text into individual press releases.
    Detects delimiters such as '===' separators or start patterns like 'AGENCIA:' / 'AGENCY:'.
    """
    if not full_text:
        return []

    # 1. Explicit separators (e.g. lines of === or ---)
    if re.search(r'(?m)^[=\-]{3,}\s*$', full_text):
        chunks = re.split(r'(?m)^[=\-]{3,}\s*$', full_text)
        items = [c.strip() for c in chunks if c.strip()]
        if len(items) > 1:
            return items

    # 2. Split by start pattern (AGENCIA: or AGENCY:)
    if re.search(r'(?:AGENCIA|AGENCY)\s*:', full_text, flags=re.IGNORECASE):
        chunks = re.split(r'(?=(?:^|\n)\s*(?:AGENCIA|AGENCY)\s*:)', full_text, flags=re.IGNORECASE)
        items = [c.strip() for c in chunks if c.strip() and re.search(r'(?:AGENCIA|AGENCY)', c, re.IGNORECASE)]
        if len(items) > 1:
            return items

    # 3. Fallback: entire document as a single press release
    return [full_text.strip()]

def fetch_and_extract_pdf_text(entry_id, liferay_token, max_retries=10, retry_delay=2):
    """
    Step 2: Fetches the document from Liferay CMS using the dynamic ID and extracts plain text.
    Includes a retry mechanism with backoff since in DXP UI uploads,
    the event triggers before file attachment upload/association completes.
    """
    url = f"{LIFERAY_BASE_URL.rstrip('/')}/o/cms/basic-documents/{entry_id}?nestedFields=file.fileBase64,file.fileURL"
    headers = {
        "Authorization": f"Bearer {liferay_token}",
        "Accept": "application/json"
    }

    doc_data = None
    file_info = None

    for attempt in range(1, max_retries + 1):
        trace("INFO", "CMS_FETCH", f"[Attempt {attempt}/{max_retries}] Fetching document ID '{entry_id}'...")
        res = requests.get(url, headers=headers, verify=LIFERAY_VERIFY_SSL, timeout=30)
        if res.status_code != 200:
            auth_err = res.headers.get("WWW-Authenticate", "")
            err_msg = f"HTTP {res.status_code} when querying document {entry_id}. Response: '{res.text.strip()}'. WWW-Authenticate: '{auth_err}'"
            trace("ERROR", "CMS_FETCH", err_msg)
            raise Exception(err_msg)

        doc_data = res.json()
        file_candidate = doc_data.get("file")

        # Check if attachment is already linked and contains data
        if file_candidate and isinstance(file_candidate, dict) and (file_candidate.get("fileBase64") or file_candidate.get("id") or file_candidate.get("link")):
            file_info = file_candidate
            trace("SUCCESS", "CMS_FETCH", f"'file' attachment successfully detected on attempt {attempt}.")
            break

        # If document is in draft state (code 2) and has no file, do not block with prolonged retries
        status_code = doc_data.get("status", {}).get("code")
        if status_code == 2 and attempt >= 2:
            trace("INFO", "CMS_FETCH", f"Document ID {entry_id} is a newly created draft without a file yet. It will be processed when published (On After Update event).")
            return None

        trace("WARN", "CMS_FETCH", f"'file' field is not yet available. Waiting {retry_delay}s...")
        time.sleep(retry_delay)

    if not file_info or not isinstance(file_info, dict):
        trace("INFO", "CMS_FETCH", f"Document ID {entry_id} has no file attachment. Waiting for publication with file.")
        return None

    title = doc_data.get("title", f"Document_{entry_id}")
    file_name = file_info.get("name", "document.pdf")
    mime_type = file_info.get("mimeType", "")
    trace("INFO", "CMS_FETCH", f"Detected file: '{file_name}' ({mime_type})")

    # Retrieve binary: prioritize fileBase64, fallback to link.href / fileURL
    pdf_bytes = None
    if file_info.get("fileBase64"):
        trace("INFO", "CMS_FETCH", "Decoding binary from 'fileBase64'...")
        pdf_bytes = base64.b64decode(file_info["fileBase64"])
    else:
        # In Liferay CMS, the download path is located in link.href
        download_path = file_info.get("link", {}).get("href") or file_info.get("fileURL", "")
        if not download_path:
            raise Exception("Neither fileBase64 nor download URL was found in the attachment.")

        if not download_path.startswith("http"):
            download_url = f"{LIFERAY_BASE_URL.rstrip('/')}/{download_path.lstrip('/')}"
        else:
            download_url = download_path

        trace("INFO", "CMS_FETCH", f"Downloading binary from: {download_url}")
        dl_res = requests.get(download_url, headers=headers, verify=LIFERAY_VERIFY_SSL, timeout=45)
        if dl_res.status_code != 200:
            raise Exception(f"Error downloading binary (HTTP {dl_res.status_code}): {dl_res.text}")
        pdf_bytes = dl_res.content

    trace("INFO", "PDF_EXTRACT", f"Binary retrieved: {len(pdf_bytes)} bytes. Extracting text with pypdf...")

    reader = PdfReader(io.BytesIO(pdf_bytes))
    num_pages = len(reader.pages)
    pages_text = []
    for page in reader.pages:
        page_content = page.extract_text() or ""
        pages_text.append(page_content)

    full_text = "\n\n".join(pages_text).strip()
    trace("SUCCESS", "PDF_EXTRACT", f"Extraction completed for '{title}' (ID {entry_id}): {num_pages} pages, {len(full_text)} characters.")

    return {
        "id": entry_id,
        "title": title,
        "fileName": file_name,
        "pages": num_pages,
        "plainText": full_text
    }

def listen_sse_stream(sse_res, note_index, total_notes, max_duration=90):
    """
    Listens on a secondary daemon thread to all SSE events emitted by AI Hub for the specified press release.
    Logs any LLM output, agent progress, or error message.
    """
    start_time = time.time()
    try:
        for line in sse_res.iter_lines(decode_unicode=True):
            if time.time() - start_time > max_duration:
                trace("INFO", "AIHUB_STREAM", f"[Note {note_index}/{total_notes}] End of SSE listen window (90s).")
                break
            if not line:
                continue
            line_str = str(line).strip()
            if not line_str:
                continue

            # Log every line sent by AI Hub (events, data, and errors)
            trace("INFO", "AIHUB_STREAM", f"[Note {note_index}/{total_notes}] {line_str}")

            if "[DONE]" in line_str or '"status":"completed"' in line_str or '"status":"failed"' in line_str:
                break
    except Exception as e:
        trace("INFO", "AIHUB_STREAM", f"[Note {note_index}/{total_notes}] SSE stream closed: {e}")
    finally:
        try:
            sse_res.close()
        except Exception:
            pass

def trigger_agent_for_note(agent_erc, service_url, access_token, user_token, context_map, note_index, total_notes):
    """
    Step 3: Obtains sseEventSinkKey, triggers the agent in AI Hub, and delegates
    stream listening to the background so as not to block processing of subsequent notes.
    """
    headers = {"Authorization": f"Bearer {access_token}"}
    subscribe_url = f"{service_url.rstrip('/')}/o/ai-hub/v1.0/agent-instances/subscribe"

    trace("INFO", "AIHUB_SSE", f"[Note {note_index}/{total_notes}] Subscribing to SSE channel: {subscribe_url}")
    # Timeout: 10s to connect, 90s for LLM inference streaming read
    sse_res = requests.get(subscribe_url, headers=headers, stream=True, verify=LIFERAY_VERIFY_SSL, timeout=(10, 90))
    if sse_res.status_code != 200:
        raise Exception(f"Error in SSE subscription (HTTP {sse_res.status_code}): {sse_res.text}")

    # 1. Read first message to extract sseEventSinkKey
    sse_event_sink_key = None
    for line in sse_res.iter_lines():
        if not line:
            continue
        line_decoded = line.decode('utf-8', errors='ignore').strip()
        if not line_decoded or not line_decoded.startswith("data:"):
            continue

        data_content = line_decoded[5:].strip()
        sse_event_sink_key = data_content
        try:
            parsed_key = json.loads(data_content)
            sse_event_sink_key = parsed_key.get("sseEventSinkKey", parsed_key.get("id", data_content))
        except Exception:
            pass
        break

    if not sse_event_sink_key:
        try:
            sse_res.close()
        except Exception:
            pass
        raise Exception("Failed to obtain sseEventSinkKey from AI Hub SSE channel.")

    trace("INFO", "AIHUB_SSE", f"[Note {note_index}/{total_notes}] SSE handshake obtained: {sse_event_sink_key}")

    # 2. Trigger Agent execution in AI Hub
    trigger_url = f"{service_url.rstrip('/')}/o/ai-hub/v1.0/agent-instances"
    trigger_headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json",
        "Liferay-AI-Hub-Cell-On-Behalf-Of": user_token
    }

    trigger_body = {
        "agentDefinitionExternalReferenceCode": agent_erc,
        "context": context_map,
        "sseEventSinkKey": sse_event_sink_key
    }

    trace("STEP", "AIHUB_TRIGGER", f"[Note {note_index}/{total_notes}] Sending POST to {trigger_url} (Agent: {agent_erc})")
    trace("INFO", "AIHUB_PAYLOAD", f"[Note {note_index}/{total_notes}] Payload sent to AI Hub:\n{json.dumps(trigger_body, indent=2, ensure_ascii=False)}")

    post_res = requests.post(trigger_url, headers=trigger_headers, json=trigger_body, verify=LIFERAY_VERIFY_SSL, timeout=20)
    if post_res.status_code not in (200, 201):
        try:
            sse_res.close()
        except Exception:
            pass
        raise Exception(f"Error starting agent (HTTP {post_res.status_code}): {post_res.text}")

    trace("SUCCESS", "AIHUB_TRIGGER", f"[Note {note_index}/{total_notes}] Agent started successfully in AI Hub (HTTP {post_res.status_code}).")

    # 3. Start listener on daemon thread to avoid blocking subsequent notes
    listener = threading.Thread(
        target=listen_sse_stream,
        args=(sse_res, note_index, total_notes),
        daemon=True
    )
    listener.start()

def async_pipeline(entry_id, liferay_token, payload):
    """
    Complete asynchronous pipeline with step-by-step trace monitoring.
    """
    try:
        trace("STEP", "PIPELINE_START", f"Starting pipeline for Document ID: {entry_id}")

        # 1. Full text extraction
        doc_result = fetch_and_extract_pdf_text(entry_id, liferay_token)
        if not doc_result:
            trace("INFO", "PIPELINE", f"Pipeline finished for Document ID: {entry_id} (no file to process).")
            return

        plain_text = doc_result.get("plainText", "")

        if not plain_text:
            trace("WARN", "PIPELINE", f"No text extracted from document ID {entry_id}. Pipeline finished.")
            return

        # 2. Segmentation into individual press releases
        press_releases = split_press_releases(plain_text)
        total_notes = len(press_releases)
        trace("INFO", "SEGMENTATION", f"Identified {total_notes} press release(s) in document.")

        # 3. Handshake with Liferay AI Hub Cell
        handshake_url = f"{LIFERAY_BASE_URL.rstrip('/')}/o/ai-hub-cell/v1.0/authorization-tokens"
        trace("INFO", "AIHUB_AUTH", f"Requesting authorization tokens from: {handshake_url}")
        
        handshake_res = requests.post(
            handshake_url,
            headers={"Authorization": f"Bearer {liferay_token}", "Accept": "application/json"},
            verify=LIFERAY_VERIFY_SSL,
            timeout=15
        )

        if handshake_res.status_code != 200:
            trace("ERROR", "AIHUB_AUTH", f"AI Hub handshake failure (HTTP {handshake_res.status_code}): {handshake_res.text}")
            return

        auth_data = handshake_res.json()
        access_token = auth_data.get("accessToken")
        user_token = auth_data.get("userToken")
        service_url = auth_data.get("serviceURL") or AIHUB_SERVICE_URL

        if not access_token or not user_token:
            trace("ERROR", "AIHUB_AUTH", "Handshake response missing valid tokens.")
            return

        trace("SUCCESS", "AIHUB_AUTH", f"Tokens obtained successfully. AI Hub Cell: {service_url}")

        # 4. Determine agent ERC
        agent_erc = AIHUB_AGENT_ERC
        if isinstance(payload.get("parameters"), dict) and payload["parameters"].get("agentERC"):
            agent_erc = payload["parameters"]["agentERC"]

        # 5. Loop: Invoke agent for each press release
        for idx, raw_note_text in enumerate(press_releases, start=1):
            formatted_text = format_press_release_for_llm(raw_note_text)
            trace("STEP", "AIHUB_LOOP", f"Executing call {idx} of {total_notes} to agent '{agent_erc}' (Note size: {len(formatted_text)} chars)")
            context_map = {
                "text": formatted_text
            }

            try:
                trigger_agent_for_note(
                    agent_erc=agent_erc,
                    service_url=service_url,
                    access_token=access_token,
                    user_token=user_token,
                    context_map=context_map,
                    note_index=idx,
                    total_notes=total_notes
                )
                trace("SUCCESS", "AIHUB_LOOP", f"Note {idx}/{total_notes} successfully sent and initiated in AI Hub.")
                if idx < total_notes:
                    time.sleep(1)
            except Exception as loop_err:
                trace("ERROR", "AIHUB_LOOP", f"Error during call {idx}/{total_notes} to AI Hub: {loop_err}")

        trace("SUCCESS", "PIPELINE_COMPLETE", f"Pipeline completed for all notes ({total_notes}) of Document ID {entry_id}.")

    except Exception as e:
        trace("ERROR", "PIPELINE_FATAL", f"Fatal error processing document {entry_id}: {e}")

@app.route('/ready', methods=['GET'])
def ready_endpoint():
    """
    Liveness/readiness endpoint for Liferay Cloud / Kubernetes.
    """
    return jsonify({"status": "ready"}), 200

@app.route('/logs', methods=['GET'])
def get_logs_endpoint():
    """
    Endpoint to inspect recent traces and monitor AI Hub calls from browser or curl.
    """
    return jsonify({
        "serverTime": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "totalLogs": len(RECENT_LOGS),
        "logs": list(RECENT_LOGS)
    }), 200

@app.route('/object/action/trigger-agent', methods=['POST'])
def handle_object_action():
    """
    Endpoint invoked by Liferay Object Action.
    """
    payload = request.json
    if not payload:
        trace("WARN", "WEBHOOK", "Request received without JSON payload.")
        return jsonify({"error": "No payload received"}), 400

    entry_id = extract_entry_id(payload)
    if not entry_id:
        trace("WARN", "WEBHOOK", f"Payload received without identifiable ID. Keys received: {list(payload.keys())}")
        return jsonify({"error": "No entry ID found in payload"}), 400

    trace("SUCCESS", "WEBHOOK", f"Event received from Liferay for Document ID: {entry_id}")

    # Obtain dynamic OAuth2 token provided by Liferay
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        trace("ERROR", "WEBHOOK", "Missing 'Authorization: Bearer <token>' header.")
        return jsonify({"error": "Missing authorization header"}), 401

    liferay_token = auth_header.split(" ")[1]

    # Start background pipeline to avoid delaying Liferay's webhook response
    bg_thread = threading.Thread(target=async_pipeline, args=(entry_id, liferay_token, payload))
    bg_thread.start()

    trace("INFO", "WEBHOOK", f"200 response sent to Liferay. Background pipeline started for doc {entry_id}.")

    return jsonify({
        "status": "success",
        "message": f"Document ID {entry_id} processing loop initiated asynchronously."
    }), 200

if __name__ == '__main__':
    port = int(os.getenv("PORT", 8081))
    trace("INFO", "STARTUP", f"Object Action server listening on port {port}")
    app.run(host='0.0.0.0', port=port)
