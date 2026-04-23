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

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * @author  Neil Griffin
 */
public class ParamBuilder {

	/**
	 * @author  Neil Griffin
	 */
	public enum Type {

		FORM_URLENCODED, MULTIPART_FORM_DATA

	}

	private Map<String, String[]> _params;

	private ParamBuilder() {
		_params = new LinkedHashMap<>();
	}

	public static ParamBuilder newBuilder() {
		return new ParamBuilder();
	}

	public ParamBuilder add(String name, String value) {
		_params.put(name, new String[] { value });

		return this;
	}

	public ParamBuilder add(String name, String[] value) {
		_params.put(name, value);

		return this;
	}

	public String build(Type type) {

		if (type == Type.FORM_URLENCODED) {
			return _buildFormURLEncoded();
		}

		throw new UnsupportedOperationException();
	}

	private String _buildFormURLEncoded() {
		StringBuilder sb = new StringBuilder();

		boolean first = true;

		for (Map.Entry<String, String[]> entry : _params.entrySet()) {
			String[] values = entry.getValue();

			for (String value : values) {

				if (first) {
					first = false;
				}
				else {
					sb.append("&");
				}

				sb.append(entry.getKey());
				sb.append("=");

				if (value != null) {
					sb.append(value.trim());
				}
			}
		}

		return sb.toString();
	}

}
