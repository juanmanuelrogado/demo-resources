# System Architecture: Liferay CMS to AI Hub Press Release Ingestion

Technical architecture specification for the automated integration between **Liferay Headless CMS**, the **`aihubobjectaction` Client Extension microservice**, **Liferay AI Hub (Agentic Orchestration)**, and the **`PressRelease` Custom Object**.

---

## 1. Architectural Overview

The solution automates the ingestion, text extraction, segmentation, and semantic restructuring of press releases published as PDF documents in Liferay CMS into individual structured records within the `PressRelease` Object.

```text
┌─────────────────────────┐
│   Liferay Headless CMS  │  User publishes PDF document
└────────────┬────────────┘
             │
             │ Webhook: POST /object/action/trigger-agent (On After Update)
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Client Extension Microservice (aihubobjectaction)                      │
│                                                                        │
│  1. Webhook Handler       Receives event, returns HTTP 200, spawns bg   │
│  2. CMS Ingestion         Fetches document binary via Headless CMS API  │
│  3. Text Extractor        Extracts plain text with pypdf               │
│  4. Segmenter             Splits document into individual articles     │
│  5. Text Normalizer       Formats text to single-line semantic HTML    │
│  6. AI Hub Dispatcher     Obtains tokens & triggers agent instances    │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     │ POST /o/ai-hub/v1.0/agent-instances
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Liferay AI Hub (Agent: JMR_AGENT_PRESS_RELEASES)                       │
│                                                                        │
│  • Node 1 (LLM Inference): Extracts title, content, summary, metadata │
│  • Node 2 (HTTP Request):  POST to Liferay Headless Object API        │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     │ POST /o/c/pressreleases/scopes/{scopeKey}
                                     ▼
┌─────────────────────────┐
│ PressRelease Object     │  Individual structured entries in Asset Library
└─────────────────────────┘
```

---

## 2. Core Components and Responsibilities

### 2.1. Liferay Headless CMS
* **Role**: Primary document intake and storage.
* **Function**: Hosts the original PDF documents (compilations or single press releases).
* **Trigger Mechanism**: Configured with an Object Action firing on **`On After Update`** (when document status reaches `approved` and the file attachment is fully bound to the entry).

### 2.2. Object Action Client Extension (`aihubobjectaction`)
A Python Flask microservice packaged as a Liferay Client Extension running in Liferay Cloud / Kubernetes:

* **Webhook Receiver (`/object/action/trigger-agent`)**:
  * Validates the incoming payload and extracts the document primary key.
  * Validates the dynamic OAuth 2.0 bearer token injected by Liferay.
  * Immediately returns HTTP 200 to acknowledge the webhook, offloading the processing pipeline to a background worker thread.
* **CMS Document Client**:
  * Calls `GET /o/cms/basic-documents/{id}?nestedFields=file.fileBase64,file.fileURL` using the authorization token.
  * Retrieves the binary payload via Base64 or relative download link.
* **PDF Extraction & Segmentation Engine**:
  * Reads the binary stream in-memory with `pypdf` without writing to disk.
  * Scans text for delimiter patterns (e.g. `===`, `---`) or agency headings (`AGENCIA:` / `AGENCY:`) to isolate individual articles.
* **Semantic HTML Normalizer**:
  * Converts article paragraphs and lists into semantic HTML (`<p>`, `<strong>`, `<ul>`, `<li>`).
  * Replaces standard double quotes with typographical chevrons (`« »`) and removes literal line break control characters (`\n`, `\r`), producing a clean, single-line string for safe template interpolation.
* **AI Hub Client & SSE Stream Manager**:
  * Authenticates against `/o/ai-hub-cell/v1.0/authorization-tokens` to retrieve cell credentials (`accessToken`, `userToken`, and target cell URL).
  * Initiates an SSE handshake via `/o/ai-hub/v1.0/agent-instances/subscribe` to obtain the `sseEventSinkKey`.
  * Triggers the agent instance via `POST /o/ai-hub/v1.0/agent-instances` with the normalized text in the context map.
  * Reads the execution event stream asynchronously using background daemon threads.
* **Diagnostics & Observability**:
  * `/logs`: Exposes the most recent 100 in-memory execution traces with ISO timestamps and log levels.
  * `/ready`: Healthcheck endpoint serving Kubernetes and Liferay Cloud liveness/readiness probes.

### 2.3. Liferay AI Hub (`JMR_AGENT_PRESS_RELEASES`)
* **Role**: Autonomous agentic orchestrator.
* **Function**:
  * **LLM Step**: Utilizes Google Gemini to parse the unstructured text, extracting structured attributes: `title`, `content` (semantic HTML), `date` (YYYY-MM-DD), `summary`, `agency`, and `link`.
  * **REST Step**: Transmits the extracted payload directly to Liferay Headless Object API via `POST /o/c/pressreleases/scopes/{scopeKey}`.

### 2.4. Custom Object: `PressRelease`
* **Role**: Structured business entity storing individual published articles.
* **Scope**: Asset Library / Depot (`Knowledge Base` space).
* **Field Definitions**:
  * `title` (Text / String, max 255 chars): Primary headline.
  * `content` (Rich Text / Clob): HTML-formatted body.
  * `summary` (Text / String): Executive summary.
  * `date` (Date / String): Publication date.
  * `agency` (Text / String): Source news agency.
  * `link` (Text / String): Source URL.

---

## 3. Interfaces and Endpoints

| Resource | Protocol | Endpoint | Direction | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Object Action Webhook** | HTTP POST | `/object/action/trigger-agent` | Inbound (Liferay → Microservice) | Receives CMS publication events |
| **Audit Logs** | HTTP GET | `/logs` | Inbound (Admin → Microservice) | Reads in-memory trace buffer |
| **Healthcheck** | HTTP GET | `/ready` | Inbound (K8s → Microservice) | Container readiness probe |
| **CMS Basic Document** | HTTP GET | `/o/cms/basic-documents/{id}?nestedFields=file.fileBase64` | Outbound (Microservice → Liferay) | Fetches document binary and metadata |
| **AI Hub Handshake** | HTTP POST | `/o/ai-hub-cell/v1.0/authorization-tokens` | Outbound (Microservice → Liferay) | Obtains AI Hub cell tokens |
| **AI Hub Stream Subscribe**| HTTP GET | `/o/ai-hub/v1.0/agent-instances/subscribe` | Outbound (Microservice → AI Hub) | Subscribes to SSE execution stream |
| **AI Hub Agent Instance** | HTTP POST | `/o/ai-hub/v1.0/agent-instances` | Outbound (Microservice → AI Hub) | Dispatches agent execution task |
| **Object Entry Creation** | HTTP POST | `/o/c/pressreleases/scopes/{scopeKey}` | Outbound (AI Hub → Liferay) | Creates structured Press Release record |

---

## 4. Security and OAuth 2.0 Configuration

The microservice operates under a dedicated OAuth 2.0 Application (`aihubobjectactionoauth`) configured with the following minimum required scopes:

* `cmsbasicdocument.everything`: Read access to CMS documents and attachments.
* `c_pressrelease.everything`: Creation and update access to the custom `PressRelease` object.
* `Liferay.Headless.Object.everything`: Access to Liferay Headless Objects engine.
* `Liferay.Headless.Delivery.everything`: Read and query access to delivery content.
* `Liferay.AI.Hub.Cell.REST.everything`: Authorization to request tokens from AI Hub Cell.

---

## 5. Environment and Runtime Configuration

Configuration is managed dynamically via environment variables:

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8081` | Microservice listening port |
| `LIFERAY_BASE_URL` | `https://webserver-lctdemoibaihub-prd.lfr.cloud` | Host URL for Liferay DXP instance |
| `LIFERAY_VERIFY_SSL` | `False` | Enables or disables SSL certificate verification |
| `AIHUB_AGENT_ERC` | `JMR_AGENT_PRESS_RELEASES` | External Reference Code of the target AI Hub Agent |
| `AIHUB_SERVICE_URL` | `https://ai.hub.liferay.com` | Base URL of the Liferay AI Hub SaaS cluster |
