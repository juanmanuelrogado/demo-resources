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
package com.liferay.demo.cmschat.dto;

/**
 * @author  Neil Griffin
 */
public class SearchResult {

	/**
	 * @author  Neil Griffin
	 */
	public enum Type {
		ARTICLE("Article"),
		BLOG("Blog"),
		CMS2_BLOG("Blog"),
		DOCUMENT("Document"),
		CMS2_DOCUMENT("CMS2Document"),
		FOLDER("Folder"),
		PAGE("Page"),
		CMS2_Press_Release("Press Release"),
		USER("User"),
		OTHER("Other");

		private final String title;

		Type(String title) {
			this.title = title;
		}

		public String getTitle() {
			return title;
		}
	}

	private String _description;
	private String _contentURL;
	private String _contentText;
	private String _itemURL;
	private String _title;
	private Type _type;

	public SearchResult(String title, String description, String itemURL, String contentText, String contentURL,
		Type type) {
		_title = title;
		_description = description;
		_itemURL = itemURL;
		_contentText = contentText;
		_contentURL = contentURL;
		_type = type;
	}

	public String getContentText() {
		return _contentText;
	}

	public String getContentURL() {
		return _contentURL;
	}

	public String getDescription() {
		return _description;
	}

	public String getItemURL() {
		return _itemURL;
	}

	public String getTitle() {
		return _title;
	}

	public Type getType() {
		return _type;
	}

	public void setContentURL(String contentURL) {
		_contentURL = contentURL;
	}
}
