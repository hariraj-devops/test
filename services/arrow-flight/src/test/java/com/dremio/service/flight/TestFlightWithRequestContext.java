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
package com.dremio.service.flight;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.users.User;
import com.dremio.service.users.UserService;
import com.dremio.service.users.proto.UID;
import com.dremio.service.usersessions.UserSessionService;
import com.google.inject.util.Providers;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightProducer;
import org.junit.Before;
import org.junit.Test;

/** Test that Flight RPC handlers are utilizing the request context. */
public class TestFlightWithRequestContext {

  private static final class DummyFlightRequestContextDecorator
      implements FlightRequestContextDecorator {

    private int callCount = 0;

    @Override
    public RequestContext apply(
        RequestContext requestContext,
        FlightProducer.CallContext flightContext,
        UserSessionService.UserSessionData userSessionData) {
      ++callCount;
      return requestContext;
    }
  }

  private DummyFlightRequestContextDecorator decorator;

  // Note: FlightProducer interface is used to intentional limit testing to Flight (not FlightSql)
  // RPC calls.
  private DremioFlightProducer producer;
  private final FlightCallHeaders emptyCallHeaders = new FlightCallHeaders();
  ;
  private final ServerCookieMiddleware serverCookieMiddleware = mock(ServerCookieMiddleware.class);
  private final FlightProducer.CallContext mockCallContext = mock(FlightProducer.CallContext.class);
  private final DremioFlightSessionsManager mockDremioFlightSessionsManager =
      mock(DremioFlightSessionsManager.class);
  private final UserSessionService.UserSessionData mockUserSessionData =
      mock(UserSessionService.UserSessionData.class);
  private final UserService mockUserService = mock(UserService.class);

  @Before
  public void setup() {
    doReturn(serverCookieMiddleware)
        .when(mockCallContext)
        .getMiddleware(DremioFlightService.FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY);
    doReturn(emptyCallHeaders).when(serverCookieMiddleware).headers();
    doReturn(mockUserSessionData)
        .when(mockDremioFlightSessionsManager)
        .getUserSession(any(), any());
    decorator = new DummyFlightRequestContextDecorator();
    producer =
        new DremioFlightProducer(
            null,
            mockDremioFlightSessionsManager,
            null,
            null,
            null,
            Providers.of(decorator),
            null,
            Providers.of(mockUserService));
  }

  @Test
  public void testGetStream() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.getStream(mockCallContext, null, null);
          return null;
        });
  }

  @Test
  public void testListFlights() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.listFlights(mockCallContext, null, null);
          return null;
        });
  }

  @Test
  public void testGetFlightInfo() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.getFlightInfo(mockCallContext, null);
          return null;
        });
  }

  @Test
  public void testGetSchema() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.getSchema(mockCallContext, null);
          return null;
        });
  }

  @Test
  public void testAcceptPut() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.acceptPut(mockCallContext, null, null);
          return null;
        });
  }

  @Test
  public void testDoExchange() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.doExchange(mockCallContext, null, null);
          return null;
        });
  }

  @Test
  public void testDoAction() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.doAction(mockCallContext, null, null);
          return null;
        });
  }

  @Test
  public void testListActions() {
    ignoreExceptionsAndValidateCallCount(
        () -> {
          producer.listActions(mockCallContext, null);
          return null;
        });
  }

  @Test
  public void testPopulateUserContextInCurrentRequestContext() throws Exception {
    UserSession userSession = mock(UserSession.class);
    User user = mock(User.class);
    String userId = UUID.randomUUID().toString();
    String userName = "test";
    UserCredentials userCredentials = mock(UserCredentials.class);
    when(mockUserSessionData.getSession()).thenReturn(userSession);
    when(userSession.getCredentials()).thenReturn(userCredentials);
    when(userCredentials.getUserName()).thenReturn(userName);
    when(mockUserService.getUser(userName)).thenReturn(user);
    when(user.getUID()).thenReturn(new UID(userId));
    RequestContext actual =
        producer.populateUserContextInCurrentRequestContext(mockUserSessionData);
    assertEquals(userId, actual.get(UserContext.CTX_KEY).getUserId());
  }

  private <V> void ignoreExceptionsAndValidateCallCount(Callable<V> rpcHandlerBody) {
    try {
      rpcHandlerBody.call();
    } catch (Exception ex) {
      // Suppress exceptions thrown from the RPC handler, since the point of this test
      // is to just verify the RequestContext was invoked correctly.
    }
    assertEquals(1, decorator.callCount);
  }
}
