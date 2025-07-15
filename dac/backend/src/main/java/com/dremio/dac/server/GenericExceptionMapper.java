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
package com.dremio.dac.server;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.NOT_IMPLEMENTED;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

import com.dremio.common.exceptions.ErrorHelper;
import com.dremio.common.exceptions.UserException;
import com.dremio.dac.model.common.DACUnauthorizedException;
import com.dremio.dac.service.errors.ClientErrorException;
import com.dremio.dac.service.errors.ConflictException;
import com.dremio.dac.service.errors.InvalidQueryException;
import com.dremio.dac.service.errors.NewDatasetQueryException;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.telemetry.api.metrics.SimpleCounter;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.StatusRuntimeException;
import java.security.AccessControlException;
import java.util.ConcurrentModificationException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ExceptionMapper;
import org.glassfish.jersey.internal.inject.ExtractorException;
import org.glassfish.jersey.server.ParamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exception display mapper for rest server. */
class GenericExceptionMapper implements ExceptionMapper<Throwable> {
  private static final Logger logger = LoggerFactory.getLogger(GenericExceptionMapper.class);

  private static final SimpleCounter GENERIC_ERROR_MESSAGE_COUNTER =
      SimpleCounter.of("resetapi.generic_errors");

  private boolean sendStackTraceToClient;

  public GenericExceptionMapper() {
    this(false);
  }

  public GenericExceptionMapper(boolean sendStackTraceToClient) {
    this.sendStackTraceToClient = sendStackTraceToClient;
  }

  @VisibleForTesting
  protected GenericExceptionMapper(UriInfo uriInfo, Request request) {
    this.uriInfo = uriInfo;
    this.request = request;
  }

  @Context private UriInfo uriInfo;

  @Context private Request request;

  @Override
  public Response toResponse(Throwable throwable) {
    String[] stackTrace = null;
    if (sendStackTraceToClient) {
      stackTrace = GenericErrorMessage.printStackTrace(throwable);
    }

    return handleException(throwable, stackTrace);
  }

  private Response handleException(Throwable throwable, String[] stackTrace) {
    // standard status already decided
    if (throwable instanceof WebApplicationException) {
      WebApplicationException wae = (WebApplicationException) throwable;
      final String errorMessage;
      if (wae instanceof ParamException) {
        errorMessage = getParamExceptionErrorMessage((ParamException) wae);
      } else {
        errorMessage = wae.getMessage();
      }

      logger.debug(
          "Status {} for {} {} : {}",
          wae.getResponse().getStatus(),
          request.getMethod(),
          uriInfo.getRequestUri(),
          errorMessage,
          wae);

      if (wae instanceof ConflictException && errorMessage != null) {
        return Response.fromResponse(wae.getResponse())
            .entity(new GenericErrorMessage(errorMessage, null, stackTrace))
            .type(APPLICATION_JSON_TYPE)
            .build();
      } else {
        return Response.fromResponse(wae.getResponse())
            .entity(new GenericErrorMessage(errorMessage, stackTrace))
            .type(APPLICATION_JSON_TYPE)
            .build();
      }
    }

    if (throwable instanceof InvalidQueryException) {
      logger.debug(
          "Initial attempt to preview new query failed {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      InvalidQueryException exception = (InvalidQueryException) throwable;
      return Response.status(BAD_REQUEST)
          .entity(
              new ApiErrorModel<>(
                  ApiErrorModel.ErrorType.INVALID_QUERY,
                  exception.getMessage(),
                  stackTrace,
                  exception.getDetails()))
          .type(APPLICATION_JSON_TYPE)
          .build();
    }

    if (throwable instanceof NewDatasetQueryException) {
      logger.debug(
          "Initial attempt to preview new query failed {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      NewDatasetQueryException exception = (NewDatasetQueryException) throwable;
      return newResponse(
          BAD_REQUEST,
          new ApiErrorModel<>(
              ApiErrorModel.ErrorType.NEW_DATASET_QUERY_EXCEPTION,
              exception.getMessage(),
              stackTrace,
              exception.getDetails()));
    }

    if (throwable instanceof UserNotFoundException) {
      logger.debug(
          "Not Found for {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      return newGenericErrorMessage(NOT_FOUND, throwable, stackTrace);
    }

    if (throwable instanceof AccessControlException) {
      logger.debug(
          "Permission denied for {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      return newGenericErrorMessage(FORBIDDEN, throwable, stackTrace);
    }

    if (throwable instanceof IllegalArgumentException) { // TODO: Move to ClientErrorException
      // bad input => 400
      logger.debug(
          "Bad input for {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      return newGenericErrorMessage(BAD_REQUEST, throwable, stackTrace);
    }

    if (throwable instanceof ConcurrentModificationException) {
      logger.debug(
          "Conflict for {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      return newGenericErrorMessage(CONFLICT, throwable, stackTrace);
    }

    if (throwable instanceof ClientErrorException) {
      ClientErrorException e = (ClientErrorException) throwable;
      // bad input => 400
      logger.debug(
          "Bad input for " + request.getMethod() + " " + uriInfo.getRequestUri() + " : " + e, e);
      return newResponse(
          BAD_REQUEST, new ValidationErrorMessage(e.getMessage(), e.getMessages(), stackTrace));
    } // or ServerErrorException

    if (throwable instanceof DACUnauthorizedException) {
      logger.debug(
          "Not Found for {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
      return newGenericErrorMessage(UNAUTHORIZED, throwable, stackTrace);
    }

    if (throwable instanceof StatusRuntimeException) {
      switch (((StatusRuntimeException) throwable).getStatus().getCode()) {
        case INVALID_ARGUMENT:
          logger.debug(
              "Invalid Argument for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(BAD_REQUEST, throwable, stackTrace);
        case NOT_FOUND:
          logger.debug(
              "Not Found for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(NOT_FOUND, throwable, stackTrace);
        case PERMISSION_DENIED:
          logger.debug(
              "Permission denied for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(FORBIDDEN, throwable, stackTrace);
        case INTERNAL:
          logger.debug(
              "Internal server error for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(INTERNAL_SERVER_ERROR, throwable, stackTrace);
        case UNIMPLEMENTED:
          logger.debug(
              "Server does not implement or support for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(NOT_IMPLEMENTED, throwable, stackTrace);
        case ALREADY_EXISTS:
          logger.debug(
              "Conflict for {} {} : {}",
              request.getMethod(),
              uriInfo.getRequestUri(),
              throwable.toString(),
              throwable);
          return newGenericErrorMessage(CONFLICT, throwable, stackTrace);
        default:
      }
    }

    Throwable rootException = ErrorHelper.findWrappedCause(throwable, UserException.class);
    if (rootException != null && rootException.getMessage() == UserException.QUERY_REJECTED_MSG) {
      logger.error(
          "Rejected for {} {} because it exceeded the maximum allowed number of live queries in a single coordinator",
          request.getMethod(),
          uriInfo.getRequestUri());
    } else {
      // bug! => 500
      logger.error(
          "Unexpected exception when processing {} {} : {}",
          request.getMethod(),
          uriInfo.getRequestUri(),
          throwable.toString(),
          throwable);
    }
    return newGenericErrorMessage(INTERNAL_SERVER_ERROR, throwable, stackTrace);
  }

  private static Response newGenericErrorMessage(
      Response.Status status, Throwable cause, String[] stackTrace) {
    GENERIC_ERROR_MESSAGE_COUNTER.increment();
    return newResponse(status, new GenericErrorMessage(cause.getMessage(), stackTrace));
  }

  private static Response newResponse(Response.Status status, Object entity) {
    return Response.status(status).entity(entity).type(APPLICATION_JSON_TYPE).build();
  }

  private String getParamExceptionErrorMessage(ParamException pe) {
    Throwable cause = pe.getCause();
    if (cause instanceof ExtractorException && cause.getCause() != null) {
      // ExtractorException does not have any extra info
      cause = cause.getCause();
    }

    final String causeMessage = (cause != null) ? " " + cause.getMessage() : "";

    return pe.getDefaultStringValue() != null
        ? String.format(
            "%s: %s %s (default: %s).%s",
            pe.getMessage(), // generic status message
            pe.getParameterType().getSimpleName(),
            pe.getParameterName(), // which param is wrong
            pe.getDefaultStringValue(), // if it has a default
            causeMessage)
        : String.format(
            "%s: %s %s.%s",
            pe.getMessage(), // generic status message
            pe.getParameterType().getSimpleName(),
            pe.getParameterName(), // which param is wrong
            causeMessage); // the underlying problem
  }
}
