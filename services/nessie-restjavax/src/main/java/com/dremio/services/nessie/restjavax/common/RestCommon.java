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
package com.dremio.services.nessie.restjavax.common;

import java.util.function.Function;
import org.projectnessie.error.ErrorCode;
import org.projectnessie.error.ErrorCodeAware;
import org.projectnessie.error.ImmutableNessieError;
import org.projectnessie.error.NessieError;
import org.projectnessie.error.NessieErrorDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RestCommon {
  private static final Logger LOGGER = LoggerFactory.getLogger(RestCommon.class);

  private RestCommon() {}

  public static NessieError buildNessieError(
      String message,
      int statusCode,
      String statusReason,
      ErrorCode errorCode,
      Exception e,
      Function<String, String> requestHeader) {

    if (message == null) {
      message = "";
    }

    LOGGER.debug(
        "Failure on server, propagated to client. Status: {} {}, Message: {}.",
        statusCode,
        statusReason,
        message,
        e);

    NessieErrorDetails errorDetails =
        (e instanceof ErrorCodeAware) ? ((ErrorCodeAware) e).getErrorDetails() : null;
    if (errorDetails != null && !isNessieClientSpec2(requestHeader)) {
      errorDetails = null;
    }

    return ImmutableNessieError.builder()
        .message(message)
        .status(statusCode)
        .errorCode(errorCode)
        .reason(statusReason)
        .errorDetails(errorDetails)
        .build();
  }

  private static boolean isNessieClientSpec2(Function<String, String> requestHeader) {
    String clientSpec = requestHeader.apply("Nessie-Client-Spec");
    if (clientSpec == null) {
      return false;
    }
    try {
      int i = clientSpec.indexOf('.');
      if (i != -1) {
        clientSpec = clientSpec.substring(0, i);
      }
      return Integer.parseInt(clientSpec) >= 2;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
