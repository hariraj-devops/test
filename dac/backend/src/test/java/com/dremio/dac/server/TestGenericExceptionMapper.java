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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.dac.model.common.DACUnauthorizedException;
import com.dremio.dac.model.job.QueryError;
import com.dremio.dac.service.errors.ClientErrorException;
import com.dremio.dac.service.errors.ConflictException;
import com.dremio.dac.service.errors.InvalidQueryException;
import com.dremio.dac.service.errors.NewDatasetQueryException;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.telemetry.api.metrics.SimpleCounter;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessControlException;
import java.util.ConcurrentModificationException;
import java.util.stream.Stream;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestGenericExceptionMapper {
  private static GenericExceptionMapper gem;

  private static SimpleMeterRegistry simpleRegistry;

  @BeforeAll
  public static void setUp() throws URISyntaxException {
    final UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getRequestUri()).thenReturn(new URI("http://dremio.foo"));
    final Request request = mock(Request.class);
    when(request.getMethod()).thenReturn("PUT");
    gem = new GenericExceptionMapper(uriInfo, request);

    simpleRegistry = new SimpleMeterRegistry();
    Metrics.addRegistry(simpleRegistry);
  }

  @AfterAll
  public static void tearDown() {
    Metrics.removeRegistry(simpleRegistry);
  }

  @AfterEach
  public void reset() {
    Metrics.globalRegistry.forEachMeter(
        meter -> {
          if (meter.getId().getName().equals("resetapi.generic_errors")) {
            Metrics.globalRegistry.remove(meter);
          }
        });
  }

  @Test
  public void testUnHandledException() {
    // Exception we do not do special handling for in GenericExceptionMapper
    assertEquals(
        "foo",
        ((GenericErrorMessage) gem.toResponse(new RuntimeException("foo")).getEntity())
            .getErrorMessage());
    assertEquals(
        GenericErrorMessage.GENERIC_ERROR_MSG,
        ((GenericErrorMessage) gem.toResponse(new RuntimeException("")).getEntity())
            .getErrorMessage());

    Assert.assertEquals(2, SimpleCounter.of("resetapi.generic_errors").count());
  }

  private static Stream<Arguments> errorResponses() {
    return Stream.of(
        Arguments.of("foo", new WebApplicationException("foo"), 500, 0),
        Arguments.of("foo", new ConflictException("foo"), 409, 0),
        Arguments.of("User 'foo' not found", new UserNotFoundException("foo"), 404, 1),
        Arguments.of("foo", new AccessControlException("foo"), 403, 1),
        Arguments.of(
            "PERMISSION_DENIED: foo",
            new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("foo")),
            403,
            1),
        Arguments.of(
            "NOT_FOUND: foo",
            new StatusRuntimeException(Status.NOT_FOUND.withDescription("foo")),
            404,
            1),
        Arguments.of(
            "INVALID_ARGUMENT: foo",
            new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("foo")),
            400,
            1),
        Arguments.of(
            "PERMISSION_DENIED: foo",
            new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("foo")),
            403,
            1),
        Arguments.of(
            "INTERNAL: foo",
            new StatusRuntimeException(Status.INTERNAL.withDescription("foo")),
            500,
            1),
        Arguments.of(
            "UNIMPLEMENTED: foo",
            new StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("foo")),
            501,
            1),
        Arguments.of(
            "ALREADY_EXISTS: foo",
            new StatusRuntimeException(Status.ALREADY_EXISTS.withDescription("foo")),
            409,
            1),
        Arguments.of("foo", new IllegalArgumentException("foo"), 400, 1),
        Arguments.of("foo", new ConcurrentModificationException("foo"), 409, 1),
        Arguments.of("foo", new ClientErrorException("foo"), 400, 0),
        Arguments.of("foo", new DACUnauthorizedException("foo"), 401, 1),
        Arguments.of(
            "foo",
            new InvalidQueryException(
                new InvalidQueryException.Details(
                    null,
                    null,
                    QueryError.of(UserException.validationError().buildSilently()),
                    null,
                    null,
                    null),
                null,
                "foo"),
            400,
            0),
        Arguments.of(
            "foo",
            new NewDatasetQueryException(
                new NewDatasetQueryException.ExplorePageInfo(null, null, null, null, null),
                new RuntimeException("foo")),
            400,
            0));
  }

  @ParameterizedTest
  @MethodSource("errorResponses")
  public void testHandledException(
      String errorMessage, Exception exception, int status, int count) {
    // Exception we do special handling for in GenericExceptionMapper
    Response response = gem.toResponse(exception);
    Assertions.assertEquals(
        errorMessage, ((GenericErrorMessage) response.getEntity()).getErrorMessage());
    Assertions.assertEquals(status, response.getStatus());

    Assert.assertEquals(count, SimpleCounter.of("resetapi.generic_errors").count());
  }
}
