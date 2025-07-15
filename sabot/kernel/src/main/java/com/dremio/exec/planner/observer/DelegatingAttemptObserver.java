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
import java.util.List;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;

public class DelegatingAttemptObserver implements AttemptObserver {

  private final AttemptObserver observer;

  public DelegatingAttemptObserver(AttemptObserver observer) {
    this.observer = observer;
  }

  @Override
  public void beginState(AttemptEvent event) {
    observer.beginState(event);
  }

  @Override
  public void queryStarted(UserRequest query, String user) {
    observer.queryStarted(query, user);
  }

  @Override
  public void commandPoolWait(long waitInMillis) {
    observer.commandPoolWait(waitInMillis);
  }

  @Override
  public void planStart(String rawPlan) {
    observer.planStart(rawPlan);
  }

  @Override
  public void resourcesPlanned(GroupResourceInformation resourceInformation, long millisTaken) {
    observer.resourcesPlanned(resourceInformation, millisTaken);
  }

  @Override
  public void planValidated(
      RelDataType rowType,
      SqlNode node,
      long millisTaken,
      boolean isMaterializationCacheInitialized) {
    observer.planValidated(rowType, node, millisTaken, isMaterializationCacheInitialized);
  }

  @Override
  public void planStepLogging(String phaseName, String text, long millisTaken) {
    observer.planStepLogging(phaseName, text, millisTaken);
  }

  @Override
  public void planSerializable(RelNode serializable) {
    observer.planSerializable(serializable);
  }

  @Override
  public void planConvertedToRel(RelNode converted, long millisTaken) {
    observer.planConvertedToRel(converted, millisTaken);
  }

  /**
   * Gets the refresh decision and how long it took to make the refresh decision
   *
   * @param text A string describing if we decided to do full or incremental refresh
   * @param millisTaken time taken in planning the refresh decision
   */
  @Override
  public void planRefreshDecision(String text, long millisTaken) {
    observer.planRefreshDecision(text, millisTaken);
  }

  @Override
  public void planConvertedScan(RelNode converted, long millisTaken) {
    observer.planConvertedScan(converted, millisTaken);
  }

  @Override
  public void planExpandView(
      RelRoot expanded, List<String> schemaPath, int nestingLevel, String sql) {
    observer.planExpandView(expanded, schemaPath, nestingLevel, sql);
  }

  @Override
  public void plansDistributionComplete(QueryWorkUnit unit) {
    observer.plansDistributionComplete(unit);
  }

  @Override
  public void planFinalPhysical(String text, long millisTaken, List<PlannerPhaseRulesStats> stats) {
    observer.planFinalPhysical(text, millisTaken, stats);
  }

  @Override
  public void finalPrelPlanGenerated(Prel prel) {
    observer.finalPrelPlanGenerated(prel);
  }

  @Override
  public void planRelTransform(
      PlannerPhase phase,
      RelOptPlanner planner,
      RelNode before,
      RelNode after,
      long millisTaken,
      final List<PlannerPhaseRulesStats> rulesBreakdownStats) {
    observer.planRelTransform(phase, planner, before, after, millisTaken, rulesBreakdownStats);
  }

  @Override
  public void planParallelStart() {
    observer.planParallelStart();
  }

  @Override
  public void planParallelized(PlanningSet planningSet) {
    observer.planParallelized(planningSet);
  }

  @Override
  public void planFindMaterializations(long millisTaken) {
    observer.planFindMaterializations(millisTaken);
  }

  @Override
  public void planNormalized(long millisTaken, List<RelWithInfo> normalizedQueryPlans) {
    observer.planNormalized(millisTaken, normalizedQueryPlans);
  }

  @Override
  public void planSubstituted(long millisTaken) {
    observer.planSubstituted(millisTaken);
  }

  @Override
  public void planConsidered(LayoutMaterializedViewProfile profile, RelWithInfo target) {
    observer.planConsidered(profile, target);
  }

  @Override
  public void planSubstituted(
      DremioMaterialization materialization,
      List<RelWithInfo> substitutions,
      RelWithInfo target,
      long millisTaken,
      boolean defaultReflection) {
    observer.planSubstituted(
        materialization, substitutions, target, millisTaken, defaultReflection);
  }

  @Override
  public void substitutionFailures(Iterable<String> errors) {
    observer.substitutionFailures(errors);
  }

  @Override
  public void addAccelerationProfileToCachedPlan(PlanCacheEntry plan) {
    observer.addAccelerationProfileToCachedPlan(plan);
  }

  @Override
  public void restoreAccelerationProfileFromCachedPlan(AccelerationProfile accelerationProfile) {
    observer.restoreAccelerationProfileFromCachedPlan(accelerationProfile);
  }

  @Override
  public void planAccelerated(final SubstitutionInfo info) {
    observer.planAccelerated(info);
  }

  @Override
  public void planCompleted(final ExecutionPlan plan, final BatchSchema batchSchema) {
    observer.planCompleted(plan, batchSchema);
  }

  @Override
  public void execStarted(QueryProfile profile) {
    observer.execStarted(profile);
  }

  @Override
  public void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result) {
    observer.execDataArrived(outcomeListener, result);
  }

  @Override
  public void attemptCompletion(UserResult result) {
    observer.attemptCompletion(result);
  }

  @Override
  public void putExecutorProfile(String nodeEndpoint) {
    observer.putExecutorProfile(nodeEndpoint);
  }

  @Override
  public void removeExecutorProfile(String nodeEndpoint) {
    observer.removeExecutorProfile(nodeEndpoint);
  }

  @Override
  public void queryClosed() {
    observer.queryClosed();
  }

  @Override
  public void planJsonPlan(String text) {
    observer.planJsonPlan(text);
  }

  @Override
  public void executorsSelected(
      long millisTaken,
      int idealNumFragments,
      int idealNumNodes,
      int numExecutors,
      String detailsText) {
    observer.executorsSelected(
        millisTaken, idealNumFragments, idealNumNodes, numExecutors, detailsText);
  }

  @Override
  public void recordsOutput(CoordinationProtos.NodeEndpoint endpoint, long recordCount) {
    observer.recordsOutput(endpoint, recordCount);
  }

  @Override
  public void outputLimited() {
    observer.outputLimited();
  }

  @Override
  public void planGenerationTime(long millisTaken) {
    observer.planGenerationTime(millisTaken);
  }

  @Override
  public void planAssignmentTime(long millisTaken) {
    observer.planAssignmentTime(millisTaken);
  }

  @Override
  public void fragmentsStarted(long millisTaken, FragmentRpcSizeStats stats) {
    observer.fragmentsStarted(millisTaken, stats);
  }

  @Override
  public void fragmentsActivated(long millisTaken) {
    observer.fragmentsActivated(millisTaken);
  }

  @Override
  public void activateFragmentFailed(Exception ex) {
    observer.activateFragmentFailed(ex);
  }

  @Override
  public void resourcesScheduled(ResourceSchedulingDecisionInfo resourceSchedulingDecisionInfo) {
    observer.resourcesScheduled(resourceSchedulingDecisionInfo);
  }

  @Override
  public void recordExtraInfo(String name, byte[] bytes) {
    observer.recordExtraInfo(name, bytes);
  }

  @Override
  public void tablesCollected(Iterable<DremioTable> tables) {
    observer.tablesCollected(tables);
  }

  @Override
  public void updateReflectionsWithHints(
      ReflectionExplanationsAndQueryDistance reflectionExplanationsAndQueryDistance) {
    observer.updateReflectionsWithHints(reflectionExplanationsAndQueryDistance);
  }

  @Override
  public void setNumJoinsInUserQuery(Integer joins) {
    observer.setNumJoinsInUserQuery(joins);
  }

  @Override
  public void setNumJoinsInFinalPrel(Integer joins) {
    observer.setNumJoinsInFinalPrel(joins);
  }

  @Override
  public void putProfileFailed() {
    observer.putProfileFailed();
  }

  @Override
  public void putProfileUpdateComplete() {
    observer.putProfileUpdateComplete();
  }
}
