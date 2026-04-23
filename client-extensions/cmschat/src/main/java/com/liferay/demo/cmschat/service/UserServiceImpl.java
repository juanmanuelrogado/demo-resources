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

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.http.HttpResponseFactory;
import com.liferay.demo.cmschat.http.LiferayHttpRequestFactory;


/**
 * @author  Neil Griffin
 */
@Component
public class UserServiceImpl implements UserService {

	@Autowired
	private LiferayHttpRequestFactory _liferayHttpRequestFactory;

	@Autowired
	private HttpResponseFactory _httpResponseFactory;

	@Override
	public String getJiraToken(Jwt jwt) throws IOException {
		return _getJiraToken(_httpResponseFactory.getHttpResponseBody(
					_liferayHttpRequestFactory.newLiferayGetRequest("/my-user-account", jwt.getTokenValue())));
	}

	private String _getJiraToken(String json) throws IOException {

		ObjectMapper objectMapper = new ObjectMapper();

		try {
			JsonNode rootNode = objectMapper.readTree(json);
			JsonNode customFieldsNode = rootNode.path("customFields");

			for (JsonNode customFieldNode : customFieldsNode) {
				String name = customFieldNode.path("name").asText();

				if ("Jira Token".equals(name)) {
					JsonNode dataNode = customFieldNode.path("customValue").path("data");
					String dataValue = dataNode.asText();

					return dataValue;
				}
			}

			throw new IOException("No value for Jira Token");

		}
		catch (JsonProcessingException jsonProcessingException) {
			throw new IOException(jsonProcessingException);
		}
	}
}
