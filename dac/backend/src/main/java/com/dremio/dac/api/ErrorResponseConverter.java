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
package com.dremio.dac.api;

import com.dremio.api.Error;
import com.dremio.api.ErrorResponseContent;
import com.dremio.api.ImmutableErrorResponseContent;
import com.dremio.api.ImmutableValidationErrorResponseContent;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import javax.ws.rs.core.Response;

/** Helper class for conversions from {@link ErrorResponseContent} to {@link Response}. */
public final class ErrorResponseConverter {
  // Response.Status does not define this error.
  // https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/422
  private static final int UNPROCESSABLE_CONTENT_STATUS_CODE = 422;

  private ErrorResponseConverter() {}

  public static Response notImplemented(String title) {
    return convertErrorResponseContent(
        Response.Status.NOT_IMPLEMENTED.getStatusCode(),
        ImmutableErrorResponseContent.builder()
            .type("https://api.dremio.dev/problems/not-implemented")
            .status(Response.Status.NOT_IMPLEMENTED.getStatusCode())
            .title("Not implemented.")
            .build());
  }

  public static Response notFound(String title) {
    return convertErrorResponseContent(
        Response.Status.NOT_FOUND.getStatusCode(),
        ImmutableErrorResponseContent.builder()
            .type("https://api.dremio.dev/problems/not-found")
            .status(Response.Status.NOT_FOUND.getStatusCode())
            .title("Not found.")
            .build());
  }

  public static Response conflict(String title) {
    return convertErrorResponseContent(
        Response.Status.CONFLICT.getStatusCode(),
        ImmutableErrorResponseContent.builder()
            .type("https://api.dremio.dev/problems/conflict")
            .status(Response.Status.CONFLICT.getStatusCode())
            .title("Conflict.")
            .build());
  }

  public static Response validationError(Collection<Error> errors) {
    return Response.status(UNPROCESSABLE_CONTENT_STATUS_CODE)
        .entity(
            ImmutableValidationErrorResponseContent.builder()
                .type("https://api.dremio.dev/problems/validation-problem")
                .status(UNPROCESSABLE_CONTENT_STATUS_CODE)
                .errors(ImmutableList.copyOf(errors))
                .title("There was a problem validating the content of the request.")
                .build())
        .build();
  }

  public static Response internalServerError(String title) {
    return convertErrorResponseContent(
        Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
        ImmutableErrorResponseContent.builder()
            .type("https://api.dremio.dev/problems/internal-server-error")
            .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
            .title("Conflict.")
            .build());
  }

  public static Response convertErrorResponseContent(int status, ErrorResponseContent content) {
    return Response.status(status).entity(content).build();
  }
}
