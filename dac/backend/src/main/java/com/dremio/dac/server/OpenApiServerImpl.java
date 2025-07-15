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

import com.dremio.api.ErrorResponseContent;
import com.dremio.api.ImmutableError;
import com.dremio.api.ValidationErrorResponseContent;
import com.dremio.common.perf.Timer;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.dac.annotations.OpenApiResource;
import com.dremio.dac.annotations.RestApiServer;
import com.dremio.dac.api.ErrorResponseConverter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.hibernate.validator.HibernateValidator;

/** Dremio Rest API Server for resources generated from Open API specs. */
@RestApiServer(pathSpec = "/api/v4/*", tags = "oss,enterprise,dcs")
public class OpenApiServerImpl extends ResourceConfig {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(OpenApiServerImpl.class);

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new GuavaModule())
          .registerModule(new JavaTimeModule())
          .setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX"))
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  @Inject
  public OpenApiServerImpl(ScanResult result) {
    try (Timer.TimedBlock b = Timer.time("new OpenApiServer")) {
      init(result);
    }
  }

  protected void init(ScanResult result) {
    // Pretty printer and accepted format filters.
    register(JSONPrettyPrintFilter.class);
    register(MediaTypeFilter.class);

    // Resources.
    RestApiServer serverAnnotation = getClass().getAnnotation(RestApiServer.class);
    for (Class<?> resource : result.getAnnotatedClasses(OpenApiResource.class)) {
      // Filter resources by pathSpec in their annotations.
      OpenApiResource resourceAnnotation = resource.getAnnotation(OpenApiResource.class);
      if (serverAnnotation.pathSpec().equals(resourceAnnotation.serverPathSpec())) {
        register(resource);
        logger.info("Registered {} for {}", resource, getClass());
      } else {
        logger.info("Skipping {} for {}", resource, getClass());
      }
    }

    // Auth, contextualization of requests.
    register(new AuthenticationBinder());
    register(DACAuthFilterFeature.class);

    // Exception mappers, handling of no-content from void methods. Per jax-rs spec, more generic
    // exception mapper will be used only if no  other mapper can be used.
    // TODO: add octet-stream media type for file upload/download.
    registerInstances(
        new JsonMappingExceptionMapper(),
        new WebApplicationExceptionMapper(),
        new GenericExceptionMapper(),
        new JsonObjectReader(),
        new JsonObjectWriter());

    // Set status from response instead of redirecting to an error page.
    property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);

    // Disable automatic discovery of "features" on the classpath, which could lead
    // to unexpected results. All features must be registered explicitly with "register*"
    // methods.
    property(ServerProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true);

    logger.info("Initialized OpenApiServer");
  }

  /** Maps {@link WebApplicationException} exceptions to {@link Response}. */
  private static final class WebApplicationExceptionMapper
      implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
      Response response = exception.getResponse();
      // Check that the entity has correct type.
      if (response.hasEntity()
          && !response.getEntity().getClass().isAssignableFrom(ErrorResponseContent.class)
          && !response
              .getEntity()
              .getClass()
              .isAssignableFrom(ValidationErrorResponseContent.class)) {
        logger.error(
            "Unexpected entity class in WebApplicationException: {} entity: {}",
            response.getEntity().getClass(),
            response.getEntity());
        return ErrorResponseConverter.internalServerError(exception.getMessage());
      }
      return response;
    }
  }

  /**
   * Maps {@link JsonMappingException} to {@link Response}. The exception of the class and its
   * derivatives indicates bad request, the mapping of response objects to JSON will generate a
   * different error resulting in internal server error.
   */
  private static final class JsonMappingExceptionMapper
      implements ExceptionMapper<JsonMappingException> {
    @Override
    public Response toResponse(JsonMappingException exception) {
      logger.error("JsonMappingException caught in exception mapper", exception);
      return ErrorResponseConverter.validationError(
          ImmutableList.of(ImmutableError.builder().detail(exception.getMessage()).build()));
    }
  }

  /** Maps generic exceptions to {@link Response}. */
  private static final class GenericExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
      logger.error("Generic exception caught in exception mapper", exception);
      return ErrorResponseConverter.internalServerError(exception.getMessage());
    }
  }

  /** Reads objects from JSON. */
  private static final class JsonObjectReader implements MessageBodyReader<Object> {
    private static final Validator INPUT_VALIDATOR =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .buildValidatorFactory()
            .getValidator();

    @Override
    public boolean isReadable(
        Class<?> aClass, Type type, Annotation[] annotations, MediaType mediaType) {
      return mediaType.equals(MediaType.APPLICATION_JSON_TYPE);
    }

    @Override
    public Object readFrom(
        Class<Object> aClass,
        Type type,
        Annotation[] annotations,
        MediaType mediaType,
        MultivaluedMap<String, String> requestHeaders,
        InputStream inputStream)
        throws IOException, WebApplicationException {
      Object parsedObject = OBJECT_MAPPER.readValue(inputStream, aClass);

      // Validate parsed object.
      validate(parsedObject);

      return parsedObject;
    }

    private void validate(Object parsedObject) {
      Set<ConstraintViolation<Object>> validationFailures = INPUT_VALIDATOR.validate(parsedObject);
      if (!validationFailures.isEmpty()) {
        throw new WebApplicationException(
            ErrorResponseConverter.validationError(
                validationFailures.stream()
                    .map(JsonObjectReader::formatViolation)
                    .collect(Collectors.toList())));
      }
    }

    private static com.dremio.api.Error formatViolation(ConstraintViolation<Object> violation) {
      return ImmutableError.builder()
          .detail(violation.getMessage())
          .pointer(violation.getPropertyPath().toString())
          .build();
    }
  }

  /** Writes objects to JSON. */
  private static final class JsonObjectWriter implements MessageBodyWriter<Object> {
    @Override
    public boolean isWriteable(
        Class<?> aClass, Type type, Annotation[] annotations, MediaType mediaType) {
      return mediaType.equals(MediaType.APPLICATION_JSON_TYPE);
    }

    @Override
    public void writeTo(
        Object o,
        Class<?> aClass,
        Type type,
        Annotation[] annotations,
        MediaType mediaType,
        MultivaluedMap<String, Object> responseHeaders,
        OutputStream outputStream)
        throws IOException, WebApplicationException {
      if (!mediaType.equals(MediaType.APPLICATION_JSON_TYPE)) {
        throw new WebApplicationException(
            ErrorResponseConverter.internalServerError(
                String.format(
                    "MediaType %s is not %s", mediaType, MediaType.APPLICATION_JSON_TYPE)));
      }
      OBJECT_MAPPER.writeValue(outputStream, o);
    }
  }
}
