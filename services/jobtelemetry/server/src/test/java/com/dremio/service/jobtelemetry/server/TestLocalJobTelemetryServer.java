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
package com.dremio.service.jobtelemetry.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.CloseableThreadPool;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.AttemptEvent.State;
import com.dremio.options.OptionManager;
import com.dremio.service.DirectProvider;
import com.dremio.service.grpc.GrpcChannelBuilderFactory;
import com.dremio.service.grpc.GrpcServerBuilderFactory;
import com.dremio.service.grpc.SimpleGrpcChannelBuilderFactory;
import com.dremio.service.grpc.SimpleGrpcServerBuilderFactory;
import com.dremio.service.jobtelemetry.DeleteProfileRequest;
import com.dremio.service.jobtelemetry.GetQueryProfileRequest;
import com.dremio.service.jobtelemetry.JobTelemetryClient;
import com.dremio.service.jobtelemetry.PutPlanningProfileRequest;
import com.dremio.service.jobtelemetry.PutTailProfileRequest;
import com.dremio.service.jobtelemetry.server.store.ProfileDistStoreConfig;
import com.dremio.telemetry.utils.GrpcTracerFacade;
import com.dremio.telemetry.utils.TracerFacade;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests LocalJobTelemetryServer. */
public class TestLocalJobTelemetryServer {
  private final GrpcChannelBuilderFactory grpcChannelBuilderFactory =
      new SimpleGrpcChannelBuilderFactory(TracerFacade.INSTANCE);
  private final GrpcServerBuilderFactory grpcServerBuilderFactory =
      new SimpleGrpcServerBuilderFactory(TracerFacade.INSTANCE);
  private final GrpcTracerFacade tracer = new GrpcTracerFacade(TracerFacade.INSTANCE);

  private KVStoreProvider kvStoreProvider;
  private LegacyKVStoreProvider legacyKVStoreProvider;
  private LocalJobTelemetryServer server;
  private JobTelemetryClient client;
  private OptionManager optionManager;
  private ProfileDistStoreConfig profileDistStoreConfig;

  @Before
  public void setUp() throws Exception {
    final CoordinationProtos.NodeEndpoint node =
        CoordinationProtos.NodeEndpoint.newBuilder().setFabricPort(30).build();

    kvStoreProvider = TempLocalKVStoreProviderCreator.create();
    legacyKVStoreProvider = TempLegacyKVStoreProviderCreator.create();
    optionManager = mock(OptionManager.class);
    profileDistStoreConfig = mock(ProfileDistStoreConfig.class);
    server =
        new LocalJobTelemetryServer(
            () -> optionManager,
            grpcServerBuilderFactory,
            DirectProvider.wrap(kvStoreProvider),
            DirectProvider.wrap(legacyKVStoreProvider),
            DirectProvider.wrap(node),
            () -> profileDistStoreConfig,
            tracer,
            CloseableThreadPool::newCachedThreadPool);
    server.start();

    client = new JobTelemetryClient(grpcChannelBuilderFactory, DirectProvider.wrap(node));
    client.start();
  }

  @After
  public void tearDown() throws Exception {
    AutoCloseables.close(client, server, kvStoreProvider, legacyKVStoreProvider);
  }

  @Test
  public void testFullProfile() throws Exception {
    UserBitShared.QueryId queryId =
        UserBitShared.QueryId.newBuilder().setPart1(10000).setPart2(10001).build();

    final UserBitShared.QueryProfile planningProfile =
        UserBitShared.QueryProfile.newBuilder()
            .setPlan("PLAN_VALUE")
            .setQuery("Select * from plan")
            .setState(UserBitShared.QueryResult.QueryState.ENQUEUED)
            .addStateList(
                AttemptEvent.newBuilder().setState(State.QUEUED).setStartTime(20L).build())
            .build();
    client
        .getBlockingStub()
        .putQueryPlanningProfile(
            PutPlanningProfileRequest.newBuilder()
                .setQueryId(queryId)
                .setProfile(planningProfile)
                .build());

    final UserBitShared.QueryProfile tailProfile =
        UserBitShared.QueryProfile.newBuilder()
            .setPlan("PLAN_VALUE")
            .setQuery("Select * from plan")
            .setErrorNode("ERROR_NODE")
            .setState(UserBitShared.QueryResult.QueryState.CANCELED)
            .addStateList(
                AttemptEvent.newBuilder().setState(State.CANCELED).setStartTime(20L).build())
            .build();
    client
        .getBlockingStub()
        .putQueryTailProfile(
            PutTailProfileRequest.newBuilder().setQueryId(queryId).setProfile(tailProfile).build());

    final UserBitShared.QueryProfile expectedMergedProfile =
        UserBitShared.QueryProfile.newBuilder()
            .setPlan("PLAN_VALUE")
            .setQuery("Select * from plan")
            .setErrorNode("ERROR_NODE")
            .setState(UserBitShared.QueryResult.QueryState.CANCELED)
            .addStateList(
                AttemptEvent.newBuilder().setState(State.CANCELED).setStartTime(20L).build())
            .setTotalFragments(0)
            .setFinishedFragments(0)
            .build();

    assertEquals(
        expectedMergedProfile,
        client
            .getBlockingStub()
            .getQueryProfile(GetQueryProfileRequest.newBuilder().setQueryId(queryId).build())
            .getProfile());

    // delete profile.
    client
        .getBlockingStub()
        .deleteProfile(DeleteProfileRequest.newBuilder().setQueryId(queryId).build());

    // verify it's gone.
    assertThatThrownBy(
            () ->
                client
                    .getBlockingStub()
                    .getQueryProfile(
                        GetQueryProfileRequest.newBuilder().setQueryId(queryId).build()))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining(
            "Unable to get query profile. Profile not found for the given queryId.");
  }

  @Test
  public void testCloudBasedDistStore() {
    when(profileDistStoreConfig.getConnection()).thenReturn("dremioS3:///");
    assertTrue(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("dremioAzureStorage:///");
    assertTrue(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("dremiogcs:///");
    assertTrue(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("file:///");
    assertFalse(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("hdfs:///");
    assertFalse(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("maprfs:///");
    assertFalse(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn("");
    assertFalse(server.verifyCloudBasedDistStore());

    when(profileDistStoreConfig.getConnection()).thenReturn(null);
    assertFalse(server.verifyCloudBasedDistStore());
  }
}
