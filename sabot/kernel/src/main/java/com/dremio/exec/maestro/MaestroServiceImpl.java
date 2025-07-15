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
package com.dremio.exec.maestro;

import static com.dremio.telemetry.api.metrics.MeterProviders.newGauge;
import static com.dremio.telemetry.api.metrics.TimerUtils.timedOperation;

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.CloseableSchedulerThreadPool;
import com.dremio.common.concurrent.ExtendedLatch;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.PhysicalPlan;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.proto.CoordExecRPC;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryCompletion;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryFirstError;
import com.dremio.exec.proto.CoordExecRPC.NodeQueryScreenCompletion;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.rpc.RpcException;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.work.foreman.CompletionListener;
import com.dremio.options.OptionManager;
import com.dremio.resource.GroupResourceInformation;
import com.dremio.resource.ResourceAllocator;
import com.dremio.resource.ResourceSchedulingProperties;
import com.dremio.resource.exception.ResourceAllocationException;
import com.dremio.sabot.rpc.ExecToCoordStatusHandler;
import com.dremio.service.commandpool.CommandPool;
import com.dremio.service.coordinator.ExecutorSetService;
import com.dremio.service.execselector.ExecutorSelectionService;
import com.dremio.service.executor.ExecutorServiceClientFactory;
import com.dremio.service.jobtelemetry.JobTelemetryClient;
import com.dremio.service.jobtelemetry.JobTelemetryServiceGrpc;
import com.dremio.service.jobtelemetry.PutExecutorProfileRequest;
import com.dremio.service.jobtelemetry.instrumentation.MetricLabel;
import com.dremio.telemetry.api.metrics.SimpleTimer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.protobuf.Empty;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Default implementation of MaestroService. */
@Singleton
public class MaestroServiceImpl implements MaestroService {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(MaestroServiceImpl.class);
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(MaestroServiceImpl.class);

  @VisibleForTesting
  public static final String INJECTOR_EXECUTE_QUERY_BEGIN_ERROR = "executeQueryBeginError";

  @VisibleForTesting
  public static final String INJECTOR_EXECUTE_QUERY_END_ERROR = "executeQueryEndError";

  @VisibleForTesting
  public static final String INJECTOR_COMMAND_POOL_SUBMIT_ERROR = "commandPoolSubmitError";

  @VisibleForTesting
  public static final String INJECTOR_FINAL_EXECUTOR_PROFILE_ERROR =
      "finalExecutorProfileUpdateError";

  public static final String UPDATE_FINAL_EXECUTOR_PROFILE_TIME_LABEL =
      "update_final_executor_profile_time";
  private static final SimpleTimer UPDATE_FINAL_EXECUTOR_PROFILE_TIMER =
      SimpleTimer.of(UPDATE_FINAL_EXECUTOR_PROFILE_TIME_LABEL);

  private final Provider<ExecutorSetService> executorSetService;
  private final Provider<SabotContext> sabotContext;
  private final Provider<CommandPool> commandPool;
  private final Provider<ResourceAllocator> resourceAllocator;
  private final Provider<ExecutorSelectionService> executorSelectionService;
  private final Provider<JobTelemetryClient> jobTelemetryClient;
  private final Provider<OptionManager> optionManagerProvider;
  // single map of currently running queries
  private final ConcurrentMap<QueryId, QueryTracker> activeQueryMap = Maps.newConcurrentMap();
  private final CloseableSchedulerThreadPool closeableSchedulerThreadPool;

  private final Provider<MaestroForwarder> forwarder;

  private PhysicalPlanReader reader;
  private ExecToCoordStatusHandler execToCoordStatusHandlerImpl;
  private final Provider<ExecutorServiceClientFactory> executorServiceClientFactory;
  private ExtendedLatch exitLatch =
      null; // This is used to wait to exit when things are still running
  private final Context grpcContext;

  @Inject
  public MaestroServiceImpl(
      final Provider<ExecutorSetService> executorSetService,
      final Provider<SabotContext> sabotContext,
      final Provider<ResourceAllocator> resourceAllocator,
      final Provider<CommandPool> commandPool,
      final Provider<ExecutorSelectionService> executorSelectionService,
      final Provider<ExecutorServiceClientFactory> executorServiceClientFactory,
      final Provider<JobTelemetryClient> jobTelemetryClient,
      final Provider<MaestroForwarder> forwarder,
      Provider<OptionManager> optionManagerProvider) {

    this.executorSetService = executorSetService;
    this.sabotContext = sabotContext;
    this.commandPool = commandPool;
    this.executorSelectionService = executorSelectionService;
    this.resourceAllocator = resourceAllocator;
    this.executorServiceClientFactory = executorServiceClientFactory;
    this.jobTelemetryClient = jobTelemetryClient;
    this.forwarder = forwarder;
    this.optionManagerProvider = optionManagerProvider;

    this.closeableSchedulerThreadPool =
        new CloseableSchedulerThreadPool(
            "cancel-fragment-retry-", Runtime.getRuntime().availableProcessors() * 2);
    // separate out grpc context for jts
    this.grpcContext = Context.current().fork();
  }

  @Override
  public void start() throws Exception {
    newGauge("maestro.active", activeQueryMap::size);

    execToCoordStatusHandlerImpl = new ExecToCoordStatusHandlerImpl(jobTelemetryClient);
    reader = sabotContext.get().getPlanReader();
  }

  @Override
  public void executeQuery(
      QueryId queryId,
      QueryContext context,
      PhysicalPlan physicalPlan,
      boolean runInSameThread,
      MaestroObserver observer,
      CompletionListener listener)
      throws ExecutionSetupException, ResourceAllocationException {

    injector.injectChecked(
        context.getExecutionControls(),
        INJECTOR_EXECUTE_QUERY_BEGIN_ERROR,
        ExecutionSetupException.class);

    // Set up the active query.
    QueryTracker queryTracker =
        new QueryTrackerImpl(
            queryId,
            context,
            physicalPlan,
            reader,
            resourceAllocator.get(),
            executorSetService.get(),
            executorSelectionService.get(),
            executorServiceClientFactory.get(),
            jobTelemetryClient.get(),
            observer,
            listener,
            () -> closeQuery(queryId),
            closeableSchedulerThreadPool);
    Preconditions.checkState(
        activeQueryMap.putIfAbsent(queryId, queryTracker) == null,
        "query already queued for execution " + QueryIdHelper.getQueryId(queryId));

    // allocate execution resources on the calling thread, as this will most likely block
    queryTracker.allocateResources();

    try {
      observer.beginState(AttemptObserver.toEvent(AttemptEvent.State.EXECUTION_PLANNING));

      // do execution planning in the bound pool
      commandPool
          .get()
          .submit(
              CommandPool.Priority.HIGH,
              QueryIdHelper.getQueryId(queryId) + ":execution-planning",
              "execution-planning",
              (waitInMillis) -> {
                injector.injectChecked(
                    context.getExecutionControls(),
                    INJECTOR_COMMAND_POOL_SUBMIT_ERROR,
                    ExecutionSetupException.class);

                observer.commandPoolWait(waitInMillis);
                queryTracker.planExecution();
                return null;
              },
              runInSameThread)
          .get();
    } catch (ExecutionException | InterruptedException e) {
      throw new ExecutionSetupException("failure during execution planning", e);
    }

    observer.beginState(AttemptObserver.toEvent(AttemptEvent.State.STARTING));
    // propagate the fragments.
    queryTracker.startFragments();

    injector.injectChecked(
        context.getExecutionControls(),
        INJECTOR_EXECUTE_QUERY_END_ERROR,
        ExecutionSetupException.class);
  }

  @Override
  public void interruptExecutionInWaitStates(QueryId queryId, AttemptEvent.State currentStage) {
    final QueryTracker queryTracker = activeQueryMap.get(queryId);
    if (queryTracker == null) {
      return;
    }
    switch (currentStage) {
      case ENGINE_START:
      case QUEUED:
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Interrupting allocation {} {}", QueryIdHelper.getQueryId(queryId), currentStage);
        }
        queryTracker.interruptAllocation();
        break;

      default:
        // TODO: support interruptions in other interruptible states of query execution within
        // maestro
        //  as well (future PRs).
        break;
    }
  }

  @Override
  public void cancelQuery(QueryId queryId) {
    QueryTracker queryTracker = activeQueryMap.get(queryId);
    if (queryTracker == null) {
      logger.debug(
          "Cancel request for non-existing query {}, ignoring", QueryIdHelper.getQueryId(queryId));
    } else {
      queryTracker.cancel();
    }
  }

  @Override
  public int getActiveQueryCount() {
    return activeQueryMap.size();
  }

  private void closeQuery(QueryId queryId) {
    QueryTracker queryTracker = activeQueryMap.remove(queryId);
    if (queryTracker != null) {
      // release resources held by the query.
      AutoCloseables.closeNoChecked(queryTracker);
      indicateIfSafeToExit();
    }
  }

  @Override
  public ExecToCoordStatusHandler getExecStatusHandler() {
    return execToCoordStatusHandlerImpl;
  }

  @Override
  public GroupResourceInformation getGroupResourceInformation(
      OptionManager optionManager, ResourceSchedulingProperties resourceSchedulingProperties)
      throws ResourceAllocationException {
    return resourceAllocator
        .get()
        .getGroupResourceInformation(optionManager, resourceSchedulingProperties);
  }

  @Override
  public void close() throws Exception {
    closeableSchedulerThreadPool.close();
  }

  @Override
  public List<QueryId> getActiveQueryIds() {
    return new ArrayList<>(activeQueryMap.keySet());
  }

  /** Handles status messages from executors. */
  private class ExecToCoordStatusHandlerImpl implements ExecToCoordStatusHandler {
    private final Provider<JobTelemetryClient> jobTelemetryClient;

    public ExecToCoordStatusHandlerImpl(Provider<JobTelemetryClient> jobTelemetryClient) {
      this.jobTelemetryClient = jobTelemetryClient;
    }

    @Override
    public void screenCompleted(NodeQueryScreenCompletion completion) throws RpcException {
      logger.info(
          "Screen complete message came in for node {} and queryId {}",
          completion.getEndpoint().getAddress(),
          QueryIdHelper.getQueryId(completion.getId()));
      QueryTracker queryTracker = activeQueryMap.get(completion.getId());

      if (queryTracker != null) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Received NodeQueryScreenCompletion request for Query {} from {} in {}",
              QueryIdHelper.getQueryId(completion.getId()),
              completion.getEndpoint().getAddress(),
              completion.getForeman().getAddress());
        }
        queryTracker.screenCompleted(completion);

      } else {
        forwarder.get().screenCompleted(completion);
      }
    }

    @Override
    @WithSpan
    public void nodeQueryCompleted(NodeQueryCompletion completion) throws RpcException {
      Span currentSpan = Span.current();
      String queryId = QueryIdHelper.getQueryId(completion.getId());
      logger.info(
          "Node query complete message came in for node {} and queryId {}",
          completion.getEndpoint().getAddress(),
          queryId);
      QueryTracker queryTracker = activeQueryMap.get(completion.getId());

      if (queryTracker != null) {
        currentSpan.setAttribute("is_active_query", true);
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Received NodeQueryCompletion request for Query {} from {} in {}",
              queryId,
              completion.getEndpoint().getAddress(),
              completion.getForeman().getAddress());
        }
        try {
          injector.injectChecked(
              queryTracker.getExecutionControls(),
              INJECTOR_FINAL_EXECUTOR_PROFILE_ERROR,
              ExecutionSetupException.class);
          timedOperation(
              UPDATE_FINAL_EXECUTOR_PROFILE_TIMER.start(),
              () -> {
                if (optionManagerProvider.get().getOption(ExecConstants.JOB_PROFILE_ASYNC_UPDATE)) {
                  updateFinalExecutorProfileAsync(completion, queryTracker);
                } else {
                  updateFinalExecutorProfileSync(completion);
                }
              });
        } catch (Throwable e) {
          currentSpan.setAttribute("jts.put_executor_profile_rpc_failed", true);
          logger.warn("Exception sending final Executor profile {}. ", queryId, e);
          jobTelemetryClient
              .get()
              .getSuppressedErrorCounter()
              .withTags(
                  MetricLabel.JTS_METRIC_TAG_KEY_RPC,
                  MetricLabel.JTS_METRIC_TAG_VALUE_RPC_PUT_EXECUTOR_PROFILE,
                  MetricLabel.JTS_METRIC_TAG_KEY_ERROR_ORIGIN,
                  MetricLabel.JTS_METRIC_TAG_VALUE_NODE_QUERY_COMPLETE)
              .increment();
          queryTracker.putProfileFailed();
        }
        queryTracker.nodeCompleted(completion);

      } else {
        currentSpan.setAttribute("is_active_query", false);
        forwarder.get().nodeQueryCompleted(completion);
      }
    }

    private void logUninitialisedStub(String endpoint) {
      // telemetry client/service has not been fully started. a message can still arrive
      // if coordinator has been restarted while active queries are running in executor.
      logger.info(
          "Dropping a profile message from end point : {}. "
              + "This is harmless since the query will be terminated shortly due to coordinator restarting",
          endpoint);
    }

    @WithSpan
    private void updateFinalExecutorProfileSync(NodeQueryCompletion completion) {
      // propagate to job-telemetry service (in-process server).
      JobTelemetryServiceGrpc.JobTelemetryServiceBlockingStub stub =
          jobTelemetryClient.get().getBlockingStub();
      CoordExecRPC.ExecutorQueryProfile profile = completion.getFinalNodeQueryProfile();
      if (stub == null) {
        logUninitialisedStub(profile.getEndpoint().toString());
      } else {
        jobTelemetryClient
            .get()
            .getExponentiaRetryer()
            .call(
                () ->
                    stub.putExecutorProfile(
                        PutExecutorProfileRequest.newBuilder()
                            .setProfile(profile)
                            .setIsFinal(true)
                            .build()));
      }
    }

    @WithSpan
    private void updateFinalExecutorProfileAsync(
        NodeQueryCompletion completion, QueryTracker queryTracker) {
      // propagate to job-telemetry service (in-process server).
      JobTelemetryServiceGrpc.JobTelemetryServiceStub stub =
          jobTelemetryClient.get().getAsyncStub();
      CoordExecRPC.ExecutorQueryProfile profile = completion.getFinalNodeQueryProfile();
      if (stub == null) {
        logUninitialisedStub(profile.getEndpoint().toString());
      } else {
        queryTracker.putExecutorProfile(profile.getEndpoint().getAddress());
        grpcContext.run(
            () -> {
              stub.putExecutorProfile(
                  PutExecutorProfileRequest.newBuilder()
                      .setProfile(profile)
                      .setIsFinal(true)
                      .build(),
                  new StreamObserver<Empty>() {
                    @Override
                    public void onNext(Empty value) {
                      // No-op
                    }

                    @Override
                    public void onError(Throwable t) {
                      logger.warn(
                          "{} : Failed to update Final Execution Profile for {}:{}",
                          QueryIdHelper.getQueryId(profile.getQueryId()),
                          profile.getEndpoint().getAddress(),
                          profile.getEndpoint().getFabricPort(),
                          t);
                    }

                    @Override
                    public void onCompleted() {
                      queryTracker.removeExecutorProfile(profile.getEndpoint().getAddress());
                    }
                  });
            });
      }
    }

    @Override
    public void nodeQueryMarkFirstError(NodeQueryFirstError error) throws RpcException {
      logger.info(
          "Node Query error came in for node {} and queryId {}",
          error.getEndpoint().getAddress(),
          QueryIdHelper.getQueryId(error.getHandle().getQueryId()));
      QueryTracker queryTracker = activeQueryMap.get(error.getHandle().getQueryId());

      if (queryTracker != null) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Received NodeQueryFirstError request for Query {} from {} in {}",
              QueryIdHelper.getQueryId(error.getHandle().getQueryId()),
              error.getEndpoint().getAddress(),
              error.getForeman().getAddress());
        }
        queryTracker.nodeMarkFirstError(error);

      } else {
        forwarder.get().nodeQueryMarkFirstError(error);
      }
    }
  }

  /**
   * Waits until it is safe to exit. Blocks until all currently running fragments have completed.
   *
   * <p>This is intended to be used by {@link com.dremio.exec.server.SabotNode#close()}.
   */
  @Override
  public void waitToExit() {
    synchronized (this) {
      if (activeQueryMap.isEmpty()) {
        return;
      }

      exitLatch = new ExtendedLatch();
    }

    // Wait for at most 5 seconds or until the latch is released.
    exitLatch.awaitUninterruptibly(5000);
  }

  /**
   * If it is safe to exit, and the exitLatch is in use, signals it so that waitToExit() will
   * unblock.
   */
  private void indicateIfSafeToExit() {
    synchronized (this) {
      if (exitLatch != null) {
        if (activeQueryMap.isEmpty()) {
          exitLatch.countDown();
        }
      }
    }
  }
}
