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
package com.liferay.demo.cmschat.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;

import com.liferay.demo.cmschat.service.ParamBuilder;


/**
 * @author  Neil Griffin
 */
@Component
public class LiferayHttpRequestFactoryImpl implements LiferayHttpRequestFactory {

	@Value("${liferay.headless.api.base.url}")
	private String liferayHeadlessApiBaseURL;

	@Override
	public HttpRequest newLiferayGetRequest(String apiPath, String authToken) throws IOException {

		try {
			return HttpRequest.newBuilder().uri(new URI(liferayHeadlessApiBaseURL + apiPath)).setHeader("Authorization",
					"Bearer " + authToken).setHeader("Content-Type", "application/json").GET().build();
		}
		catch (URISyntaxException uriSyntaxException) {
			uriSyntaxException.printStackTrace();
			throw new IOException(uriSyntaxException);
		}
	}

	@Override
	public HttpRequest newLiferayPostRequest(String apiPath, String authToken, Map<String, String> urlParameters)
		throws IOException {

		String searchContextString = "{" +
			"  \"attributes\": {" +
			"    \"search.experiences.blueprint.external.reference.code\": \"CMSCHAT-BLUEPRINT\"" +
			"  }" +
			"}";

		//HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString("{}");
		HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(searchContextString);

		try {
			ParamBuilder paramBuilder = ParamBuilder.newBuilder();

			for (Map.Entry<String, String> entry : urlParameters.entrySet()) {
				paramBuilder.add(entry.getKey(), entry.getValue());
			}

			String queryString = paramBuilder.build(ParamBuilder.Type.FORM_URLENCODED);
			URI uri = new URI(liferayHeadlessApiBaseURL + apiPath + "?" + queryString);

			return HttpRequest.newBuilder().uri(uri).setHeader("Authorization", "Bearer " + authToken).setHeader(
					"Content-Type", "application/json").POST(bodyPublisher).build();
		}
		catch (URISyntaxException uriSyntaxException) {
			uriSyntaxException.printStackTrace();
			throw new IOException(uriSyntaxException);
		}
	}
}
