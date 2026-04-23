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
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.stereotype.Component;


/**
 * @author  Neil Griffin
 */
@Component
public class HttpResponseFactoryImpl implements HttpResponseFactory {

	public String getHttpResponseBody(HttpRequest httpRequest) throws IOException {

		System.err.println("!@#$ httpRequest=" + httpRequest);

		HttpResponse<String> httpResponse = getHttpResponse(httpRequest);

		int statusCode = httpResponse.statusCode();

		if (statusCode >= 400) {
			throw new IOException("HTTP request failed with status code: " + statusCode + "body: " +
				httpResponse.body());
		}

		return httpResponse.body();
	}

	protected HttpResponse<String> getHttpResponse(HttpRequest httpRequest) throws IOException {

		try {
			HttpClient httpClient = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).version(
					HttpClient.Version.HTTP_1_1).build();

			return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException interruptedException) {
			throw new IOException(interruptedException);
		}
	}
}
