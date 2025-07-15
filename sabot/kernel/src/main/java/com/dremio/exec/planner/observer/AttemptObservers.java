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

import com.dremio.common.utils.protos.QueryWritableBatch;
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
import java.util.LinkedList;
import java.util.List;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;

/** Collection of observers. */
public class AttemptObservers implements AttemptObserver {

  private final List<AttemptObserver> observers = new LinkedList<>();

  // use #of()
  protected AttemptObservers() {}

  @Override
  public void beginState(AttemptEvent event) {
    for (final AttemptObserver observer : observers) {
      observer.beginState(event);
    }
  }

  @Override
  public void queryStarted(UserRequest query, String user) {
    for (final AttemptObserver observer : observers) {
      observer.queryStarted(query, user);
    }
  }

  @Override
  public void commandPoolWait(long waitInMillis) {
    for (final AttemptObserver observer : observers) {
      observer.commandPoolWait(waitInMillis);
    }
  }

  @Override
  public void planStart(String rawPlan) {
    for (final AttemptObserver observer : observers) {
      observer.planStart(rawPlan);
    }
  }

  @Override
  public void resourcesPlanned(GroupResourceInformation resourceInformation, long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.resourcesPlanned(resourceInformation, millisTaken);
    }
  }

  @Override
  public void planValidated(
      RelDataType rowType,
      SqlNode node,
      long millisTaken,
      boolean isMaterializationCacheInitialized) {
    for (final AttemptObserver observer : observers) {
      observer.planValidated(rowType, node, millisTaken, isMaterializationCacheInitialized);
    }
  }

  @Override
  public void planStepLogging(String phaseName, String text, long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planStepLogging(phaseName, text, millisTaken);
    }
  }

  @Override
  public void addAccelerationProfileToCachedPlan(PlanCacheEntry planCacheEntry) {
    for (final AttemptObserver observer : observers) {
      observer.addAccelerationProfileToCachedPlan(planCacheEntry);
    }
  }

  @Override
  public void restoreAccelerationProfileFromCachedPlan(AccelerationProfile accelerationProfile) {
    for (final AttemptObserver observer : observers) {
      observer.restoreAccelerationProfileFromCachedPlan(accelerationProfile);
    }
  }

  @Override
  public void planSerializable(RelNode serializable) {
    for (final AttemptObserver observer : observers) {
      observer.planSerializable(serializable);
    }
  }

  @Override
  public void planConvertedToRel(RelNode converted, long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planConvertedToRel(converted, millisTaken);
    }
  }

  @Override
  public void planConvertedScan(RelNode converted, long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planConvertedScan(converted, millisTaken);
    }
  }

  /**
   * Gets the refresh decision for a reflection and how long it took to make it
   *
   * @param text A string describing if we decided to do full or incremental refresh
   * @param millisTaken Time taken in planning the refresh decision
   */
  @Override
  public void planRefreshDecision(String text, long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planRefreshDecision(text, millisTaken);
    }
  }

  @Override
  public void planExpandView(
      RelRoot expanded, List<String> schemaPath, int nestingLevel, String sql) {
    for (final AttemptObserver observer : observers) {
      observer.planExpandView(expanded, schemaPath, nestingLevel, sql);
    }
  }

  @Override
  public void planRelTransform(
      PlannerPhase phase,
      RelOptPlanner planner,
      RelNode before,
      RelNode after,
      long millisTaken,
      final List<PlannerPhaseRulesStats> rulesBreakdownStats) {
    for (final AttemptObserver observer : observers) {
      observer.planRelTransform(phase, planner, before, after, millisTaken, rulesBreakdownStats);
    }
  }

  @Override
  public void planFinalPhysical(String text, long millisTaken, List<PlannerPhaseRulesStats> stats) {
    for (final AttemptObserver observer : observers) {
      observer.planFinalPhysical(text, millisTaken, stats);
    }
  }

  @Override
  public void finalPrelPlanGenerated(Prel prel) {
    for (final AttemptObserver observer : observers) {
      observer.finalPrelPlanGenerated(prel);
    }
  }

  @Override
  public void planParallelStart() {
    for (final AttemptObserver observer : observers) {
      observer.planParallelStart();
    }
  }

  @Override
  public void planParallelized(PlanningSet planningSet) {
    for (final AttemptObserver observer : observers) {
      observer.planParallelized(planningSet);
    }
  }

  @Override
  public void plansDistributionComplete(QueryWorkUnit unit) {
    for (final AttemptObserver observer : observers) {
      observer.plansDistributionComplete(unit);
    }
  }

  @Override
  public void planFindMaterializations(long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planFindMaterializations(millisTaken);
    }
  }

  @Override
  public void planNormalized(long millisTaken, List<RelWithInfo> normalizedQueryPlans) {
    for (final AttemptObserver observer : observers) {
      observer.planNormalized(millisTaken, normalizedQueryPlans);
    }
  }

  @Override
  public void planSubstituted(long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planSubstituted(millisTaken);
    }
  }

  @Override
  public void planConsidered(LayoutMaterializedViewProfile profile, RelWithInfo target) {
    for (final AttemptObserver observer : observers) {
      observer.planConsidered(profile, target);
    }
  }

  @Override
  public void planSubstituted(
      DremioMaterialization materialization,
      List<RelWithInfo> substitutions,
      RelWithInfo target,
      long millisTaken,
      boolean defaultReflection) {
    for (final AttemptObserver observer : observers) {
      observer.planSubstituted(
          materialization, substitutions, target, millisTaken, defaultReflection);
    }
  }

  @Override
  public void substitutionFailures(Iterable<String> errors) {
    for (final AttemptObserver observer : observers) {
      observer.substitutionFailures(errors);
    }
  }

  @Override
  public void planAccelerated(SubstitutionInfo info) {
    for (final AttemptObserver observer : observers) {
      observer.planAccelerated(info);
    }
  }

  @Override
  public void planCompleted(ExecutionPlan plan, BatchSchema batchSchema) {
    for (final AttemptObserver observer : observers) {
      observer.planCompleted(plan, batchSchema);
    }
  }

  @Override
  public void execStarted(QueryProfile profile) {
    for (final AttemptObserver observer : observers) {
      observer.execStarted(profile);
    }
  }

  @Override
  public void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
    for (final AttemptObserver observer : observers) {
      observer.execDataArrived(outcomeListener, result);
    }
  }

  @Override
  public void planJsonPlan(String text) {
    for (final AttemptObserver observer : observers) {
      observer.planJsonPlan(text);
    }
  }

  @Override
  public void attemptCompletion(UserResult result) {
    for (final AttemptObserver observer : observers) {
      observer.attemptCompletion(result);
    }
  }

  @Override
  public void putExecutorProfile(String nodeEndpoint) {
    for (final AttemptObserver observer : observers) {
      observer.putExecutorProfile(nodeEndpoint);
    }
  }

  @Override
  public void removeExecutorProfile(String nodeEndpoint) {
    for (final AttemptObserver observer : observers) {
      observer.removeExecutorProfile(nodeEndpoint);
    }
  }

  @Override
  public void queryClosed() {
    for (final AttemptObserver observer : observers) {
      observer.queryClosed();
    }
  }

  @Override
  public void executorsSelected(
      long millisTaken,
      int idealNumFragments,
      int idealNumNodes,
      int numExecutors,
      String detailsText) {
    for (final AttemptObserver observer : observers) {
      observer.executorsSelected(
          millisTaken, idealNumFragments, idealNumNodes, numExecutors, detailsText);
    }
  }

  @Override
  public void recordsOutput(CoordinationProtos.NodeEndpoint endpoint, long recordCount) {
    for (final AttemptObserver observer : observers) {
      observer.recordsOutput(endpoint, recordCount);
    }
  }

  @Override
  public void outputLimited() {
    for (final AttemptObserver observer : observers) {
      observer.outputLimited();
    }
  }

  @Override
  public void planGenerationTime(long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planGenerationTime(millisTaken);
    }
  }

  @Override
  public void planAssignmentTime(long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.planAssignmentTime(millisTaken);
    }
  }

  @Override
  public void fragmentsStarted(long millisTaken, FragmentRpcSizeStats stats) {
    for (final AttemptObserver observer : observers) {
      observer.fragmentsStarted(millisTaken, stats);
    }
  }

  @Override
  public void fragmentsActivated(long millisTaken) {
    for (final AttemptObserver observer : observers) {
      observer.fragmentsActivated(millisTaken);
    }
  }

  @Override
  public void activateFragmentFailed(Exception ex) {
    for (final AttemptObserver observer : observers) {
      observer.activateFragmentFailed(ex);
    }
  }

  @Override
  public void resourcesScheduled(ResourceSchedulingDecisionInfo resourceSchedulingDecisionInfo) {
    for (final AttemptObserver observer : observers) {
      observer.resourcesScheduled(resourceSchedulingDecisionInfo);
    }
  }

  @Override
  public void updateReflectionsWithHints(
      ReflectionExplanationsAndQueryDistance reflectionExplanationsAndQueryDistance) {
    for (AttemptObserver observer : observers) {
      observer.updateReflectionsWithHints(reflectionExplanationsAndQueryDistance);
    }
  }

  @Override
  public void recordExtraInfo(String name, byte[] bytes) {
    for (final AttemptObserver observer : observers) {
      observer.recordExtraInfo(name, bytes);
    }
  }

  @Override
  public void tablesCollected(Iterable<DremioTable> tables) {
    observers.forEach(o -> o.tablesCollected(tables));
  }

  @Override
  public void setNumJoinsInUserQuery(Integer joins) {
    for (final AttemptObserver observer : observers) {
      observer.setNumJoinsInUserQuery(joins);
    }
  }

  @Override
  public void setNumJoinsInFinalPrel(Integer joins) {
    for (final AttemptObserver observer : observers) {
      observer.setNumJoinsInFinalPrel(joins);
    }
  }

  @Override
  public void putProfileFailed() {
    for (final AttemptObserver observer : observers) {
      observer.putProfileFailed();
    }
  }

  @Override
  public void putProfileUpdateComplete() {
    for (final AttemptObserver observer : observers) {
      observer.putProfileUpdateComplete();
    }
  }

  /**
   * Add to the collection of observers.
   *
   * @param observer attempt observer
   */
  public void add(final AttemptObserver observer) {
    observers.add(observer);
  }

  /**
   * Create a collection of observers.
   *
   * @param observers attempt observers
   * @return attempt observers
   */
  public static AttemptObservers of(final AttemptObserver... observers) {
    final AttemptObservers chain = new AttemptObservers();
    for (final AttemptObserver observer : observers) {
      chain.add(observer);
    }

    return chain;
  }

  public List<AttemptObserver> getObservers() {
    return observers;
  }
}
