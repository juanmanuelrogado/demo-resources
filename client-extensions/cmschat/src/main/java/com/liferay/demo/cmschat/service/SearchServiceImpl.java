/**
 * Copyright (c) 2000-2025 Liferay, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.liferay.demo.cmschat.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.stereotype.Component;

import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.dto.SearchResult;
import com.liferay.demo.cmschat.http.HttpResponseFactory;
import com.liferay.demo.cmschat.http.LiferayHttpRequestFactory;

import org.springframework.web.util.UriComponentsBuilder;
import org.xml.sax.SAXException;

/**
 * @author  Neil Griffin
 */
@Component
public class SearchServiceImpl implements SearchService {

	@Autowired
	private HttpResponseFactory _httpResponseFactory;

	@Autowired
	private LiferayHttpRequestFactory _liferayHttpRequestFactory;

	@Value("${cms2.blog.display.page.template.id}")
	private String _cms2BlogDisplayPageTemplateId;

	@Value("${cms2.press.release.display.page.template.id}")
	private String _cms2PressReleaseDisplayPageTemplateId;

	@Value("${cms2.display.page.base.url}")
	private String _cms2DisplayPageBaseURL;

	private static String _downloadText(Jwt jwt, String url, int maxChars) throws IOException {

		System.err.println("!@#$ _downloadText url=" + url);
		System.err.println("!@#$ _downloadText maxChars=" + maxChars);
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();

		// Bearer token authentication
		headers.setBearerAuth(jwt.getTokenValue());

		// Build Basic Auth header manually
		// String auth = "test@liferay.com:test";
		// byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
		// String authHeader = "Basic " + new String(encodedAuth);
		// headers.set("Authorization", authHeader);

		HttpEntity<String> entity = new HttpEntity<>(headers);
		System.err.println("!@#$ entity=" + entity);
		ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class);

		System.err.println("!@#$ response=" + response);
		byte[] fileData = response.getBody();
		if (fileData == null) {
			throw new IOException("Failed to download file content from " + url);
		}

		String contentType = response.getHeaders().getContentType() != null
				? response.getHeaders().getContentType().toString()
				: "";

		// If the content-type indicates plain text, skip Tika and return raw string
		System.err.println("!@#$ contentType=" + contentType);

		if (contentType.contains("text/plain")) {
			return new String(fileData, StandardCharsets.UTF_8);
		}

		try (InputStream stream = new java.io.ByteArrayInputStream(fileData)) {

			AutoDetectParser parser = new AutoDetectParser();
			// 0 or -1 => no limit; BodyContentHandler(maxChars) truncates at limit
			BodyContentHandler handler = (maxChars > 0)
					? new BodyContentHandler(maxChars)
					: new BodyContentHandler(-1);

			Metadata metadata = new Metadata();
			ParseContext context = new ParseContext();

			try {
				parser.parse(stream, handler, metadata, context);
			} catch (WriteLimitReachedException e) {
				System.err.println("Truncated at " + maxChars + " chars.");
			}

			String text = handler.toString().trim();
			if (text.isEmpty()) {
				throw new RuntimeException("No text extracted. Ensure tika-parsers-standard-package is on the classpath.");
			}
			return text;

		} catch (IOException | SAXException | TikaException e) {
			throw new RuntimeException("Failed to parse file content", e);
		}
	}

	@Override
	public List<SearchResult> getSearchResults(Jwt jwt, String keywords, String blueprintExternalReferenceCode, String scope) throws IOException {
		Map<String, String> urlParameters = new HashMap<>();

		System.err.println("-----------------------");
		System.err.println("keywords=" + keywords);
		System.err.println("urlParameters=");
		System.err.println(urlParameters);

		String url = UriComponentsBuilder
			.fromUriString("/search/v1.0/search")
			.queryParam("blueprintExternalReferenceCode", blueprintExternalReferenceCode)
			// .queryParam("scope", scope)
			.queryParam("nestedFields", "embedded")
			.queryParam("search", keywords)
			.toUriString();

		System.err.println("url=" + url);
		System.err.println("-----------------------");

		boolean limitToFirstResult = keywords.toLowerCase().startsWith("summarize the");

		return _getSearchResults(jwt,
				_httpResponseFactory.getHttpResponseBody(
					_liferayHttpRequestFactory.newLiferayGetRequest(url, jwt.getTokenValue())
						), limitToFirstResult);
	}

	private Document _downloadDocument(Jwt jwt, String contentURL, int maxChars) throws IOException {


		System.err.println("!@#$ contentURL=" + contentURL);
		if (contentURL == null) {
			System.err.println("!@#$ contentURL is null, returning empty Document");
			return new Document();
		}

		if (contentURL.contains("/o/headless-delivery/v1.0/documents")) {
			// Extract the fileEntryId (the number after the last slash)
			String fileEntryId = contentURL.substring(contentURL.lastIndexOf('/') + 1);

			// Build the new URL
			// NOTE: does not work because it probably isn't a JWT-ified URL
			contentURL = contentURL.replaceAll("/o/headless-delivery/v1.0/documents/\\d+",
					"/c/document_library/get_file?fileEntryId=" + fileEntryId);

			System.err.println("!@#$ fixed the contentURL=" + contentURL);
		}

		System.err.println("!@#$ calling _downloadText");
		String _downloadedText = _downloadText(jwt, contentURL, maxChars);

		System.err.println("!@#$ _downloadedText=" + _downloadedText);
		return new Document(_downloadedText, contentURL);
	}

	private List<SearchResult> _getSearchResults(Jwt jwt, String json, boolean limitToFirstResult) throws IOException {
		List<SearchResult> searchResults = new ArrayList<>();
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			JsonNode rootNode = objectMapper.readTree(json);
			JsonNode itemsJsonNode = rootNode.path("items");

			System.err.println("!@#$ itemsJsonNode=" + itemsJsonNode);
			if (!itemsJsonNode.isArray()) {
				System.err.println("No search results found.");
				return searchResults;
			}
			System.err.println("!@#$ itemsJsonNode.size()=" + itemsJsonNode.size());

			System.err.println("!@#$ limitToFirstResult=" + limitToFirstResult);
			// 128_000 is the approximate upper bound of ChatGPT tokens for a single request
			// 80_000 is a safe margin for model + leaving room for the system prompt
			int remainingChars = 80_000;

			for (JsonNode itemJsonNode : itemsJsonNode) {

				int totalResults = searchResults.size();

				System.err.println("!@#$ CUR totalResults=" + totalResults);
				if ((limitToFirstResult && (totalResults == 1)) || (remainingChars <= 1024)) {

					System.err.println("Limiting to first result only, not three results");

					// Stop after 1 search results since it's a summary request
					break;
				}

				if ((totalResults == 3) || (remainingChars <= 1024)) {

					System.err.println("Limiting to first three results");
					// Stop after 3 search results for now until relevance/score can be figured out better.
					break;
				}

				String title = itemJsonNode.path("title").asText();
				String description = itemJsonNode.path("description").asText();
				String contentURL = null;
				String itemURL = itemJsonNode.path("itemURL").asText();
				if ((itemURL != null) && (itemURL.trim().length() == 0)) {
					itemURL = _cms2DisplayPageBaseURL;
				}
				JsonNode embeddedJsonNode = itemJsonNode.path("embedded");
				SearchResult.Type type = _getType(embeddedJsonNode);
				if ((embeddedJsonNode != null) && (embeddedJsonNode.size() > 0)) {
					System.err.println("!@#$ embeddedJsonNode.size()=" + embeddedJsonNode.size());
					type = _getType(embeddedJsonNode);
				}
				else {
					String entryClassName = itemJsonNode.path("entryClassName").asText();
					if ("com.liferay.document.library.kernel.model.DLFileEntry".equals(entryClassName)) {
						type = SearchResult.Type.DOCUMENT;
					}
				}

				String contentText = null;
				String relativeContentURL = null;
				boolean addSearchResult = false;

				switch (type) {

				case ARTICLE: {
					addSearchResult = true;

					JsonNode contentFields = embeddedJsonNode.path("contentFields");

					if (contentFields.isArray()) {
						StringBuilder sb = new StringBuilder();

						for (int i = 0; i < contentFields.size(); i++) {
							JsonNode contentField = contentFields.get(i);

							if ("string".equals(contentField.path("dataType"))) {
								JsonNode contentFieldValue = contentField.path("contentFieldValue");
								sb.append(contentFieldValue.path("data").asText());
								sb.append("\n");
							}
						}

						String text = sb.toString();
						contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;

						System.err.println("embeddedJsonNode.path('siteId').asInt()=" + embeddedJsonNode.path("siteId").asInt());

						relativeContentURL = "/web/guest/w/" + embeddedJsonNode.path("friendlyUrlPath").asText();
						contentURL = _relative2AbsoluteURL(itemURL, relativeContentURL);
					}

					break;
				}

				case BLOG: {
					addSearchResult = true;
					String rawHtml = embeddedJsonNode.path("articleBody").asText();
					String text = _html2Text(rawHtml);
					contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;
					relativeContentURL = "/web/guest/b/" + embeddedJsonNode.path("friendlyUrlPath").asText();
					contentURL = _relative2AbsoluteURL(itemURL, relativeContentURL);

					break;
				}

				case CMS2_BLOG: {
					addSearchResult = true;
					String rawHtml = embeddedJsonNode.path("contentRawText").asText();
					String text = _html2Text(rawHtml);
					contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;
					relativeContentURL = "/e/blog/" + _cms2BlogDisplayPageTemplateId + "/" + embeddedJsonNode.path("id").asText();
					System.err.println("!@#$ HINT relativeContentURL=" + relativeContentURL);
					contentURL = _relative2AbsoluteURL(itemURL, relativeContentURL);
					System.err.println("!@#$ HINT contentURL=" + contentURL);

					break;
				}

				case CMS2_Press_Release: {
					addSearchResult = true;
					String rawHtml = embeddedJsonNode.path("contentRawText").asText();
					String text = _html2Text(rawHtml);
					contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;
					System.err.println("!@#$ HINT relativeContentURL=" + relativeContentURL);
					relativeContentURL = "/e/press-release/" + _cms2PressReleaseDisplayPageTemplateId + "/" + embeddedJsonNode.path("id").asText();
					contentURL = _relative2AbsoluteURL(itemURL, relativeContentURL);
					System.err.println("!@#$ HINT contentURL=" + contentURL);
					break;
				}

				case DOCUMENT: {
					addSearchResult = true;

					String encodingFormat = embeddedJsonNode.path("encodingFormat").asText();

					if ("application/vnd+liferay.video.external.shortcut+html".equals(encodingFormat)) {
						contentText = description;
						relativeContentURL = "/web/guest/d/" + embeddedJsonNode.path("friendlyUrlPath").asText();
						contentURL = _relative2AbsoluteURL(itemURL, relativeContentURL);
					}
					else {
						String absoluteURL = itemURL;
						relativeContentURL = embeddedJsonNode.path("contentUrl").asText();
						if ((relativeContentURL != null) && !relativeContentURL.isBlank()) {
							absoluteURL = _relative2AbsoluteURL(itemURL, relativeContentURL);
						}
						Document document = _downloadDocument(jwt, absoluteURL, remainingChars);
						contentText = document.getContentText();
						contentURL = document.getURL();
					}

					break;
				}

				case CMS2_DOCUMENT: {
					addSearchResult = true;
					JsonNode fileJsonNode = embeddedJsonNode.get("file");
					if (fileJsonNode != null) {
						JsonNode linkJsonNode = fileJsonNode.get("link");
						if (linkJsonNode != null) {
							JsonNode hrefJsonNode = linkJsonNode.get("href");
							if (hrefJsonNode != null) {
								String href = hrefJsonNode.asText();

								System.err.println("!@#$ file href=" + href);
								int downloadIndex = href.indexOf("download=true");

								if (downloadIndex != -1) {
									System.err.println("!@#$ file href contains download=true");
									href = href.substring(
										0, downloadIndex + "download=true".length());
									System.err.println("!@#$ trimmed file href=" + href);
								}

								String absoluteURL = _relative2AbsoluteURL(itemURL, href);

								System.err.println("!@#$ absouluteURL=" + absoluteURL);

								Document document = _downloadDocument(
									jwt, absoluteURL, remainingChars);

								contentText = document.getContentText();
								contentURL = document.getURL();
							}
							else {
								System.err.println("!@#$ embeddedJsonNode.get('href') is null");
							}
						}
						else {
							System.err.println("!@#$ embeddedJsonNode.get('link') is null");
						}
					}
					else {
						System.err.println("!@#$ embeddedJsonNode.get('file') is null");
					}

					break;
				}

				}

				if (addSearchResult) {

					System.err.println("!@#$ Search Result: type=\"" + type.name() + "\" title=\"" + title + "\" description=\"" + description + "\" contentURL=\"" + contentURL + "\"" );

					searchResults.add(new SearchResult(title, description, itemURL, contentText, contentURL, type));

					remainingChars -= contentText.length();
				}
			}

		}
		catch (JsonProcessingException jsonProcessingException) {
			System.err.println("!@#$ Exception happened, gonna throw it: " + jsonProcessingException.getMessage());
			throw new IOException(jsonProcessingException);
		}

		System.err.println("!@#$ returning searchResults, size=" + searchResults.size());
		return searchResults;
	}

	private SearchResult.Type _getType(JsonNode embeddedJsonNode) {

		if (embeddedJsonNode.has("pageType")) {
			return SearchResult.Type.PAGE;
		}

		if (embeddedJsonNode.has("numberOfDocuments")) {
			return SearchResult.Type.FOLDER;
		}

		if (embeddedJsonNode.has("jobTitle")) {
			return SearchResult.Type.USER;
		}

		if (embeddedJsonNode.has("documentType")) {
			return SearchResult.Type.DOCUMENT;
		}

		if (embeddedJsonNode.has("headline")) {
			return SearchResult.Type.BLOG;
		}

		if (embeddedJsonNode.has("subtitle")) {
			if (embeddedJsonNode.has("actions")) {
				JsonNode actions = embeddedJsonNode.get("actions");
				if (actions.has("get-by-scope")) {
					JsonNode getByScope = actions.get("get-by-scope");
					if (getByScope.has("href")) {
						String href = getByScope.get("href").asText();
						if (href.contains("/pressreleases/")) {
							return SearchResult.Type.CMS2_Press_Release;
						}
					}
				}
			}
			System.err.println("!@#$ embeddedJsonNode CMS2_BLOG, value=" + embeddedJsonNode.toPrettyString());
			return SearchResult.Type.CMS2_BLOG;
		}

		if (embeddedJsonNode.has("file")) {
			return SearchResult.Type.CMS2_DOCUMENT;
		}

		if (embeddedJsonNode.has("contentStructureId")) {
			return SearchResult.Type.ARTICLE;
		}

		System.err.println("!@#$ embeddedJsonNode OTHER, value=" + embeddedJsonNode.toPrettyString());
		return SearchResult.Type.OTHER;
	}

	private String _html2Text(String html) throws IOException {
		Tika tika = new Tika();

		try {
			org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
			metadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, "text/html");

			return tika.parseToString(new ByteArrayInputStream(html.getBytes()), metadata);
		}
		catch (TikaException e) {
			throw new IOException(e);
		}
	}

	private String _relative2AbsoluteURL(String referenceURL, String relativeContentURL) {

		if ((referenceURL != null) && !referenceURL.isBlank() && (relativeContentURL != null) &&
				!relativeContentURL.isBlank()) {
			URI referenceURI = null;

			try {
				referenceURI = new URI(referenceURL);
			}
			catch (URISyntaxException e) {
				return null;
			}

			URI contentURI = referenceURI.resolve(relativeContentURL);

			return contentURI.toString();
		}

		return null;
	}

	private class Document {
		private String _contentText;
		private String _url;

		public Document() {
		}

		public Document(String contentText, String url) {
			_contentText = contentText;
			_url = url;
		}

		public String getContentText() {
			return _contentText;
		}

		public String getURL() {
			return _url;
		}
	}
}