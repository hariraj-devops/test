/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.plugins.azure;

import org.asynchttpclient.Request;

/** Implementation should provide the value of Authorization header */
public interface AzureAuthTokenProvider {

  /**
   * Checks whether token is about to expire or is already expired If it is either of the scenarios,
   * method updates the token and returns true Otherwise, the token remains unchanged and method
   * returns false
   *
   * @return
   */
  boolean checkAndUpdateToken();

  /**
   * Should return the value of Authorization header. The value should be prefixed with token type.
   * Example "Bearer <token>"
   *
   * @param req
   * @return
   */
  default String getAuthorizationHeader(final Request req) {
    return "";
  }

  /**
   * Should return a SAS signature in the following format: path to resource + "?" + SAS token. For
   * example:
   *
   * @return
   */
  default String getSasSignature(final boolean withPath) {
    return "";
  }
}
