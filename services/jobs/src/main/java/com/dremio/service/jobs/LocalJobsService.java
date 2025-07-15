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
package com.dremio.service.jobs;

import static com.dremio.common.utils.Protos.listNotNull;
import static com.dremio.exec.ExecConstants.DEBUG_TTL_JOB_MAX_AGE_IN_MILLISECONDS;
import static com.dremio.exec.ExecConstants.JOB_MAX_AGE_IN_DAYS;
import static com.dremio.service.job.proto.JobState.PENDING;
import static com.dremio.service.job.proto.QueryType.UI_INITIAL_PREVIEW;
import static com.dremio.service.jobs.AbandonJobsHelper.setAbandonedJobsToFailedState;
import static com.dremio.service.jobs.JobIndexKeys.DATASET_VERSION;
import static com.dremio.service.jobs.JobIndexKeys.PARENT_DATASET;
import static com.dremio.service.jobs.JobIndexKeys.USER;
import static com.dremio.service.jobs.JobsServiceUtil.getIndexKey;
import static com.dremio.service.jobs.JobsServiceUtil.getReflectionIdFilter;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;
import static com.google.common.base.Preconditions.checkNotNull;

import com.dremio.common.AutoCloseables;
import com.dremio.common.DeferredException;
import com.dremio.common.VM;
import com.dremio.common.concurrent.CloseableExecutorService;
import com.dremio.common.concurrent.CloseableSchedulerThreadPool;
import com.dremio.common.concurrent.CloseableThreadPool;
import com.dremio.common.concurrent.ContextMigratingExecutorService;
import com.dremio.common.concurrent.ContextMigratingExecutorService.ContextMigratingCloseableExecutorService;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logging.StructuredLogger;
import com.dremio.common.nodes.EndpointHelper;
import com.dremio.common.perf.Timer;
import com.dremio.common.perf.Timer.TimedBlock;
import com.dremio.common.util.Closeable;
import com.dremio.common.util.DremioVersionInfo;
import com.dremio.common.util.concurrent.DremioFutures;
import com.dremio.common.utils.ProtostuffUtil;
import com.dremio.common.utils.protos.AttemptId;
import com.dremio.common.utils.protos.AttemptIdUtils;
import com.dremio.common.utils.protos.ExternalIdHelper;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.common.utils.protos.QueryWritableBatch;
import com.dremio.config.DremioConfig;
import com.dremio.context.SupportContext;
import com.dremio.datastore.DatastoreException;
import com.dremio.datastore.IndexedSearchQueryConverterUtil;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.SearchTypes.SearchFieldSorting;
import com.dremio.datastore.SearchTypes.SearchQuery;
import com.dremio.datastore.WarningTimer;
import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyIndexedStore.LegacyFindByCondition;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.planner.AccelerationDetailsPopulator;
import com.dremio.exec.planner.IcebergTableUtils.IcebergTableChecker;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.RootSchemaFinder;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.acceleration.RelWithInfo;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionInfo;
import com.dremio.exec.planner.cost.DremioCost;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.observer.AbstractAttemptObserver;
import com.dremio.exec.planner.observer.AbstractQueryObserver;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.observer.QueryObserver;
import com.dremio.exec.planner.observer.QueryObserverFactory;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.PlannerSettings.StoreQueryResultsPolicy;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.SearchProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.AccelerationProfile;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.ExternalId;
import com.dremio.exec.proto.UserBitShared.LayoutMaterializedViewProfile;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.proto.UserBitShared.QueryResult.QueryState;
import com.dremio.exec.proto.UserBitShared.RpcEndpointInfos;
import com.dremio.exec.proto.UserBitShared.WorkloadClass;
import com.dremio.exec.proto.UserProtos.CreatePreparedStatementReq;
import com.dremio.exec.proto.UserProtos.QueryPriority;
import com.dremio.exec.proto.UserProtos.RunQuery;
import com.dremio.exec.proto.UserProtos.SubmissionSource;
import com.dremio.exec.proto.beans.CancelType;
import com.dremio.exec.proto.beans.NodeEndpoint;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.RecordBatchHolder;
import com.dremio.exec.record.RecordBatchLoader;
import com.dremio.exec.rpc.RpcException;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.server.JobResultInfoProvider;
import com.dremio.exec.server.SimpleJobRunner;
import com.dremio.exec.server.options.SessionOptionManager;
import com.dremio.exec.server.options.SessionOptionManagerFactory;
import com.dremio.exec.server.options.SessionOptionManagerFactoryWithExpiration;
import com.dremio.exec.server.options.SessionOptionManagerImpl;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.JobResultsStoreConfig;
import com.dremio.exec.store.easy.arrow.ArrowFileFormat;
import com.dremio.exec.store.easy.arrow.ArrowFileMetadata;
import com.dremio.exec.store.easy.arrow.ArrowFileReader;
import com.dremio.exec.store.sys.accel.AccelerationManager;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.exec.work.foreman.ExecutionPlan;
import com.dremio.exec.work.protector.ForemenTool;
import com.dremio.exec.work.protector.UserRequest;
import com.dremio.exec.work.protector.UserResponseHandler;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.exec.work.rpc.CoordTunnel;
import com.dremio.exec.work.rpc.CoordTunnelCreator;
import com.dremio.exec.work.user.LocalExecutionConfig;
import com.dremio.exec.work.user.LocalQueryExecutor;
import com.dremio.exec.work.user.LocalUserUtil;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.proto.model.attempts.AttemptReason;
import com.dremio.reflection.hints.ReflectionExplanationsAndQueryDistance;
import com.dremio.resource.ResourceSchedulingDecisionInfo;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.dremio.sabot.rpc.user.UserRpcUtils;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.Pointer;
import com.dremio.service.Service;
import com.dremio.service.commandpool.CommandPool;
import com.dremio.service.commandpool.CommandPool.Priority;
import com.dremio.service.commandpool.ReleasableCommandPool;
import com.dremio.service.conduit.client.ConduitProvider;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.job.ActiveJobSummary;
import com.dremio.service.job.ActiveJobsRequest;
import com.dremio.service.job.CancelJobRequest;
import com.dremio.service.job.CancelReflectionJobRequest;
import com.dremio.service.job.DeleteJobCountsRequest;
import com.dremio.service.job.HasAtLeastOneJobRequest;
import com.dremio.service.job.HasAtLeastOneJobResponse;
import com.dremio.service.job.HasAtLeastOneJobResponse.HasAtLeastOneJobWithType;
import com.dremio.service.job.JobAndUserStat;
import com.dremio.service.job.JobAndUserStats;
import com.dremio.service.job.JobAndUserStatsRequest;
import com.dremio.service.job.JobCountByQueryType;
import com.dremio.service.job.JobCounts;
import com.dremio.service.job.JobCountsRequest;
import com.dremio.service.job.JobCountsRequestDaily;
import com.dremio.service.job.JobDetailsRequest;
import com.dremio.service.job.JobEvent;
import com.dremio.service.job.JobStats;
import com.dremio.service.job.JobStatsRequest;
import com.dremio.service.job.JobSummary;
import com.dremio.service.job.JobSummaryRequest;
import com.dremio.service.job.JobsWithParentDatasetRequest;
import com.dremio.service.job.NodeStatusRequest;
import com.dremio.service.job.NodeStatusResponse;
import com.dremio.service.job.QueryProfileRequest;
import com.dremio.service.job.QueryResultData;
import com.dremio.service.job.RecentJobSummary;
import com.dremio.service.job.RecentJobsRequest;
import com.dremio.service.job.ReflectionJobDetailsRequest;
import com.dremio.service.job.ReflectionJobEventsRequest;
import com.dremio.service.job.ReflectionJobProfileRequest;
import com.dremio.service.job.ReflectionJobSummaryRequest;
import com.dremio.service.job.RequestType;
import com.dremio.service.job.SearchJobsRequest;
import com.dremio.service.job.SearchReflectionJobsRequest;
import com.dremio.service.job.SqlQuery;
import com.dremio.service.job.StoreJobResultRequest;
import com.dremio.service.job.SubmitJobRequest;
import com.dremio.service.job.UniqueUsersCountByQueryType;
import com.dremio.service.job.VersionedDatasetPath;
import com.dremio.service.job.log.LoggedQuery;
import com.dremio.service.job.proto.Acceleration;
import com.dremio.service.job.proto.ExecutionNode;
import com.dremio.service.job.proto.ExtraInfo;
import com.dremio.service.job.proto.ExtraJobInfo;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobCancellationInfo;
import com.dremio.service.job.proto.JobDetails;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.job.proto.JobState;
import com.dremio.service.job.proto.JobSubmission;
import com.dremio.service.job.proto.JoinAnalysis;
import com.dremio.service.job.proto.JoinInfo;
import com.dremio.service.job.proto.ParentDatasetInfo;
import com.dremio.service.job.proto.QueryLabel;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.job.proto.ResourceSchedulingInfo;
import com.dremio.service.job.proto.ResultsCacheUsed;
import com.dremio.service.job.proto.SessionId;
import com.dremio.service.jobcounts.GetJobCountsRequest;
import com.dremio.service.jobcounts.GetJobCountsRequestDaily;
import com.dremio.service.jobcounts.JobCountType;
import com.dremio.service.jobcounts.JobCountUpdate;
import com.dremio.service.jobcounts.JobCountsClient;
import com.dremio.service.jobcounts.UpdateJobCountsRequest;
import com.dremio.service.jobs.metadata.QueryMetadata;
import com.dremio.service.jobtelemetry.GetQueryProfileRequest;
import com.dremio.service.jobtelemetry.JobTelemetryClient;
import com.dremio.service.jobtelemetry.JobTelemetryServiceGrpc;
import com.dremio.service.jobtelemetry.PutTailProfileRequest;
import com.dremio.service.jobtelemetry.instrumentation.MetricLabel;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.Origin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.usersessions.UserSessionService;
import com.dremio.service.usersessions.UserSessionServiceOptions;
import com.dremio.telemetry.api.metrics.CounterWithOutcome;
import com.dremio.telemetry.api.metrics.SimpleCounter;
import com.dremio.telemetry.api.metrics.SimpleDistributionSummary;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.protobuf.Empty;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.protostuff.ByteString;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Submit and monitor jobs from DAC. */
public class LocalJobsService implements Service, JobResultInfoProvider, SimpleJobRunner {
  private static final Logger logger = LoggerFactory.getLogger(LocalJobsService.class);
  public static final String QUERY_LOGGER = "query.logger";
  public static final int DISABLE_CLEANUP_VALUE = -1;
  public static final int MAX_NUMBER_JOBS_TO_FETCH = 20;
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(LocalJobsService.class);
  private static final String LOCAL_JOBS_ABANDON_TASK_LEADER_NAME = "localjobsabandon";
  private static final int SEARCH_JOBS_PAGE_SIZE = 100;
  private static final int GET_RECENT_JOBS_PAGE_SIZE = 100;
  private static final long LOCAL_ABANDONED_JOBS_TASK_SCHEDULE_MILLIS = 1800000;
  private static final CounterWithOutcome JOB_AND_USER_STAT_CACHE_COUNTER =
      CounterWithOutcome.of("jobs_service.user_stat_cache");
  private static final SimpleDistributionSummary JOB_AND_USER_STAT_CACHE_LOGIN_TIMER =
      SimpleDistributionSummary.of("jobs_service.user_stat_cache.durations");
  private static final CounterWithOutcome UNIQUE_USER_UPDATE_COUNTER =
      CounterWithOutcome.of("jobs_service.unique_user_update");
  private static final SimpleDistributionSummary UNIQUE_USER_UPDATE_LOGIN_TIMER =
      SimpleDistributionSummary.of("jobs_service.unique_user_update.durations");
  private static final CounterWithOutcome ABANDON_LOCAL_JOB_COUNTER =
      CounterWithOutcome.of("jobs_service.abandon_local_job");
  private static final SimpleDistributionSummary ABANDON_LOCAL_JOB_LOGIN_TIMER =
      SimpleDistributionSummary.of("jobs_service.abandon_local_job.durations");
  private static final SimpleCounter PROFILE_UPDATE_FAILURE_COUNTER =
      SimpleCounter.of(
          "profile.update.failures",
          "Counter for tail and final executor profiles update failures");

  @VisibleForTesting
  public static final String INJECTOR_ATTEMPT_COMPLETION_ERROR = "attempt-completion-error";

  @VisibleForTesting
  public static final String INJECTOR_ATTEMPT_COMPLETION_KV_ERROR = "attempt-completion-kv-error";

  private final Provider<LocalQueryExecutor> queryExecutor;
  private final Provider<LegacyKVStoreProvider> kvStoreProvider;
  private final Provider<JobResultsStoreConfig> jobResultsStoreConfig;
  private final Provider<JobResultsStore> jobResultsStoreProvider;
  private final ConcurrentHashMap<JobId, QueryListener> runningJobs;
  private final BufferAllocator allocator;
  private final Provider<ForemenTool> foremenTool;
  private final Provider<CoordinationProtos.NodeEndpoint> nodeEndpointProvider;
  private final Provider<Collection<CoordinationProtos.NodeEndpoint>> jobServiceInstances;
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<AccelerationManager> accelerationManagerProvider;
  private final Provider<CoordTunnelCreator> coordTunnelCreator;
  private final Provider<SchedulerService> schedulerService;
  private final Provider<CommandPool> commandPoolService;
  private final Provider<JobTelemetryClient> jobTelemetryClientProvider;
  private final Provider<UserSessionService> userSessionService;
  private final Provider<OptionValidatorListing> optionValidatorProvider;
  private final Provider<CatalogService> catalogServiceProvider;
  private final boolean isMaster;
  private final LocalAbandonedJobsHandler localAbandonedJobsHandler;
  private final StructuredLogger<Job> jobResultLogger;
  private final ContextMigratingCloseableExecutorService executorService;
  private final ContextMigratingCloseableExecutorService jobCountsExecutorService;
  private final CloseableExecutorService queryLoggerExecutorService;
  private final Provider<JobCountsClient> jobCountsClientProvider;
  private final Provider<JobSubmissionListener> jobSubmissionListenerProvider;
  private NodeEndpoint identity;
  private LegacyIndexedStore<JobId, JobResult> store;
  private LegacyIndexedStore<JobId, ExtraJobInfo> extraJobInfoStore;
  private CatalogService catalogService;
  private String storageName;
  private volatile JobResultsStore jobResultsStore;
  private Cancellable abandonLocalJobsTaskPeriodic;
  private Cancellable abandonLocalJobsTaskByEvents;
  private Cancellable uniqueUsersUpdateTask;
  private Cancellable jobAndUserStatsCacheTask;
  private QueryObserverFactory queryObserverFactory;
  private JobTelemetryServiceGrpc.JobTelemetryServiceBlockingStub jobTelemetryServiceStub;
  private SessionOptionManagerFactory sessionOptionManagerFactory;
  private final RemoteJobServiceForwarder forwarder;
  private final DremioConfig config;
  private JobAndUserStatsCache jobAndUserStatsCache;

  private static final List<SearchFieldSorting> DEFAULT_SORTER =
      ImmutableList.of(
          JobIndexKeys.START_TIME.toSortField(SearchTypes.SortOrder.DESCENDING),
          JobIndexKeys.END_TIME.toSortField(SearchTypes.SortOrder.DESCENDING),
          JobIndexKeys.JOBID.toSortField(SearchTypes.SortOrder.DESCENDING));

  /**
   * A utility method to create and compose a StructuredLogger with Job
   *
   * @return
   */
  public static StructuredLogger<Job> createJobResultLogger() {
    return StructuredLogger.get(LoggedQuery.class, QUERY_LOGGER)
        .compose(new JobResultToLogEntryConverter());
  }

  public LocalJobsService(
      final Provider<LegacyKVStoreProvider> kvStoreProvider,
      final BufferAllocator allocator,
      final Provider<JobResultsStoreConfig> jobResultsStoreConfig,
      final Provider<JobResultsStore> jobResultsStoreProvider,
      final Provider<LocalQueryExecutor> queryExecutor,
      final Provider<CoordTunnelCreator> coordTunnelCreator,
      final Provider<ForemenTool> foremenTool,
      final Provider<CoordinationProtos.NodeEndpoint> nodeEndpointProvider,
      final Provider<ClusterCoordinator> clusterCoordinatorProvider,
      final Provider<OptionManager> optionManagerProvider,
      final Provider<AccelerationManager> accelerationManagerProvider,
      final Provider<SchedulerService> schedulerService,
      final Provider<CommandPool> commandPoolService,
      final Provider<JobTelemetryClient> jobTelemetryClientProvider,
      final StructuredLogger<Job> jobResultLogger,
      final boolean isMaster,
      final Provider<ConduitProvider> conduitProvider,
      final Provider<UserSessionService> userSessionService,
      final Provider<OptionValidatorListing> optionValidatorProvider,
      final Provider<CatalogService> catalogServiceProvider,
      final Provider<JobCountsClient> jobCountsClientProvider,
      final Provider<JobSubmissionListener> jobSubmissionListenerProvider,
      final DremioConfig config) {
    this.kvStoreProvider = kvStoreProvider;
    this.allocator = allocator;
    this.queryExecutor = checkNotNull(queryExecutor);
    this.jobResultsStoreConfig = checkNotNull(jobResultsStoreConfig);
    this.jobResultsStoreProvider = jobResultsStoreProvider;
    this.jobResultLogger = jobResultLogger;
    this.runningJobs = new ConcurrentHashMap<>();
    this.foremenTool = foremenTool;
    this.nodeEndpointProvider = nodeEndpointProvider;
    this.jobServiceInstances = () -> clusterCoordinatorProvider.get().getCoordinatorEndpoints();
    this.optionManagerProvider = optionManagerProvider;
    this.accelerationManagerProvider = accelerationManagerProvider;
    this.coordTunnelCreator = coordTunnelCreator;
    this.schedulerService = schedulerService;
    this.commandPoolService = commandPoolService;
    this.jobTelemetryClientProvider = jobTelemetryClientProvider;
    this.isMaster = isMaster;
    this.forwarder = new RemoteJobServiceForwarder(conduitProvider);
    this.localAbandonedJobsHandler = new LocalAbandonedJobsHandler();
    this.userSessionService = userSessionService;
    this.optionValidatorProvider = optionValidatorProvider;
    this.catalogServiceProvider = catalogServiceProvider;
    this.executorService =
        new ContextMigratingCloseableExecutorService<>(
            new CloseableThreadPool("job-event-collating-observer"));
    this.jobCountsExecutorService =
        new ContextMigratingCloseableExecutorService<>(
            new CloseableThreadPool("job-counts-update-pool"));
    this.jobCountsClientProvider = jobCountsClientProvider;
    this.jobSubmissionListenerProvider = jobSubmissionListenerProvider;
    this.config = config;
    this.queryLoggerExecutorService = new CloseableThreadPool("async-query-logger");
  }

  public QueryObserverFactory getQueryObserverFactory() {
    return queryObserverFactory;
  }

  public long getJobsTTLExpiryAtInMillis() {
    // Used 'jobs.max.age_in_days' for TTL based expiry of jobs.
    // Used 'debug.ttl.jobs.max.age_in_milliseconds' for faster testing and debugging.
    // 'debug.ttl.jobs.max.age_in_milliseconds' is 0 by default and can be set when required for
    // testing.
    // For TTL expiry, override 'jobs.max.age_in_days' with 'debug.ttl.jobs.max.age_in_milliseconds'
    // if value is greater than 0.
    return System.currentTimeMillis()
        + (getOptionManagerProvider().get().getOption(DEBUG_TTL_JOB_MAX_AGE_IN_MILLISECONDS) > 0
            ? getOptionManagerProvider().get().getOption(DEBUG_TTL_JOB_MAX_AGE_IN_MILLISECONDS)
            : TimeUnit.DAYS.toMillis(
                getOptionManagerProvider().get().getOption(JOB_MAX_AGE_IN_DAYS)));
  }

  @Override
  public void start() throws Exception {
    logger.info("Starting JobsService");
    this.identity = JobsServiceUtil.toStuff(nodeEndpointProvider.get());
    this.store = kvStoreProvider.get().getStore(JobsStoreCreator.class);
    this.extraJobInfoStore = kvStoreProvider.get().getStore(ExtraJobInfoStoreCreator.class);
    this.catalogService = catalogServiceProvider.get();
    final JobResultsStoreConfig resultsStoreConfig = jobResultsStoreConfig.get();
    this.storageName = resultsStoreConfig.getStorageName();
    this.jobTelemetryServiceStub = jobTelemetryClientProvider.get().getBlockingStub();
    this.sessionOptionManagerFactory =
        new SessionOptionManagerFactoryWithExpiration(
            optionValidatorProvider.get(),
            Duration.ofSeconds(
                UserSessionServiceOptions.SESSION_TTL_BUFFER_MULTIPLIER
                    * optionManagerProvider.get().getOption(UserSessionServiceOptions.SESSION_TTL)),
            (int)
                optionManagerProvider
                    .get()
                    .getOption(UserSessionServiceOptions.MAX_SESSION_OPTION_MANAGERS));
    this.queryObserverFactory = new JobsObserverFactory();
    final OptionManager optionManager = optionManagerProvider.get();
    logger.debug("Scheduling event based abandon jobs task");
    // schedule abandon jobs task to run after 30s & on every coordinator death
    abandonLocalJobsTaskByEvents =
        schedulerService
            .get()
            .schedule(
                Schedule.SingleShotBuilder.at(
                        Instant.ofEpochMilli(
                            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)))
                    .asClusteredSingleton(LOCAL_JOBS_ABANDON_TASK_LEADER_NAME)
                    .setSingleShotType(Schedule.SingleShotType.RUN_ONCE_EVERY_MEMBER_DEATH)
                    .build(),
                new AbandonLocalJobsTask());
    if (isMaster) {
      // Only LeaderlessScheduler supports RUN_ONCE_EVERY_MEMBER_DEATH, if it's disabled schedule
      // recurring task
      final boolean isLeaderlessScheduler =
          config.getBoolean(DremioConfig.SCHEDULER_LEADERLESS_CLUSTERED_SINGLETON);
      final boolean isDistributedCoordinator =
          config.isMasterlessEnabled() && config.getBoolean(DremioConfig.ENABLE_COORDINATOR_BOOL);
      if (!isLeaderlessScheduler || !isDistributedCoordinator) {
        logger.debug("Scheduling recurring abandon jobs task");
        // Schedule a recurring abandoned jobs cleanup
        final Schedule abandonedJobsSchedule =
            Schedule.Builder.everyMinutes(5).withTimeZone(ZoneId.systemDefault()).build();
        abandonLocalJobsTaskPeriodic =
            schedulerService.get().schedule(abandonedJobsSchedule, new AbandonLocalJobsTask());
      }
      if (optionManager.getOption(ExecConstants.ENABLE_JOBS_USER_STATS_API)) {
        jobAndUserStatsCache = new JobAndUserStatsCache();
        if (!VM.areAssertsEnabled()) {
          jobAndUserStatsCacheTask =
              schedulerService
                  .get()
                  .schedule(
                      Schedule.Builder.everyHours(
                              (int)
                                  optionManager.getOption(
                                      ExecConstants.JOBS_USER_STATS_CACHE_REFRESH_HRS))
                          .startingAt(
                              Instant.ofEpochMilli(
                                  System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)))
                          .withTimeZone(ZoneId.systemDefault())
                          .build(),
                      () -> {
                        final Stopwatch stopwatch = Stopwatch.createStarted();
                        try {
                          jobAndUserStatsCache.refreshCacheIfRequired();
                          JOB_AND_USER_STAT_CACHE_COUNTER.succeeded();
                          logger.debug("Finished building jobAndUserStatsCache");
                        } catch (Exception e) {
                          JOB_AND_USER_STAT_CACHE_COUNTER.errored();
                          logger.error("Could not build jobAndUserStatsCache", e);
                        } finally {
                          final long elapsedTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
                          JOB_AND_USER_STAT_CACHE_LOGIN_TIMER.recordAmount(elapsedTime);
                        }
                      });
        }
      }
    }
    // schedule the task every 30 minutes to set abandoned jobs state to FAILED.
    localAbandonedJobsHandler.schedule(LOCAL_ABANDONED_JOBS_TASK_SCHEDULE_MILLIS);
    MapFilterToJobState.init();
    logger.info("JobsService is up");
  }

  @Override
  public void close() throws Exception {
    logger.info("Stopping JobsService");
    if (abandonLocalJobsTaskPeriodic != null) {
      abandonLocalJobsTaskPeriodic.cancel(false);
      abandonLocalJobsTaskPeriodic = null;
    }
    if (abandonLocalJobsTaskByEvents != null) {
      abandonLocalJobsTaskByEvents.cancel(false);
      abandonLocalJobsTaskByEvents = null;
    }
    if (uniqueUsersUpdateTask != null) {
      uniqueUsersUpdateTask.cancel(true);
      uniqueUsersUpdateTask = null;
    }
    if (jobAndUserStatsCacheTask != null) {
      jobAndUserStatsCacheTask.cancel(true);
      jobAndUserStatsCacheTask = null;
    }
    AutoCloseables.close(
        localAbandonedJobsHandler,
        jobResultsStore,
        allocator,
        executorService,
        queryLoggerExecutorService,
        jobCountsExecutorService);
    logger.info("Stopped JobsService");
  }

  public void registerListener(JobId jobId, StreamObserver<JobEvent> observer) {
    Job job;
    try {
      final GetJobRequest request =
          GetJobRequest.newBuilder().setJobId(jobId).setUserName(SYSTEM_USERNAME).build();
      job = getJob(request);
      registerListenerWithJob(job, observer);
    } catch (JobNotFoundException e) {
      throw UserException.validationError(e)
          .message("Status requested for unknown job %s.", jobId.getId())
          .build(logger);
    }
  }

  public void registerListenerWithJob(Job job, StreamObserver<JobEvent> observer)
      throws JobNotFoundException {
    final QueryListener queryListener = runningJobs.get(job.getJobId());
    if (queryListener != null) {
      queryListener.listeners.register(observer, JobsServiceUtil.toJobSummary(job));
    } else {
      // check if its for remote co-ordinator
      if (mustForwardRequest(job)) {
        final NodeEndpoint source = job.getJobAttempt().getEndpoint();
        try {
          forwarder.subscribeToJobEvents(
              JobsProtoUtil.toBuf(source), JobsProtoUtil.toBuf(job.getJobId()), observer);
        } catch (ExecutionException executionException) {
          JobsRpcUtils.handleException(
              observer, UserException.systemError(executionException).buildSilently());
        } finally {
          return;
        }
      } // forward the request to remote server
      if (!job.isCompleted()) {
        final GetJobRequest request =
            GetJobRequest.newBuilder()
                .setJobId(job.getJobId())
                .setUserName(SYSTEM_USERNAME)
                .build();
        job = getJob(request); // not completed implies running, so try again once more
        if (!job.isCompleted()) {
          // there is still a race condition with runningJobs in #execCompletion, so inform the
          // client to retry
          JobsRpcUtils.handleException(
              observer,
              UserException.systemError(
                      new IllegalStateException(
                          "Job is in an inconsistent state. Wait, and try again later."))
                  .buildSilently());
          return;
        }
      }

      observer.onNext(
          JobEvent.newBuilder().setFinalJobSummary(JobsServiceUtil.toJobSummary(job)).build());
      observer.onCompleted();
    }
  }

  private void startJob(
      ExternalId externalId,
      SubmitJobRequest jobRequest,
      JobEventCollatingObserver eventObserver,
      SessionObserver sessionObserver,
      PlanTransformationListener planTransformationListener,
      String sessionId,
      long jobSubmissionTime) {
    // (1) create job details
    final JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    Catalog catalog = CatalogUtil.getSystemCatalogForJobs(catalogService);
    final String inSpace =
        !jobRequest.hasVersionedDataset()
                && !jobRequest.getVersionedDataset().getPathList().isEmpty()
                && catalog.exists(
                    new NamespaceKey(jobRequest.getVersionedDataset().getPath(0)),
                    NameSpaceContainer.Type.SPACE)
            ? jobRequest.getVersionedDataset().getPath(0)
            : null;
    int sqlTruncateLen = getSqlTruncateLenFromOptionMgr();
    final JobInfo jobInfo =
        JobsServiceUtil.createJobInfo(
            jobRequest, jobId, inSpace, sqlTruncateLen, getJobsTTLExpiryAtInMillis());
    final JobAttempt jobAttempt =
        new JobAttempt()
            .setInfo(jobInfo)
            .setEndpoint(identity)
            .setState(PENDING)
            .setDetails(new JobDetails());
    final Job job = new Job(jobId, jobAttempt, new SessionId().setId(sessionId));
    // (2) deduce execution configuration
    final QueryType queryType = jobInfo.getQueryType();
    final boolean enableLeafLimits = QueryTypeUtils.requiresLeafLimits(queryType);
    final LocalExecutionConfig config =
        LocalExecutionConfig.newBuilder()
            .setEnableLeafLimits(enableLeafLimits)
            .setEnableOutputLimits(QueryTypeUtils.isQueryFromUI(queryType))
            .setFailIfNonEmptySent(!QueryTypeUtils.isQueryFromUI(queryType))
            .setUsername(jobRequest.getUsername())
            .setSqlContext(jobRequest.getSqlQuery().getContextList())
            .setInternalSingleThreaded(
                queryType == UI_INITIAL_PREVIEW || jobRequest.getRunInSameThread())
            .setIgnoreColumnsLimits(jobRequest.getIgnoreColumnLimits())
            .setQueryResultsStorePath(storageName)
            .setAllowPartitionPruning(queryType != QueryType.ACCELERATOR_EXPLAIN)
            .setExposeInternalSources(QueryTypeUtils.isInternal(queryType))
            .setSubstitutionSettings(
                JobsProtoUtil.toPojo(
                    jobRequest.getMaterializationSettings().getSubstitutionSettings()))
            .setReflectionMode(
                jobRequest
                    .getMaterializationSettings()
                    .getMaterializationSummary()
                    .getReflectionMode())
            .setSourceVersionMapping(
                JobsProtoUtil.toSourceVersionMapping(
                    jobRequest.getSqlQuery().getSourceVersionMappingMap()))
            .setEngineName(jobRequest.getSqlQuery().getEngineName())
            .setSessionId(
                sessionId) // Should be the same as jobRequest.getSqlQuery().getSessionId() after
            // the first statement
            .setStoreQueryResultsPolicy(
                jobRequest.getStreamResultsMode()
                    ? StoreQueryResultsPolicy.NO
                    : StoreQueryResultsPolicy.PATH_AND_ATTEMPT_ID)
            .build();
    // (3) register listener
    final QueryListener jobObserver =
        new QueryListener(
            job,
            eventObserver,
            sessionObserver,
            planTransformationListener,
            jobRequest.getStreamResultsMode());
    storeJob(job);
    if (jobInfo.getIsTruncatedSql()) {
      extraJobInfoStore.put(
          jobId,
          new ExtraJobInfo()
              .setSql(jobRequest.getSqlQuery().getSql())
              .setTtlExpireAt(getJobsTTLExpiryAtInMillis() + TimeUnit.MINUTES.toMillis(10)));
    }
    runningJobs.put(jobId, jobObserver);
    jobSubmissionListenerProvider.get().onJobSubmitted(jobId);
    final boolean isPrepare = queryType.equals(QueryType.PREPARE_INTERNAL);
    final WorkloadClass workloadClass = QueryTypeUtils.getWorkloadClassFor(queryType);
    final UserBitShared.WorkloadType workloadType = QueryTypeUtils.getWorkloadType(queryType);
    final String queryLabel =
        QueryLabelUtils.getQueryLabelString(JobsProtoUtil.toStuff(jobRequest.getQueryLabel()));
    final Object queryRequest;
    if (isPrepare) {
      queryRequest =
          CreatePreparedStatementReq.newBuilder()
              .setSqlQuery(jobRequest.getSqlQuery().getSql())
              .build();
    } else {
      queryRequest =
          RunQuery.newBuilder()
              .setType(UserBitShared.QueryType.SQL)
              .setSource(SubmissionSource.LOCAL)
              .setPlan(jobRequest.getSqlQuery().getSql())
              .setPriority(
                  QueryPriority.newBuilder()
                      .setWorkloadClass(workloadClass)
                      .setWorkloadType(workloadType))
              .setQueryLabel(queryLabel)
              .build();
    }
    // (4) submit the job
    try {
      UserSession session = null;
      if (optionManagerProvider.get().getOption(UserSession.ENABLE_SESSION_IDS)) {
        session = userSessionService.get().getSession(sessionId).getSession();
        if (jobInfo.getContextList().isEmpty() && session.getDefaultSchemaPath() != null) {
          // If the context is not available from the jobInfo,
          // the context from the session should be used for the query.
          // Record that information in the jobInfo, so it is easily available later
          jobInfo.setContextList(session.getDefaultSchemaPath().getPathComponents());
        }
        SessionOptionManager sessionOptionManager =
            sessionOptionManagerFactory.getOrCreate(sessionId);
        session.setSessionOptionManager(sessionOptionManager, optionManagerProvider.get());
      }
      sessionObserver.onSessionModified(session);
      queryExecutor
          .get()
          .submitLocalQuery(
              externalId,
              jobObserver,
              queryRequest,
              isPrepare,
              config,
              jobRequest.getRunInSameThread(),
              session,
              jobSubmissionTime);
    } catch (Exception ex) {
      // Failed to submit the job
      jobAttempt.setState(JobState.FAILED);
      jobAttempt.setInfo(
          jobAttempt
              .getInfo()
              .setFinishTime(System.currentTimeMillis())
              .setFailureInfo("Failed to submit the job"));
      job.setCompleted(true);
      // Update the job in KVStore
      storeJob(job);
      // Add profile for this job
      final AttemptId attemptId = new AttemptId(JobsServiceUtil.getJobIdAsExternalId(jobId), 0);
      UserException userException = UserException.systemError(ex).build(logger);
      final QueryProfile.Builder profileBuilder =
          QueryProfile.newBuilder()
              .setQuery(jobRequest.getSqlQuery().getSql())
              .setUser(jobRequest.getUsername())
              .setId(attemptId.toQueryId())
              .setState(QueryState.FAILED)
              .setStart(jobAttempt.getInfo().getStartTime())
              .setEnd(jobAttempt.getInfo().getFinishTime())
              .setCommandPoolWaitMillis(0)
              .setError(userException.getMessage())
              .setVerboseError(userException.getVerboseMessage(false))
              .setErrorId(userException.getErrorId())
              .setDremioVersion(DremioVersionInfo.getVersion());

      try {
        jobTelemetryServiceStub.putQueryTailProfile(
            PutTailProfileRequest.newBuilder()
                .setQueryId(attemptId.toQueryId())
                .setProfile(profileBuilder.build())
                .build());
      } catch (Exception telemetryEx) {
        jobTelemetryClientProvider
            .get()
            .getSuppressedErrorCounter()
            .withTags(
                MetricLabel.JTS_METRIC_TAG_KEY_RPC,
                MetricLabel.JTS_METRIC_TAG_VALUE_RPC_PUT_QUERY_TAIL_PROFILE,
                MetricLabel.JTS_METRIC_TAG_KEY_ERROR_ORIGIN,
                MetricLabel.JTS_METRIC_TAG_VALUE_START_JOB)
            .increment();
        ex.addSuppressed(telemetryEx);
      }
      // Remove the job from running jobs
      runningJobs.remove(jobId);
      throw ex;
    }
  }

  /** Validates JobRequest */
  @VisibleForTesting
  static SubmitJobRequest validateJobRequest(SubmitJobRequest submitJobRequest) {
    final SubmitJobRequest.Builder submitJobRequestBuilder = SubmitJobRequest.newBuilder();
    Preconditions.checkArgument(submitJobRequest.hasSqlQuery(), "sql query not provided");
    submitJobRequestBuilder.setSqlQuery(submitJobRequest.getSqlQuery());
    submitJobRequestBuilder.setQueryType(submitJobRequest.getQueryType());
    submitJobRequestBuilder.setRunInSameThread(submitJobRequest.getRunInSameThread());
    submitJobRequestBuilder.setStreamResultsMode(submitJobRequest.getStreamResultsMode());
    submitJobRequestBuilder.setIgnoreColumnLimits(submitJobRequest.getIgnoreColumnLimits());

    if (submitJobRequest.hasDownloadSettings()) {
      Preconditions.checkArgument(
          submitJobRequest.getQueryType() == com.dremio.service.job.QueryType.UI_EXPORT,
          "download jobs must be of UI_EXPORT type");
      Preconditions.checkArgument(
          !submitJobRequest.getDownloadSettings().getDownloadId().isEmpty(),
          "download id not provided");
      Preconditions.checkArgument(
          !submitJobRequest.getDownloadSettings().getFilename().isEmpty(),
          "file name not provided");
      Preconditions.checkArgument(
          !submitJobRequest.getDownloadSettings().getTriggeringJobId().isEmpty(),
          "triggering job id not provided");
      Preconditions.checkArgument(
          !submitJobRequest.getDownloadSettings().getExtension().isEmpty(),
          "extension not provided");
      submitJobRequestBuilder.setDownloadSettings(submitJobRequest.getDownloadSettings());
    } else if (submitJobRequest.hasMaterializationSettings()) {
      Preconditions.checkArgument(
          submitJobRequest.getMaterializationSettings().hasMaterializationSummary(),
          "materialization summary not provided");
      Preconditions.checkArgument(
          submitJobRequest.getMaterializationSettings().hasSubstitutionSettings(),
          "substitution settings not provided");
      submitJobRequestBuilder.setMaterializationSettings(
          submitJobRequest.getMaterializationSettings());
    }
    String username = submitJobRequest.getUsername();
    final String queryUsername = submitJobRequest.getSqlQuery().getUsername();
    if (username.isEmpty() && queryUsername.isEmpty()) {
      throw new IllegalArgumentException("Username not provided");
    } else if (username.isEmpty()) {
      username = queryUsername;
    }
    submitJobRequestBuilder.setUsername(username);
    List<String> datasetPathComponents = ImmutableList.of("UNKNOWN");
    String datasetVersion = "UNKNOWN";
    if (submitJobRequest.hasVersionedDataset()) {
      if (!submitJobRequest.getVersionedDataset().getPathList().isEmpty()) {
        datasetPathComponents = submitJobRequest.getVersionedDataset().getPathList();
      }
      if (!Strings.isNullOrEmpty(submitJobRequest.getVersionedDataset().getVersion())) {
        datasetVersion = submitJobRequest.getVersionedDataset().getVersion();
      }
    }
    return submitJobRequestBuilder
        .setVersionedDataset(
            VersionedDatasetPath.newBuilder()
                .addAllPath(datasetPathComponents)
                .setVersion(datasetVersion)
                .build())
        .build();
  }

  @VisibleForTesting
  public JobSubmission submitJob(
      SubmitJobRequest submitJobRequest,
      StreamObserver<JobEvent> eventObserver,
      PlanTransformationListener planTransformationListener) {
    final SubmitJobRequest jobRequest = validateJobRequest(submitJobRequest);
    final ExternalId externalId = ExternalIdHelper.generateExternalId();
    final JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    final JobSubmission jobSubmission = new JobSubmission().setJobId(jobId);
    checkNotNull(eventObserver, "an event observer must be provided");
    final JobEventCollatingObserver collatingObserver =
        new JobEventCollatingObserver(
            jobId, eventObserver, executorService, submitJobRequest.getStreamResultsMode());
    final String sessionId;
    final SessionObserver sessionObserver;
    final long jobSubmissionTime = System.currentTimeMillis();
    if (optionManagerProvider.get().getOption(UserSession.ENABLE_SESSION_IDS)) {
      sessionId = getSessionId(eventObserver, jobRequest);
      sessionObserver = new SessionUpdateObserver(userSessionService.get(), sessionId);
      jobSubmission.setSessionId(new SessionId().setId(sessionId));
    } else {
      sessionId = null;
      sessionObserver = SessionObserver.NO_OP;
    }

    CompletableFuture<Void> jobSubmissionFuture;
    if (jobRequest.getRunInSameThread()) {
      jobSubmissionFuture = new CompletableFuture<>();
      try {
        submitJob(
            jobRequest,
            jobId,
            externalId,
            sessionId,
            collatingObserver,
            sessionObserver,
            planTransformationListener,
            jobSubmissionTime);
        jobSubmissionFuture.complete(null);
      } catch (Throwable th) {
        jobSubmissionFuture.completeExceptionally(th);
      }
    } else {
      jobSubmissionFuture =
          CompletableFuture.runAsync(
              () ->
                  submitJob(
                      jobRequest,
                      jobId,
                      externalId,
                      sessionId,
                      collatingObserver,
                      sessionObserver,
                      planTransformationListener,
                      jobSubmissionTime),
              queryExecutor.get().getJobSubmissionThreadPool());
    }

    jobSubmissionFuture
        // once job submission is done, make sure we call either jobSubmitted() or
        // submissionFailed() depending on
        // the outcome
        .whenComplete(
        (o, e) -> {
          if (e == null) {
            collatingObserver.onSubmitted(
                JobEvent.newBuilder().setJobSubmitted(Empty.newBuilder().build()).build());
          } else {
            collatingObserver.onError(
                UserException.systemError(e)
                    .message("Failed to submit job %s", jobId.getId())
                    .buildSilently());
          }
        });
    // begin sending events to the client, and start with jobId + sessionId
    collatingObserver.start(
        JobEvent.newBuilder().setJobSubmission(JobsProtoUtil.toBuf(jobSubmission)).build());
    return jobSubmission;
  }

  @WithSpan("job-submission")
  void submitJob(
      SubmitJobRequest jobRequest,
      JobId jobId,
      ExternalId externalId,
      String sessionId,
      JobEventCollatingObserver collatingObserver,
      SessionObserver sessionObserver,
      PlanTransformationListener planTransformationListener,
      long jobSubmissionTime) {
    try (WarningTimer timer =
        new WarningTimer(
            String.format("Job submission %s", jobId.getId()),
            TimeUnit.MILLISECONDS.toMillis(100),
            logger)) {
      final Thread currentThread = Thread.currentThread();
      final String originalName = currentThread.getName();
      currentThread.setName(jobId.getId() + ":job-submission");

      try {
        Span.current().setAttribute("dremio.jobId", jobId.getId());
        if (!queryExecutor.get().canAcceptWork()) {
          throw UserException.resourceError()
              .message(UserException.QUERY_REJECTED_MSG)
              .buildSilently();
        }
        startJob(
            externalId,
            jobRequest,
            collatingObserver,
            sessionObserver,
            planTransformationListener,
            sessionId,
            jobSubmissionTime);
        logger.debug(
            "Submitted new job. Id: {} Type: {} Sql: {}",
            jobId.getId(),
            jobRequest.getQueryType(),
            jobRequest.getSqlQuery());
      } finally {
        currentThread.setName(originalName);
      }
    }
  }

  private String getSessionId(StreamObserver<JobEvent> eventObserver, SubmitJobRequest jobRequest) {
    String sessionId = jobRequest.getSqlQuery().getSessionId();
    if (Strings.isNullOrEmpty(sessionId)) {
      // Create a new session
      final QueryType queryType = JobsProtoUtil.toStuff(jobRequest.getQueryType());
      final String queryLabel =
          QueryLabelUtils.getQueryLabelString(JobsProtoUtil.toStuff(jobRequest.getQueryLabel()));
      UserSession session =
          UserSession.Builder.newBuilder()
              .withSessionOptionManager(
                  new SessionOptionManagerImpl(
                      optionManagerProvider.get().getOptionValidatorListing()),
                  optionManagerProvider.get())
              .setSupportComplexTypes(true)
              .withCredentials(
                  UserBitShared.UserCredentials.newBuilder()
                      .setUserName(jobRequest.getUsername())
                      .build())
              .exposeInternalSources(QueryTypeUtils.isInternal(queryType))
              .withDefaultSchema(jobRequest.getSqlQuery().getContextList())
              .withSubstitutionSettings(
                  JobsProtoUtil.toPojo(
                      jobRequest.getMaterializationSettings().getSubstitutionSettings()))
              .withClientInfos(UserRpcUtils.getRpcEndpointInfos("Dremio Java local client"))
              .withEngineName(jobRequest.getSqlQuery().getEngineName())
              .withSourceVersionMapping(
                  JobsProtoUtil.toSourceVersionMapping(
                      jobRequest.getSqlQuery().getSourceVersionMappingMap()))
              .build();

      session.setLastQueryId(UserBitShared.QueryId.getDefaultInstance());
      session.setQueryLabel(queryLabel);
      sessionId = userSessionService.get().putSession(session).getId();
    } else {
      // Check if the session is still active
      if (userSessionService.get().getSession(sessionId) == null) {
        // Session expired/not found.
        JobsRpcUtils.handleException(
            eventObserver,
            UserException.systemError(new SessionNotFoundException(sessionId)).buildSilently());
      }
    }
    return sessionId;
  }

  void submitJob(SubmitJobRequest jobRequest, StreamObserver<JobEvent> eventObserver) {
    submitJob(jobRequest, eventObserver, PlanTransformationListener.NO_OP);
  }

  JobSubmissionHelper getJobSubmissionHelper(
      SubmitJobRequest jobRequest,
      StreamObserver<JobEvent> eventObserver,
      PlanTransformationListener planTransformationListener) {
    CommandPool commandPool = commandPoolService.get();
    if (commandPool instanceof ReleasableCommandPool) {
      ReleasableCommandPool releasableCommandPool = (ReleasableCommandPool) commandPool;
      // Protecting this code from callers who do not hold the command pool slot
      // check if the caller holds the command pool slot before releasing it
      if (releasableCommandPool.amHoldingSlot()) {
        SubmitJobRequest newJobRequest =
            SubmitJobRequest.newBuilder(jobRequest).setRunInSameThread(false).build();
        logger.debug(
            "The SQL query {} will be submitted to the releasable command pool",
            jobRequest.getSqlQuery().getSql());
        return new SubmitJobToReleasableCommandPool(
            newJobRequest, eventObserver, planTransformationListener, releasableCommandPool);
      }
    }
    logger.info(
        "The SQL query {} will be submitted on the same thread", jobRequest.getSqlQuery().getSql());
    return new JobSubmissionHelper(jobRequest, eventObserver, planTransformationListener);
  }

  @Override
  public void runQueryAsJob(String query, String userName, String queryType, String queryLabel)
      throws Exception {
    runQueryAsJobInternal(query, userName, queryType, queryLabel);
  }

  @Override
  public List<RecordBatchHolder> runQueryAsJobForResults(
      String query, String userName, String queryType, String queryLabel, int offset, int limit)
      throws Exception {
    JobId jobId = runQueryAsJobInternal(query, userName, queryType, queryLabel);
    return getJobData(jobId, offset, limit).getRecordBatches();
  }

  private JobId runQueryAsJobInternal(
      String query, String userName, String queryType, String queryLabel) throws Exception {
    Span.current().setAttribute("dremio.query.type", queryType);
    SqlQuery.Builder sqlQuery = SqlQuery.newBuilder().setSql(query);
    final SubmitJobRequest jobRequest =
        SubmitJobRequest.newBuilder()
            .setQueryType(com.dremio.service.job.QueryType.valueOf(queryType))
            .setQueryLabel(
                Strings.isNullOrEmpty(queryLabel)
                    ? com.dremio.service.job.QueryLabel.NONE
                    : com.dremio.service.job.QueryLabel.valueOf(queryLabel))
            .setSqlQuery(sqlQuery.build())
            .setUsername(userName)
            .setRunInSameThread(true)
            .build();
    final CompletionListener completionListener = new CompletionListener(false);
    final JobStatusListenerAdapter streamObserver =
        new JobStatusListenerAdapter(completionListener);
    final JobSubmissionHelper jobSubmissionHelper =
        getJobSubmissionHelper(jobRequest, streamObserver, PlanTransformationListener.NO_OP);

    // release the slot in the releasable command pool; submit the job and wait before re-acquiring
    try (Closeable closeable =
        jobSubmissionHelper.releaseAndReacquireCommandPool(Priority.VERY_HIGH)) {
      // submit the job to the command pool
      JobSubmission jobSubmission = jobSubmissionHelper.submitJobToCommandPool();
      JobId submittedJobId = jobSubmission.getJobId();
      logger.info(
          "New job submitted. Job Id: {} - Type: {} - Query: {}",
          submittedJobId,
          jobRequest.getQueryType(),
          jobRequest.getSqlQuery().getSql());
      // Renames the current thread to indicate the JobId of the new triggered job
      final String originalThreadName = Thread.currentThread().getName();
      Thread.currentThread().setName(originalThreadName + ":" + submittedJobId);
      try {
        completionListener.await();
        if (completionListener.getException() != null) {
          logger.info("Submitted job (JobID {}) has failed", submittedJobId);
          throw new IllegalStateException(completionListener.getException());
        }
        if (!completionListener.isCompleted()) {
          logger.info("Submitted job (JobID {}) was cancelled", submittedJobId);
          throw new IllegalStateException(
              String.format(
                  "Submitted job (JobID %s) was cancelled. %s",
                  submittedJobId, completionListener.getCancelledReason()));
        } else {
          logger.info("Submitted job (JobID {}) has completed successfully", submittedJobId);
        }
      } catch (Exception e) {
        logger.info("Submitted job (JobID {}) has failed", submittedJobId);
        throw e;
      } finally {
        // Reverts the thread renaming once the submitted job is completed (passed or failed).
        Thread.currentThread().setName(originalThreadName);
      }

      return submittedJobId;
    }
  }

  public Job getJob(GetJobRequest request) throws JobNotFoundException {
    JobId jobId = request.getJobId();
    if (!request.isFromStore()) {
      QueryListener listener = runningJobs.get(jobId);
      if (listener != null) {
        return listener.getJob();
      }
    }
    return getJobFromStore(jobId, request.isProfileInfoRequired());
  }

  Job getJobFromStore(final JobId jobId, boolean profileDetailsRequired)
      throws JobNotFoundException {
    logger.debug("Fetching job details from store {}", jobId.getId());
    final JobResult jobResult = store.get(jobId);
    if (jobResult == null) {
      throw new JobNotFoundException(jobId);
    }

    SessionId sessionId = jobResult.getSessionId();
    Job job = new Job(jobId, jobResult, getJobResultsStore(), sessionId);
    populateJobDetailsFromFullProfile(job, profileDetailsRequired);
    return job;
  }

  /**
   * The job page in the UI shows a lot of stats collected from the full profile. These are
   * populated in the job info on the first such access from the UI.
   *
   * @param setQueryProfileInJob If true and if the QueryProfile is fetched, then it will be set
   *     inside the Job object
   */
  private void populateJobDetailsFromFullProfile(Job job, boolean setQueryProfileInJob) {
    try {
      JobAttempt attempt = job.getJobAttempt();
      if (!attempt.getIsProfileIncomplete()
          && (optionManagerProvider.get().getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE)
              || !QueryTypeUtils.isAccelerationType(attempt.getInfo().getQueryType()))
          && JobsServiceUtil.ifJobAttemptHasRunningState(attempt)
          && !job.profileDetailsCapturedPostTermination()) {
        logger.debug("Populating job details from full profile {}", job.getJobId());
        if (setQueryProfileInJob || JobsServiceUtil.finalJobStates.contains(attempt.getState())) {
          QueryProfile profile = getProfileFromJob(job, job.getAttempts().size() - 1);
          if (profile != null) {
            job.setProfile(profile);
            updateJobDetails(job, profile);
            if (JobsServiceUtil.finalJobStates.contains(attempt.getState())) {
              logger.debug(
                  "Updating job details in store from query profile for terminated job {}",
                  job.getJobId().getId());
              job.setProfileDetailsCapturedPostTermination(true);
              storeJob(job);
            }
          }
        }
      }
    } catch (Exception e) {
      // StatusRuntimeException from profile or NPE from Job attempt fields is possible
      logger.warn("Exception while populating job details from full profile: ", e);
    }
  }

  JobSummary getJobSummary(JobSummaryRequest jobSummaryRequest)
      throws JobNotFoundException, ExecutionException {
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(JobsProtoUtil.toStuff(jobSummaryRequest.getJobId()))
            .setFromStore(jobSummaryRequest.getFromStore())
            .setUserName(jobSummaryRequest.getUserName())
            .build();

    Job job = null;
    JobSummary summary = null;
    if (getJobRequest.isFromStore()) {
      job = getJobFromStore(getJobRequest.getJobId(), false);
      summary = JobsServiceUtil.toJobSummary(job);
    } else {
      job = getJob(getJobRequest);
      if (mustForwardRequest(job)) {
        logger.debug(
            "Forwarding JobSummary request for jobId {} to target {}",
            getJobRequest.getJobId().getId(),
            job.getJobAttempt().getEndpoint());
        summary =
            forwarder.getJobSummary(
                JobsProtoUtil.toBuf(job.getJobAttempt().getEndpoint()), jobSummaryRequest);
      } else {
        summary = JobsServiceUtil.toJobSummary(job);
      }
    }
    if (job.getJobAttempt().getInfo().getIsTruncatedSql()
        && (jobSummaryRequest.getMaxSqlLength() == 0
            || jobSummaryRequest.getMaxSqlLength() > getSqlTruncateLenFromOptionMgr())) {
      ExtraJobInfo extraJobInfo = extraJobInfoStore.get(job.getJobId());
      if (extraJobInfo == null) {
        logger.warn("ExtraJobInfo for JobId : {} not found.", job.getJobId().getId());
      } else {
        String fullSql = extraJobInfo.getSql();
        summary = summary.toBuilder().setSql(fullSql).build();
      }
    }
    return summary;
  }

  com.dremio.service.job.JobDetails getJobDetails(JobDetailsRequest jobDetailsRequest)
      throws JobNotFoundException, ExecutionException {
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(JobsProtoUtil.toStuff(jobDetailsRequest.getJobId()))
            .setUserName(jobDetailsRequest.getUserName())
            .setFromStore(jobDetailsRequest.getFromStore())
            .setProfileInfoRequired(!jobDetailsRequest.getSkipProfileInfo())
            .build();

    Job job = null;
    com.dremio.service.job.JobDetails details = null;
    if (getJobRequest.isFromStore()) {
      job = getJobFromStore(getJobRequest.getJobId(), !jobDetailsRequest.getSkipProfileInfo());
      details = JobsServiceUtil.toJobDetails(job, jobDetailsRequest.getProvideResultInfo());
    } else {
      job = getJob(getJobRequest);
      if (mustForwardRequest(job)) {
        logger.debug(
            "Forwarding JobDetails request for jobId {} to target {}",
            getJobRequest.getJobId().getId(),
            job.getJobAttempt().getEndpoint());
        details =
            this.forwarder.getJobDetails(
                JobsProtoUtil.toBuf(job.getJobAttempt().getEndpoint()), jobDetailsRequest);
      } else {
        details = JobsServiceUtil.toJobDetails(job, jobDetailsRequest.getProvideResultInfo());
      }
      if (!jobDetailsRequest.getSkipProfileInfo()
          && !job.getJobAttempt().getIsProfileIncomplete()) {
        if (job.getProfile() == null) {
          try {
            QueryProfile profile = getProfileFromJob(job, jobDetailsRequest.getAttemptIndex());
            if (profile != null) {
              details = details.toBuilder().setProfile(profile).build();
            }
          } catch (Exception e) {
            logger.debug(
                "Exception while fetching job profile for job {}",
                jobDetailsRequest.getJobId().getId(),
                e);
          }
        } else {
          details = details.toBuilder().setProfile(job.getProfile()).build();
        }
      }
    }
    if (job.getJobAttempt().getInfo().getIsTruncatedSql()) {
      ExtraJobInfo extraJobInfo = extraJobInfoStore.get(job.getJobId());
      if (extraJobInfo == null) {
        logger.warn("ExtraJobInfo for JobId : {} not found.", job.getJobId().getId());
      } else {
        String fullSql = extraJobInfo.getSql();
        int ai = details.getAttemptsCount() - 1;
        com.dremio.service.job.proto.JobProtobuf.JobInfo info =
            details.getAttempts(ai).getInfo().toBuilder().setSql(fullSql).build();
        com.dremio.service.job.proto.JobProtobuf.JobAttempt lastAttempt =
            details.getAttempts(ai).toBuilder().setInfo(info).build();
        return details.toBuilder().setAttempts(ai, lastAttempt).build();
      }
    }
    return details;
  }

  JobCounts getJobCounts(JobCountsRequest request) {
    final List<SearchQuery> conditions = new ArrayList<>();
    if (!request.getDatasetsList().isEmpty()) {
      if (optionManagerProvider.get().getOption(ExecConstants.JOBS_COUNT_FAST_ENABLED)) {
        GetJobCountsRequest getJobCountsRequest =
            GetJobCountsRequest.newBuilder()
                .addAllIds(
                    request.getDatasetsList().stream()
                        .map(
                            s -> {
                              NamespaceKey nsk = new NamespaceKey(s.getPathList());
                              return nsk.toString();
                            })
                        .collect(Collectors.toList()))
                .setType(JobCountType.CATALOG)
                .setJobCountsAgeInDays(request.getJobCountsAgeInDays())
                .build();
        com.dremio.service.jobcounts.JobCounts jobCounts =
            jobCountsClientProvider.get().getBlockingStub().getJobCounts(getJobCountsRequest);
        return toJobCounts(jobCounts);
      }

      request.getDatasetsList().stream()
          .map(JobsServiceUtil::getDatasetFilter)
          .forEach(searchQuery -> conditions.add(searchQuery));
    } else if (!request.getReflections().getReflectionIdsList().isEmpty()) {
      if (optionManagerProvider.get().getOption(ExecConstants.JOBS_COUNT_FAST_ENABLED)) {
        GetJobCountsRequest getJobCountsRequest =
            GetJobCountsRequest.newBuilder()
                .addAllIds(request.getReflections().getReflectionIdsList())
                .setType(JobCountType.valueOf(request.getReflections().getUsageType().name()))
                .setJobCountsAgeInDays(request.getJobCountsAgeInDays())
                .build();
        com.dremio.service.jobcounts.JobCounts jobCounts =
            jobCountsClientProvider.get().getBlockingStub().getJobCounts(getJobCountsRequest);
        return toJobCounts(jobCounts);
      }

      IndexKey indexKey = getIndexKey(request.getReflections().getUsageType());
      request.getReflections().getReflectionIdsList().stream()
          .map(id -> getReflectionIdFilter(id, indexKey))
          .forEach(searchQuery -> conditions.add(searchQuery));
    }

    JobCounts.Builder jobCounts = JobCounts.newBuilder();
    jobCounts.addAllCount(store.getCounts(conditions.stream().toArray(SearchQuery[]::new)));
    return jobCounts.build();
  }

  JobCounts getJobCountsDaily(JobCountsRequestDaily request) {
    GetJobCountsRequestDaily getJobCountsRequestDaily =
        GetJobCountsRequestDaily.newBuilder()
            .addAllIds(request.getReflections().getReflectionIdsList())
            .setType(JobCountType.valueOf(request.getReflections().getUsageType().name()))
            .setJobCountsAgeInDays(request.getJobCountsAgeInDays())
            .build();

    com.dremio.service.jobcounts.JobCounts jobCounts =
        jobCountsClientProvider.get().getBlockingStub().getJobCountsDaily(getJobCountsRequestDaily);

    return toJobCounts(jobCounts);
  }

  void deleteJobCounts(DeleteJobCountsRequest request) {
    com.dremio.service.jobcounts.DeleteJobCountsRequest.Builder deleteJobCountsBuilder =
        com.dremio.service.jobcounts.DeleteJobCountsRequest.newBuilder();
    if (!request.getDatasetsList().isEmpty()) {
      request.getDatasetsList().stream()
          .map(
              versionedDatasetPath -> {
                NamespaceKey namespaceKey = new NamespaceKey(versionedDatasetPath.getPathList());
                return namespaceKey.toString();
              })
          .forEach(deleteJobCountsBuilder::addIds);
    } else if (!request.getReflections().getReflectionIdsList().isEmpty()) {
      request.getReflections().getReflectionIdsList().forEach(deleteJobCountsBuilder::addIds);
    }
    jobCountsClientProvider.get().getBlockingStub().deleteJobCounts(deleteJobCountsBuilder.build());
  }

  private JobCounts toJobCounts(com.dremio.service.jobcounts.JobCounts jobCounts) {
    return JobCounts.newBuilder().addAllCount(jobCounts.getCountList()).build();
  }

  @VisibleForTesting
  public JobDataFragment getJobData(JobId jobId, int offset, int limit)
      throws JobNotFoundException {
    GetJobRequest request =
        GetJobRequest.newBuilder().setJobId(jobId).setUserName(SYSTEM_USERNAME).build();
    return getJob(request).getData().range(offset, limit);
  }

  @VisibleForTesting
  JobResultsStore getJobResultsStore() {
    if (this.jobResultsStore == null) {
      // Lazy initialization to allow the late setup on pre-warmed coordinators in the cloud
      // use-cases.
      this.jobResultsStore = jobResultsStoreProvider.get();
    }
    return this.jobResultsStore;
  }

  protected void onJobExecCompletion(
      Job job,
      List<Pair<NamespaceKey, IcebergTableChecker>> topViews,
      SqlKind sqlKind,
      QueryProfile profile,
      RelNode converted) {}

  private int getSqlTruncateLenFromOptionMgr() {
    int sqlTruncateLen =
        (int) optionManagerProvider.get().getOption(ExecConstants.SQL_TEXT_TRUNCATE_LENGTH);
    // value of 0 in SQL_TEXT_TRUNCATE_LENGTH is used to disable truncation
    return sqlTruncateLen == 0 ? Integer.MAX_VALUE : sqlTruncateLen;
  }

  private static final ImmutableList<SimpleEntry<JobStats.Type, SearchQuery>>
      JOBS_STATS_TYPE_TO_SEARCH_QUERY_MAPPING =
          ImmutableList.of(
              new SimpleEntry<>(JobStats.Type.UI, JobIndexKeys.UI_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.EXTERNAL, JobIndexKeys.EXTERNAL_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.ACCELERATION, JobIndexKeys.ACCELERATION_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.DOWNLOAD, JobIndexKeys.DOWNLOAD_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.INTERNAL, JobIndexKeys.INTERNAL_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.DAILY_JOBS, JobIndexKeys.DAILY_JOBS_FILTER),
              new SimpleEntry<>(JobStats.Type.USER_JOBS, JobIndexKeys.USER_JOBS_FILTER));

  JobStats getJobStats(JobStatsRequest request) {
    final long startDate = Timestamps.toMillis(request.getStartDate());
    final long endDate = Timestamps.toMillis(request.getEndDate());
    final List<JobStats.Type> typeList = request.getJobStatsTypeList();
    final JobStats.Builder jobStats = JobStats.newBuilder();
    for (JobStats.Type jobStatsType : typeList) {
      jobStats.addCounts(
          JobStats.JobCountWithType.newBuilder()
              .setType(
                  JOBS_STATS_TYPE_TO_SEARCH_QUERY_MAPPING.get(jobStatsType.getNumber()).getKey())
              .setCount(
                  store
                      .getCounts(
                          SearchQueryUtils.and(
                              SearchQueryUtils.newRangeLong(
                                  JobIndexKeys.START_TIME.getIndexFieldName(),
                                  startDate,
                                  endDate,
                                  true,
                                  true),
                              JOBS_STATS_TYPE_TO_SEARCH_QUERY_MAPPING
                                  .get(jobStatsType.getNumber())
                                  .getValue()))
                      .get(0)));
    }
    return jobStats.build();
  }

  HasAtLeastOneJobResponse hasAtLeastOneJob(HasAtLeastOneJobRequest request) {
    final long startDate = Timestamps.toMillis(request.getStartDate());
    final long endDate = Timestamps.toMillis(request.getEndDate());
    final List<JobStats.Type> typeList = request.getJobStatsTypeList();
    final HasAtLeastOneJobResponse.Builder response = HasAtLeastOneJobResponse.newBuilder();
    for (JobStats.Type jobStatsType : typeList) {
      Iterable<Entry<JobId, JobResult>> iterable =
          store.find(
              new LegacyFindByCondition()
                  .setCondition(
                      SearchQueryUtils.and(
                          SearchQueryUtils.newRangeLong(
                              JobIndexKeys.START_TIME.getIndexFieldName(),
                              startDate,
                              endDate,
                              true,
                              true),
                          JOBS_STATS_TYPE_TO_SEARCH_QUERY_MAPPING
                              .get(jobStatsType.getNumber())
                              .getValue()))
                  .setLimit(1));
      response.addTypeResponse(
          HasAtLeastOneJobWithType.newBuilder()
              .setJobStatsType(
                  JOBS_STATS_TYPE_TO_SEARCH_QUERY_MAPPING.get(jobStatsType.getNumber()).getKey())
              .setHasAtLeastOneJob(iterable.iterator().hasNext()));
    }
    return response.build();
  }

  private static final String FILTER = "(st=gt=%d;st=lt=%d)";

  private static long getEpochMillisFromLocalDate(LocalDate localDate) {
    return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  private static SearchQuery getDatasetFilter(String datasetPath, String version, String userName) {
    final ImmutableList.Builder<SearchTypes.SearchQuery> builder =
        ImmutableList.<SearchTypes.SearchQuery>builder()
            .add(SearchQueryUtils.newTermQuery(JobIndexKeys.ALL_DATASETS, datasetPath))
            .add(JobIndexKeys.UI_EXTERNAL_JOBS_FILTER);

    if (!Strings.isNullOrEmpty(version)) {
      DatasetVersion datasetVersion = new DatasetVersion(version);
      builder.add(SearchQueryUtils.newTermQuery(DATASET_VERSION, datasetVersion.getVersion()));
    }
    // TODO(DX-17909): this must be provided for authorization purposes
    if (!Strings.isNullOrEmpty(userName)) {
      builder.add(SearchQueryUtils.newTermQuery(USER, userName));
    }
    return SearchQueryUtils.and(builder.build());
  }

  private static List<SearchFieldSorting> buildSorter(
      final String sortKey, final SearchTypes.SortOrder order) {
    if (!Strings.isNullOrEmpty(sortKey)) {
      final IndexKey key = JobIndexKeys.MAPPING.getKey(sortKey);
      if (key == null || !key.isSorted()) {
        throw UserException.functionError()
            .message("Unable to sort by field %s.", sortKey)
            .buildSilently();
      }
      return ImmutableList.of(key.toSortField(order));
    }

    return DEFAULT_SORTER;
  }

  LegacyFindByCondition createCondition(SearchJobsRequest searchJobsRequest) {
    final LegacyFindByCondition condition = new LegacyFindByCondition();
    VersionedDatasetPath versionedDatasetPath = searchJobsRequest.getDataset();

    if (!versionedDatasetPath.getPathList().isEmpty()) {
      final NamespaceKey namespaceKey = new NamespaceKey(versionedDatasetPath.getPathList());
      condition.setCondition(
          getDatasetFilter(
              namespaceKey.toString(),
              versionedDatasetPath.getVersion(),
              searchJobsRequest.getUserName()));
    } else {
      condition.setCondition(
          MapFilterToJobState.map(searchJobsRequest.getFilterString()), JobIndexKeys.MAPPING);
    }

    final int offset = searchJobsRequest.getOffset();
    if (offset > 0) {
      condition.setOffset(offset);
    }

    final int limit = searchJobsRequest.getLimit();
    if (limit > 0) {
      condition.setLimit(limit);
    }

    condition.setPageSize(SEARCH_JOBS_PAGE_SIZE);
    final String sortColumn = searchJobsRequest.getSortColumn();
    if (!Strings.isNullOrEmpty(sortColumn)) {
      condition.addSortings(
          buildSorter(
              sortColumn, JobsProtoUtil.toStoreSortOrder(searchJobsRequest.getSortOrder())));
    }
    return condition;
  }

  Iterable<JobSummary> searchJobs(LegacyFindByCondition condition) {
    final Iterable<Job> jobs = toJobs(store.find(condition));
    return FluentIterable.from(jobs)
        .filter(job -> job.getJobAttempt() != null)
        .transform(job -> JobsServiceUtil.toJobSummary(job));
  }

  Iterable<JobSummary> searchJobs(SearchJobsRequest request) {
    LegacyFindByCondition condition = createCondition(request);
    return searchJobs(condition);
  }

  LegacyFindByCondition createActiveJobCondition(ActiveJobsRequest activeJobsRequest) {
    String filterString =
        "(jst==RUNNING,jst==QUEUED,jst==ENQUEUED,jst==PLANNING,jst==STARTING,jst==PENDING,jst==METADATA_RETRIEVAL,jst==ENGINE_START,jst==EXECUTION_PLANNING)";
    if (!activeJobsRequest.getUserName().equals("")) {
      filterString = filterString + ";usr==" + activeJobsRequest.getUserName();
    }
    final LegacyFindByCondition condition = new LegacyFindByCondition();
    condition.setCondition(filterString, JobIndexKeys.MAPPING);
    condition.setPageSize(SEARCH_JOBS_PAGE_SIZE);

    SearchProtos.SearchQuery query = activeJobsRequest.getQuery();
    if (query != null && query.getQueryCase() != SearchProtos.SearchQuery.QueryCase.QUERY_NOT_SET) {
      SearchQuery filterQuery = condition.getCondition();
      SearchQuery pushDownQuery =
          IndexedSearchQueryConverterUtil.toSearchQuery(query, JobSearchUtils.FIELDS);
      SearchQuery finalQuery;
      if (pushDownQuery != null) {
        finalQuery = SearchQueryUtils.and(filterQuery, pushDownQuery);
      } else {
        finalQuery = filterQuery;
      }
      condition.setCondition(finalQuery);
    }
    return condition;
  }

  Iterable<ActiveJobSummary> getActiveJobs(LegacyFindByCondition condition) {
    final Iterable<Job> jobs = toJobs(store.find(condition));
    return FluentIterable.from(jobs)
        .filter(
            job ->
                job.getJobAttempt() != null
                    && !isTerminal(
                        JobsServiceUtil.jobStatusToAttemptStatus(job.getJobAttempt().getState())))
        .transform(
            job -> {
              try {
                return JobsServiceUtil.toActiveJobSummary(job);
              } catch (Exception e) {
                logger.error(
                    "Exception while constructing ActiveJobSummary for job {}", job.getJobId(), e);
                return null;
              }
            });
  }

  Iterable<ActiveJobSummary> getActiveJobs(ActiveJobsRequest request) {
    LegacyFindByCondition condition = createActiveJobCondition(request);
    return getActiveJobs(condition);
  }

  LegacyFindByCondition createRecentJobCondition(RecentJobsRequest recentJobsRequest) {
    final LegacyFindByCondition condition = new LegacyFindByCondition();
    condition.setPageSize(GET_RECENT_JOBS_PAGE_SIZE);
    condition.setCondition(SearchQuery.newBuilder().setType(SearchQuery.Type.MATCH_ALL).build());
    SearchProtos.SearchQuery query = recentJobsRequest.getQuery();
    if (query.getQueryCase() != SearchProtos.SearchQuery.QueryCase.QUERY_NOT_SET) {
      SearchQuery pushDownQuery =
          IndexedSearchQueryConverterUtil.toSearchQuery(query, JobSearchUtils.FIELDS);
      if (pushDownQuery != null) {
        condition.setCondition(pushDownQuery);
      }
    }
    return condition;
  }

  Iterable<RecentJobSummary> getRecentJobs(LegacyFindByCondition condition) {
    Iterable<Entry<JobId, JobResult>> results = store.find(condition);
    return FluentIterable.from(results)
        .filter(
            result ->
                result.getValue().getAttemptsList().size() != 0
                    && result
                            .getValue()
                            .getAttemptsList()
                            .get(result.getValue().getAttemptsList().size() - 1)
                        != null)
        .transform(
            job -> {
              try {
                return JobsServiceUtil.toRecentJobSummary(job.getKey(), job.getValue());
              } catch (Exception e) {
                logger.error(
                    "Exception while constructing RecentJobSummary for job {}", job.getKey(), e);
                return null;
              }
            });
  }

  Iterable<RecentJobSummary> getRecentJobs(RecentJobsRequest request) {
    LegacyFindByCondition condition = createRecentJobCondition(request);
    return getRecentJobs(condition);
  }

  NodeStatusResponse getNodeStatus(NodeStatusRequest request) {
    return NodeStatusResponse.newBuilder().setStartTime(identity.getStartTime()).build();
  }

  Iterable<com.dremio.service.job.JobDetails> getJobsForParent(
      JobsWithParentDatasetRequest jobsWithParentDatasetRequest) {
    final VersionedDatasetPath versionedDatasetPath = jobsWithParentDatasetRequest.getDataset();
    if (!versionedDatasetPath.getPathList().isEmpty()) {
      final NamespaceKey namespaceKey = new NamespaceKey(versionedDatasetPath.getPathList());
      final SearchQuery query =
          SearchQueryUtils.and(
              SearchQueryUtils.newTermQuery(PARENT_DATASET, namespaceKey.getSchemaPath()),
              JobIndexKeys.UI_EXTERNAL_RUN_JOBS_FILTER);
      final LegacyFindByCondition condition =
          new LegacyFindByCondition()
              .setCondition(query)
              .setLimit(jobsWithParentDatasetRequest.getLimit())
              .setPageSize(100);
      final Iterable<Job> jobs = toJobs(store.find(condition));
      return FluentIterable.from(jobs).transform(job -> JobsServiceUtil.toJobDetails(job, false));
    }
    return null;
  }

  private Iterable<Job> toJobs(final Iterable<Entry<JobId, JobResult>> entries) {
    return Iterables.transform(
        entries,
        new Function<Entry<JobId, JobResult>, Job>() {
          @Override
          public Job apply(Entry<JobId, JobResult> input) {
            return new Job(
                input.getKey(),
                input.getValue(),
                getJobResultsStore(),
                input.getValue().getSessionId());
          }
        });
  }

  @Override
  public java.util.Optional<JobResultInfo> getJobResultInfo(String jobId, String username) {
    try {
      final Job job =
          getJob(
              GetJobRequest.newBuilder().setJobId(new JobId(jobId)).setUserName(username).build());
      if (job.isCompleted()) {
        final BatchSchema batchSchema =
            BatchSchema.deserialize(job.getJobAttempt().getInfo().getBatchSchema());
        final List<String> tableName = getJobResultsStore().getOutputTablePath(job.getJobId());
        return java.util.Optional.of(new JobResultInfo(tableName, batchSchema));
      } // else, fall through
    } catch (JobNotFoundException ignored) {
      // fall through
    }
    return java.util.Optional.empty();
  }

  private final class JobsObserverFactory implements QueryObserverFactory {

    @Override
    public QueryObserver createNewQueryObserver(
        ExternalId id, UserSession session, UserResponseHandler handler) {
      final JobId jobId = JobsServiceUtil.getExternalIdAsJobId(id);
      final RpcEndpointInfos clientInfos = session.getClientInfos();
      final QueryType queryType = QueryTypeUtils.getQueryType(clientInfos);
      final QueryLabel queryLabel =
          QueryLabelUtils.getQueryLabelFromString(session.getQueryLabel());

      final JobInfo jobInfo =
          new JobInfo(jobId, "UNKNOWN", "UNKNOWN", queryType)
              .setUser(session.getCredentials().getUserName())
              .setDatasetPathList(Arrays.asList("UNKNOWN"))
              .setStartTime(System.currentTimeMillis())
              .setQueryLabel(queryLabel)
              .setTtlExpireAt(getJobsTTLExpiryAtInMillis());
      if ((jobInfo.getContextList() == null || jobInfo.getContextList().isEmpty())
          && session.getDefaultSchemaPath() != null) {
        // If the context is not available from the jobInfo,
        // the context from the session should be used for the query.
        // Record that information in the jobInfo, so it is easily available later
        jobInfo.setContextList(session.getDefaultSchemaPath().getPathComponents());
      }
      final JobAttempt jobAttempt =
          new JobAttempt()
              .setInfo(jobInfo)
              .setEndpoint(identity)
              .setState(PENDING)
              .setDetails(new JobDetails());
      final Job job = new Job(jobId, jobAttempt, null);
      // Set `resultsCleaned` to true for external queries.
      // If we decide to save results for external queries in the future, simply remove this line.
      job.setResultsCleaned(true);
      QueryListener listener = new QueryListener(job, handler, session.getSessionOptionManager());
      runningJobs.put(jobId, listener);
      storeJob(job);
      return listener;
    }
  }

  private final class QueryListener extends AbstractQueryObserver {
    private final Job job;
    private final ExternalId externalId;
    private final UserResponseHandler responseHandler;
    private final JobEventCollatingObserver eventObserver;
    private final SessionObserver sessionObserver;
    private final PlanTransformationListener planTransformationListener;
    private final boolean isInternal;
    private final boolean streamResultsMode;
    private final ExternalListenerManager listeners = new ExternalListenerManager();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final DeferredException exception = new DeferredException();

    private JobResultListener attemptObserver;
    private SessionOptionManager sessionOptionManager = null;
    private ExecutionControls executionControls;
    private final Set<String> pendingExecutorProfiles = ConcurrentHashMap.newKeySet();
    private volatile boolean queryClosed = false;

    // Tells if all the Executor Profiles were received from Executor

    private QueryListener(
        Job job, UserResponseHandler connection, SessionOptionManager sessionOptionManager) {
      this.job = job;
      this.externalId = JobsServiceUtil.getJobIdAsExternalId(job.getJobId());
      this.responseHandler = Preconditions.checkNotNull(connection, "handler cannot be null");
      this.eventObserver = null;
      this.sessionObserver = null;
      this.planTransformationListener = null;
      this.isInternal = false;
      this.job.setIsInternal(false);
      this.streamResultsMode = true;
      setupJobData();
      this.sessionOptionManager = sessionOptionManager;
    }

    private QueryListener(
        Job job,
        JobEventCollatingObserver eventObserver,
        SessionObserver sessionObserver,
        PlanTransformationListener planTransformationListener,
        boolean streamResultsMode) {
      this.job = job;
      this.externalId = JobsServiceUtil.getJobIdAsExternalId(job.getJobId());
      this.responseHandler = null;
      this.eventObserver =
          Preconditions.checkNotNull(eventObserver, "eventObserver cannot be null");
      this.sessionObserver =
          Preconditions.checkNotNull(sessionObserver, "sessionObserver cannot be null");
      this.planTransformationListener =
          Preconditions.checkNotNull(planTransformationListener, "statusListener cannot be null");
      this.isInternal = true;
      this.job.setIsInternal(true);
      this.streamResultsMode = streamResultsMode;
      setupJobData();
    }

    private Job getJob() {
      return job;
    }

    private void setupJobData() {
      final JobLoader jobLoader =
          (isInternal && !streamResultsMode)
              ? new InternalJobLoader(
                  exception, completionLatch, job.getJobId(), getJobResultsStore(), store)
              : new ExternalJobLoader(completionLatch, exception);
      JobData jobData = new JobDataImpl(jobLoader, job.getJobId(), job.getSessionId());
      job.setData(jobData);
    }

    @Override
    public AttemptObserver newAttempt(AttemptId attemptId, AttemptReason reason) {
      Span.current().setAttribute("dremio.job.type", job.getQueryType().toString());
      // first attempt is already part of the job
      if (attemptId.getAttemptNum() > 0) {
        // create a new JobAttempt for the new attempt
        final JobInfo jobInfo =
            ProtostuffUtil.copy(job.getJobAttempt().getInfo())
                .setStartTime(
                    System.currentTimeMillis()) // use different startTime for every attempt
                .setFailureInfo(null)
                .setDetailedFailureInfo(null)
                .setResultMetadataList(new ArrayList<ArrowFileMetadata>())
                .setTtlExpireAt(getJobsTTLExpiryAtInMillis());
        final JobAttempt jobAttempt =
            new JobAttempt()
                .setInfo(jobInfo)
                .setReason(reason)
                .setEndpoint(identity)
                .setDetails(new JobDetails())
                .setState(PENDING);
        job.addAttempt(jobAttempt);
      }
      job.getJobAttempt().setAttemptId(AttemptIdUtils.toString(attemptId));
      if (isInternal) {
        if (streamResultsMode) {
          attemptObserver =
              new InternalJobResultStreamingListener(
                  attemptId, job, allocator, eventObserver, planTransformationListener, listeners);
        } else {
          attemptObserver =
              new JobResultListener(
                  attemptId, job, allocator, eventObserver, planTransformationListener, listeners);
        }
      } else {
        attemptObserver =
            new ExternalJobResultListener(attemptId, responseHandler, job, allocator, listeners);
      }
      if (!isInternal) {
        this.executionControls =
            new ExecutionControls(sessionOptionManager, JobsServiceUtil.toPB(identity));
      } else {
        this.executionControls =
            new ExecutionControls(optionManagerProvider.get(), JobsServiceUtil.toPB(identity));
      }
      storeJob(job);
      return attemptObserver;
    }

    @Override
    public void putExecutorProfile(String nodeEndpoint) {
      pendingExecutorProfiles.add(nodeEndpoint);
    }

    @Override
    public void removeExecutorProfile(String nodeEndpoint) {
      pendingExecutorProfiles.remove(nodeEndpoint);
    }

    @Override
    public void queryClosed() {
      queryClosed = true;
    }

    @Override
    public void execCompletion(UserResult userResult) {
      final QueryState state = userResult.getState();
      final QueryProfile profile = userResult.getProfile();
      final UserException ex = userResult.getException();
      final OptionManager optionManager = optionManagerProvider.get();
      try {
        // mark the job as completed
        job.setCompleted(true);
        if (state == QueryState.COMPLETED) {
          if (!optionManager.getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE)) {
            updateJoinAnalysis(optionManager);
          }
          LocalJobsService.this.onJobExecCompletion(
              job,
              attemptObserver.topViews,
              attemptObserver.sqlKind,
              profile,
              attemptObserver.converted);
        }
        injector.injectChecked(
            executionControls, INJECTOR_ATTEMPT_COMPLETION_ERROR, IOException.class);
        injector.injectChecked(
            executionControls, INJECTOR_ATTEMPT_COMPLETION_KV_ERROR, DatastoreException.class);
        // includes a call to storeJob()
        addAttemptToJob(job, state, profile, ex);
      } catch (Exception e) {
        exception.addException(e);
      }
      logger.debug("Removing job from running job list: {}", job.getJobId().getId());
      runningJobs.remove(job.getJobId());
      if (ex != null) {
        exception.addException(ex);
      }
      if (attemptObserver.getException() != null) {
        exception.addException(attemptObserver.getException());
      }
      this.completionLatch.countDown();
      if (isInternal) {
        try {
          switch (state) {
            case COMPLETED:
            case CANCELED:
              eventObserver.onFinalJobSummary(
                  JobEvent.newBuilder()
                      .setFinalJobSummary(JobsServiceUtil.toJobSummary(job))
                      .build());
              eventObserver.onCompleted();
              sessionObserver.onCompleted();
              break;
            case FAILED:
              eventObserver.onError(ex);
              sessionObserver.onError(ex);
              break;
            default:
              logger.warn("Invalid completed state {}", state);
          }
        } catch (Exception e) {
          exception.addException(e);
        }
      } else {
        // send result to client.
        UserResult newResult = userResult.withNewQueryId(ExternalIdHelper.toQueryId(externalId));
        responseHandler.completed(newResult);
      }
      listeners.close(JobsServiceUtil.toJobSummary(job));
      try {
        AutoCloseables.close(eventObserver);
      } catch (Exception e) {
        logger.error("Exception while closing JobEventObserver: {}", job.getJobId(), e);
      }
      if (optionManager.getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE)) {
        updateFinalProfilesAsync(profile, optionManager);
        updateJoinAnalysis(optionManager);
      } else {
        attemptObserver.putProfileUpdateComplete();
      }
      storeJob(job);
      queryLoggerExecutorService.submit(
          () -> {
            // We dont want to load the query profile as part of job execution for DCS. Hence,
            // separating this into a thread.
            populateJobDetailsFromFullProfile(job, false);
            jobResultLogger.info(
                job,
                "Query: {}; outcome: {}",
                job.getJobId().getId(),
                job.getJobAttempt().getState());
          });
    }

    private void updateJoinAnalysis(OptionManager optionManager) {
      if (!job.getJobAttempt().getIsProfileIncomplete()
          && (QueryTypeUtils.isAccelerationType(job.getJobAttempt().getInfo().getQueryType())
              || optionManager.getOption(ExecConstants.ENABLE_JOIN_ANALYSIS_POPULATOR))) {
        JoinAnalysis joinAnalysis = null;
        try {
          if (attemptObserver.joinPreAnalyzer != null) {
            QueryProfile fullProfile = getProfileFromJob(job, job.getAttempts().size() - 1);
            JoinAnalyzer joinAnalyzer =
                new JoinAnalyzer(fullProfile, attemptObserver.joinPreAnalyzer);
            joinAnalysis = joinAnalyzer.computeJoinAnalysis();
          }
          // If no Prel, probably because user only asked for the plan
        } catch (Exception e) {
          logger.warn("Failure while setting joinAnalysis for jobId {}", job.getJobId().getId(), e);
        }
        if (joinAnalysis != null) {
          job.getJobAttempt().getInfo().setJoinAnalysis(joinAnalysis);
        }
      }
    }

    private void updateFinalProfilesAsync(QueryProfile profile, OptionManager optionManager) {
      try {
        long startTime = System.currentTimeMillis();
        long waitTimeout =
            optionManager.getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE_WAIT_IN_MILLISECONDS);
        while (((profile.getState().equals(QueryState.COMPLETED) && !queryClosed)
                || !pendingExecutorProfiles.isEmpty())
            && (System.currentTimeMillis() - startTime) < waitTimeout) {
          try {
            // Sleep for a short period to avoid active spinning
            TimeUnit.MILLISECONDS.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve interrupt status
            break; // Exit the loop if interrupted
          }
        }
        // After waiting, check if there are still pending executor profiles
        if (!pendingExecutorProfiles.isEmpty()) {
          jobTelemetryClientProvider
              .get()
              .getSuppressedErrorCounter()
              .withTags(
                  MetricLabel.JTS_METRIC_TAG_KEY_RPC,
                  MetricLabel.JTS_METRIC_TAG_VALUE_RPC_PUT_EXECUTOR_PROFILE,
                  MetricLabel.JTS_METRIC_TAG_KEY_ERROR_ORIGIN,
                  MetricLabel.JTS_METRIC_TAG_VALUE_PENDING_PROFILE)
              .increment();
          logger.warn("Pending executor profiles not empty for job {}", job.getJobId().getId());
          attemptObserver.putProfileFailed();
        }
        try {
          final AttemptId attemptId =
              new AttemptId(
                  JobsServiceUtil.getJobIdAsExternalId(job.getJobId()),
                  job.getAttempts().size() - 1);
          jobTelemetryServiceStub.putQueryTailProfile(
              PutTailProfileRequest.newBuilder()
                  .setQueryId(attemptId.toQueryId())
                  .setProfile(profile)
                  .build());
          attemptObserver.putProfileUpdateComplete();
        } catch (Throwable e) {
          jobTelemetryClientProvider
              .get()
              .getSuppressedErrorCounter()
              .withTags(
                  MetricLabel.JTS_METRIC_TAG_KEY_RPC,
                  MetricLabel.JTS_METRIC_TAG_VALUE_RPC_SEND_TAIL_PROFILE,
                  MetricLabel.JTS_METRIC_TAG_KEY_ERROR_ORIGIN,
                  MetricLabel.JTS_METRIC_TAG_VALUE_EXEC_COMPLETION)
              .increment();
          logger.warn("Exception sending Tail profile {}", job.getJobId().getId(), e);
          attemptObserver.putProfileFailed();
        }
      } catch (Throwable e) {
        jobTelemetryClientProvider
            .get()
            .getSuppressedErrorCounter()
            .withTags(
                MetricLabel.JTS_METRIC_TAG_KEY_RPC,
                MetricLabel.JTS_METRIC_TAG_VALUE_RPC_PUT_EXECUTOR_PROFILE,
                MetricLabel.JTS_METRIC_TAG_KEY_ERROR_ORIGIN,
                MetricLabel.JTS_METRIC_TAG_VALUE_EXEC_COMPLETION)
            .increment();
        logger.warn("Exception sending Executor profile {}", job.getJobId().getId(), e);
        attemptObserver.putProfileFailed();
      }
      pendingExecutorProfiles.clear();
    }
  }

  /** A query observer for external queries. Delegates the data back to the original connection. */
  private final class ExternalJobResultListener extends JobResultListener {
    private final UserResponseHandler connection;
    private final ExternalId externalId;

    ExternalJobResultListener(
        AttemptId attemptId,
        UserResponseHandler connection,
        Job job,
        BufferAllocator allocator,
        ExternalListenerManager externalListenerManager) {
      super(
          attemptId,
          job,
          allocator,
          new JobEventCollatingObserver(
              job.getJobId(), NoopStreamObserver.instance(), executorService, false),
          PlanTransformationListener.NO_OP,
          externalListenerManager);
      this.connection = connection;
      this.externalId = JobsServiceUtil.getJobIdAsExternalId(job.getJobId());
    }

    @Override
    public void execDataArrived(
        RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
      connection.sendData(outcomeListener, ExternalIdHelper.replaceQueryId(result, externalId));
      // TODO: maybe capture and write the first few result batches to the job store so we can view
      // those results?
    }
  }

  /**
   * A query observer for internal queries with streamResultsMode. Delegates the data back to the
   * original grpc connection.
   */
  private final class InternalJobResultStreamingListener extends JobResultListener {
    private final JobEventCollatingObserver eventObserver;

    InternalJobResultStreamingListener(
        AttemptId attemptId,
        Job job,
        BufferAllocator allocator,
        JobEventCollatingObserver eventObserver,
        PlanTransformationListener planTransformationListener,
        ExternalListenerManager externalListenerManager) {
      super(
          attemptId,
          job,
          allocator,
          eventObserver,
          planTransformationListener,
          externalListenerManager);
      this.eventObserver = eventObserver;
    }

    @Override
    public void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch data) {
      try {
        int totalSize = 0;
        for (int i = 0; i < data.getBuffers().length; i++) {
          totalSize += data.getBuffers()[i].readableBytes();
        }
        com.google.protobuf.ByteString.Output outputStream =
            com.google.protobuf.ByteString.newOutput(totalSize);
        for (int i = 0; i < data.getBuffers().length; i++) {
          data.getBuffers()[i].readBytes(outputStream, data.getBuffers()[i].readableBytes());
          data.getBuffers()[i].release();
        }
        QueryResultData.Builder queryResultsBuilder =
            QueryResultData.newBuilder()
                .setHeader(data.getHeader())
                .setResultData(outputStream.toByteString());
        // todo: fix ser/deser overhead (DX-46512)
        eventObserver.onData(
            JobEvent.newBuilder().setQueryResultData(queryResultsBuilder.build()).build(),
            outcomeListener);
      } catch (IOException ex) {
        outcomeListener.failed(new RpcException(ex));
        getDeferredException().addException(ex);
      }
    }
  }

  @VisibleForTesting
  Iterable<Job> getAllJobs() {
    LegacyFindByCondition condition = new LegacyFindByCondition();
    condition.addSortings(DEFAULT_SORTER);
    return toJobs(store.find(condition));
  }

  @VisibleForTesting
  void storeJob(Job job) {
    store.put(job.getJobId(), job.toJobResult(job));
  }

  void recordJobResult(StoreJobResultRequest request) {
    final JobId jobId = JobsProtoUtil.toStuff(request.getJobId());
    final JobResult jobResult = JobsServiceUtil.toJobResult(request, getJobsTTLExpiryAtInMillis());
    store.put(jobId, jobResult);
  }

  /** Listener for internally submitted jobs. */
  private class JobResultListener extends AbstractAttemptObserver {
    private final AttemptId attemptId;
    private final DeferredException exception = new DeferredException();
    private final Job job;
    private final JobId jobId;
    private final BufferAllocator allocator;
    private final JobEventCollatingObserver eventObserver;
    private final PlanTransformationListener planTransformationListener;
    private QueryMetadata.Builder builder;
    private final AccelerationDetailsPopulator detailsPopulator;
    private final ExternalListenerManager externalListenerManager;
    private JoinPreAnalyzer joinPreAnalyzer;
    private final List<JobCountUpdate> jobCountUpdatesList = new ArrayList<>();
    private List<Pair<NamespaceKey, IcebergTableChecker>> topViews = List.of();
    private SqlKind sqlKind = SqlKind.OTHER;
    private Map<String, Long> executorOutputRecordMap;
    private RelNode converted;

    JobResultListener(
        AttemptId attemptId,
        Job job,
        BufferAllocator allocator,
        JobEventCollatingObserver eventObserver,
        PlanTransformationListener planTransformationListener,
        ExternalListenerManager externalListenerManager) {
      Preconditions.checkNotNull(getJobResultsStore());
      this.attemptId = attemptId;
      this.job = job;
      this.jobId = job.getJobId();
      this.allocator = allocator;
      Catalog systemCatalog = CatalogUtil.getSystemCatalogForJobs(catalogService);
      this.builder =
          QueryMetadata.builder(systemCatalog, systemCatalog, storageName)
              .addQuerySql(job.getJobAttempt().getInfo().getSql())
              .addQueryContext(job.getJobAttempt().getInfo().getContextList());
      this.eventObserver = eventObserver;
      this.planTransformationListener = planTransformationListener;
      this.detailsPopulator = accelerationManagerProvider.get().newPopulator();
      this.externalListenerManager = externalListenerManager;
      this.executorOutputRecordMap = new HashMap<>();
    }

    Exception getException() {
      return exception.getException();
    }

    DeferredException getDeferredException() {
      return exception;
    }

    @Override
    @WithSpan
    public void recordsOutput(CoordinationProtos.NodeEndpoint endpoint, long outputRecords) {
      String endpointKey = EndpointHelper.getMinimalString(endpoint);
      if (executorOutputRecordMap.containsKey(endpointKey)) {
        // Since recordsOutput is currently called only during node completion message from
        // executors
        // We should not consider the recordsOutput again from the same executor in case of retry or
        // race condition
        logger.debug(
            "Output records: {} for Endpoint: {} is already updated, skipping update.",
            outputRecords,
            endpointKey);
        return;
      }
      executorOutputRecordMap.putIfAbsent(endpointKey, outputRecords);
      JobAttempt jobAttempt = job.getJobAttempt();
      if (jobAttempt.getStats() != null) {
        Long currOutputRecords = jobAttempt.getStats().getOutputRecords();
        if (currOutputRecords != null && currOutputRecords > 0) {
          jobAttempt.getStats().setOutputRecords(currOutputRecords + outputRecords);
        } else {
          jobAttempt.getStats().setOutputRecords(outputRecords);
        }
      } else {
        jobAttempt.setStats(
            new com.dremio.service.job.proto.JobStats().setOutputRecords(outputRecords));
      }
      JobSummary summary = JobsServiceUtil.toJobSummary(job);
      externalListenerManager.queryProgressed(summary);
      logger.debug(
          "Output records: {} for Endpoint: {} updated to total output records.",
          outputRecords,
          endpointKey);
    }

    @Override
    public void outputLimited() {
      JobAttempt jobAttempt = job.getJobAttempt();
      if (jobAttempt.getStats() != null) {
        jobAttempt.getStats().setIsOutputLimited(true);
      } else {
        jobAttempt.setStats(new com.dremio.service.job.proto.JobStats().setIsOutputLimited(true));
      }
    }

    @Override
    public void queryStarted(UserRequest query, String user) {
      job.getJobAttempt().getInfo().setRequestType(query.getRequestType());
      if (job.getJobAttempt().getInfo().getSql() == null
          || job.getJobAttempt().getInfo().getSql().equals("UNKNOWN")) {
        int sqlTruncateLen = getSqlTruncateLenFromOptionMgr();
        String sqlText =
            query.getSql().length() > sqlTruncateLen
                ? query.getSql().substring(0, sqlTruncateLen)
                : query.getSql();
        job.getJobAttempt().getInfo().setSql(sqlText);
        if (query.getSql().length() > sqlTruncateLen) {
          job.getJobAttempt().getInfo().setIsTruncatedSql(true);
          extraJobInfoStore.put(
              jobId,
              new ExtraJobInfo()
                  .setSql(query.getSql())
                  .setTtlExpireAt(getJobsTTLExpiryAtInMillis() + TimeUnit.MINUTES.toMillis(10)));
        }
      }
      if (RequestType.valueOf(query.getRequestType().toString()) != RequestType.RUN_SQL) {
        job.getJobAttempt().getInfo().setDescription(query.getDescription());
      } else {
        job.getJobAttempt().getInfo().setDescription("NA");
      }
      if (externalListenerManager != null) {
        externalListenerManager.queryProgressed(JobsServiceUtil.toJobSummary(job));
      }
    }

    @Override
    public void commandPoolWait(long waitInMillis) {
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      long currentWait = Optional.ofNullable(jobInfo.getCommandPoolWaitMillis()).orElse(0L);
      jobInfo.setCommandPoolWaitMillis(currentWait + waitInMillis);
      if (externalListenerManager != null) {
        externalListenerManager.queryProgressed(JobsServiceUtil.toJobSummary(job));
      }
    }

    @Override
    public void planStart(String rawPlan) {}

    @Override
    public void planValidated(
        RelDataType rowType,
        SqlNode node,
        long millisTaken,
        boolean isMaterializationCacheInitialized) {
      builder.addRowType(rowType).addParsedSql(node);
      sqlKind = node.getKind();
    }

    @Override
    public void planParallelized(final PlanningSet planningSet) {
      builder.setPlanningSet(planningSet);
    }

    @Override
    public void planConsidered(LayoutMaterializedViewProfile profile, RelWithInfo target) {
      detailsPopulator.planConsidered(profile);
    }

    @Override
    public void planSubstituted(
        DremioMaterialization materialization,
        List<RelWithInfo> substitutions,
        RelWithInfo target,
        long millisTaken,
        boolean defaultReflection) {
      detailsPopulator.planSubstituted(
          materialization, substitutions, target.getRel(), millisTaken, defaultReflection);
    }

    @Override
    public void substitutionFailures(Iterable<String> errors) {
      detailsPopulator.substitutionFailures(errors);
    }

    @Override
    public void planAccelerated(final SubstitutionInfo info) {
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      final Acceleration acceleration = new Acceleration(info.getAcceleratedCost());
      final List<Acceleration.Substitution> substitutions =
          Lists.transform(
              info.getSubstitutions(),
              new Function<SubstitutionInfo.Substitution, Acceleration.Substitution>() {
                @Nullable
                @Override
                public Acceleration.Substitution apply(
                    @Nullable final SubstitutionInfo.Substitution sub) {
                  final Acceleration.Substitution.Identifier id =
                      new Acceleration.Substitution.Identifier(
                          "", sub.getMaterialization().getLayoutId());
                  id.setMaterializationId(sub.getMaterialization().getMaterializationId());
                  return new Acceleration.Substitution(
                          id, sub.getMaterialization().getOriginalCost(), sub.getSpeedup())
                      .setTablePathList(sub.getMaterialization().getPath());
                }
              });
      acceleration.setSubstitutionsList(substitutions);
      jobInfo.setAcceleration(acceleration);
      detailsPopulator.planAccelerated(info);
    }

    @Override
    public void planCompleted(final ExecutionPlan plan, final BatchSchema batchSchema) {
      if (plan != null) {
        try {
          builder.addBatchSchema(RootSchemaFinder.getSchema(plan.getRootOperator()));
        } catch (Exception e) {
          exception.addException(e);
        }
      } else if (batchSchema != null) {
        builder.addBatchSchema(batchSchema);
      }
      job.getJobAttempt()
          .setAccelerationDetails(ByteString.copyFrom(detailsPopulator.computeAcceleration()));
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      final boolean countReflectionUsage =
          JobsServiceUtil.countReflectionUsage(jobInfo.getQueryType());
      // Reverse refectionId to not affect existing job search functions that search jobs by
      // reflectionId.
      if (optionManagerProvider.get().getOption(PlannerSettings.ENABLE_JOB_COUNT_CONSIDERED)) {
        jobInfo.setConsideredReflectionIdsList(
            detailsPopulator.getConsideredReflectionIds().stream()
                .map(
                    s -> {
                      if (countReflectionUsage) {
                        jobCountUpdatesList.add(
                            JobCountUpdate.newBuilder()
                                .setId(s)
                                .setType(JobCountType.CONSIDERED)
                                .build());
                      }
                      return new StringBuilder(s).reverse().toString();
                    })
                .collect(Collectors.toList()));
      }
      if (optionManagerProvider.get().getOption(PlannerSettings.ENABLE_JOB_COUNT_MATCHED)) {
        jobInfo.setMatchedReflectionIdsList(
            detailsPopulator.getMatchedReflectionIds().stream()
                .map(
                    s -> {
                      if (countReflectionUsage) {
                        jobCountUpdatesList.add(
                            JobCountUpdate.newBuilder()
                                .setId(s)
                                .setType(JobCountType.MATCHED)
                                .build());
                      }
                      return new StringBuilder(s).reverse().toString();
                    })
                .collect(Collectors.toList()));
      }
      if (optionManagerProvider.get().getOption(PlannerSettings.ENABLE_JOB_COUNT_CHOSEN)) {
        jobInfo.setChosenReflectionIdsList(
            detailsPopulator.getChosenReflectionIds().stream()
                .map(
                    s -> {
                      if (countReflectionUsage) {
                        jobCountUpdatesList.add(
                            JobCountUpdate.newBuilder()
                                .setId(s)
                                .setType(JobCountType.CHOSEN)
                                .build());
                      }
                      return new StringBuilder(s).reverse().toString();
                    })
                .collect(Collectors.toList()));
      }
      if (externalListenerManager != null) {
        externalListenerManager.queryProgressed(JobsServiceUtil.toJobSummary(job));
      }
      // plan is parallelized after physical planning is done so we need to finalize metadata here
      finalizeMetadata();
    }

    @Override
    public void restoreAccelerationProfileFromCachedPlan(AccelerationProfile accelerationProfile) {
      detailsPopulator.restoreAccelerationProfile(accelerationProfile);
    }

    @Override
    public void resourcesScheduled(ResourceSchedulingDecisionInfo resourceSchedulingDecisionInfo) {
      final JobInfo jobInfo = job.getJobAttempt().getInfo();
      if (resourceSchedulingDecisionInfo != null) {
        if (jobInfo.getResourceSchedulingInfo() == null) {
          jobInfo.setResourceSchedulingInfo(new ResourceSchedulingInfo());
        }
        jobInfo
            .getResourceSchedulingInfo()
            .setQueueName(resourceSchedulingDecisionInfo.getQueueName())
            .setQueueId(resourceSchedulingDecisionInfo.getQueueId())
            .setResourceSchedulingStart(resourceSchedulingDecisionInfo.getSchedulingStartTimeMs())
            .setResourceSchedulingEnd(resourceSchedulingDecisionInfo.getSchedulingEndTimeMs())
            .setQueryCost(
                resourceSchedulingDecisionInfo.getResourceSchedulingProperties().getQueryCost())
            .setEngineName(resourceSchedulingDecisionInfo.getEngineName());
      }
    }

    @Override
    public void execStarted(QueryProfile profile) {
      try (TimedBlock b = Timer.time("execStarted")) {
        b.addID("attempt=" + attemptId);
        final JobInfo jobInfo = job.getJobAttempt().getInfo();
        if (profile != null) {
          jobInfo.setStartTime(profile.getStart());
          final QueryProfileToJobConverter profileParser =
              new QueryProfileToJobConverter(jobId, profile);
          if (profile.getResourceSchedulingProfile() != null) {
            if (jobInfo.getResourceSchedulingInfo() == null) {
              jobInfo.setResourceSchedulingInfo(new ResourceSchedulingInfo());
            }
            jobInfo
                .getResourceSchedulingInfo()
                .setQueueName(profile.getResourceSchedulingProfile().getQueueName());
            jobInfo
                .getResourceSchedulingInfo()
                .setQueueId(profile.getResourceSchedulingProfile().getQueueId());
            jobInfo
                .getResourceSchedulingInfo()
                .setEngineName(profile.getResourceSchedulingProfile().getEngineName());
          }
          job.getJobAttempt().setStats(profileParser.getJobStats());
          job.getJobAttempt().setDetails(profileParser.getJobDetails());
          if (externalListenerManager != null) {
            externalListenerManager.queryProgressed(JobsServiceUtil.toJobSummary(job));
          }
        }
      } catch (IOException e) {
        exception.addException(e);
      }
    }

    private void finalizeMetadata() {
      try {
        QueryMetadata metadata = builder.build();
        builder = null; // no longer needed.
        Set<String> countDatasets = new HashSet<>();
        Optional<List<ParentDatasetInfo>> parents = metadata.getParents();
        JobInfo jobInfo = job.getJobAttempt().getInfo();
        if (jobInfo.getDatasetPathList() != null) {
          countDatasets.add(new NamespaceKey(jobInfo.getDatasetPathList()).toString());
        }
        if (parents.isPresent()) {
          jobInfo.setParentsList(parents.get());
          for (ParentDatasetInfo pdi : parents.get()) {
            countDatasets.add(new NamespaceKey(pdi.getDatasetPathList()).toString());
          }
        }
        Optional<List<JoinInfo>> joins = metadata.getJoins();
        if (joins.isPresent()) {
          jobInfo.setJoinsList(joins.get());
        }
        Optional<List<FieldOrigin>> fieldOrigins = metadata.getFieldOrigins();
        if (fieldOrigins.isPresent()) {
          jobInfo.setFieldOriginsList(fieldOrigins.get());
          for (FieldOrigin fieldOrigin : fieldOrigins.get()) {
            for (Origin origin : listNotNull(fieldOrigin.getOriginsList())) {
              List<String> tableList = listNotNull(origin.getTableList());
              if (!tableList.isEmpty()) {
                countDatasets.add(new NamespaceKey(tableList).toString());
              }
            }
          }
        }
        Optional<List<ParentDataset>> grandParents = metadata.getGrandParents();
        if (grandParents.isPresent()) {
          jobInfo.setGrandParentsList(grandParents.get());
          for (ParentDataset parentDataset : listNotNull(jobInfo.getGrandParentsList())) {
            countDatasets.add(new NamespaceKey(parentDataset.getDatasetPathList()).toString());
          }
        }
        final Optional<RelOptCost> cost = metadata.getCost();
        if (cost.isPresent()) {
          final double aggCost = DremioCost.aggregateCost(cost.get());
          jobInfo.setOriginalCost(aggCost);
        }
        if (metadata.getScanPaths() != null) {
          jobInfo.setScanPathsList(metadata.getScanPaths());
        }
        Optional<BatchSchema> schema = metadata.getBatchSchema();
        if (schema.isPresent()) {
          // There is DX-14280. We will be able to remove clone call, when it would be resolved.
          jobInfo.setBatchSchema(
              schema.get().clone(BatchSchema.SelectionVectorMode.NONE).toByteString());
        }
        if (metadata.getSourceNames() != null) {
          jobInfo.setSourceNamesList(metadata.getSourceNames());
        }
        if (metadata.getSinkPath() != null) {
          jobInfo.setSinkPathList(metadata.getSinkPath());
        }
        if (JobsServiceUtil.isUserQuery(jobInfo.getQueryType())) {
          countDatasets.forEach(
              k ->
                  jobCountUpdatesList.add(
                      JobCountUpdate.newBuilder().setId(k).setType(JobCountType.CATALOG).build()));
        }
        updateJobCountInStore(jobCountUpdatesList);
        eventObserver.onQueryMetadata(
            JobEvent.newBuilder().setQueryMetadata(JobsProtoUtil.toBuf(metadata)).build());
        externalListenerManager.metadataAvailable(JobsProtoUtil.toBuf(metadata));
      } catch (Exception ex) {
        exception.addException(ex);
      }
    }

    private void updateJobCountInStore(List<JobCountUpdate> jobCountUpdates) {
      if (jobCountUpdates.size() > 0) {
        UpdateJobCountsRequest request =
            UpdateJobCountsRequest.newBuilder().addAllCountUpdates(jobCountUpdates).build();
        if (VM.areAssertsEnabled()) {
          // for testing purposes.
          jobCountsClientProvider.get().getBlockingStub().updateJobCounts(request);
        } else {
          jobCountsExecutorService.submit(
              ContextMigratingExecutorService.makeContextMigratingTask(
                  () -> jobCountsClientProvider.get().getBlockingStub().updateJobCounts(request),
                  "update-job-counts"));
        }
      }
    }

    @Override
    public void recordExtraInfo(String name, byte[] bytes) {
      // TODO DX-10977 the reflection manager should rely on its own observer to store this
      // information in a separate store
      if (job.getJobAttempt().getExtraInfoList() == null) {
        job.getJobAttempt().setExtraInfoList(new ArrayList<ExtraInfo>());
      }

      job.getJobAttempt()
          .getExtraInfoList()
          .add(new ExtraInfo().setData(ByteString.copyFrom(bytes)).setName(name));
      super.recordExtraInfo(name, bytes);
    }

    @Override
    public void planRelTransform(
        PlannerPhase phase,
        RelOptPlanner planner,
        RelNode before,
        RelNode after,
        long millisTaken,
        List<PlannerPhaseRulesStats> rulesBreakdownStats) {
      planTransformationListener.onPhaseCompletion(phase, before, after, millisTaken);
      switch (phase) {
        case LOGICAL:
          builder.addLogicalPlan(before, after);
          // set final pre-accelerated cost
          final RelOptCost cost = after.getCluster().getMetadataQuery().getCumulativeCost(after);
          builder.addCost(cost);
          break;
        case JOIN_PLANNING_MULTI_JOIN:
          // Join planning starts with multi-join analysis phase
          builder.addPreJoinPlan(before);
          break;
        case ENTITY_EXPANSION:
          // This plan is used for lineage and needs to have all UDFs expanded
          builder.addExpandedPlan(after);
          break;
        case PHYSICAL:
          builder.addPhysicalPlan(before);
          break;
        default:
          break;
      }
    }

    @Override
    public void finalPrelPlanGenerated(Prel prel) {
      joinPreAnalyzer = JoinPreAnalyzer.prepare(prel);
    }

    @Override
    public synchronized void attemptCompletion(UserResult result) {
      try {
        final QueryState queryState = result.getState();
        if (queryState == QueryState.COMPLETED) {
          if (joinPreAnalyzer != null) {
            JoinAnalyzer joinAnalyzer = new JoinAnalyzer(result.getProfile(), joinPreAnalyzer);
            JoinAnalysis joinAnalysis = joinAnalyzer.computeJoinAnalysis();
            if (joinAnalysis != null) {
              job.getJobAttempt().getInfo().setJoinAnalysis(joinAnalysis);
            }
          }
        }
        addAttemptToJob(job, queryState, result.getProfile(), result.getException());
      } catch (IOException e) {
        exception.addException(e);
      }
    }

    @Override
    public synchronized void putExecutorProfile(String nodeEndpoint) {}

    @Override
    public synchronized void removeExecutorProfile(String nodeEndpoint) {}

    @Override
    public synchronized void queryClosed() {}

    @Override
    public synchronized void execDataArrived(
        RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
      try (TimedBlock b = Timer.time("dataMetadataArrived");
          QueryDataBatch dataBatch = LocalUserUtil.acquireData(allocator, outcomeListener, result);
          RecordBatchLoader loader = new RecordBatchLoader(allocator)) {
        b.addID("attempt=" + attemptId);
        loader.load(
            dataBatch.getHeader().getDef(),
            dataBatch
                .getData()); // Query output just contains the batch unique id and number of records
        // in the batch.
        try (RecordBatchData batch = new RecordBatchData(loader, allocator)) {
          List<ValueVector> vectors = batch.getVectors();
          if (vectors.size() < 4 || !(vectors.get(3) instanceof VarBinaryVector)) {
            throw UserException.unsupportedError()
                .message("Job output contains invalid data")
                .build(logger);
          }
          VarBinaryVector metadataVector = (VarBinaryVector) vectors.get(3);
          for (int i = 0; i < batch.getRecordCount(); i++) {
            final ArrowFileFormat.ArrowFileMetadata metadata =
                ArrowFileFormat.ArrowFileMetadata.parseFrom(metadataVector.getObject(i));
            job.getJobAttempt()
                .getInfo()
                .getResultMetadataList()
                .add(ArrowFileReader.toBean(metadata));
          }
        }
      } catch (Exception ex) {
        exception.addException(ex);
      }
    }

    @Override
    public void beginState(AttemptEvent event) {
      final JobAttempt jobAttempt = job.getJobAttempt();
      synchronized (jobAttempt) {
        if (!isTerminal(event.getState())) {
          jobAttempt.setState(JobsServiceUtil.attemptStatusToJobStatus(event.getState()));
        }
        if (jobAttempt.getStateListList() == null) {
          jobAttempt.setStateListList(new ArrayList<>());
        }
        jobAttempt
            .getStateListList()
            .add(JobsServiceUtil.createAttemptEvent(event.getState(), event.getStartTime()));
      }
      storeJob(job);

      if (externalListenerManager != null) {
        externalListenerManager.queryProgressed(JobsServiceUtil.toJobSummary(job));
      }
    }

    @Override
    public void updateReflectionsWithHints(
        ReflectionExplanationsAndQueryDistance reflectionExplanationsAndQueryDistance) {
      detailsPopulator.addReflectionHints(reflectionExplanationsAndQueryDistance);
    }

    @Override
    public void planConvertedToRel(RelNode converted, long millisTaken) {
      detailsPopulator.planConvertedToRel(converted);
      this.converted = converted;
      findTopViews(converted);
    }

    @Override
    public void putProfileFailed() {
      if (!job.getJobAttempt().getIsProfileIncomplete()) {
        job.getJobAttempt().setIsProfileIncomplete(true);
        PROFILE_UPDATE_FAILURE_COUNTER.increment();
      }
    }

    @Override
    public void putProfileUpdateComplete() {
      job.getJobAttempt().setIsProfileUpdateComplete(true);
    }

    /*
    Collect the top view information
    */
    private void findTopViews(RelNode converted) {
      Map<Integer, List<ExpansionNode>> expansionsByDepth = new HashMap<>();
      ExpansionNode.collectExpansionsByDepth(converted, expansionsByDepth, new Pointer<>(0));
      topViews =
          expansionsByDepth.getOrDefault(0, ImmutableList.of()).stream()
              .map(
                  node -> {
                    return new Pair<>(node.getPath(), IcebergTableChecker.check(node));
                  })
              .collect(Collectors.toList());
    }
  }

  private boolean isTerminal(AttemptEvent.State state) {
    return (state == AttemptEvent.State.COMPLETED
        || state == AttemptEvent.State.CANCELED
        || state == AttemptEvent.State.FAILED);
  }

  private void addAttemptToJob(Job job, QueryState state, QueryProfile profile, UserException ex)
      throws IOException {
    final JobAttempt jobAttempt = job.getJobAttempt();
    final JobInfo jobInfo = jobAttempt.getInfo();
    if (profile.getStart() != 0L) {
      jobInfo.setStartTime(profile.getStart());
    }
    if (profile.getEnd() != 0L) {
      jobInfo.setFinishTime(profile.getEnd());
    }
    if (profile.getResourceSchedulingProfile() != null) {
      if (jobInfo.getResourceSchedulingInfo() == null) {
        jobInfo.setResourceSchedulingInfo(new ResourceSchedulingInfo());
      }
      if (jobInfo.getResourceSchedulingInfo().getQueueName() == null) {
        jobInfo
            .getResourceSchedulingInfo()
            .setQueueName(profile.getResourceSchedulingProfile().getQueueName());
      }
      if (jobInfo.getResourceSchedulingInfo().getQueueId() == null) {
        jobInfo
            .getResourceSchedulingInfo()
            .setQueueId(profile.getResourceSchedulingProfile().getQueueId());
      }
      if (jobInfo.getResourceSchedulingInfo().getEngineName() == null) {
        jobInfo
            .getResourceSchedulingInfo()
            .setEngineName(profile.getResourceSchedulingProfile().getEngineName());
      }
    }
    switch (state) {
      case FAILED:
        if (profile.hasError()) {
          jobInfo.setFailureInfo(profile.getError());
        } else if (ex != null) {
          jobInfo.setFailureInfo(ex.getVerboseMessage(false));
        }
        if (profile.hasVerboseError()) {
          /* If the state is FAILED, there should always be a non-null exception. However, since it is a nullable field by
          definition, we assign default error type as SYSTEM error in case we don't receive any. */
          UserBitShared.DremioPBError.ErrorType errorType =
              ex != null ? ex.getErrorType() : UserBitShared.DremioPBError.ErrorType.SYSTEM;
          jobInfo.setDetailedFailureInfo(
              JobsServiceUtil.toFailureInfo(profile.getVerboseError(), errorType));
        }
        break;
      case CANCELED:
        if (profile.hasCancelReason()) {
          final JobCancellationInfo cancellationInfo = new JobCancellationInfo();
          cancellationInfo.setMessage(profile.getCancelReason());
          cancellationInfo.setType(CancelType.valueOf(profile.getCancelType().getNumber()));
          jobInfo.setCancellationInfo(cancellationInfo);
        }
        break;
      default:
        // nothing
    }
    jobAttempt.getInfo().setOutputTableList(Arrays.asList(storageName, jobAttempt.getAttemptId()));
    updateJobDetails(job, profile);
    jobAttempt.setState(JobsServiceUtil.queryStatusToJobStatus(state));
    storeJob(job);
  }

  private void updateJobDetails(Job job, QueryProfile profile) throws IOException {
    JobAttempt jobAttempt = job.getJobAttempt();
    // continue if the profile is a full profile
    if (profile.getFragmentProfileList() != null && profile.getFragmentProfileList().size() != 0) {
      final QueryProfileToJobConverter profileParser =
          new QueryProfileToJobConverter(job.getJobId(), profile);
      jobAttempt.setDetails(profileParser.getJobDetails());
      jobAttempt.getInfo().setSpillJobDetails(profileParser.getSpillDetails());
      jobAttempt.getInfo().setExecutionCpuTimeNs(0L);
      jobAttempt.getInfo().setSetupTimeNs(0L);
      jobAttempt.getInfo().setWaitTimeNs(0L);
      jobAttempt.getInfo().setMemoryAllocated(0L);
      profile
          .getFragmentProfileList()
          .forEach(
              majorFrag -> {
                if (majorFrag.getMinorFragmentProfileList() != null) {
                  majorFrag
                      .getMinorFragmentProfileList()
                      .forEach(
                          minorFrag -> {
                            if (minorFrag.getOperatorProfileList() != null) {
                              minorFrag
                                  .getOperatorProfileList()
                                  .forEach(
                                      opProfile -> {
                                        jobAttempt
                                            .getInfo()
                                            .setExecutionCpuTimeNs(
                                                jobAttempt.getInfo().getExecutionCpuTimeNs()
                                                    + opProfile.getProcessNanos());
                                        jobAttempt
                                            .getInfo()
                                            .setSetupTimeNs(
                                                jobAttempt.getInfo().getSetupTimeNs()
                                                    + opProfile.getSetupNanos());
                                        jobAttempt
                                            .getInfo()
                                            .setWaitTimeNs(
                                                jobAttempt.getInfo().getWaitTimeNs()
                                                    + opProfile.getWaitNanos());
                                        jobAttempt
                                            .getInfo()
                                            .setMemoryAllocated(
                                                jobAttempt.getInfo().getMemoryAllocated()
                                                    + opProfile.getPeakLocalMemoryAllocated());
                                      });
                            }
                          });
                }
              });
      jobAttempt.setStats(profileParser.getJobStats());
      jobAttempt.getInfo().setResultsCacheUsed(getResultsCacheUsed(profile));
    }
    if (profile.getNodeProfileList() != null) {
      final List<ExecutionNode> executionNodes = new ArrayList<>();
      profile
          .getNodeProfileList()
          .forEach(
              nodeProfile -> {
                ExecutionNode executionNode = new ExecutionNode();
                executionNode.setFabricPort(nodeProfile.getEndpoint().getFabricPort());
                executionNode.setHostName(nodeProfile.getEndpoint().getAddress());
                executionNode.setHostIp(nodeProfile.getEndpoint().getAddress());
                executionNode.setMaxMemoryUsedKb((int) (nodeProfile.getMaxMemoryUsed() / 1000L));
                executionNodes.add(executionNode);
              });
      jobAttempt.getInfo().setNodeDetailsList(executionNodes);
    }
  }

  public QueryProfile getProfile(QueryProfileRequest queryProfileRequest)
      throws JobNotFoundException {
    JobId jobId = JobsProtoUtil.toStuff(queryProfileRequest.getJobId());
    int attempt = queryProfileRequest.getAttempt();
    GetJobRequest request =
        GetJobRequest.newBuilder()
            .setJobId(jobId)
            .setUserName(queryProfileRequest.getUserName())
            .build();
    Job job = getJob(request);
    if (job.getJobAttempt().getIsProfileIncomplete() && !SupportContext.isSupportUser()) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("Unable to fetch profile as it's incomplete."));
    }
    QueryProfile profile = getProfileFromJob(job, attempt);
    if (optionManagerProvider.get().getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE)
        && isProfileIncomplete(profile, job)) {
      if (!job.getJobAttempt().getIsProfileIncomplete()) {
        job.getJobAttempt().setIsProfileIncomplete(true);
        PROFILE_UPDATE_FAILURE_COUNTER.increment();
      }
      storeJob(job);
    }
    return profile;
  }

  private boolean isProfileIncomplete(QueryProfile profile, Job job) {
    JobAttempt attempt = job.getJobAttempt();
    boolean isProfileInTerminalState =
        JobsServiceUtil.finalProfileStates.contains(profile.getState());
    boolean isJobInTerminalState = JobsServiceUtil.finalJobStates.contains(attempt.getState());
    return !isProfileInTerminalState
        && isJobInTerminalState
        && attempt.getInfo().getFinishTime()
            < System.currentTimeMillis()
                - optionManagerProvider
                    .get()
                    .getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE_WAIT_IN_MILLISECONDS);
  }

  QueryProfile getProfileFromJob(Job job, int attempt) {
    JobId jobId = job.getJobId();
    NodeEndpoint endpoint = job.getJobAttempt().getEndpoint();
    final AttemptId attemptId = new AttemptId(JobsServiceUtil.getJobIdAsExternalId(jobId), attempt);
    // Check if the profile for given attempt already exists. Even if the job is not done, it is
    // possible that
    // profile exists for previous attempts
    try {
      return jobTelemetryServiceStub
          .getQueryProfile(
              GetQueryProfileRequest.newBuilder().setQueryId(attemptId.toQueryId()).build())
          .getProfile();
    } catch (StatusRuntimeException sre) {
      logger.debug("Unable to fetch query profile for {} on Node {}", jobId, identity, sre);
      try {
        if (!areNodeEndpointsEqual(endpoint, identity)) {
          logger.debug("Fetching query profile for {} on Node {}", jobId, endpoint);
          final QueryProfileRequest request =
              QueryProfileRequest.newBuilder()
                  .setJobId(JobsProtoUtil.toBuf(jobId))
                  .setAttempt(attempt)
                  .setUserName(SYSTEM_USERNAME)
                  .build();
          return forwarder.getProfile(JobsServiceUtil.toPB(endpoint), request);
        }
      } catch (StatusRuntimeException e) {
        logger.debug("Unable to fetch profile for {} on Node {}", jobId, endpoint, e);
        if (!e.getStatus().getCode().equals(Status.UNAVAILABLE.getCode())) {
          errorHandler(e, jobId);
        }
      }
      errorHandler(sre, jobId);
      // Will always be unreachable.
      throw sre;
    }
  }

  protected void errorHandler(StatusRuntimeException caughtException, JobId jobId)
      throws StatusRuntimeException {
    if (caughtException.getStatus().getCode().equals(Status.INVALID_ARGUMENT.getCode())) {
      logger.debug("Unable to get profile for {}", jobId, caughtException);
      throw caughtException;
    } else {
      logger.debug(
          "Unable to fetch profile for {} at the moment, try after sometime.",
          jobId,
          caughtException);
      throw new StatusRuntimeException(
          Status.INTERNAL
              .withDescription("Unable to fetch profile at the moment, try after sometime.")
              .withCause(caughtException));
    }
  }

  /**
   * Get the QueryProfile for the last attempt of the given jobId.
   *
   * @param jobId job id
   * @param username username to run under
   * @return optional QueryProfile
   */
  @Override
  public Optional<QueryProfile> getProfile(String jobId, String username) {
    JobId jobIdObj = new JobId(jobId);
    GetJobRequest request =
        GetJobRequest.newBuilder().setJobId(jobIdObj).setUserName(username).build();
    try {
      Job job = getJob(request);
      if (job.getJobAttempt().getIsProfileIncomplete()) {
        return java.util.Optional.empty();
      }
      UserBitShared.QueryProfile profile = getProfileFromJob(job, job.getAttempts().size() - 1);
      return java.util.Optional.of(profile);
    } catch (JobNotFoundException ex) {
      return java.util.Optional.empty();
    }
  }

  void cancel(CancelJobRequest request) throws JobException {
    final String reason = request.getReason();
    final JobId jobId = JobsProtoUtil.toStuff(request.getJobId());

    final ForemenTool tool = this.foremenTool.get();
    final ExternalId id =
        ExternalIdHelper.toExternal(QueryIdHelper.getQueryIdFromString(jobId.getId()));
    if (tool.cancel(id, reason)) {
      logger.debug("Job cancellation requested on current node.");
      return;
    }
    // now remote...
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(jobId)
            .setUserName(SYSTEM_USERNAME) // TODO (DX-17909): Add and use username in request
            .build();
    final Job job = getJob(getJobRequest);
    NodeEndpoint endpoint = job.getJobAttempt().getEndpoint();
    remoteCancel(jobId, id, endpoint, reason);
  }

  void remoteCancel(JobId jobId, ExternalId externalId, NodeEndpoint endpoint, String reason)
      throws JobException {
    if (areNodeEndpointsEqual(endpoint, identity)) {
      throw new JobNotFoundException(jobId, JobNotFoundException.CauseOfFailure.CANCEL_FAILED);
    }
    try {
      final CoordTunnel tunnel = coordTunnelCreator.get().getTunnel(JobsServiceUtil.toPB(endpoint));
      Ack ack =
          DremioFutures.getChecked(
              tunnel.requestCancelQuery(externalId, reason),
              RpcException.class,
              15,
              TimeUnit.SECONDS,
              RpcException::mapException);
      if (ack.getOk()) {
        logger.debug("Job cancellation requested on {}.", endpoint.getAddress());
      } else {
        throw new JobNotFoundException(jobId, JobNotFoundException.CauseOfFailure.CANCEL_FAILED);
      }
    } catch (TimeoutException | RpcException | RuntimeException e) {
      logger.info(
          "Unable to cancel remote job for external id: {}",
          ExternalIdHelper.toString(externalId),
          e);
      throw new JobWarningException(
          jobId, String.format("Unable to cancel job on node %s.", endpoint.getAddress()));
    }
  }

  /**
   * For the master coordinator, periodically iterate over all jobs, only marking jobs as FAILED if
   * their issuing coordinator is no longer present.
   */
  class AbandonLocalJobsTask implements Runnable {
    @Override
    public void run() {
      manage();
    }

    public void manage() {
      final Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        setAbandonedJobsToFailedState(
            jobTelemetryClientProvider,
            store,
            jobServiceInstances.get(),
            jobResultLogger,
            forwarder,
            identity,
            getJobsTTLExpiryAtInMillis());
        ABANDON_LOCAL_JOB_COUNTER.succeeded();
      } catch (Throwable e) {
        ABANDON_LOCAL_JOB_COUNTER.errored();
        logger.warn("Exception running abandoned jobs task ", e);
      } finally {
        final long elapsedTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        ABANDON_LOCAL_JOB_LOGIN_TIMER.recordAmount(elapsedTime);
      }
    }
  }

  /**
   * Get a condition that returns jobs that have been completed before the cutoff time
   *
   * @param cutOffTime The epoch millis cutoff time.
   * @return the condition for kv store use.
   */
  public static LegacyFindByCondition getOldJobsCondition(long prevCutOffTime, long cutOffTime) {
    SearchQuery searchQuery = getOldJobsConditionHelper(prevCutOffTime, cutOffTime);
    return new LegacyFindByCondition().setCondition(searchQuery);
  }

  public static SearchQuery getOldJobsConditionHelper(long prevCutOffTime, long cutOffTime) {
    return SearchQueryUtils.or(
        SearchQueryUtils.and(
            SearchQueryUtils.newExistsQuery(JobIndexKeys.END_TIME.getIndexFieldName()),
            SearchQueryUtils.newRangeLong(
                JobIndexKeys.END_TIME.getIndexFieldName(), prevCutOffTime, cutOffTime, true, true)),
        SearchQueryUtils.and(
            SearchQueryUtils.newDoesNotExistQuery(JobIndexKeys.END_TIME.getIndexFieldName()),
            SearchQueryUtils.or(
                SearchQueryUtils.newTermQuery(
                    JobIndexKeys.JOB_STATE.getIndexFieldName(), JobState.FAILED.toString()),
                SearchQueryUtils.newTermQuery(
                    JobIndexKeys.JOB_STATE.getIndexFieldName(), JobState.CANCELED.toString()),
                SearchQueryUtils.newTermQuery(
                    JobIndexKeys.JOB_STATE.getIndexFieldName(), JobState.COMPLETED.toString())),
            SearchQueryUtils.newRangeLong(
                JobIndexKeys.START_TIME.getIndexFieldName(),
                prevCutOffTime,
                cutOffTime,
                true,
                true)));
  }

  Provider<OptionManager> getOptionManagerProvider() {
    return optionManagerProvider;
  }

  Provider<ForemenTool> getForemenTool() {
    return foremenTool;
  }

  void registerReflectionJobListener(
      ReflectionJobEventsRequest jobEventsRequest, StreamObserver<JobEvent> observer) {
    Job job;
    JobId jobId = JobsProtoUtil.toStuff(jobEventsRequest.getJobId());
    try {
      final GetJobRequest request =
          GetJobRequest.newBuilder()
              .setJobId(jobId)
              .setUserName(jobEventsRequest.getUserName())
              .build();
      job = getJob(request);
      validateReflectionIdWithJob(job, jobEventsRequest.getReflectionId());
      registerListenerWithJob(job, observer);
    } catch (JobNotFoundException e) {
      throw UserException.validationError(e)
          .message("Status requested for unknown job %s.", jobId.getId())
          .buildSilently();
    } catch (ReflectionJobValidationException e) {
      throw UserException.validationError(e)
          .message(
              "Status requested for job %s that didn't materialize the reflection %s.",
              e.getJobId(), e.getReflectionId())
          .buildSilently();
    }
  }

  Iterable<JobSummary> searchReflectionJobs(SearchReflectionJobsRequest request) {
    String reflectionSearchQuery = getReflectionSearchQuery(request.getReflectionId());
    SearchJobsRequest searchJobsRequest =
        SearchJobsRequest.newBuilder()
            .setOffset(request.getOffset())
            .setLimit(request.getLimit())
            .setSortColumn("st")
            .setSortOrder(SearchJobsRequest.SortOrder.DESCENDING)
            .setFilterString(reflectionSearchQuery)
            .build();
    return searchJobs(searchJobsRequest);
  }

  com.dremio.service.job.JobSummary getReflectionJobSummary(ReflectionJobSummaryRequest request)
      throws JobNotFoundException, ReflectionJobValidationException {
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(JobsProtoUtil.toStuff(request.getJobSummaryRequest().getJobId()))
            .setUserName(request.getJobSummaryRequest().getUserName())
            .setFromStore(request.getJobSummaryRequest().getFromStore())
            .build();
    final Job job = getJob(getJobRequest);
    validateReflectionIdWithJob(job, request.getReflectionId());
    return JobsServiceUtil.toJobSummary(job);
  }

  com.dremio.service.job.JobDetails getReflectionJobDetails(ReflectionJobDetailsRequest request)
      throws JobNotFoundException, ReflectionJobValidationException {
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(JobsProtoUtil.toStuff(request.getJobDetailsRequest().getJobId()))
            .setUserName(request.getJobDetailsRequest().getUserName())
            .setFromStore(request.getJobDetailsRequest().getFromStore())
            .build();
    final Job job = getJob(getJobRequest);
    validateReflectionIdWithJob(job, request.getReflectionId());
    return JobsServiceUtil.toJobDetails(job, request.getJobDetailsRequest().getProvideResultInfo());
  }

  void cancelReflectionJob(CancelReflectionJobRequest request) throws JobException {
    JobId jobId = JobsProtoUtil.toStuff(request.getCancelJobRequest().getJobId());
    final GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(jobId)
            .setUserName(request.getCancelJobRequest().getUsername())
            .build();
    final Job job = getJob(getJobRequest);
    validateReflectionIdWithJob(job, request.getReflectionId());
    final String reason = request.getCancelJobRequest().getReason();
    final ForemenTool tool = getForemenTool().get();
    final UserBitShared.ExternalId externalId =
        ExternalIdHelper.toExternal(QueryIdHelper.getQueryIdFromString(jobId.getId()));
    if (tool.cancel(externalId, reason)) {
      return;
    }
    NodeEndpoint endpoint = job.getJobAttempt().getEndpoint();
    remoteCancel(jobId, externalId, endpoint, reason);
  }

  UserBitShared.QueryProfile getReflectionJobProfile(ReflectionJobProfileRequest request)
      throws JobNotFoundException, ReflectionJobValidationException {
    GetJobRequest getJobRequest =
        GetJobRequest.newBuilder()
            .setJobId(JobsProtoUtil.toStuff(request.getQueryProfileRequest().getJobId()))
            .setUserName(request.getQueryProfileRequest().getUserName())
            .build();
    Job job = getJob(getJobRequest);
    validateReflectionIdWithJob(job, request.getReflectionId());
    if (job.getJobAttempt().getIsProfileIncomplete() && !SupportContext.isSupportUser()) {
      throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("Unable to fetch profile as it's incomplete."));
    }

    return getProfileFromJob(job, request.getQueryProfileRequest().getAttempt());
  }

  public static String getReflectionSearchQuery(String reflectionId) {
    StringBuilder stringBuilder =
        new StringBuilder()
            .append("(qt==\"ACCELERATION\");(")
            .append("sql==\"*")
            .append(reflectionId)
            .append("*\")");
    return stringBuilder.toString();
  }

  void validateReflectionIdWithJob(Job job, String reflectionId)
      throws JobNotFoundException, ReflectionJobValidationException {
    if ((job.getJobAttempt().getInfo() == null
        || job.getJobAttempt().getInfo().getMaterializationFor() == null
        || job.getJobAttempt().getInfo().getMaterializationFor().getReflectionId() == null
        || !job.getJobAttempt()
            .getInfo()
            .getMaterializationFor()
            .getReflectionId()
            .equals(reflectionId))) {
      throw new ReflectionJobValidationException(job.getJobId(), reflectionId);
    }
  }

  private boolean mustForwardRequest(final Job job) {
    if (job.getJobAttempt().getEndpoint() == null) { // for UTs
      return false;
    }

    final OptionManager optionManager = optionManagerProvider.get();
    final boolean enabled = optionManager.getOption(ExecConstants.ENABLE_REMOTE_JOB_FETCH);
    // Do not directly use NodeEndpoint for comparison as protobuf requires messages to match
    // exactly including if fields have been set or not
    // As NodeEndpoint does have several fields with default value and the conversion from
    // protostuff to protobuf does not guarantee that field status (set/unset) is actually accurate
    // simply use the address and the port for comparison.
    final NodeEndpoint source = job.getJobAttempt().getEndpoint();
    return enabled
        && !job.isCompleted()
        && !areNodeEndpointsEqual(source, identity)
        && jobServiceInstances.get().stream()
            .anyMatch(
                instance ->
                    Objects.equal(instance.getAddress(), source.getAddress())
                        && Objects.equal(instance.getConduitPort(), source.getConduitPort())
                        && Objects.equal(instance.getStartTime(), source.getStartTime()));
  }

  class LocalAbandonedJobsHandler implements AutoCloseable {
    private ScheduledFuture abandonedJobsTask;
    private final CloseableSchedulerThreadPool threadPool;

    public LocalAbandonedJobsHandler() {
      this.threadPool = new CloseableSchedulerThreadPool("local-abandoned-jobs-handler", 1);
    }

    void schedule(long scheduleInterval) {
      abandonedJobsTask =
          threadPool.scheduleAtFixedRate(
              () -> terminateLocalAbandonedJobs(getJobsTTLExpiryAtInMillis()),
              0,
              scheduleInterval,
              TimeUnit.MILLISECONDS);
      logger.info("Scheduled abandonedJobsTask for interval {}", scheduleInterval);
    }

    @Override
    public void close() throws Exception {
      cancel();
      AutoCloseables.close(threadPool);
    }

    private void cancel() {
      if (abandonedJobsTask != null) {
        abandonedJobsTask.cancel(true);
        abandonedJobsTask = null;
      }
    }

    @VisibleForTesting
    public void reschedule(long scheduleInterval) {
      cancel();
      schedule(scheduleInterval);
    }

    private JobAttempt getJobAttemptIfNotFinalState(JobResult jobResult) {
      final List<JobAttempt> attempts = jobResult.getAttemptsList();
      final int numAttempts = attempts.size();
      if (numAttempts > 0) {
        final JobAttempt lastAttempt = attempts.get(numAttempts - 1);
        if (JobsServiceUtil.isNonFinalState(lastAttempt.getState())) {
          return lastAttempt;
        }
      }
      return null;
    }

    @WithSpan("terminate-abandoned-jobs-due-to-transient-failures")
    private void terminateLocalAbandonedJobs(long ttlExpireAtInMillis) {
      try {
        final Set<Entry<JobId, JobResult>> apparentlyAbandoned =
            StreamSupport.stream(
                    store
                        .find(
                            new LegacyFindByCondition()
                                .setCondition(JobsServiceUtil.getApparentlyAbandonedQuery())
                                .setPageSize(20))
                        .spliterator(),
                    false)
                .collect(Collectors.toSet());
        for (final Entry<JobId, JobResult> entry : apparentlyAbandoned) {
          JobResult jobResult = entry.getValue();
          JobAttempt lastAttempt = getJobAttemptIfNotFinalState(jobResult);
          if (lastAttempt != null) {
            NodeEndpoint source = lastAttempt.getEndpoint();
            boolean isLocalJob = areNodeEndpointsEqual(source, identity);
            boolean isJobInProgress = true;
            if (isLocalJob) {
              isJobInProgress = runningJobs.get(lastAttempt.getInfo().getJobId()) != null;
            }
            if (!isJobInProgress) {
              // Before updating the job to FAILED state check if the job status in store is not
              // final state.
              // This is required because between the time apparentlyAbandoned jobs are retrieved
              // and the time the job
              // is verified to be not running in runningJobs, the job status might have got
              // changed.
              jobResult = store.get(lastAttempt.getInfo().getJobId());
              if (jobResult != null) {
                lastAttempt = getJobAttemptIfNotFinalState(jobResult);
                if (lastAttempt != null) {
                  logger.info("Failing abandoned job {}", lastAttempt.getInfo().getJobId().getId());
                  final long finishTimestamp = System.currentTimeMillis();
                  List<com.dremio.exec.proto.beans.AttemptEvent> attemptEventList =
                      lastAttempt.getStateListList();
                  if (attemptEventList == null) {
                    attemptEventList = new ArrayList<>();
                  }
                  attemptEventList.add(
                      JobsServiceUtil.createAttemptEvent(
                          AttemptEvent.State.FAILED, finishTimestamp));
                  JobInfo jobInfo = lastAttempt.getInfo();
                  jobInfo
                      .setFinishTime(finishTimestamp)
                      .setFailureInfo(
                          "Query failed due to kvstore or network errors. Details and profile information for this job may be partial or missing.");
                  if (jobInfo.getTtlExpireAt() == null) {
                    jobInfo.setTtlExpireAt(ttlExpireAtInMillis);
                  }
                  final JobAttempt newLastAttempt =
                      lastAttempt
                          .setState(JobState.FAILED)
                          .setStateListList(attemptEventList)
                          .setInfo(jobInfo);
                  final List<JobAttempt> attempts = jobResult.getAttemptsList();
                  final int numAttempts = attempts.size();
                  attempts.remove(numAttempts - 1);
                  attempts.add(newLastAttempt);
                  jobResult.setCompleted(true); // mark the job as completed
                  jobResult.setProfileDetailsCapturedPostTermination(
                      true); // mark ProfileDetailsCapturedPostTermination as true
                  // Don't fetch partial or missing profile for abandoned job
                  store.put(entry.getKey(), jobResult);
                  Job job = new Job(entry.getKey(), jobResult);
                  jobResultLogger.info(
                      job,
                      "Query: {}; outcome: {}",
                      job.getJobId().getId(),
                      job.getJobAttempt().getState());
                }
              }
            }
          }
        }
      } catch (Exception e) {
        logger.error(
            "Error while setting FAILED state for any abandoned jobs that may be present. Will attempt in next invocation",
            e);
      }
    }
  }

  private boolean areNodeEndpointsEqual(NodeEndpoint source, NodeEndpoint target) {
    return Objects.equal(source.getAddress(), target.getAddress())
        && Objects.equal(source.getConduitPort(), target.getConduitPort());
  }

  @VisibleForTesting
  public LocalAbandonedJobsHandler getLocalAbandonedJobsHandler() {
    return localAbandonedJobsHandler;
  }

  private class JobSubmissionHelper {
    private final SubmitJobRequest jobRequest;
    private final StreamObserver<JobEvent> eventObserver;
    private final PlanTransformationListener planTransformationListener;

    JobSubmissionHelper(
        SubmitJobRequest jobRequest,
        StreamObserver<JobEvent> eventObserver,
        PlanTransformationListener planTransformationListener) {
      this.jobRequest = jobRequest;
      this.eventObserver = eventObserver;
      this.planTransformationListener = planTransformationListener;
    }

    JobSubmission submitJobToCommandPool() {
      return submitJob(this.jobRequest, this.eventObserver, this.planTransformationListener);
    }

    Closeable releaseAndReacquireCommandPool(Priority priority) {
      // return a no-op closeable
      return () -> {};
    }
  }

  private class SubmitJobToReleasableCommandPool extends JobSubmissionHelper {
    private final ReleasableCommandPool releasableCommandPool;

    SubmitJobToReleasableCommandPool(
        SubmitJobRequest jobRequest,
        StreamObserver<JobEvent> eventObserver,
        PlanTransformationListener planTransformationListener,
        ReleasableCommandPool releasableCommandPool) {
      super(jobRequest, eventObserver, planTransformationListener);
      this.releasableCommandPool = releasableCommandPool;
    }

    @Override
    Closeable releaseAndReacquireCommandPool(Priority priority) {
      return releasableCommandPool.releaseAndReacquireSlot(priority);
    }
  }

  JobAndUserStats getJobAndUserStats(JobAndUserStatsRequest jobAndUserStatsRequest)
      throws Exception {
    final LocalDate today = LocalDate.now();
    List<JobAndUserStat> stats = new ArrayList<>();
    for (LocalDate date = today.minusDays(jobAndUserStatsRequest.getNumDaysBack() - 1);
        !date.isAfter(today);
        date = date.plusDays(1)) {
      stats.add(jobAndUserStatsCache.get(date));
    }
    if (jobAndUserStatsRequest.getDetailedStats()) {
      stats.addAll(getWeeklyUserStats());
      stats.addAll(getMonthlyUserStats());
    }
    JobAndUserStats.Builder jobAndUserStatsResponse = JobAndUserStats.newBuilder();
    jobAndUserStatsResponse.addAllStats(stats);
    return jobAndUserStatsResponse.build();
  }

  JobAndUserStat getDailyJobAndUserStatsForADay(LocalDate day) {
    final SearchJobsRequest searchJobsRequest =
        SearchJobsRequest.newBuilder()
            .setFilterString(
                String.format(
                    FILTER,
                    getEpochMillisFromLocalDate(day),
                    getEpochMillisFromLocalDate(day.plusDays(1))))
            .build();
    Map<QueryType, Long> jobCountByQueryType = new HashMap<>();
    Map<QueryType, Set<String>> uniqueUsersByQueryType = new HashMap<>();
    long totalJobs = 0;
    Set<String> totalUniqueUsers = new HashSet<>();
    for (Entry<JobId, JobResult> entry : store.find(createCondition(searchJobsRequest))) {
      totalJobs++;
      String user = entry.getValue().getAttemptsList().get(0).getInfo().getUser();
      totalUniqueUsers.add(user);
      QueryType queryType = entry.getValue().getAttemptsList().get(0).getInfo().getQueryType();
      jobCountByQueryType.putIfAbsent(queryType, 0L);
      jobCountByQueryType.put(queryType, jobCountByQueryType.get(queryType) + 1);
      uniqueUsersByQueryType.putIfAbsent(queryType, new HashSet<>());
      uniqueUsersByQueryType.get(queryType).add(user);
    }
    List<JobCountByQueryType> jobCounts =
        jobCountByQueryType.entrySet().stream()
            .map(
                (entry) ->
                    JobCountByQueryType.newBuilder()
                        .setQueryType(
                            com.dremio.service.job.QueryType.valueOf(entry.getKey().toString()))
                        .setJobCount(entry.getValue())
                        .build())
            .collect(Collectors.toList());
    List<UniqueUsersCountByQueryType> uniqueUsers =
        uniqueUsersByQueryType.entrySet().stream()
            .map(
                (entry) ->
                    UniqueUsersCountByQueryType.newBuilder()
                        .setQueryType(
                            com.dremio.service.job.QueryType.valueOf(entry.getKey().toString()))
                        .addAllUniqueUsers(entry.getValue())
                        .build())
            .collect(Collectors.toList());
    return JobAndUserStat.newBuilder()
        .setDate(day.toString())
        .setTotalJobs(totalJobs)
        .setTotalUniqueUsers(totalUniqueUsers.size())
        .addAllJobCountByQueryType(jobCounts)
        .addAllUniqueUsersCountByQueryType(uniqueUsers)
        .build();
  }

  List<JobAndUserStat> getDailyJobAndUserStats(int days) throws Exception {
    final LocalDate today = LocalDate.now();
    final String dailyQuerySubmittedTimeGreaterThan =
        "SELECT\n"
            + "TO_CHAR(submitted_ts, 'YYYY-MM-DD') AS \"date\",\n"
            + "query_type,\n"
            + "LISTAGG(DISTINCT user_name, ';') AS \"Unique Users\",\n"
            + "COUNT(*) AS \"Jobs Executed\"\n"
            + "FROM sys.jobs_recent\n"
            + "WHERE submitted_epoch_millis > %s\n"
            + "GROUP BY ROLLUP(\"date\", query_type)\n"
            + "ORDER BY \"date\" DESC";
    final String finalQuery =
        String.format(
            dailyQuerySubmittedTimeGreaterThan,
            getEpochMillisFromLocalDate(today.minusDays(days - 1)));
    List<RecordBatchHolder> results =
        runQueryAsJobForResults(
            finalQuery,
            SYSTEM_USERNAME,
            QueryType.UI_INTERNAL_RUN.toString(),
            QueryLabel.NONE.toString(),
            0,
            10000);
    try {
      return JobsServiceUtil.buildDailyJobAndUserStats(days, today, results);
    } finally {
      AutoCloseables.close(results);
    }
  }

  List<JobAndUserStat> getWeeklyUserStats() throws Exception {
    List<JobAndUserStat> weeklyStats = new ArrayList<>();
    LocalDate today = LocalDate.now();
    LocalDate endWeekDate = today.minusDays(today.getDayOfWeek().getValue() % 7);
    LocalDate startWeekDate = endWeekDate.minusWeeks(1);
    for (LocalDate weekDate = startWeekDate;
        !weekDate.isAfter(endWeekDate);
        weekDate = weekDate.plusWeeks(1)) {
      Set<String> totalUniqueUsers = new HashSet<>();
      Map<com.dremio.service.job.QueryType, Set<String>> uniqueUsersByQueryType = new HashMap<>();
      for (LocalDate date = weekDate;
          date.isBefore(weekDate.plusWeeks(1));
          date = date.plusDays(1)) {
        JobAndUserStat dailyStat = jobAndUserStatsCache.get(date);
        if (dailyStat == null) {
          continue;
        }
        for (UniqueUsersCountByQueryType uniqueUsersCountByQueryType :
            dailyStat.getUniqueUsersCountByQueryTypeList()) {
          totalUniqueUsers.addAll(uniqueUsersCountByQueryType.getUniqueUsersList());
          uniqueUsersByQueryType.putIfAbsent(
              uniqueUsersCountByQueryType.getQueryType(), new HashSet<>());
          uniqueUsersByQueryType
              .get(uniqueUsersCountByQueryType.getQueryType())
              .addAll(uniqueUsersCountByQueryType.getUniqueUsersList());
        }
      }
      if (!totalUniqueUsers.isEmpty()) {
        List<UniqueUsersCountByQueryType> uniqueUsers =
            uniqueUsersByQueryType.entrySet().stream()
                .map(
                    (entry) ->
                        UniqueUsersCountByQueryType.newBuilder()
                            .setQueryType(
                                com.dremio.service.job.QueryType.valueOf(entry.getKey().toString()))
                            .addAllUniqueUsers(entry.getValue())
                            .build())
                .collect(Collectors.toList());
        JobAndUserStat.Builder weeklyStat =
            JobAndUserStat.newBuilder()
                .setIsWeeklyStat(true)
                .setDate(weekDate.toString())
                .setTotalUniqueUsers(totalUniqueUsers.size())
                .addAllUniqueUsersCountByQueryType(uniqueUsers);
        weeklyStats.add(weeklyStat.build());
      }
    }
    return weeklyStats;
  }

  List<JobAndUserStat> getMonthlyUserStats() throws Exception {
    List<JobAndUserStat> monthlyStats = new ArrayList<>();
    LocalDate today = LocalDate.now();
    LocalDate endDate = today.minusDays(30);
    LocalDate endMonthDate = today.minusDays(today.getDayOfMonth() - 1);
    LocalDate startMonthDate = endDate.minusDays(endDate.getDayOfMonth() - 1);
    for (LocalDate monthDate = startMonthDate;
        !monthDate.isAfter(endMonthDate);
        monthDate = monthDate.plusMonths(1)) {
      Set<String> totalUniqueUsers = new HashSet<>();
      Map<com.dremio.service.job.QueryType, Set<String>> uniqueUsersByQueryType = new HashMap<>();
      for (LocalDate date = monthDate;
          date.isBefore(monthDate.plusMonths(1));
          date = date.plusDays(1)) {
        JobAndUserStat dailyStat = jobAndUserStatsCache.get(date);
        if (dailyStat == null) {
          continue;
        }
        for (UniqueUsersCountByQueryType uniqueUsersCountByQueryType :
            dailyStat.getUniqueUsersCountByQueryTypeList()) {
          totalUniqueUsers.addAll(uniqueUsersCountByQueryType.getUniqueUsersList());
          uniqueUsersByQueryType.putIfAbsent(
              uniqueUsersCountByQueryType.getQueryType(), new HashSet<>());
          uniqueUsersByQueryType
              .get(uniqueUsersCountByQueryType.getQueryType())
              .addAll(uniqueUsersCountByQueryType.getUniqueUsersList());
        }
      }
      if (!totalUniqueUsers.isEmpty()) {
        List<UniqueUsersCountByQueryType> uniqueUsers =
            uniqueUsersByQueryType.entrySet().stream()
                .map(
                    (entry) ->
                        UniqueUsersCountByQueryType.newBuilder()
                            .setQueryType(
                                com.dremio.service.job.QueryType.valueOf(entry.getKey().toString()))
                            .addAllUniqueUsers(entry.getValue())
                            .build())
                .collect(Collectors.toList());
        JobAndUserStat.Builder monthlyStat =
            JobAndUserStat.newBuilder()
                .setIsMonthlyStat(true)
                .setDate(monthDate.toString())
                .setTotalUniqueUsers(totalUniqueUsers.size())
                .addAllUniqueUsersCountByQueryType(uniqueUsers);
        monthlyStats.add(monthlyStat.build());
      }
    }
    return monthlyStats;
  }

  ResultsCacheUsed getResultsCacheUsed(QueryProfile profile) {
    if (profile.hasResultsCacheProfile()) {
      ResultsCacheUsed resultsCacheUsed = new ResultsCacheUsed();
      resultsCacheUsed.setCacheAge(profile.getResultsCacheProfile().getCacheAge());
      resultsCacheUsed.setCacheFileSize(profile.getResultsCacheProfile().getCacheFileSize());
      return resultsCacheUsed;
    }
    return null;
  }

  /** Cache for Job and user stats. */
  public class JobAndUserStatsCache {
    private final Cache<LocalDate, JobAndUserStat> statsCache;
    private final SortedSet<LocalDate> keys;
    private static final int MAX_DAYS = 30;

    JobAndUserStatsCache() {
      this.statsCache = Caffeine.newBuilder().build();
      this.keys = new TreeSet<>();
    }

    public JobAndUserStat get(LocalDate date) throws Exception {
      refreshCacheIfRequired(false);
      return statsCache.getIfPresent(date);
    }

    public void refreshCacheIfRequired() throws Exception {
      refreshCacheIfRequired(true);
    }

    private synchronized void refreshCacheIfRequired(boolean isPeriodicRefresh) throws Exception {
      if (keys.size() != MAX_DAYS) {
        // Build the cache
        buildCache();
      } else if (keys.last().equals(LocalDate.now())) {
        // Cache contains all the data
        if (isPeriodicRefresh) {
          // Refresh stats only for today, if called from jobAndUserStatsCacheTask
          statsCache.put(keys.last(), getDailyJobAndUserStatsForADay(keys.last()));
        }
      } else if (keys.last().equals(LocalDate.now().minusDays(1))) {
        // Cache needs to be updated with today's stats
        // For the last 31st day, evict its cache entry & remove its key
        Preconditions.checkArgument(keys.first().equals(LocalDate.now().minusDays(MAX_DAYS)));
        statsCache.invalidate(keys.first());
        keys.remove(keys.first());
        // Finalize stats for yesterday
        statsCache.put(keys.last(), getDailyJobAndUserStatsForADay(keys.last()));
        // Add today to the key set
        keys.add(LocalDate.now());
        // Add today's stats to the cache
        statsCache.put(keys.last(), getDailyJobAndUserStatsForADay(keys.last()));
      } else {
        // Cache is stale since a long time (can happen when the cache is not accessed since a long
        // time)
        // Invalidate the key set and the cache
        statsCache.invalidateAll();
        keys.clear();
        // Build the cache
        buildCache();
      }
    }

    private void buildCache() throws Exception {
      try {
        List<JobAndUserStat> data = getDailyJobAndUserStats(MAX_DAYS);
        LocalDate today = LocalDate.now();
        keys.clear();
        for (int i = 0; i < MAX_DAYS; i++) {
          keys.add(today.minusDays(i));
          statsCache.put(keys.first(), data.get(i));
        }
      } catch (Exception ex) {
        keys.clear();
        statsCache.invalidateAll();
        logger.error("Could not build the cache", ex);
        throw ex;
      }
    }
  }
}
