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

import static com.dremio.io.file.UriSchemes.DREMIO_AZURE_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_S3_SCHEME;

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.CloseableExecutorService;
import com.dremio.common.concurrent.ContextMigratingExecutorService.ContextMigratingCloseableExecutorService;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.options.OptionManager;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.service.Service;
import com.dremio.service.grpc.GrpcServerBuilderFactory;
import com.dremio.service.jobtelemetry.JobTelemetryRpcUtils;
import com.dremio.service.jobtelemetry.server.store.DistProfileStore;
import com.dremio.service.jobtelemetry.server.store.LegacyLocalProfileStore;
import com.dremio.service.jobtelemetry.server.store.LocalProfileStore;
import com.dremio.service.jobtelemetry.server.store.ProfileDistStoreConfig;
import com.dremio.service.jobtelemetry.server.store.ProfileStore;
import com.dremio.telemetry.utils.GrpcTracerFacade;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.Server;
import io.grpc.util.TransmitStatusRuntimeExceptionInterceptor;
import java.util.function.Function;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local job telemetry server, running on coordinator node. */
@Options
public class LocalJobTelemetryServer implements Service {
  private static final Logger logger = LoggerFactory.getLogger(LocalJobTelemetryServer.class);
  public static final BooleanValidator ENABLE_INTERMEDIATE_PROFILE_BLOB_STORAGE =
      new BooleanValidator("intermediate.profile.blob.storage", true);
  public static final BooleanValidator ENABLE_PROFILE_IN_DIST_STORE =
      new BooleanValidator("profile.dist.store.enabled", false);
  private Provider<OptionManager> optionManagerProvider;
  private final GrpcServerBuilderFactory grpcFactory;
  private final Provider<LegacyKVStoreProvider> legacyKvStoreProvider;
  private final Provider<KVStoreProvider> kvStoreProvider;
  private final Provider<CoordinationProtos.NodeEndpoint> selfEndpoint;
  private final Provider<ProfileDistStoreConfig> profileDistStoreConfigProvider;
  private final Function<String, CloseableExecutorService> executorServiceFactory;
  private ContextMigratingCloseableExecutorService executorService;
  private JobTelemetryServiceImpl jobTelemetryService;
  private GrpcTracerFacade tracer;
  private ProfileStore profileStore;
  private Server server;

  public LocalJobTelemetryServer(
      Provider<OptionManager> optionManagerProvider,
      GrpcServerBuilderFactory grpcServerBuilderFactory,
      Provider<KVStoreProvider> kvStoreProvider,
      Provider<LegacyKVStoreProvider> legacyKvStoreProvider,
      Provider<CoordinationProtos.NodeEndpoint> selfEndpoint,
      Provider<ProfileDistStoreConfig> profileDistStoreConfigProvider,
      GrpcTracerFacade tracer,
      Function<String, CloseableExecutorService> executorServiceFactory) {
    this.grpcFactory = grpcServerBuilderFactory;
    this.kvStoreProvider = kvStoreProvider;
    this.legacyKvStoreProvider = legacyKvStoreProvider;
    this.selfEndpoint = selfEndpoint;
    this.profileDistStoreConfigProvider = profileDistStoreConfigProvider;
    this.tracer = tracer;
    this.executorServiceFactory = executorServiceFactory;
    this.optionManagerProvider = optionManagerProvider;
  }

  @Override
  public void start() throws Exception {
    boolean enableKVstoreProfileStorage =
        optionManagerProvider.get().getOption(ENABLE_INTERMEDIATE_PROFILE_BLOB_STORAGE);
    boolean enableProfileInDistStore =
        optionManagerProvider.get().getOption(ENABLE_PROFILE_IN_DIST_STORE)
            && verifyCloudBasedDistStore();
    if (enableProfileInDistStore) {
      profileStore = new DistProfileStore(profileDistStoreConfigProvider);
    } else {
      profileStore =
          enableKVstoreProfileStorage
              ? new LocalProfileStore(kvStoreProvider.get())
              : new LegacyLocalProfileStore(legacyKvStoreProvider.get());
    }
    profileStore.start();

    executorService =
        new ContextMigratingCloseableExecutorService<>(
            executorServiceFactory.apply("local-job-telemetry-service-"));
    jobTelemetryService = new JobTelemetryServiceImpl(profileStore, tracer, true, executorService);

    server =
        JobTelemetryRpcUtils.newInProcessServerBuilder(
                grpcFactory, selfEndpoint.get().getFabricPort())
            .maxInboundMetadataSize(81920) // GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE * 10
            .intercept(TransmitStatusRuntimeExceptionInterceptor.instance())
            .addService(jobTelemetryService)
            .build();

    server.start();
    logger.info("LocalJobTelemetryServer started");
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(
        () -> {
          if (server != null) {
            server.shutdown();
            server = null;
          }
        },
        jobTelemetryService,
        executorService,
        profileStore);
    logger.info("LocalJobTelemetryServer stopped");
  }

  @VisibleForTesting
  public Provider<ProfileDistStoreConfig> getProfileDistStoreConfigProvider() {
    return profileDistStoreConfigProvider;
  }

  @VisibleForTesting
  boolean verifyCloudBasedDistStore() {
    String profileDistStoreConfigConnection = profileDistStoreConfigProvider.get().getConnection();
    boolean isCloudBasedDistStore =
        profileDistStoreConfigConnection != null
            && (profileDistStoreConfigConnection.startsWith(DREMIO_S3_SCHEME)
                || profileDistStoreConfigConnection.startsWith(DREMIO_AZURE_SCHEME)
                || profileDistStoreConfigConnection.startsWith(DREMIO_GCS_SCHEME));
    if (!isCloudBasedDistStore) {
      logger.info(
          "Storing profiles in distributed storage is not supported for {}",
          profileDistStoreConfigConnection);
      return false;
    }
    return true;
  }
}
