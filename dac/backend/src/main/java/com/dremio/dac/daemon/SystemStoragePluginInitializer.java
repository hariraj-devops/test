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
package com.dremio.dac.daemon;

import static com.dremio.dac.service.datasets.DatasetDownloadManager.DATASET_DOWNLOAD_STORAGE_PLUGIN;
import static com.dremio.dac.support.SupportService.*;
import static com.dremio.exec.ExecConstants.METADATA_CLOUD_CACHING_ENABLED;
import static com.dremio.exec.ExecConstants.NODE_HISTORY_ENABLED;
import static com.dremio.service.reflection.ReflectionOptions.CLOUD_CACHING_ENABLED;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;

import com.dremio.common.DeferredException;
import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.dac.homefiles.HomeFileConf;
import com.dremio.dac.homefiles.HomeFileSystemStoragePlugin;
import com.dremio.dac.service.nodeshistory.NodesHistoryPluginInitializer;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.FileSystemConf;
import com.dremio.exec.store.dfs.InternalFileConf;
import com.dremio.exec.store.dfs.MetadataStoragePluginConfig;
import com.dremio.exec.store.dfs.SchemaMutability;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePluginConfigFactory;
import com.dremio.options.TypeValidators;
import com.dremio.plugins.nodeshistory.NodeHistorySourceConfigFactory;
import com.dremio.service.BindingProvider;
import com.dremio.service.DirectProvider;
import com.dremio.service.Initializer;
import com.dremio.service.coordinator.ProjectConfig;
import com.dremio.service.coordinator.TaskLeaderElection;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.reflection.materialization.AccelerationStoragePluginConfig;
import com.google.common.annotations.VisibleForTesting;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ConcurrentModificationException;
import java.util.concurrent.Callable;
import org.jetbrains.annotations.Nullable;

/**
 * Create all the system storage plugins, such as results, accelerator, etc. Also creates the
 * backing directories for each of these plugins
 */
@SuppressWarnings("unused") // found through reflection search and executed by InitializerRegistry
public class SystemStoragePluginInitializer implements Initializer<Void> {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SystemStoragePluginInitializer.class);

  private static final String LOCAL_TASK_LEADER_NAME = "plugininitv3";
  private static final int MAX_CACHE_SPACE_PERCENT = 100;

  @Override
  public Void initialize(BindingProvider provider) throws Exception {
    initializeIcebergTablePlugin(provider);
    if (!canStoragePluginsBeCreated(provider)) {
      logger.debug("System storage plugins will be created only on master coordinator");
      return null;
    }
    return initializePlugins(provider);
  }

  private static void initializeIcebergTablePlugin(BindingProvider provider) {
    createIcebergTablePlugin(
        provider.lookup(ProjectConfig.class),
        provider.lookup(SabotContext.class).getNamespaceService(SYSTEM_USERNAME),
        provider.lookup(DremioConfig.class),
        provider.lookup(SystemIcebergTablesStoragePluginConfigFactory.class),
        provider.lookup(CatalogService.class));
  }

  private static @Nullable Void initializePlugins(BindingProvider provider) throws Exception {
    SabotContext sabotContext = provider.lookup(SabotContext.class);
    if (!sabotContext.isMaster()) {
      return initializePluginsForMasterlessMode(provider, sabotContext);
    }

    initializeSystemStoragePlugins(provider, sabotContext);
    return null;
  }

  private static boolean canStoragePluginsBeCreated(BindingProvider provider) {
    SabotContext sabotContext = provider.lookup(SabotContext.class);
    DremioConfig dremioConfig = provider.lookup(DremioConfig.class);

    boolean isDistributedCoordinator =
        dremioConfig.isMasterlessEnabled() && sabotContext.isCoordinator();
    boolean isMaster = sabotContext.isMaster();
    return isMaster || isDistributedCoordinator;
  }

  private static @Nullable Void initializePluginsForMasterlessMode(
      BindingProvider provider, SabotContext sabotContext) throws Exception {
    TaskLeaderElection taskLeaderElection = electTaskLeader(sabotContext);
    if (!taskLeaderElection.isTaskLeader()) {
      logger.debug("System storage plugins will be created only on task leader coordinator");
      return null;
    }

    try {
      return initializeSystemStoragePlugins(provider, sabotContext);
    } catch (Exception e) {
      logger.warn(
          "Exception while trying to init system plugins. Let other node (if available) handle it");
      // close leader elections for this service
      // let others take over leadership - if they initialize later
      taskLeaderElection.close();
      throw e;
    }
  }

  private static TaskLeaderElection electTaskLeader(SabotContext sabotContext) throws Exception {
    TaskLeaderElection taskLeaderElection =
        new TaskLeaderElection(
            LOCAL_TASK_LEADER_NAME,
            DirectProvider.wrap(sabotContext.getClusterCoordinator()),
            DirectProvider.wrap(sabotContext.getClusterCoordinator()),
            DirectProvider.wrap(sabotContext.getEndpoint()));
    taskLeaderElection.start();
    // waiting for the leader to show
    taskLeaderElection.getTaskLeader();
    return taskLeaderElection;
  }

  private static void createIcebergTablePlugin(
      ProjectConfig projectConfig,
      NamespaceService systemNamespaceService,
      DremioConfig dremioConfig,
      SystemIcebergTablesStoragePluginConfigFactory systemIcebergTablesStoragePluginConfigFactory,
      CatalogService catalogService) {
    final ProjectConfig.DistPathConfig systemIcebergTablesPathConfig =
        projectConfig.getSystemIcebergTablesConfig();
    final DeferredException deferred = new DeferredException();

    final boolean enableAsyncForSystemIcebergTablesStorage =
        enable(dremioConfig, DremioConfig.DEBUG_SYSTEM_ICEBERG_TABLES_STORAGE_ASYNC_ENABLED);

    final ConnectionConf<?, ?> connectionConf =
        systemIcebergTablesStoragePluginConfigFactory.create(
            systemIcebergTablesPathConfig.getUri(),
            enableAsyncForSystemIcebergTablesStorage,
            isEnableS3FileStatusCheck(dremioConfig, systemIcebergTablesPathConfig),
            systemIcebergTablesPathConfig.getDataCredentials());

    createSafe(
        catalogService,
        systemNamespaceService,
        SystemIcebergTablesStoragePluginConfigFactory.create(connectionConf),
        deferred);
  }

  private static @Nullable Void initializeSystemStoragePlugins(
      BindingProvider provider, SabotContext sabotContext) throws Exception {
    DremioConfig dremioConfig = provider.lookup(DremioConfig.class);
    final CatalogService catalogService = provider.lookup(CatalogService.class);
    final NamespaceService systemNamespaceService =
        sabotContext.getNamespaceService(SYSTEM_USERNAME);
    final DeferredException deferred = new DeferredException();
    final ProjectConfig projectConfig = provider.lookup(ProjectConfig.class);
    final Path supportPath =
        Paths.get(sabotContext.getOptionManager().getOption(TEMPORARY_SUPPORT_PATH));
    final Path logPath = Paths.get(System.getProperty(DREMIO_LOG_PATH_PROPERTY, "/var/log/dremio"));
    final ProjectConfig.DistPathConfig accelerationPathConfig =
        projectConfig.getAcceleratorConfig();
    final ProjectConfig.DistPathConfig scratchPathConfig = projectConfig.getScratchConfig();
    final URI downloadPath = dremioConfig.getURI(DremioConfig.DOWNLOADS_PATH_STRING);
    final URI resultsPath = dremioConfig.getURI(DremioConfig.RESULTS_PATH_STRING);
    // Do not construct URI simply by concatenating, as it might not be encoded properly
    final URI logsPath = new URI("pdfs", "//" + logPath.toUri().getPath(), null);
    final URI supportURI = supportPath.toUri();
    final int maxCacheSpacePercent =
        dremioConfig.hasPath(DremioConfig.DEBUG_DIST_MAX_CACHE_SPACE_PERCENT)
            ? dremioConfig.getInt(DremioConfig.DEBUG_DIST_MAX_CACHE_SPACE_PERCENT)
            : MAX_CACHE_SPACE_PERCENT;

    initializeHomeStoragePlugin(
        projectConfig,
        catalogService,
        systemNamespaceService,
        dremioConfig,
        scratchPathConfig,
        deferred);
    initializeAccelerationStoragePlugin(
        sabotContext,
        dremioConfig,
        accelerationPathConfig,
        catalogService,
        systemNamespaceService,
        maxCacheSpacePercent,
        deferred);
    initializeJobStoragePlugin(
        dremioConfig, catalogService, systemNamespaceService, resultsPath, deferred);
    initializeScratchStoragePlugin(
        dremioConfig, catalogService, systemNamespaceService, scratchPathConfig, deferred);
    initializeDownloadDataStoragePlugin(
        dremioConfig, catalogService, systemNamespaceService, downloadPath, deferred);
    initializeProfileStoragePlugin(
        dremioConfig, projectConfig, catalogService, systemNamespaceService, deferred);
    initializeMetadataStoragePlugin(
        sabotContext,
        dremioConfig,
        projectConfig,
        catalogService,
        systemNamespaceService,
        maxCacheSpacePercent,
        deferred);
    initializeLogsStoragePlugin(
        dremioConfig, catalogService, systemNamespaceService, logsPath, deferred);
    initializeLocalStoragePlugin(
        dremioConfig, catalogService, systemNamespaceService, supportURI, deferred);
    initializeNodeHistoryStoragePlugin(
        provider, sabotContext, projectConfig, catalogService, systemNamespaceService, deferred);

    deferred.throwAndClear();
    return null;
  }

  private static void initializeNodeHistoryStoragePlugin(
      BindingProvider provider,
      SabotContext sabotContext,
      ProjectConfig projectConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      DeferredException deferred) {
    if (sabotContext.getOptionManager().getOption(NODE_HISTORY_ENABLED)) {
      final ProjectConfig.DistPathConfig nodeHistoryPathConfig =
          projectConfig.getNodeHistoryConfig();
      NodesHistoryPluginInitializer nodesHistoryPluginInitializer =
          provider.lookup(NodesHistoryPluginInitializer.class);
      createSafe(
          catalogService,
          systemNamespaceService,
          NodeHistorySourceConfigFactory.newSourceConfig(
              nodeHistoryPathConfig.getUri(), nodeHistoryPathConfig.getDataCredentials()),
          deferred);
      deferExceptions(
          () -> {
            nodesHistoryPluginInitializer.initialize();
            return null;
          },
          deferred);
    }
  }

  private static void initializeLocalStoragePlugin(
      DremioConfig dremioConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      URI supportURI,
      DeferredException deferred) {
    final boolean enableAsyncForSupport =
        enable(dremioConfig, DremioConfig.DEBUG_SUPPORT_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            LOCAL_STORAGE_PLUGIN,
            supportURI,
            SchemaMutability.SYSTEM_TABLE,
            CatalogService.NEVER_REFRESH_POLICY,
            enableAsyncForSupport,
            null),
        deferred);
  }

  private static void initializeLogsStoragePlugin(
      DremioConfig dremioConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      URI logsPath,
      DeferredException deferred) {
    final boolean enableAsyncForLogs = enable(dremioConfig, DremioConfig.DEBUG_LOGS_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            LOGS_STORAGE_PLUGIN,
            logsPath,
            SchemaMutability.NONE,
            CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE,
            enableAsyncForLogs,
            null),
        deferred);
  }

  private static void initializeMetadataStoragePlugin(
      SabotContext sabotContext,
      DremioConfig dremioConfig,
      ProjectConfig projectConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      int maxCacheSpacePercent,
      DeferredException deferred) {
    final boolean enableAsyncForMetadata =
        enable(dremioConfig, DremioConfig.DEBUG_METADATA_ASYNC_ENABLED);

    final ProjectConfig.DistPathConfig metadataPathConfig = projectConfig.getMetadataConfig();
    final boolean enableS3FileStatusCheckForMetadata =
        isEnableS3FileStatusCheck(dremioConfig, metadataPathConfig);
    boolean enableCachingForMetadata =
        isEnableCaching(
            sabotContext, dremioConfig, metadataPathConfig, METADATA_CLOUD_CACHING_ENABLED);

    createSafe(
        catalogService,
        systemNamespaceService,
        MetadataStoragePluginConfig.create(
            metadataPathConfig.getUri(),
            enableAsyncForMetadata,
            enableCachingForMetadata,
            maxCacheSpacePercent,
            enableS3FileStatusCheckForMetadata,
            metadataPathConfig.getDataCredentials()),
        deferred);
  }

  private static void initializeProfileStoragePlugin(
      DremioConfig dremioConfig,
      ProjectConfig projectConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      DeferredException deferred) {
    final boolean enableAsyncForProfile =
        enable(dremioConfig, DremioConfig.DEBUG_PROFILE_ASYNC_ENABLED);
    final ProjectConfig.DistPathConfig profilePathConfig = projectConfig.getProfileConfig();
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            DACDaemonModule.PROFILE_STORAGEPLUGIN_NAME,
            profilePathConfig.getUri(),
            SchemaMutability.SYSTEM_TABLE,
            CatalogService.DEFAULT_METADATA_POLICY_WITH_AUTO_PROMOTE,
            enableAsyncForProfile,
            profilePathConfig.getDataCredentials()),
        deferred);
  }

  private static void initializeDownloadDataStoragePlugin(
      DremioConfig dremioConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      URI downloadPath,
      DeferredException deferred) {
    final boolean enableAsyncForDownload =
        enable(dremioConfig, DremioConfig.DEBUG_DOWNLOAD_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            DATASET_DOWNLOAD_STORAGE_PLUGIN,
            downloadPath,
            SchemaMutability.USER_TABLE,
            CatalogService.NEVER_REFRESH_POLICY,
            enableAsyncForDownload,
            null),
        deferred);
  }

  private static void initializeScratchStoragePlugin(
      DremioConfig dremioConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      ProjectConfig.DistPathConfig scratchPathConfig,
      DeferredException deferred) {
    final boolean enableAsyncForScratch =
        enable(dremioConfig, DremioConfig.DEBUG_SCRATCH_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            DACDaemonModule.SCRATCH_STORAGEPLUGIN_NAME,
            scratchPathConfig.getUri(),
            SchemaMutability.USER_TABLE,
            CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE,
            enableAsyncForScratch,
            scratchPathConfig.getDataCredentials()),
        deferred);
  }

  private static void initializeJobStoragePlugin(
      DremioConfig dremioConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      URI resultsPath,
      DeferredException deferred) {
    final boolean enableAsyncForJobs = enable(dremioConfig, DremioConfig.DEBUG_JOBS_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        InternalFileConf.create(
            DACDaemonModule.JOBS_STORAGEPLUGIN_NAME,
            resultsPath,
            SchemaMutability.SYSTEM_TABLE,
            CatalogService.DEFAULT_METADATA_POLICY_WITH_AUTO_PROMOTE,
            enableAsyncForJobs,
            null),
        deferred);
  }

  private static void initializeAccelerationStoragePlugin(
      SabotContext sabotContext,
      DremioConfig dremioConfig,
      ProjectConfig.DistPathConfig accelerationPathConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      int maxCacheSpacePercent,
      DeferredException deferred) {
    final boolean enableAsyncForAcceleration =
        enable(dremioConfig, DremioConfig.DEBUG_DIST_ASYNC_ENABLED);

    final boolean enableS3FileStatusCheck =
        isEnableS3FileStatusCheck(dremioConfig, accelerationPathConfig);
    boolean enableCachingForAcceleration =
        isEnableCaching(sabotContext, dremioConfig, accelerationPathConfig, CLOUD_CACHING_ENABLED);

    createSafe(
        catalogService,
        systemNamespaceService,
        AccelerationStoragePluginConfig.create(
            accelerationPathConfig.getUri(),
            enableAsyncForAcceleration,
            enableCachingForAcceleration,
            maxCacheSpacePercent,
            enableS3FileStatusCheck,
            accelerationPathConfig.getDataCredentials()),
        deferred);
  }

  private static void initializeHomeStoragePlugin(
      ProjectConfig projectConfig,
      CatalogService catalogService,
      NamespaceService systemNamespaceService,
      DremioConfig dremioConfig,
      ProjectConfig.DistPathConfig scratchPathConfig,
      DeferredException deferred) {
    final ProjectConfig.DistPathConfig uploadsPathConfig = projectConfig.getUploadsConfig();
    final boolean enableAsyncForUploads =
        enable(dremioConfig, DremioConfig.DEBUG_UPLOADS_ASYNC_ENABLED);
    createSafe(
        catalogService,
        systemNamespaceService,
        HomeFileConf.create(
            HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME,
            uploadsPathConfig.getUri(),
            dremioConfig.getThisNode(),
            SchemaMutability.USER_TABLE,
            CatalogService.NEVER_REFRESH_POLICY,
            enableAsyncForUploads,
            scratchPathConfig.getDataCredentials()),
        deferred);
  }

  private static boolean isEnableCaching(
      SabotContext sabotContext,
      DremioConfig config,
      ProjectConfig.DistPathConfig pathConfig,
      TypeValidators.BooleanValidator optionValidator) {
    boolean enableCachingForAcceleration = enable(config, DremioConfig.DEBUG_DIST_CACHING_ENABLED);
    if (FileSystemConf.isCloudFileSystemScheme(pathConfig.getUri().getScheme())) {
      enableCachingForAcceleration = sabotContext.getOptionManager().getOption(optionValidator);
    }
    return enableCachingForAcceleration;
  }

  private static boolean isEnableS3FileStatusCheck(
      DremioConfig dremioConfig, ProjectConfig.DistPathConfig pathConfig) {
    return !FileSystemConf.CloudFileSystemScheme.S3_FILE_SYSTEM_SCHEME
            .getScheme()
            .equals(pathConfig.getUri().getScheme())
        || enable(dremioConfig, DremioConfig.DEBUG_DIST_S3_FILE_STATUS_CHECK);
  }

  private static boolean enable(DremioConfig config, String path) {
    return !config.hasPath(path) || config.getBoolean(path);
  }

  protected static void createSafe(
      final CatalogService catalogService,
      final NamespaceService systemNamespaceService,
      final SourceConfig config,
      DeferredException deferred) {
    deferExceptions(
        () -> {
          createOrUpdateSystemSource(catalogService, systemNamespaceService, config);
          return null;
        },
        deferred);
  }

  private static void deferExceptions(Callable<Void> callable, DeferredException deferred) {
    try {
      callable.call();
    } catch (Exception ex) {
      deferred.addException(ex);
    }
  }

  /**
   * Create provided source if does not exist or update if does exist used for Such internal sources
   * that can change based on the external configuration such as hdfs to pdfs, directory structures
   *
   * @param catalogService
   * @param ns
   * @param config
   */
  @VisibleForTesting
  static void createOrUpdateSystemSource(
      final CatalogService catalogService, final NamespaceService ns, final SourceConfig config)
      throws Exception {
    try {
      config.setAllowCrossSourceSelection(true);
      final boolean isCreated = catalogService.createSourceIfMissingWithThrow(config);
      if (isCreated) {
        return;
      }
    } catch (ConcurrentModificationException ex) {
      // someone else got there first, ignore this failure.
      logger.info(ex.getMessage(), ex);
      // proceed with update
    } catch (UserException ex) {
      if (ex.getErrorType() != UserBitShared.DremioPBError.ErrorType.CONCURRENT_MODIFICATION) {
        throw ex;
      }
      // someone else got there first, ignore this failure.
      logger.info(ex.getMessage(), ex);
    }
    final NamespaceKey nsKey = new NamespaceKey(config.getName());
    final SourceConfig oldConfig = ns.getSource(nsKey);
    final SourceConfig updatedConfig = config;
    // make incoming config match existing config to be used in comparison
    updatedConfig
        .setId(oldConfig.getId())
        .setCtime(oldConfig.getCtime())
        .setTag(oldConfig.getTag())
        .setConfigOrdinal(oldConfig.getConfigOrdinal())
        .setLastModifiedAt(oldConfig.getLastModifiedAt());
    // if old and new configs match don't update
    if (oldConfig.equals(updatedConfig)) {
      return;
    }
    catalogService
        .getSystemUserCatalog()
        .updateSource(updatedConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }
}
