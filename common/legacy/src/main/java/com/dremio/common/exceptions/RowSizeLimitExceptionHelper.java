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

package com.dremio.common.exceptions;

import com.dremio.common.exceptions.FieldSizeLimitExceptionHelper.FieldSizeLimitException;

public final class RowSizeLimitExceptionHelper {

  private RowSizeLimitExceptionHelper() {}

  public static void checkSizeLimit(
      int size, int maxSize, RowSizeLimitExceptionType type, org.slf4j.Logger logger) {
    if (size > maxSize) {
      throw createRowSizeLimitException(maxSize, type, logger);
    }
  }

  public static UserException createRowSizeLimitException(
      int maxSize, RowSizeLimitExceptionType type, org.slf4j.Logger logger) {
    String whereExceptionHappened;
    switch (type) {
      case READ:
        whereExceptionHappened = "reading";
        break;
      case WRITE:
        whereExceptionHappened = "writing";
        break;
      case PROCESSING:
        whereExceptionHappened = "processing";
        break;
      default:
        throw new IllegalArgumentException("Unsupported RowSizeLimitExceptionType: " + type);
    }
    return UserException.unsupportedError(new FieldSizeLimitException())
        .message(
            "Exceeded maximum allowed row size of %d bytes %s data.",
            maxSize, whereExceptionHappened)
        .addContext("limit", maxSize)
        .build(logger);
  }

  public enum RowSizeLimitExceptionType {
    READ,
    WRITE,
    PROCESSING
  }
}
