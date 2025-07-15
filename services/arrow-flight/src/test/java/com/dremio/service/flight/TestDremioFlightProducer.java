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

import static com.dremio.common.map.CaseInsensitiveMap.newHashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.map.CaseInsensitiveMap;
import com.dremio.context.RequestContext;
import com.dremio.context.TenantContext;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.work.protector.UserWorker;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.SessionOptionValue;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.flight.impl.FlightWorkManager;
import com.dremio.service.flight.utils.TestSessionOptionValueVisitor;
import com.dremio.service.usersessions.UserSessionService;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Provider;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.DremioToFlightSessionOptionValueConverter;
import org.apache.arrow.flight.FlightConstants;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.GetSessionOptionsResult;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SyncPutListener;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestDremioFlightProducer {

  @Mock private Location location;
  @Mock private DremioFlightSessionsManager sessionsManager;
  @Mock private UserWorker userWorker;
  @Mock private OptionManager optionManager;
  @Mock private FlightRequestContextDecorator requestContextDecorator;
  @Mock private FlightWorkManager.RunQueryResponseHandlerFactory responseHandlerFactory;
  @Mock private FlightProducer.StreamListener<Result> streamListener;
  @Mock private FlightProducer.CallContext callContext;
  @Mock private Provider<FlightRequestContextDecorator> requestContextDecoratorProvider;
  private RootAllocator allocator;
  private DremioFlightProducer flightProducer;

  @Mock private Provider<UserWorker> workerProvider;
  @Mock private Provider<OptionManager> optionManagerProvider;
  @Mock private CallHeaders callHeaders;

  @Test
  public void testDoActionGetSessionOptions() {
    MockitoAnnotations.openMocks(this);

    ServerCookieMiddleware middleware = mock(ServerCookieMiddleware.class);
    CallHeaders headers = mock(CallHeaders.class);
    when(middleware.headers()).thenReturn(headers);
    when(callContext.getMiddleware(DremioFlightService.FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY))
        .thenReturn(middleware);

    when(requestContextDecoratorProvider.get()).thenReturn(requestContextDecorator);
    when(requestContextDecorator.apply(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              RequestContext currentContext = RequestContext.current();
              Map<RequestContext.Key<?>, Object> contextMap = new HashMap<>();
              contextMap.put(
                  RequestContext.newKey("key"),
                  new TenantContext(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
              return currentContext.with(contextMap);
            });

    UserSessionService.UserSessionData sessionData = mock(UserSessionService.UserSessionData.class);
    when(sessionsManager.getUserSession(any(), any())).thenReturn(sessionData);

    CaseInsensitiveMap<com.dremio.sabot.rpc.user.SessionOptionValue> dremioSessionOptions =
        newHashMap();
    dremioSessionOptions.put(
        "option1", SessionOptionValue.Builder.newBuilder().setStringValue("value1").build());
    dremioSessionOptions.put(
        "option2", SessionOptionValue.Builder.newBuilder().setBoolValue(true).build());
    dremioSessionOptions.put(
        "option3", SessionOptionValue.Builder.newBuilder().setDoubleValue(0.0).build());
    dremioSessionOptions.put(
        "option4", SessionOptionValue.Builder.newBuilder().setInt64Value(1).build());
    dremioSessionOptions.put(
        "option5",
        SessionOptionValue.Builder.newBuilder()
            .setStringListValue(Arrays.asList("value1", "value2"))
            .build());

    when(sessionData.getSession()).thenReturn(mock(UserSession.class));
    when(sessionData.getSession().getSessionOptionsMap()).thenReturn(dremioSessionOptions);

    flightProducer =
        new DremioFlightProducer(
            Optional.empty(),
            sessionsManager,
            workerProvider,
            optionManagerProvider,
            allocator,
            requestContextDecoratorProvider,
            null,
            null);

    Action action = new Action(FlightConstants.GET_SESSION_OPTIONS.getType(), new byte[0]);
    FlightProducer.StreamListener<Result> streamListener =
        mock(FlightProducer.StreamListener.class);

    flightProducer.doAction(callContext, action, streamListener);

    ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
    verify(streamListener, never()).onError(any());
    verify(streamListener).onNext(resultCaptor.capture());
    verify(streamListener).onCompleted();

    Result capturedResult = resultCaptor.getValue();
    ByteBuffer buffer = ByteBuffer.wrap(capturedResult.getBody());
    GetSessionOptionsResult getSessionOptionsResult;
    try {
      getSessionOptionsResult = GetSessionOptionsResult.deserialize(buffer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Map<String, org.apache.arrow.flight.SessionOptionValue> resultOptions =
        getSessionOptionsResult.getSessionOptions();
    Map<String, org.apache.arrow.flight.SessionOptionValue> expectedOptions =
        DremioToFlightSessionOptionValueConverter.convert(dremioSessionOptions);

    assertEquals(expectedOptions.size(), resultOptions.size());

    for (Map.Entry<String, org.apache.arrow.flight.SessionOptionValue> entry :
        expectedOptions.entrySet()) {
      Object expectedSessionOptionValue =
          entry.getValue().acceptVisitor(new TestSessionOptionValueVisitor<>());
      Object actualSessionOptionValue =
          resultOptions.get(entry.getKey()).acceptVisitor(new TestSessionOptionValueVisitor<>());

      if (expectedSessionOptionValue instanceof String[]) {
        assertArrayEquals(
            (String[]) expectedSessionOptionValue, (String[]) actualSessionOptionValue);
      } else {
        assertEquals(expectedSessionOptionValue, actualSessionOptionValue);
      }
    }
  }

  @Test
  public void testAcceptPutPreparedStatementQuery() throws Exception {

    try (BufferAllocator allocator = new RootAllocator(1024);
        IntVector vector0 = new IntVector("?0", allocator);
        IntVector vector1 = new IntVector("?1", allocator);
        IntVector vector2 = new IntVector("?2", allocator);
        IntVector vector3 = new IntVector("?3", allocator);
        IntVector vector4 = new IntVector("?4", allocator);
        VectorSchemaRoot batch1 = VectorSchemaRoot.of(vector0, vector1, vector2);
        VectorSchemaRoot batch2 = VectorSchemaRoot.of(vector3, vector4);
        SyncPutListener streamListener = new SyncPutListener()) {

      vector0.allocateNew(1);
      vector0.set(0, 0);
      vector0.setValueCount(1);

      vector1.allocateNew(1);
      vector1.set(0, 1);
      vector1.setValueCount(1);

      vector2.allocateNew(1);
      vector2.set(0, 2);
      vector2.setValueCount(1);

      vector3.allocateNew(1);
      vector3.set(0, 3);
      vector3.setValueCount(1);

      vector4.allocateNew(1);
      vector4.set(0, 4);
      vector4.setValueCount(1);

      batch1.setRowCount(1);
      batch2.setRowCount(1);

      // FlightStream will return 2 record batches
      FlightStream flightStream = mock(FlightStream.class);
      when(flightStream.next()).thenReturn(true).thenReturn(true).thenReturn(false);
      when(flightStream.hasRoot()).thenReturn(true).thenReturn(true).thenReturn(false);
      when(flightStream.getRoot()).thenReturn(batch1).thenReturn(batch2);

      UserProtos.PreparedStatementHandle dummyServerHandle =
          UserProtos.PreparedStatementHandle.newBuilder()
              .setServerInfo(ByteString.copyFromUtf8("dummyServerHandle"))
              .build();
      ByteString dummySchema = ByteString.copyFromUtf8("dummySchema");
      ByteString dummyParamSchema = ByteString.copyFromUtf8("dummyParamSchema");

      FlightSql.CommandPreparedStatementQuery commandPreparedStatementQuery =
          FlightSql.CommandPreparedStatementQuery.newBuilder()
              .setPreparedStatementHandle(
                  UserProtos.PreparedStatementArrow.newBuilder()
                      .setServerHandle(dummyServerHandle)
                      .setArrowSchema(dummySchema)
                      .setParameterArrowSchema(dummyParamSchema)
                      .build()
                      .toByteString())
              .build();

      DremioFlightProducer flightProducer =
          new DremioFlightProducer(
              Optional.of(location),
              sessionsManager,
              () -> userWorker,
              () -> optionManager,
              allocator,
              () -> requestContextDecorator,
              responseHandlerFactory,
              null);

      flightProducer
          .acceptPutPreparedStatementQuery(
              commandPreparedStatementQuery, null, flightStream, streamListener)
          .run();

      try (PutResult putResult = streamListener.read()) {
        FlightSql.DoPutPreparedStatementResult doPutPreparedStatementResult =
            FlightSql.DoPutPreparedStatementResult.parseFrom(
                putResult.getApplicationMetadata().nioBuffer());
        UserProtos.PreparedStatementArrow handle =
            UserProtos.PreparedStatementArrow.parseFrom(
                doPutPreparedStatementResult.getPreparedStatementHandle());

        // PreparedStatementHandle should now include the parameter list from the record batches
        assertThat(handle.getServerHandle()).isEqualTo(dummyServerHandle);
        assertThat(handle.getArrowSchema()).isEqualTo(dummySchema);
        assertThat(handle.getParameterArrowSchema()).isEqualTo(dummyParamSchema);
        assertThat(handle.getParametersList())
            .hasSize(5)
            .map(UserProtos.PreparedStatementParameterValue::getIntValue)
            .containsExactly(0, 1, 2, 3, 4);
      }
    }
  }
}
