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
package com.dremio.common.util;

import com.google.common.primitives.Ints;
import java.util.Base64;

public final class RequestUtil {

  // Base64 digits are 6 bits each; 4 bits are wasted
  public static final int BASE64_DIGITS_FOR_INTEGER = 6;

  private static final Base64.Decoder DECODER;

  static {
    // We can omit padding without ambiguity because we encode only fixed-size data (a 32 bit int)
    DECODER = Base64.getUrlDecoder();
  }

  private RequestUtil() {}

  /**
   * base64url-decodes the parameter, after prepending "A" digits until the length of the input is
   * {@value BASE64_DIGITS_FOR_INTEGER} chars/digits.
   */
  public static int decodePageToken(String pageToken, RuntimeException exception) {
    try {
      String padded = prependLeadingA(pageToken);
      byte[] rawBytes = DECODER.decode(padded);
      return Ints.fromByteArray(rawBytes);
    } catch (IllegalArgumentException e) {
      throw exception;
    }
  }

  /**
   * Prepends leading "A" characters to the argument until it has length {@value
   * BASE64_DIGITS_FOR_INTEGER}.
   *
   * @throws IllegalArgumentException if the parameter exceeds this length
   */
  private static String prependLeadingA(String incomingToken) {
    if (BASE64_DIGITS_FOR_INTEGER == incomingToken.length()) {
      return incomingToken;
    } else if (BASE64_DIGITS_FOR_INTEGER < incomingToken.length()) {
      throw new IllegalArgumentException("Invalid page token: " + incomingToken);
    }
    StringBuilder sb = new StringBuilder(incomingToken);
    while (sb.length() < BASE64_DIGITS_FOR_INTEGER) {
      sb.insert(0, "A");
    }
    return sb.toString();
  }
}
