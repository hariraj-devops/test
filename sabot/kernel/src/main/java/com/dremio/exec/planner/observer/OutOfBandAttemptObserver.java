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
package com.dremio.exec.planner.observer;

import com.dremio.common.DeferredException;
import com.dremio.common.SerializedExecutor;
import com.dremio.common.tracing.TracingUtils;
import com.dremio.common.utils.protos.QueryWritableBatch;
import com.dremio.context.RequestContext;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.RelWithInfo;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionInfo;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCacheEntry;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.UserBitShared.AccelerationProfile;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.FragmentRpcSizeStats;
import com.dremio.exec.proto.UserBitShared.LayoutMaterializedViewProfile;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.work.QueryWorkUnit;
import com.dremio.exec.work.foreman.ExecutionPlan;
import com.dremio.exec.work.protector.UserRequest;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.reflection.hints.ReflectionExplanationsAndQueryDistance;
import com.dremio.resource.GroupResourceInformation;
import com.dremio.resource.ResourceSchedulingDecisionInfo;
import com.dremio.telemetry.utils.TracerFacade;
import io.opentracing.Span;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.inject.Provider;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;

/**
 * Does query observations in order but not in the query execution thread. This ensures two things:
 * - any blocking commands don't block the underlying thread - any exceptions don't bleed into the
 * caller.
 *
 * <p>Additionally, the observer will report back all exceptions thrown in the callbacks to the
 * delegate {@link #attemptCompletion(UserResult)} callback
 */
public class OutOfBandAttemptObserver implements AttemptObserver {
  private final SerializedExecutor<Runnable> serializedExec;
  private final AttemptObserver innerObserver;
  private final DeferredException deferred = new DeferredException();

  private Span executionSpan;
  private long numCallsToExecDataArrived = 0;
  // log the next span event after this many calls to execDataArrived
  private long recordNextEventOnNumCalls = 1;
  private long eventNameSuffix = 1;
  private final Provider<RequestContext> requestContextProvider;

  OutOfBandAttemptObserver(
      AttemptObserver innerObserver,
      SerializedExecutor<Runnable> serializedExec,
      Provider<RequestContext> requestContextProvider) {
    this.serializedExec = serializedExec;
    this.innerObserver = innerObserver;
    this.requestContextProvider = requestContextProvider;
  }

  @Override
  public void beginState(final AttemptEvent event) {
    switch (event.getState()) {
      case RUNNING:
        {
          executionSpan = TracingUtils.buildChildSpan(TracerFacade.INSTANCE, "execution-started");
          break;
        }
      case COMPLETED:
      case CANCELED:
      case FAILED:
        {
          if (executionSpan != null) {
            executionSpan.finish();
          }
          break;
        }
    }
    execute(() -> innerObserver.beginState(event));
  }

  @Override
  public void queryStarted(final UserRequest query, final String user) {
    execute(() -> innerObserver.queryStarted(query, user));
  }

  @Override
  public void commandPoolWait(long waitInMillis) {
    execute(() -> innerObserver.commandPoolWait(waitInMillis));
  }

  @Override
  public void planFinalPhysical(
      final String text, final long millisTaken, List<PlannerPhaseRulesStats> stats) {
    execute(() -> innerObserver.planFinalPhysical(text, millisTaken, stats));
  }

  @Override
  public void finalPrelPlanGenerated(final Prel prel) {
    execute(() -> innerObserver.finalPrelPlanGenerated(prel));
  }

  @Override
  public void recordExtraInfo(final String name, final byte[] bytes) {
    execute(() -> innerObserver.recordExtraInfo(name, bytes));
  }

  @Override
  public void planRelTransform(
      final PlannerPhase phase,
      final RelOptPlanner planner,
      final RelNode before,
      final RelNode after,
      final long millisTaken,
      final List<PlannerPhaseRulesStats> rulesBreakdownStats) {
    execute(
        () ->
            innerObserver.planRelTransform(
                phase, planner, before, after, millisTaken, rulesBreakdownStats));
  }

  /**
   * Gets the refresh decision and how long it took to make the refresh decision
   *
   * @param text A string describing if we decided to do full or incremental refresh
   * @param millisTaken time taken in planning the refresh decision
   */
  @Override
  public void planRefreshDecision(String text, long millisTaken) {
    execute(() -> innerObserver.planRefreshDecision(text, millisTaken));
  }

  @Override
  public void planParallelStart() {
    execute(innerObserver::planParallelStart);
  }

  @Override
  public void planParallelized(final PlanningSet planningSet) {
    execute(() -> innerObserver.planParallelized(planningSet));
  }

  @Override
  public void planFindMaterializations(final long millisTaken) {
    execute(() -> innerObserver.planFindMaterializations(millisTaken));
  }

  @Override
  public void planNormalized(final long millisTaken, final List<RelWithInfo> normalizedQueryPlans) {
    execute(() -> innerObserver.planNormalized(millisTaken, normalizedQueryPlans));
  }

  @Override
  public void planSubstituted(final long millisTaken) {
    execute(() -> innerObserver.planSubstituted(millisTaken));
  }

  @Override
  public void planConsidered(LayoutMaterializedViewProfile profile, RelWithInfo target) {
    execute(() -> innerObserver.planConsidered(profile, target));
  }

  @Override
  public void planSubstituted(
      final DremioMaterialization materialization,
      final List<RelWithInfo> substitutions,
      final RelWithInfo target,
      final long millisTaken,
      boolean defaultReflection) {
    execute(
        () ->
            innerObserver.planSubstituted(
                materialization, substitutions, target, millisTaken, defaultReflection));
  }

  @Override
  public void substitutionFailures(Iterable<String> errors) {
    execute(() -> innerObserver.substitutionFailures(errors));
  }

  @Override
  public void planAccelerated(final SubstitutionInfo info) {
    execute(() -> innerObserver.planAccelerated(info));
  }

  @Override
  public void addAccelerationProfileToCachedPlan(PlanCacheEntry plan) {
    execute(() -> innerObserver.addAccelerationProfileToCachedPlan(plan));
  }

  @Override
  public void restoreAccelerationProfileFromCachedPlan(AccelerationProfile accelerationProfile) {
    execute(() -> innerObserver.restoreAccelerationProfileFromCachedPlan(accelerationProfile));
  }

  @Override
  public void planCompleted(final ExecutionPlan plan, final BatchSchema batchSchema) {
    // TODO(DX-61807): The catalog lookup will be avoided if we use cache.
    final RequestContext requestContext =
        (RequestContext.current() != RequestContext.empty() || requestContextProvider == null)
            ? RequestContext.current()
            : requestContextProvider.get();

    execute(() -> requestContext.run(() -> innerObserver.planCompleted(plan, batchSchema)));
  }

  @Override
  public void execStarted(final QueryProfile profile) {
    execute(() -> innerObserver.execStarted(profile));
  }

  @Override
  public void execDataArrived(
      final RpcOutcomeListener<Ack> outcomeListener, final QueryWritableBatch result) {
    if ((eventNameSuffix <= 10) && (executionSpan != null)) {
      numCallsToExecDataArrived++;
      if (numCallsToExecDataArrived == recordNextEventOnNumCalls) {
        // log data-arrived event
        executionSpan.log("execDataArrived-" + eventNameSuffix);
        // increase the gap between events as the output data is large
        recordNextEventOnNumCalls *= 2;
        eventNameSuffix++;
      }
    }
    execute(() -> innerObserver.execDataArrived(outcomeListener, result));
  }

  @Override
  public void planJsonPlan(final String text) {
    execute(() -> innerObserver.planJsonPlan(text));
  }

  @Override
  public void planStart(final String rawPlan) {
    execute(() -> innerObserver.planStart(rawPlan));
  }

  @Override
  public void resourcesPlanned(GroupResourceInformation resourceInformation, long millisTaken) {
    execute(() -> innerObserver.resourcesPlanned(resourceInformation, millisTaken));
  }

  @Override
  public void planValidated(
      final RelDataType rowType,
      final SqlNode node,
      final long millisTaken,
      final boolean isMaterializationCacheInitialized) {
    execute(
        () ->
            innerObserver.planValidated(
                rowType, node, millisTaken, isMaterializationCacheInitialized));
  }

  @Override
  public void planStepLogging(String phaseName, String text, long millisTaken) {
    execute(() -> innerObserver.planStepLogging(phaseName, text, millisTaken));
  }

  @Override
  public void planSerializable(final RelNode serializable) {
    execute(() -> innerObserver.planSerializable(serializable));
  }

  @Override
  public void planConvertedToRel(final RelNode converted, final long millisTaken) {
    execute(() -> innerObserver.planConvertedToRel(converted, millisTaken));
  }

  @Override
  public void planConvertedScan(final RelNode converted, final long millisTaken) {
    execute(() -> innerObserver.planConvertedScan(converted, millisTaken));
  }

  @Override
  public void planExpandView(
      final RelRoot expanded,
      final List<String> schemaPath,
      final int nestingLevel,
      final String sql) {
    execute(() -> innerObserver.planExpandView(expanded, schemaPath, nestingLevel, sql));
  }

  @Override
  public void plansDistributionComplete(final QueryWorkUnit unit) {
    execute(() -> innerObserver.plansDistributionComplete(unit));
  }

  @Override
  public void attemptCompletion(final UserResult result) {
    // make sure we have correct ordering (this should come after all previous observations).
    final CountDownLatch cd = new CountDownLatch(1);
    serializedExec.execute(
        () -> {
          try {
            UserResult finalResult = result;
            if (deferred.hasException()) {
              finalResult = finalResult.withException(deferred.getAndClear());
            }
            innerObserver.attemptCompletion(finalResult);
          } finally {
            cd.countDown();
          }
        });
    try {
      cd.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void putExecutorProfile(String nodeEndpoint) {}

  @Override
  public void removeExecutorProfile(String nodeEndpoint) {}

  @Override
  public void queryClosed() {}

  @Override
  public void executorsSelected(
      long millisTaken,
      int idealNumFragments,
      int idealNumNodes,
      int numExecutors,
      String detailsText) {
    execute(
        () ->
            innerObserver.executorsSelected(
                millisTaken, idealNumFragments, idealNumNodes, numExecutors, detailsText));
  }

  @Override
  public void recordsOutput(CoordinationProtos.NodeEndpoint endpoint, long recordCount) {
    execute(() -> innerObserver.recordsOutput(endpoint, recordCount));
  }

  @Override
  public void outputLimited() {
    execute(innerObserver::outputLimited);
  }

  @Override
  public void planGenerationTime(final long millisTaken) {
    execute(() -> innerObserver.planGenerationTime(millisTaken));
  }

  @Override
  public void planAssignmentTime(final long millisTaken) {
    execute(() -> innerObserver.planAssignmentTime(millisTaken));
  }

  @Override
  public void fragmentsStarted(final long millisTaken, FragmentRpcSizeStats stats) {
    execute(() -> innerObserver.fragmentsStarted(millisTaken, stats));
  }

  @Override
  public void fragmentsActivated(final long millisTaken) {
    execute(() -> innerObserver.fragmentsActivated(millisTaken));
  }

  @Override
  public void activateFragmentFailed(Exception ex) {
    execute(() -> innerObserver.activateFragmentFailed(ex));
  }

  @Override
  public void resourcesScheduled(ResourceSchedulingDecisionInfo resourceSchedulingDecisionInfo) {
    execute(() -> innerObserver.resourcesScheduled(resourceSchedulingDecisionInfo));
  }

  @Override
  public void updateReflectionsWithHints(
      ReflectionExplanationsAndQueryDistance reflectionExplanationsAndQueryDistance) {
    execute(() -> innerObserver.updateReflectionsWithHints(reflectionExplanationsAndQueryDistance));
  }

  @Override
  public void tablesCollected(Iterable<DremioTable> tables) {
    execute(() -> innerObserver.tablesCollected(tables));
  }

  @Override
  public void setNumJoinsInUserQuery(Integer joins) {
    execute(() -> innerObserver.setNumJoinsInUserQuery(joins));
  }

  @Override
  public void setNumJoinsInFinalPrel(Integer joins) {
    execute(() -> innerObserver.setNumJoinsInFinalPrel(joins));
  }

  @Override
  public void putProfileFailed() {
    execute(innerObserver::putProfileFailed);
  }

  @Override
  public void putProfileUpdateComplete() {
    execute(innerObserver::putProfileUpdateComplete);
  }

  /**
   * Wraps the runnable so that any exception thrown will eventually cause the attempt to fail when
   * handling the {@link #attemptCompletion(UserResult)} callback
   */
  private void execute(Runnable runnable) {
    serializedExec.execute(
        () -> {
          try {
            runnable.run();
          } catch (Throwable ex) {
            deferred.addThrowable(ex);
          }
        });
  }
}
