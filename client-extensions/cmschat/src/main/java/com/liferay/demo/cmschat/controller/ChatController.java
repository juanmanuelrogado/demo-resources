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
package com.liferay.demo.cmschat.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.commonmark.node.Link;
import org.commonmark.node.Node;

import org.commonmark.parser.Parser;

import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.AttributeProviderContext;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.dto.SearchResult;
import com.liferay.demo.cmschat.service.SearchService;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionAssistantMessageParam;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessageParam;
import com.openai.models.ChatCompletionSystemMessageParam;
import com.openai.models.ChatCompletionUserMessageParam;


/**
 * Author: Neil Griffin
 */
@RestController
@CrossOrigin
@RequestMapping("/cmschat")
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	@Autowired
	private SearchService _searchService;

	@Value("${openai.key}")
	private String _apiKeyOpenAI;

	private static String _convertToHtml(String markdown) {
		Parser parser = Parser.builder().build();
		Node document = parser.parse(markdown);
		HtmlRenderer renderer = HtmlRenderer.builder().attributeProviderFactory(new AttributeProviderFactory() {
					@Override
					public AttributeProvider create(AttributeProviderContext context) {
						return new LinkTargetAttributeProvider();
					}
				}).build();

		return renderer.render(document);
	}

	@PostMapping("/completions")
	String postCompletions(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest chatRequest) {

		log.debug("jwt={}", jwt);

		List<String> messages = chatRequest.getMessages();

		List<SearchResult> searchResults = new ArrayList<>();

		System.out.println("ChatRequest: messages=" + messages);

		if (messages.size() > 0) {

			// Search based on last message
			try {
				searchResults = _searchService.getSearchResults(jwt, messages.get(messages.size() - 1), chatRequest.getBlueprintExternalReferenceCode(),
						chatRequest.getScope());
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(_apiKeyOpenAI).build();

		ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder();

		paramsBuilder.addMessage(ChatCompletionMessageParam.ofChatCompletionSystemMessageParam(
				ChatCompletionSystemMessageParam.builder().role(ChatCompletionSystemMessageParam.Role.SYSTEM).content(
					ChatCompletionSystemMessageParam.Content.ofTextContent(
						"You are an assistant that incorporates the title, description, and text content provided within user messages into responses. The responses should also contain hyperlinked titles using download URLs provided within user messages."))
					.build()));

		for (int i = 0; i < messages.size(); i++) {
			String message = messages.get(i);
			String role = chatRequest.getRoles().get(i);

			if ("assistant".equals(role)) {
				paramsBuilder.addMessage(ChatCompletionMessageParam.ofChatCompletionAssistantMessageParam(
						ChatCompletionAssistantMessageParam.builder().role(
							ChatCompletionAssistantMessageParam.Role.ASSISTANT).content(
							ChatCompletionAssistantMessageParam.Content.ofTextContent(message)).build()));
			}
			else {
				paramsBuilder.addMessage(ChatCompletionMessageParam.ofChatCompletionUserMessageParam(
						ChatCompletionUserMessageParam.builder().role(ChatCompletionUserMessageParam.Role.USER).content(
							ChatCompletionUserMessageParam.Content.ofTextContent(
								"INSTRUCTION; exclusive keywords to highlight as bold+italic:\n" + message + "\n---\n"))
							.build()));

				for (SearchResult searchResult : searchResults) {
					StringBuilder msg = new StringBuilder();
					String title = searchResult.getTitle();
					String typeTitle = searchResult.getType().getTitle();
					log.debug("Search Result: type=\"" + typeTitle + "\" title=\"" + title + "\"");

					if ((title != null) && !title.isBlank()) {
						msg.append("INSTRUCTION; " + typeTitle + " title for inclusion in a reference:");
						msg.append("\n");
						msg.append(title);
						msg.append("\n---\n");
					}

					String contentURL = searchResult.getContentURL();

					if ((contentURL != null) && !contentURL.isBlank()) {
						msg.append("INSTRUCTION; " + typeTitle + " URL for inclusion in a reference:");
						msg.append("\n");
						msg.append(contentURL);
						msg.append("\n---\n");
					}

					String textContent = searchResult.getContentText();

					if ((textContent != null) && !textContent.isBlank()) {
						msg.append("INSTRUCTION; The main focus of the response should center around these keywords:");
						msg.append("\n");
						msg.append(message);
						msg.append("\n---\n");
						msg.append("INSTRUCTION; Use the following " + typeTitle +
							" text as a basis for responding to the keywords:");
						msg.append(textContent);
					}

					log.debug("OpenAI message=" + msg.toString());

					paramsBuilder.addMessage(ChatCompletionMessageParam.ofChatCompletionUserMessageParam(
							ChatCompletionUserMessageParam.builder().role(ChatCompletionUserMessageParam.Role.USER)
								.content(ChatCompletionUserMessageParam.Content.ofTextContent(msg.toString())).build()));
				}
			}
		}

		ChatCompletionCreateParams params = paramsBuilder.model("gpt-4o").build();

		// Log the parameters for debugging
		for (ChatCompletionMessageParam messageParam : params.messages()) {
			// System.err.println(messageParam.toString());
			// System.err.println("\n---\n");
		}

		ChatCompletion chatCompletion = client.chat().completions().create(params);

		ChatCompletion.Choice firstChoice = chatCompletion.choices().get(0);

		Optional<String> content = firstChoice.message().content();

		if (content.isPresent()) {
			ObjectMapper objectMapper = new ObjectMapper();

			try {
				return objectMapper.writeValueAsString(Map.of("assistant", _convertToHtml(content.get())));
			}
			catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}

		return "";
	}

	public static class ChatRequest {
		private List<String> messages;
		private List<String> roles;
		private String blueprintExternalReferenceCode;
		private String scope;

		public List<String> getMessages() {
			return messages;
		}

		public List<String> getRoles() {
			return roles;
		}

		public String getBlueprintExternalReferenceCode() {
			return blueprintExternalReferenceCode;
		}

		public String getScope() {
			return scope;
		}

		public void setMessages(List<String> messages) {
			this.messages = messages;
		}

		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

		public void setBlueprintExternalReferenceCode(String blueprintExternalReferenceCode) {
			this.blueprintExternalReferenceCode = blueprintExternalReferenceCode;
		}

		public void setScope(String scope) {
			this.scope = scope;
		}
	}

	static class LinkTargetAttributeProvider implements AttributeProvider {
		@Override
		public void setAttributes(Node node, String tagName, Map<String, String> attributes) {

			if (node instanceof Link) {
				attributes.put("target", "_blank");
			}
		}
	}
}
