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

import com.dremio.api.ImmutableError;
import com.dremio.api.ImmutableValidationErrorResponseContent;
import com.dremio.api.ValidationErrorResponseContent;
import com.dremio.example.api.ImmutableStorageObject;
import com.dremio.example.api.StorageObject;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.annotation.Nullable;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import org.junit.Assert;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

// TODO: migrate this class to use BaseTestServerJunit5.
public class TestOpenApiServerImpl extends BaseTestServer {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new GuavaModule())
          .registerModule(new JavaTimeModule())
          .setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX"))
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL)
          .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  private static final StorageObject DEFAULT_OBJECT =
      ImmutableStorageObject.builder()
          .path("abc")
          .intValue1(11)
          .stringValue1("")
          .patternString("123")
          .email("a@company.com")
          .timestampValue(
              ZonedDateTime.ofInstant(
                  Instant.parse("2024-04-16T21:00:30.00Z"), ZoneId.ofOffset("", ZoneOffset.UTC)))
          .durationValue(Duration.ofMinutes(63))
          .build();
  private static final String DEFAULT_OBJECT_JSON =
      "{"
          + "\"path\":\"abc\","
          + "\"intValue1\":11,"
          + "\"stringValue1\":\"\","
          + "\"patternString\":\"123\","
          + "\"email\":\"a@company.com\","
          + "\"timestampValue\":\"2024-04-16T21:00:30Z\","
          + "\"durationValue\":\"PT1H3M\""
          + "}";

  private static WebTarget getExamplesEndpoint() {
    return getHttpClient().getAPIRoot().path("v4test").path("examples");
  }

  @Test
  public void testPostGet() throws Exception {
    // Post an object.
    Response postResponse =
        getBuilder(getExamplesEndpoint().path(DEFAULT_OBJECT.getPath()))
            .buildPost(Entity.json(DEFAULT_OBJECT))
            .invoke();
    Assertions.assertEquals(Response.Status.OK.getStatusCode(), postResponse.getStatus());

    // Verify string representation.
    String json = postResponse.readEntity(String.class);
    Assertions.assertEquals(DEFAULT_OBJECT_JSON, json);

    // Get it back.
    Assert.assertEquals(
        DEFAULT_OBJECT,
        parseResponse(
            getBuilder(getExamplesEndpoint().path(DEFAULT_OBJECT.getPath())).buildGet().invoke(),
            StorageObject.class));
  }

  @Test
  public void testPost_invalidJson() throws Exception {
    // Post an empty object.
    Response response =
        getBuilder(getExamplesEndpoint().path("abcdef")).buildPost(Entity.json("{}")).invoke();

    // Verify parser error.
    Assertions.assertEquals(422, response.getStatus());
    Assertions.assertEquals(
        buildError(
            "Cannot construct instance of `com.dremio.example.api.ImmutableStorageObject`"
                + ", problem: Cannot build StorageObject, some of "
                + "required attributes are not set [intValue1, stringValue1, patternString, email]\n"
                + " at [Source: (org.glassfish.jersey.message.internal."
                + "ReaderInterceptorExecutor$UnCloseableInputStream); line: 1, column: 2]",
            null),
        parseResponse(response, ValidationErrorResponseContent.class));
  }

  @Test
  public void testPostDelete() throws Exception {
    // Post an object.
    StorageObject object = DEFAULT_OBJECT.toBuilder().path("cde").build();
    expectStatus(
        Response.Status.OK,
        getBuilder(getExamplesEndpoint().path(object.getPath())).buildPost(Entity.json(object)));
    expectStatus(
        Response.Status.OK, getBuilder(getExamplesEndpoint().path(object.getPath())).buildGet());

    // Delete.
    expectStatus(
        Response.Status.NO_CONTENT,
        getBuilder(getExamplesEndpoint().path(object.getPath())).buildDelete());
    expectStatus(
        Response.Status.NOT_FOUND,
        getBuilder(getExamplesEndpoint().path(object.getPath())).buildGet());
  }

  @Test
  public void testValidation_minimum() throws IOException {
    validateError(
        DEFAULT_OBJECT.toBuilder().intValue1(0).build(),
        buildError("must be greater than or equal to 10", "intValue1"));
  }

  @Test
  public void testValidation_maximum() throws IOException {
    validateError(
        DEFAULT_OBJECT.toBuilder().intValue1(30).build(),
        buildError("must be less than or equal to 20", "intValue1"));
  }

  @Test
  public void testValidation_email() throws IOException {
    validateError(
        DEFAULT_OBJECT.toBuilder().email("a").build(),
        buildError("must be a well-formed email address", "email"));
  }

  @Test
  public void testValidation_pattern() throws IOException {
    validateError(
        DEFAULT_OBJECT.toBuilder().patternString("a").build(),
        buildError("must match \"[0-9]+\"", "patternString"));
  }

  private void validateError(StorageObject object, ValidationErrorResponseContent error)
      throws IOException {
    Response response =
        getBuilder(getExamplesEndpoint().path(object.getPath()))
            .buildPost(Entity.json(object))
            .invoke();
    Assert.assertEquals((int) error.getStatus(), response.getStatus());
    Assert.assertEquals("application/json", response.getHeaderString("Content-Type"));
    Assert.assertEquals(error, parseResponse(response, ValidationErrorResponseContent.class));
  }

  private static ValidationErrorResponseContent buildError(
      String detail, @Nullable String pointer) {
    return ImmutableValidationErrorResponseContent.builder()
        .type("https://api.dremio.dev/problems/validation-problem")
        .status(422)
        .errors(ImmutableList.of(ImmutableError.builder().detail(detail).pointer(pointer).build()))
        .title("There was a problem validating the content of the request.")
        .build();
  }

  private static <T> T parseResponse(Response response, Class<T> clazz) throws IOException {
    return OBJECT_MAPPER.readValue(
        response.readEntity(String.class).getBytes(StandardCharsets.UTF_8), clazz);
  }
}
