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
package com.dremio.exec.planner.sql.handlers;

import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.CheapestPlanWithReflectionVisitor;
import com.dremio.exec.planner.DremioHepPlanner;
import com.dremio.exec.planner.DremioVolcanoPlanner;
import com.dremio.exec.planner.MatchCountListener;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.PlannerType;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.acceleration.MaterializationList;
import com.dremio.exec.planner.acceleration.substitution.AccelerationAwareSubstitutionProvider;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.ConstExecutor;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlannerUtil {
  private static final Logger LOGGER = LoggerFactory.getLogger(PlannerUtil.class);

  @SuppressWarnings("Slf4jIllegalPassedClass") // intentionally using logger from another class
  private static final org.slf4j.Logger CALCITE_LOGGER =
      org.slf4j.LoggerFactory.getLogger(RelOptPlanner.class);

  /**
   * Transform RelNode to a new RelNode, targeting the provided set of traits. Also will log the
   * outcome if asked.
   *
   * @param plannerType The type of Planner to use.
   * @param phase The transformation phase we're running.
   * @param input The original RelNode
   * @param targetTraits The traits we are targeting for output.
   * @param log Whether to log the planning phase.
   * @return The transformed relnode.
   */
  public static RelNode transform(
      SqlHandlerConfig config,
      PlannerType plannerType,
      PlannerPhase phase,
      final RelNode input,
      RelTraitSet targetTraits,
      boolean log) {
    final RuleSet rules = config.getRules(phase);
    final RelTraitSet toTraits = targetTraits.simplify();
    final RelOptPlanner planner;
    final Supplier<TransformationContext> toPlan;
    final PlannerSettings plannerSettings = config.getContext().getPlannerSettings();

    CALCITE_LOGGER.trace(
        "Starting Planning for phase {} with target traits {}.", phase, targetTraits);
    if (Iterables.isEmpty(rules)) {
      CALCITE_LOGGER.trace("Completed Phase: {}. No rules.", phase);
      return input;
    }

    if (plannerType.isHep()) {

      final HepProgramBuilder hepPgmBldr = new HepProgramBuilder();

      long relNodeCount = MoreRelOptUtil.countRelNodes(input);
      long rulesCount = Iterables.size(rules);
      int matchLimit =
          (int) plannerSettings.getOptions().getOption(PlannerSettings.HEP_PLANNER_MATCH_LIMIT);
      hepPgmBldr.addMatchLimit(matchLimit);

      MatchCountListener matchCountListener =
          new MatchCountListener(
              relNodeCount, rulesCount, matchLimit, Thread.currentThread().getName());

      hepPgmBldr.addMatchOrder(plannerType.getMatchOrder());
      if (plannerType.isCombineRules()) {
        hepPgmBldr.addRuleCollection(Lists.newArrayList(rules));
      } else {
        for (RelOptRule rule : rules) {
          hepPgmBldr.addRuleInstance(rule);
        }
      }

      SqlConverter converter = config.getConverter();
      final DremioHepPlanner hepPlanner =
          new DremioHepPlanner(
              hepPgmBldr.build(),
              plannerSettings,
              converter.getCostFactory(),
              phase,
              matchCountListener);
      hepPlanner.setExecutor(
          new ConstExecutor(
              converter.getFunctionImplementationRegistry(),
              converter.getFunctionContext(),
              converter.getSettings()));

      // Modify RelMetaProvider for every RelNode in the SQL operator Rel tree.
      RelOptCluster cluster = input.getCluster();
      cluster.setMetadataQuerySupplier(config.getContext().getRelMetadataQuerySupplier());
      cluster.invalidateMetadataQuery();

      // Begin planning
      hepPlanner.setRoot(input);
      if (!input.getTraitSet().equals(targetTraits)) {
        hepPlanner.changeTraits(input, toTraits);
      }

      planner = hepPlanner;
      toPlan =
          () -> {
            RelNode relNode;
            try {
              relNode = hepPlanner.findBestExp();
            } catch (Throwable t) {
              try {
                // In case of an exception, lets collect planner rule stats, so we don't lose them
                PlanLogUtil.log(plannerType, phase, input, LOGGER, null);
                List<PlannerPhaseRulesStats> stats = matchCountListener.getRulesBreakdownStats();
                config.getObserver().planRelTransform(phase, planner, input, input, 0, stats);
              } catch (Throwable unexpected) {
                t.addSuppressed(unexpected);
              }
              throw t;
            }
            List<PlannerPhaseRulesStats> rulesBreakdownStats =
                matchCountListener.getRulesBreakdownStats();
            if (log) {
              LOGGER.debug("Phase: {}", phase);
              LOGGER.debug(matchCountListener.toString());
            }
            return new TransformationContext(relNode, rulesBreakdownStats);
          };
    } else {
      // as weird as it seems, the cluster's only planner is the volcano planner.
      Preconditions.checkArgument(
          input.getCluster().getPlanner() instanceof DremioVolcanoPlanner,
          "Cluster is expected to be constructed using DremioVolcanoPlanner. Was actually of type %s.",
          input.getCluster().getPlanner().getClass().getName());
      final DremioVolcanoPlanner volcanoPlanner =
          (DremioVolcanoPlanner) input.getCluster().getPlanner();
      volcanoPlanner.setPlannerPhase(phase);
      volcanoPlanner.setNoneConventionHasInfiniteCost(
          (phase != PlannerPhase.JDBC_PUSHDOWN) && (phase != PlannerPhase.RELATIONAL_PLANNING));
      final Program program = Programs.of(rules);

      // Modify RelMetaProvider for every RelNode in the SQL operator Rel tree.
      RelOptCluster cluster = input.getCluster();
      cluster.setMetadataQuerySupplier(config.getContext().getRelMetadataQuerySupplier());
      cluster.invalidateMetadataQuery();

      // Configure substitutions
      final AccelerationAwareSubstitutionProvider substitutions =
          config.getConverter().getSubstitutionProvider();
      substitutions.setObserver(config.getObserver());
      substitutions.setEnabled(phase.useMaterializations);
      substitutions.setCurrentPlan(input);

      planner = volcanoPlanner;
      toPlan =
          () -> {
            try {
              RelNode relNode =
                  program.run(
                      volcanoPlanner, input, toTraits, ImmutableList.of(), ImmutableList.of());
              List<PlannerPhaseRulesStats> rulesBreakdownStats =
                  volcanoPlanner.getMatchCountListener().getRulesBreakdownStats();
              if (log) {
                LOGGER.debug("Phase: {}", phase);
                LOGGER.debug(volcanoPlanner.getMatchCountListener().toString());
              }
              return new TransformationContext(relNode, rulesBreakdownStats);
            } catch (Throwable t) {
              try {
                // In case of an exception, lets collect planner rule stats, so we don't lose them
                PlanLogUtil.log(plannerType, phase, input, LOGGER, null);
                List<PlannerPhaseRulesStats> stats =
                    volcanoPlanner.getMatchCountListener().getRulesBreakdownStats();
                config.getObserver().planRelTransform(phase, planner, input, input, 0, stats);
              } catch (Throwable unexpected) {
                t.addSuppressed(unexpected);
              }
              throw t;
            } finally {
              substitutions.setEnabled(false);
            }
          };
    }

    return doTransform(config, plannerType, phase, planner, input, log, toPlan);
  }

  @WithSpan("transform-plan")
  private static RelNode doTransform(
      SqlHandlerConfig config,
      final PlannerType plannerType,
      final PlannerPhase phase,
      final RelOptPlanner planner,
      final RelNode input,
      boolean log,
      Supplier<TransformationContext> toPlan) {
    Span.current().setAttribute("dremio.planner.phase", phase.name());
    final Stopwatch watch = Stopwatch.createStarted();

    try {
      final TransformationContext context = toPlan.get();
      final RelNode intermediateNode = context.getRelNode();
      final RelNode output;
      if (phase == PlannerPhase.LOGICAL) {
        RelNode forcedLogical = intermediateNode;
        Set<String> chooseReflections =
            MaterializationList.parseReflectionIds(
                config.getContext().getOptions().getOption(PlannerSettings.CHOOSE_REFLECTIONS));
        if (!chooseReflections.isEmpty()) {
          final DremioVolcanoPlanner volcanoPlanner =
              (DremioVolcanoPlanner) intermediateNode.getCluster().getPlanner();
          Set<String> forcedMatches =
              Sets.intersection(volcanoPlanner.getMatchedReflections(), chooseReflections);
          if (!forcedMatches.isEmpty()) {
            Map<String, CheapestPlanWithReflectionVisitor.RelCostPair> bestPlansWithReflections =
                new CheapestPlanWithReflectionVisitor(volcanoPlanner, forcedMatches)
                    .getBestPlansWithReflections();
            CheapestPlanWithReflectionVisitor.RelCostPair best = null;
            for (String reflection : bestPlansWithReflections.keySet()) {
              CheapestPlanWithReflectionVisitor.RelCostPair current =
                  bestPlansWithReflections.get(reflection);
              if (best == null || current.getCost().isLt(best.getCost())) {
                best = current; // Pick the best cost plan containing a reflection from the
                // choose_reflections hint
              }
            }
            forcedLogical = best != null ? best.getRel() : intermediateNode;
          }
        }
        output = forcedLogical;
      } else {
        output = intermediateNode;
      }

      if (log) {
        PlanLogUtil.log(plannerType, phase, output, LOGGER, watch);
        config
            .getObserver()
            .planRelTransform(
                phase,
                planner,
                input,
                output,
                watch.elapsed(TimeUnit.MILLISECONDS),
                context.getRulesBreakdownStats());
      }

      CALCITE_LOGGER.trace("Completed Phase: {}.", phase);

      return output;
    } catch (Throwable t) {
      // log our input state as output anyways so we can ensure that we have details.
      try {
        PlanLogUtil.log(plannerType, phase, input, LOGGER, watch);
        config
            .getObserver()
            .planRelTransform(
                phase,
                planner,
                input,
                input,
                watch.elapsed(TimeUnit.MILLISECONDS),
                ImmutableList.of());
      } catch (Throwable unexpected) {
        t.addSuppressed(unexpected);
      }
      throw t;
    }
  }

  public static RelTransformer createPostSubstitutionTransformer(
      QueryContext queryContext, PlannerSettings plannerSettings, PlannerPhase phase) {
    return relNode -> {
      final HepProgramBuilder builder = HepProgram.builder();
      builder.addMatchOrder(HepMatchOrder.ARBITRARY);
      builder.addRuleCollection(Lists.newArrayList(phase.getRules(queryContext)));

      final HepProgram p = builder.build();

      final HepPlanner pl = new HepPlanner(p, plannerSettings);
      pl.setRoot(relNode);
      return pl.findBestExp();
    };
  }

  public static class TransformationContext {
    private final RelNode relNode;
    private final List<PlannerPhaseRulesStats> rulesBreakdownStats;

    TransformationContext(RelNode relNode, List<PlannerPhaseRulesStats> rulesBreakdownStats) {
      this.relNode = relNode;
      this.rulesBreakdownStats = rulesBreakdownStats;
    }

    public RelNode getRelNode() {
      return relNode;
    }

    public List<PlannerPhaseRulesStats> getRulesBreakdownStats() {
      return rulesBreakdownStats;
    }
  }

  private static class BoostMaterializationVisitor extends StatelessRelShuttleImpl {
    private List<String> qualifiedName = new ArrayList<>();

    @Override
    public RelNode visit(TableScan scan) {
      qualifiedName = scan.getTable().getQualifiedName();
      return super.visit(scan);
    }

    public List<String> getQualifiedName() {
      return qualifiedName;
    }
  }
}
