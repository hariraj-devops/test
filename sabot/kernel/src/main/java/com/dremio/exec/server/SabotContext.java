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
package com.dremio.exec.server;

import static com.google.common.base.Preconditions.checkNotNull;

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.OutOfMemoryOrResourceExceptionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.memory.DremioRootAllocator;
import com.dremio.common.memory.MemoryDebugInfo;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.catalog.CatalogSabotContext;
import com.dremio.exec.catalog.ViewCreatorFactory;
import com.dremio.exec.compile.CodeCompiler;
import com.dremio.exec.expr.ExpressionSplitCache;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.maestro.GlobalKeysService;
import com.dremio.exec.ops.QueryContextCreator;
import com.dremio.exec.ops.QueryContextCreatorImpl;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.RulesFactory;
import com.dremio.exec.planner.cost.RelMetadataQuerySupplier;
import com.dremio.exec.planner.observer.QueryObserverFactory;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.exec.store.dfs.MetadataIOPool;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.exec.store.sys.accel.AccelerationManager;
import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;
import com.dremio.exec.store.sys.statistics.StatisticsAdministrationService;
import com.dremio.exec.store.sys.statistics.StatisticsListManager;
import com.dremio.exec.store.sys.statistics.StatisticsService;
import com.dremio.exec.store.sys.udf.UserDefinedFunctionService;
import com.dremio.exec.work.WorkStats;
import com.dremio.exec.work.protector.ForemenWorkManager;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.resource.GroupResourceInformation;
import com.dremio.resource.common.ReflectionRoutingManager;
import com.dremio.service.catalog.DatasetCatalogServiceGrpc.DatasetCatalogServiceBlockingStub;
import com.dremio.service.catalog.InformationSchemaServiceGrpc.InformationSchemaServiceBlockingStub;
import com.dremio.service.conduit.client.ConduitProvider;
import com.dremio.service.conduit.server.ConduitInProcessChannelProvider;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.ClusterCoordinator.Role;
import com.dremio.service.coordinator.CoordinatorModeInfo;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.spill.SpillService;
import com.dremio.service.users.UserService;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.services.credentials.SecretsCreator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.projectnessie.client.api.NessieApiV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* SabotContext
 * TODO - Add description for SabotContext's responsibility.
 */
public class SabotContext implements AutoCloseable, SabotQueryContext, CatalogSabotContext {
  private static final Logger logger = LoggerFactory.getLogger(SabotContext.class);

  private final SabotConfig config;
  private final Set<Role> roles;
  private final BufferAllocator allocator;
  private final PhysicalPlanReader planReader;
  private final ClusterCoordinator coord;
  private final NodeEndpoint endpoint;
  private final FunctionImplementationRegistry functionRegistry;
  private final FunctionImplementationRegistry decimalFunctionImplementationRegistry;
  private final OptionManager optionManager;
  private final Provider<WorkStats> workStatsProvider;
  private final CodeCompiler compiler;
  private final ExpressionSplitCache expressionSplitCache;
  private final ScanResult classpathScan;
  private final LogicalPlanPersistence lpPersistence;
  private final Provider<MaterializationDescriptorProvider> materializationProvider;
  private final NamespaceService.Factory namespaceServiceFactory;
  private final Orphanage.Factory orphanageFactory;
  private final DatasetListingService datasetListing;
  private final LegacyKVStoreProvider kvStoreProvider;
  private final UserService userService;
  private final Provider<QueryObserverFactory> queryObserverFactory;
  private final Provider<AccelerationManager> accelerationManager;
  private final Provider<StatisticsService> statisticsService;
  private final Provider<RelMetadataQuerySupplier> relMetadataQuerySupplier;
  private final Provider<AccelerationListManager> accelerationListManager;
  private final Provider<CatalogService> catalogService;
  private final ConduitProvider conduitProvider;
  private final Provider<InformationSchemaServiceBlockingStub> informationSchemaStub;
  private final Provider<ViewCreatorFactory> viewCreatorFactory;
  private final DremioConfig dremioConfig;
  private final BufferAllocator queryPlanningAllocator;
  private final Provider<SpillService> spillService;
  private final GroupResourceInformation clusterInfo;
  private final FileSystemWrapper fileSystemWrapper;
  private final JobResultInfoProvider jobResultInfoProvider;
  private final List<RulesFactory> rules;
  private final OptionValidatorListing optionValidatorListing;
  private final SchemaFetcherFactoryContext schemaFetcherFactoryContext;
  private final Provider<CoordinatorModeInfo> coordinatorModeInfoProvider;
  private final Provider<NessieApiV2> nessieApiProvider;
  private final Provider<StatisticsAdministrationService.Factory> statisticsAdministrationFactory;
  private final Provider<StatisticsListManager> statisticsListManagerProvider;
  private final Provider<UserDefinedFunctionService> userDefinedFunctionListManagerProvider;
  private final Provider<SimpleJobRunner> jobsRunnerProvider;
  private final Provider<DatasetCatalogServiceBlockingStub> datasetCatalogStub;
  private final Provider<GlobalKeysService> globalCredentailsServiceProvider;
  private final Provider<CredentialsService> credentialsServiceProvider;
  private final Provider<ConduitInProcessChannelProvider> conduitInProcessChannelProviderProvider;
  private final Provider<SysFlightChannelProvider> sysFlightChannelProviderProvider;

  private final Provider<SourceVerifier> sourceVerifierProvider;
  private final Provider<SecretsCreator> secretsCreator;
  private final Provider<ForemenWorkManager> foremenWorkManagerProvider;
  private final Provider<MetadataIOPool> metadataIOPoolProvider;

  private final NodeDebugContextProvider nodeDebugContext;

  private static List<RulesFactory> getRulesFactories(ScanResult scan) {
    ImmutableList.Builder<RulesFactory> factoryBuilder = ImmutableList.builder();
    for (Class<? extends RulesFactory> f : scan.getImplementations(RulesFactory.class)) {
      try {
        factoryBuilder.add(f.getDeclaredConstructor().newInstance());
      } catch (InvocationTargetException ex) {
        logger.warn("Failure while configuring rules factory {}", f.getName(), ex.getCause());
      } catch (ReflectiveOperationException ex) {
        logger.warn("Failure while configuring rules factory {}", f.getName(), ex);
      }
    }
    return factoryBuilder.build();
  }

  SabotContext(
      DremioConfig dremioConfig,
      NodeEndpoint endpoint,
      SabotConfig config,
      Collection<Role> roles,
      ScanResult scan,
      LogicalPlanPersistence lpPersistence,
      BufferAllocator allocator,
      ClusterCoordinator coord,
      Provider<WorkStats> workStatsProvider,
      LegacyKVStoreProvider kvStoreProvider,
      NamespaceService.Factory namespaceServiceFactory,
      Orphanage.Factory orphanageFactory,
      DatasetListingService datasetListing,
      UserService userService,
      Provider<MaterializationDescriptorProvider> materializationProvider,
      Provider<QueryObserverFactory> queryObserverFactory,
      Provider<AccelerationManager> accelerationManager,
      Provider<AccelerationListManager> accelerationListManager,
      Provider<CatalogService> catalogService,
      ConduitProvider conduitProvider,
      Provider<InformationSchemaServiceBlockingStub> informationSchemaStub,
      Provider<ViewCreatorFactory> viewCreatorFactory,
      BufferAllocator queryPlanningAllocator,
      Provider<SpillService> spillService,
      JobResultInfoProvider jobResultInfoProvider,
      PhysicalPlanReader physicalPlanReader,
      OptionManager optionManager,
      FunctionImplementationRegistry functionImplementationRegistry,
      FunctionImplementationRegistry decimalFunctionImplementationRegistry,
      CodeCompiler codeCompiler,
      GroupResourceInformation clusterInfo,
      FileSystemWrapper fileSystemWrapper,
      OptionValidatorListing optionValidatorListing,
      Provider<CoordinatorModeInfo> coordinatorModeInfoProvider,
      Provider<NessieApiV2> nessieApiProvider,
      Provider<StatisticsService> statisticsService,
      Provider<StatisticsAdministrationService.Factory> statisticsAdministrationFactory,
      Provider<StatisticsListManager> statisticsListManagerProvider,
      Provider<UserDefinedFunctionService> userDefinedFunctionListManagerProvider,
      Provider<RelMetadataQuerySupplier> relMetadataQuerySupplier,
      Provider<SimpleJobRunner> jobsRunnerProvider,
      Provider<DatasetCatalogServiceBlockingStub> datasetCatalogStub,
      Provider<GlobalKeysService> globalCredentailsServiceProvider,
      Provider<CredentialsService> credentialsServiceProvider,
      Provider<ConduitInProcessChannelProvider> conduitInProcessChannelProviderProvider,
      Provider<SysFlightChannelProvider> sysFlightChannelProviderProvider,
      Provider<SourceVerifier> sourceVerifierProvider,
      Provider<SecretsCreator> secretsCreatorProvider,
      Provider<ForemenWorkManager> foremenWorkManagerProvider,
      Provider<MetadataIOPool> metadataIOPoolProvider) {
    this.dremioConfig = dremioConfig;
    this.config = config;
    this.roles = ImmutableSet.copyOf(roles);
    this.allocator = allocator;
    this.workStatsProvider = workStatsProvider;
    this.classpathScan = scan;
    this.coord = coord;
    this.endpoint = checkNotNull(endpoint);
    this.lpPersistence = lpPersistence;
    this.accelerationManager = accelerationManager;
    this.accelerationListManager = accelerationListManager;
    this.foremenWorkManagerProvider = foremenWorkManagerProvider;
    this.metadataIOPoolProvider = metadataIOPoolProvider;
    this.planReader = physicalPlanReader;
    this.optionManager = optionManager;
    this.functionRegistry = functionImplementationRegistry;
    this.decimalFunctionImplementationRegistry = decimalFunctionImplementationRegistry;
    this.compiler = codeCompiler;
    this.kvStoreProvider = kvStoreProvider;
    this.namespaceServiceFactory = namespaceServiceFactory;
    this.orphanageFactory = orphanageFactory;
    this.datasetListing = datasetListing;
    this.userService = userService;
    this.queryObserverFactory = queryObserverFactory;
    this.materializationProvider = materializationProvider;
    this.catalogService = catalogService;
    this.conduitProvider = conduitProvider;
    this.informationSchemaStub = informationSchemaStub;
    this.viewCreatorFactory = viewCreatorFactory;
    this.queryPlanningAllocator = queryPlanningAllocator;
    this.spillService = spillService;
    this.clusterInfo = clusterInfo;
    this.fileSystemWrapper = fileSystemWrapper;
    this.jobResultInfoProvider = jobResultInfoProvider;
    this.rules = getRulesFactories(scan);
    this.optionValidatorListing = optionValidatorListing;
    this.schemaFetcherFactoryContext =
        new SchemaFetcherFactoryContext(optionManager, credentialsServiceProvider.get());
    this.coordinatorModeInfoProvider = coordinatorModeInfoProvider;
    this.nessieApiProvider = nessieApiProvider;
    this.statisticsService = statisticsService;
    this.statisticsAdministrationFactory = statisticsAdministrationFactory;
    this.statisticsListManagerProvider = statisticsListManagerProvider;
    this.userDefinedFunctionListManagerProvider = userDefinedFunctionListManagerProvider;
    this.relMetadataQuerySupplier = relMetadataQuerySupplier;
    this.jobsRunnerProvider = jobsRunnerProvider;
    this.datasetCatalogStub = datasetCatalogStub;
    this.globalCredentailsServiceProvider = globalCredentailsServiceProvider;
    this.credentialsServiceProvider = credentialsServiceProvider;
    this.conduitInProcessChannelProviderProvider = conduitInProcessChannelProviderProvider;
    this.sysFlightChannelProviderProvider = sysFlightChannelProviderProvider;
    this.sourceVerifierProvider = sourceVerifierProvider;
    this.secretsCreator = secretsCreatorProvider;
    expressionSplitCache = new ExpressionSplitCache(optionManager, config);
    this.nodeDebugContext =
        (allocator instanceof DremioRootAllocator)
            ? new SabotContext.NodeDebugContextProviderImpl((DremioRootAllocator) allocator)
            : NodeDebugContextProvider.NOOP;
  }

  @Override
  public Orphanage.Factory getOrphanageFactory() {
    return orphanageFactory;
  }

  protected Provider<AccelerationManager> getAccelerationManagerProvider() {
    return accelerationManager;
  }

  protected Provider<StatisticsService> getStatisticsServiceProvider() {
    return statisticsService;
  }

  protected Provider<AccelerationListManager> getAccelerationListManagerProvider() {
    return accelerationListManager;
  }

  @Override
  public Provider<StatisticsListManager> getStatisticsListManagerProvider() {
    return statisticsListManagerProvider;
  }

  @Override
  public Provider<UserDefinedFunctionService> getUserDefinedFunctionListManagerProvider() {
    return userDefinedFunctionListManagerProvider;
  }

  public Provider<ForemenWorkManager> getForemenWorkManagerProvider() {
    return foremenWorkManagerProvider;
  }

  @Override
  public Provider<MetadataIOPool> getMetadataIOPoolProvider() {
    return metadataIOPoolProvider;
  }

  @Override
  public MetadataIOPool getMetadataIOPool() {
    return metadataIOPoolProvider.get();
  }

  public Provider<CatalogService> getCatalogServiceProvider() {
    return catalogService;
  }

  @Override
  public StatisticsService getStatisticsService() {
    return statisticsService.get();
  }

  @Override
  public Provider<StatisticsAdministrationService.Factory>
      getStatisticsAdministrationFactoryProvider() {
    return statisticsAdministrationFactory;
  }

  @Override
  public Provider<RelMetadataQuerySupplier> getRelMetadataQuerySupplier() {
    return relMetadataQuerySupplier;
  }

  @Override
  public Provider<ViewCreatorFactory> getViewCreatorFactoryProvider() {
    return viewCreatorFactory;
  }

  @Override
  public FunctionImplementationRegistry getFunctionImplementationRegistry() {
    return functionRegistry;
  }

  @Override
  public FunctionImplementationRegistry getDecimalFunctionImplementationRegistry() {
    return decimalFunctionImplementationRegistry;
  }

  @Override
  public Set<Role> getRoles() {
    return roles;
  }

  /**
   * @return the option manager. It is important to note that this manager only contains options at
   *     the "system" level and not "session" level.
   */
  @Override
  public OptionManager getOptionManager() {
    return optionManager;
  }

  @Override
  public NodeEndpoint getEndpoint() {
    return endpoint;
  }

  @Override
  public SabotConfig getConfig() {
    return config;
  }

  @Override
  public DremioConfig getDremioConfig() {
    return dremioConfig;
  }

  @Override
  public GroupResourceInformation getClusterResourceInformation() {
    return clusterInfo;
  }

  @Override
  public BufferAllocator getAllocator() {
    return allocator;
  }

  @Override
  public BufferAllocator getQueryPlanningAllocator() {
    return queryPlanningAllocator;
  }

  @Override
  public PhysicalPlanReader getPlanReader() {
    return planReader;
  }

  @Override
  public ClusterCoordinator getClusterCoordinator() {
    return coord;
  }

  public CodeCompiler getCompiler() {
    return compiler;
  }

  @Override
  public LogicalPlanPersistence getLpPersistence() {
    return lpPersistence;
  }

  @Override
  public ScanResult getClasspathScan() {
    return classpathScan;
  }

  @Override
  public NamespaceService getNamespaceService(String userName) {
    // TODO (DX-10053): Add the below check when the ticket is resolved
    // checkIfCoordinator();
    return namespaceServiceFactory.get(userName);
  }

  @Override
  public DatasetListingService getDatasetListing() {
    return datasetListing;
  }

  @Override
  public CatalogService getCatalogService() {
    return catalogService.get();
  }

  @Override
  public ConduitProvider getConduitProvider() {
    return conduitProvider;
  }

  public Provider<InformationSchemaServiceBlockingStub>
      getInformationSchemaServiceBlockingStubProvider() {
    return informationSchemaStub;
  }

  public Provider<ConduitInProcessChannelProvider> getConduitInProcessChannelProviderProvider() {
    return conduitInProcessChannelProviderProvider;
  }

  @Override
  public InformationSchemaServiceBlockingStub getInformationSchemaServiceBlockingStub() {
    return informationSchemaStub.get();
  }

  public SpillService getSpillService() {
    return spillService.get();
  }

  public Provider<SpillService> getSpillServiceProvider() {
    return spillService;
  }

  @Override
  public LegacyKVStoreProvider getKVStoreProvider() {
    return kvStoreProvider;
  }

  @Override
  public Provider<MaterializationDescriptorProvider> getMaterializationProvider() {
    return materializationProvider;
  }

  public Provider<QueryObserverFactory> getQueryObserverFactory() {
    return queryObserverFactory;
  }

  @Override
  public UserService getUserService() {
    checkNotNull(userService, "UserService instance is not set yet.");
    return userService;
  }

  @Override
  public boolean isUserAuthenticationEnabled() {
    return userService != null;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(fileSystemWrapper);
  }

  @Override
  public Provider<WorkStats> getWorkStatsProvider() {
    return workStatsProvider;
  }

  @Override
  public AccelerationManager getAccelerationManager() {
    return accelerationManager.get();
  }

  @Override
  public AccelerationListManager getAccelerationListManager() {
    return accelerationListManager.get();
  }

  @Override
  public boolean isCoordinator() {
    return roles.contains(Role.COORDINATOR);
  }

  @Override
  public boolean isExecutor() {
    return roles.contains(Role.EXECUTOR);
  }

  @Override
  public boolean isMaster() {
    return roles.contains(Role.MASTER);
  }

  @Override
  public Collection<RulesFactory> getInjectedRulesFactories() {
    return rules;
  }

  @Override
  public FileSystemWrapper getFileSystemWrapper() {
    return fileSystemWrapper;
  }

  @Override
  public JobResultInfoProvider getJobResultInfoProvider() {
    return jobResultInfoProvider;
  }

  @Override
  public OptionValidatorListing getOptionValidatorListing() {
    return optionValidatorListing;
  }

  // TODO(DX-26296): Return JdbcSchemaFetcherFactory
  @Override
  public SchemaFetcherFactoryContext getSchemaFetcherFactoryContext() {
    return schemaFetcherFactoryContext;
  }

  @Override
  public Provider<CoordinatorModeInfo> getCoordinatorModeInfoProvider() {
    return this.coordinatorModeInfoProvider;
  }

  @Override
  public AccessControlListingManager getAccessControlListingManager() {
    return null;
  }

  @Override
  public Provider<NessieApiV2> getNessieApiProvider() {
    return nessieApiProvider;
  }

  @Override
  public Provider<SimpleJobRunner> getJobsRunner() {
    return jobsRunnerProvider;
  }

  @Override
  public Provider<DatasetCatalogServiceBlockingStub> getDatasetCatalogBlockingStub() {
    return datasetCatalogStub;
  }

  @Override
  public Provider<GlobalKeysService> getGlobalCredentialsServiceProvider() {
    return globalCredentailsServiceProvider;
  }

  @Override
  public Provider<CredentialsService> getCredentialsServiceProvider() {
    return credentialsServiceProvider;
  }

  @Override
  public Provider<SecretsCreator> getSecretsCreator() {
    return secretsCreator;
  }

  // TODO - Why is this null?
  @Override
  public ReflectionRoutingManager getReflectionRoutingManager() {
    return null;
  }

  @Override
  public Provider<SysFlightChannelProvider> getSysFlightChannelProviderProvider() {
    return sysFlightChannelProviderProvider;
  }

  public Provider<SourceVerifier> getSourceVerifierProvider() {
    return sourceVerifierProvider;
  }

  @Override
  public ExpressionSplitCache getExpressionSplitCache() {
    return expressionSplitCache;
  }

  @Override
  public QueryContextCreator getQueryContextCreator() {
    return new QueryContextCreatorImpl(this);
  }

  public NodeDebugContextProvider getNodeDebugContext() {
    return this.nodeDebugContext;
  }

  public class NodeDebugContextProviderImpl implements NodeDebugContextProvider {
    private final DremioRootAllocator rootAllocator;
    private final String role;

    public NodeDebugContextProviderImpl(final DremioRootAllocator rootAllocator) {
      this.rootAllocator = rootAllocator;
      if (isExecutor()) {
        role = Role.EXECUTOR.name();
      } else if (isCoordinator()) {
        role = Role.COORDINATOR.name();
      } else {
        role = UserException.UNCLASSIFIED_ERROR_ORIGIN;
      }
    }

    @Override
    public void addMemoryContext(UserException.Builder exceptionBuilder) {
      String detail = MemoryDebugInfo.getSummaryFromRoot(rootAllocator);
      exceptionBuilder.setAdditionalExceptionContext(
          new OutOfMemoryOrResourceExceptionContext(
              OutOfMemoryOrResourceExceptionContext.MemoryType.DIRECT_MEMORY, detail));
      exceptionBuilder.addErrorOrigin(role);
    }

    @Override
    public void addMemoryContext(UserException.Builder exceptionBuilder, Throwable e) {
      if (e instanceof OutOfMemoryException) {
        addMemoryContext(exceptionBuilder, (OutOfMemoryException) e);
      } else {
        addMemoryContext(exceptionBuilder);
      }
      exceptionBuilder.addErrorOrigin(role);
    }

    @Override
    public void addMemoryContext(UserException.Builder exceptionBuilder, OutOfMemoryException e) {
      String detail = MemoryDebugInfo.getDetailsOnAllocationFailure(e, rootAllocator);
      exceptionBuilder.setAdditionalExceptionContext(
          new OutOfMemoryOrResourceExceptionContext(
              OutOfMemoryOrResourceExceptionContext.MemoryType.DIRECT_MEMORY, detail));
      exceptionBuilder.addErrorOrigin(role);
    }

    @Override
    public void addHeapMemoryContext(UserException.Builder exceptionBuilder, Throwable e) {
      String detail = MemoryDebugInfo.getSummaryFromRoot(rootAllocator);
      exceptionBuilder.setAdditionalExceptionContext(
          new OutOfMemoryOrResourceExceptionContext(
              OutOfMemoryOrResourceExceptionContext.MemoryType.HEAP_MEMORY, detail));
      exceptionBuilder.addErrorOrigin(role);
    }

    @Override
    public void addErrorOrigin(UserException.Builder builder) {
      builder.addErrorOrigin(role);
    }

    @Override
    public void addErrorOrigin(UserException userException) {
      userException.addErrorOrigin(role);
    }
  }
}
