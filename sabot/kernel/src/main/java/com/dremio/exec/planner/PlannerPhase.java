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
package com.dremio.exec.planner;

import static com.dremio.exec.planner.physical.PlannerSettings.AGG_PUSH_DOWN_MERGE_RULE;
import static com.dremio.exec.planner.physical.PlannerSettings.AGG_PUSH_DOWN_PHASE;
import static com.dremio.exec.planner.physical.PlannerSettings.AGG_PUSH_DOWN_SIMPLE_JOIN_RULE;

import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.logical.AggregateRule;
import com.dremio.exec.planner.logical.BridgeExchangePrule;
import com.dremio.exec.planner.logical.BridgeReaderPrule;
import com.dremio.exec.planner.logical.CopyIntoTableRule;
import com.dremio.exec.planner.logical.CorrelateRule;
import com.dremio.exec.planner.logical.CountOnScanToValuesRule;
import com.dremio.exec.planner.logical.DremioAggregateReduceFunctionsRule;
import com.dremio.exec.planner.logical.DremioExpandDistinctAggregatesRule;
import com.dremio.exec.planner.logical.DremioRelFactories;
import com.dremio.exec.planner.logical.DremioSortMergeRule;
import com.dremio.exec.planner.logical.EmptyRule;
import com.dremio.exec.planner.logical.EmptyValuesRule;
import com.dremio.exec.planner.logical.EnhancedFilterJoinRule;
import com.dremio.exec.planner.logical.EnhancedFilterJoinRuleV2;
import com.dremio.exec.planner.logical.EnhancedFilterJoinRuleV3;
import com.dremio.exec.planner.logical.ExpansionDrule;
import com.dremio.exec.planner.logical.FilterRule;
import com.dremio.exec.planner.logical.FlattenRule;
import com.dremio.exec.planner.logical.InClauseCommonSubexpressionEliminationRule;
import com.dremio.exec.planner.logical.JoinBooleanRewriteRule;
import com.dremio.exec.planner.logical.JoinFilterCanonicalizationRule;
import com.dremio.exec.planner.logical.JoinNormalizationRule;
import com.dremio.exec.planner.logical.JoinRule;
import com.dremio.exec.planner.logical.LimitRule;
import com.dremio.exec.planner.logical.MergeProjectForFlattenRule;
import com.dremio.exec.planner.logical.MergeProjectRule;
import com.dremio.exec.planner.logical.ProjectRule;
import com.dremio.exec.planner.logical.PushFilterPastExpansionRule;
import com.dremio.exec.planner.logical.PushFilterPastFlattenrule;
import com.dremio.exec.planner.logical.PushFilterPastProjectRule;
import com.dremio.exec.planner.logical.PushJoinFilterIntoProjectRule;
import com.dremio.exec.planner.logical.PushProjectForFlattenIntoScanRule;
import com.dremio.exec.planner.logical.PushProjectForFlattenPastProjectRule;
import com.dremio.exec.planner.logical.PushProjectIntoFilesystemScanRule;
import com.dremio.exec.planner.logical.PushProjectIntoScanRule;
import com.dremio.exec.planner.logical.PushProjectPastFlattenRule;
import com.dremio.exec.planner.logical.RemoveEmptyScansRule;
import com.dremio.exec.planner.logical.RollupWithBridgeExchangeRule;
import com.dremio.exec.planner.logical.SimpleFilterJoinRule;
import com.dremio.exec.planner.logical.SortRule;
import com.dremio.exec.planner.logical.TableModifyRule;
import com.dremio.exec.planner.logical.TableOptimizeRule;
import com.dremio.exec.planner.logical.UnionAllRule;
import com.dremio.exec.planner.logical.VacuumCatalogRule;
import com.dremio.exec.planner.logical.VacuumTableRule;
import com.dremio.exec.planner.logical.ValuesRule;
import com.dremio.exec.planner.logical.WindowRule;
import com.dremio.exec.planner.logical.rule.LogicalAggregateGroupKeyFixRule;
import com.dremio.exec.planner.logical.rule.MinusToJoin;
import com.dremio.exec.planner.physical.CopyIntoTablePrule;
import com.dremio.exec.planner.physical.EmptyPrule;
import com.dremio.exec.planner.physical.FileSystemTableModifyPrule;
import com.dremio.exec.planner.physical.FilterPrule;
import com.dremio.exec.planner.physical.FlattenPrule;
import com.dremio.exec.planner.physical.HashAggPrule;
import com.dremio.exec.planner.physical.HashJoinPrule;
import com.dremio.exec.planner.physical.IncrementalRefreshByPartitionWriterPrule;
import com.dremio.exec.planner.physical.LimitPrule;
import com.dremio.exec.planner.physical.LimitUnionExchangeTransposeRule;
import com.dremio.exec.planner.physical.MergeJoinPrule;
import com.dremio.exec.planner.physical.NestedLoopJoinPrule;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.ProjectPrule;
import com.dremio.exec.planner.physical.PushLimitToTopN;
import com.dremio.exec.planner.physical.ResultWriterPrule;
import com.dremio.exec.planner.physical.SamplePrule;
import com.dremio.exec.planner.physical.SampleToLimitPrule;
import com.dremio.exec.planner.physical.ScreenPrule;
import com.dremio.exec.planner.physical.SortConvertPrule;
import com.dremio.exec.planner.physical.SortPrule;
import com.dremio.exec.planner.physical.StreamAggPrule;
import com.dremio.exec.planner.physical.UnionAllPrule;
import com.dremio.exec.planner.physical.ValuesPrule;
import com.dremio.exec.planner.physical.WindowPrule;
import com.dremio.exec.planner.physical.WriterPrule;
import com.dremio.exec.planner.physical.rule.AddFilterWindowBelowExchangeRule;
import com.dremio.exec.planner.physical.rule.FilterNestedLoopJoinPRule;
import com.dremio.exec.planner.physical.rule.FilterProjectTransposePRule;
import com.dremio.exec.planner.physical.rule.MergeProjectsPRule;
import com.dremio.exec.planner.physical.rule.computation.HashJoinComputationExtractionRule;
import com.dremio.exec.planner.physical.rule.computation.NestedLoopJoinComputationExtractionRule;
import com.dremio.exec.planner.rules.DremioCoreRules;
import com.dremio.exec.planner.tablefunctions.ExternalQueryScanPrule;
import com.dremio.exec.planner.tablefunctions.ExternalQueryScanRule;
import com.dremio.exec.planner.transpose.FilterFlattenTransposeRule;
import com.dremio.exec.planner.transpose.FilterWindowTransposeRule;
import com.dremio.exec.store.mfunctions.MFunctionQueryScanPrule;
import com.dremio.exec.store.mfunctions.MFunctionQueryScanRule;
import com.dremio.exec.tablefunctions.clusteringinfo.ClusteringInfoPrule;
import com.dremio.exec.tablefunctions.clusteringinfo.ClusteringInfoRule;
import com.dremio.exec.tablefunctions.copyerrors.CopyErrorsPrule;
import com.dremio.exec.tablefunctions.copyerrors.CopyErrorsRule;
import com.dremio.options.OptionResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.volcano.AbstractConverter.ExpandConversionRule;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.DremioLoptOptimizeJoinRule;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;

public enum PlannerPhase {
  ARRAY_UNNEST_REWRITE("Array and Unnest Rewrite") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  SPECIAL_ENTITY_EXPANSION("Special Entity Expansion") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  SPECIAL_PULL_UP("Special Pull Up") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  ENTITY_EXPANSION("Entity Expansion") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  DEFAULT_HASH_MATCHING("Default Hash Matching") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  DEFAULT_RAW_MATCHING("Default Raw Matching") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }

    @Override
    public boolean forceVerbose() {
      return true;
    }
  },

  AGGREGATE_REWRITE("Aggregate Rewrite") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  OPERATOR_EXPANSION("Operator Expansion") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  JDBC_PUSHDOWN("JDBC Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      final ImmutableList.Builder<RelOptRule> rules = ImmutableList.builder();
      rules.add(DremioCoreRules.CALCITE_AGG_REDUCE_FUNCTIONS_NO_REDUCE_SUM);
      if (context
          .getPlannerSettings()
          .getOptions()
          .getOption(PlannerSettings.JDBC_PUSH_DOWN_PLUS)) {
        rules.add(
            SimpleFilterJoinRule.CALCITE_INSTANCE,
            DremioCoreRules.JOIN_CONDITION_PUSH_CALCITE_RULE,
            PushFilterPastProjectRule.CALCITE_INSTANCE);
      }

      return RuleSets.ofList(rules.build());
    }
  },

  // fake for reporting purposes.
  FIELD_TRIMMING("Field Trimming") {

    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new UnsupportedOperationException();
    }
  },

  FLATTEN_PUSHDOWN("Flatten Function Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
      if (context.getPlannerSettings().pushArrayColumnsIntoScan()) {
        b.add(PushProjectForFlattenIntoScanRule.INSTANCE);
      } else {
        b.add(PushProjectForFlattenIntoScanRule.PUSH_ONLY_FIELD_ACCESS_INSTANCE);
      }
      b.add(
          PushProjectPastFlattenRule.INSTANCE,
          PushProjectForFlattenPastProjectRule.INSTANCE,
          MergeProjectForFlattenRule.INSTANCE,
          DremioCoreRules.PUSH_PROJECT_PAST_FILTER_INSTANCE,
          PushFilterPastProjectRule.INSTANCE,
          DremioCoreRules.PUSH_PROJECT_PAST_JOIN_RULE);
      return RuleSets.ofList(b.build());
    }
  },

  NESTED_SCHEMA_PROJECT_PUSHDOWN("Nested-Schema Project Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      return RuleSets.ofList(
          DremioCoreRules.PUSH_PROJECT_PAST_FILTER_LOGICAL_INSTANCE,
          DremioCoreRules.PUSH_PROJECT_PAST_JOIN_RULE_WITH_EXPR_JOIN,
          MergeProjectRule.LOGICAL_INSTANCE);
    }
  },

  PROJECT_PULLUP("Project Pullup") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      return RuleSets.ofList(
          CoreRules.FILTER_PROJECT_TRANSPOSE,
          DremioCoreRules.JOIN_PROJECT_TRANSPOSE_LEFT,
          DremioCoreRules.JOIN_PROJECT_TRANSPOSE_RIGHT,
          CoreRules.AGGREGATE_PROJECT_MERGE,
          DremioCoreRules.FILTER_MERGE_CRULE_ALLOW_CORRELATIONS,
          CoreRules.PROJECT_MERGE,
          DremioCoreRules.UNION_MERGE_RULE);
    }
  },

  PROJECT_PUSHDOWN("Project Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      if (context.getPlannerSettings().pushArrayColumnsIntoScan()) {
        return RuleSets.ofList(PushProjectIntoScanRule.INSTANCE);
      }
      return RuleSets.ofList(PushProjectIntoScanRule.PUSH_ONLY_FIELD_ACCESS_INSTANCE);
    }
  },

  FILTER_CONSTANT_RESOLUTION_PUSHDOWN("Filter Constant Resolution Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
      PlannerSettings ps = context.getPlannerSettings();
      b.add(
          PushFilterPastProjectRule.CALCITE_NO_CHILD_CHECK,
          JoinFilterCanonicalizationRule.INSTANCE,
          DremioCoreRules.FILTER_AGGREGATE_TRANSPOSE_CALCITE_RULE,
          FilterWindowTransposeRule.INSTANCE,
          PushFilterPastExpansionRule.INSTANCE,
          DremioCoreRules.FILTER_SORT_TRANSPOSE_RULE);

      boolean enableCorrelatedFilterPushdown =
          context
              .getPlannerSettings()
              .options
              .getOption(PlannerSettings.ENABLE_CORRELATED_FILTER_PUSHDOWN);

      b.add(
          enableCorrelatedFilterPushdown
              ? DremioCoreRules.FILTER_MERGE_CRULE_ALLOW_CORRELATIONS
              : DremioCoreRules.FILTER_MERGE_RULE_DISALLOW_CORRELATIONS);

      b.add(
          enableCorrelatedFilterPushdown
              ? DremioCoreRules.FILTER_CORRELATE_RULE_ALLOW_CORRELATION
              : DremioCoreRules.FILTER_CORRELATE_RULE_DISALLOW_CORRELATION);

      if (!enableCorrelatedFilterPushdown) {
        b.add(DremioCoreRules.FILTER_CORRELATED_FILTER_TRANSPOSE_RULE);
        b.add(DremioCoreRules.CORRELATED_FILTER_SPLIT_RULE);
      }

      if (ps.isPushFilterPastFlattenEnabled()) {
        b.add(PushFilterPastFlattenrule.INSTANCE);
      }

      if (!ps.isEnhancedFilterJoinPushdownEnabled() || ps.useEnhancedFilterJoinGuardRail()) {
        b.add(DremioCoreRules.FILTER_SET_OP_TRANSPOSE_CALCITE_RULE);
      } else {
        b.add(DremioCoreRules.FILTER_SET_OP_TRANSPOSE_RULE);
      }

      if (ps.isEnhancedFilterJoinPushdownEnabled()) {
        if (ps.useEnhancedFilterJoinGuardRail()) {
          if (ps.options.getOption(PlannerSettings.USE_ENHANCED_FILTER_JOIN_GUARDRAIL_FOR_JOIN)) {
            b.add(EnhancedFilterJoinRuleV3.WITH_FILTER);
            b.add(EnhancedFilterJoinRuleV3.NO_FILTER);
          } else {
            b.add(EnhancedFilterJoinRuleV2.WITH_FILTER);
            b.add(EnhancedFilterJoinRuleV2.NO_FILTER);
          }
        } else {
          b.add(EnhancedFilterJoinRule.WITH_FILTER);
          b.add(EnhancedFilterJoinRule.NO_FILTER);
        }
      }

      if (ps.isTransitiveFilterPushdownEnabled()) {
        // Add reduce expression rules to reduce any filters after applying transitive rule.
        if (ps.options.getOption(PlannerSettings.REDUCE_ALGEBRAIC_EXPRESSIONS)) {
          b.add(ReduceTrigFunctionsRule.INSTANCE);
        }

        if (ps.isConstantFoldingEnabled()) {
          if (ps.isTransitiveReduceProjectExpressionsEnabled()) {
            b.add(DremioCoreRules.PROJECT_REDUCE_EXPRESSIONS_RULE);
          }
          if (ps.isTransitiveReduceFilterExpressionsEnabled()) {
            b.add(DremioCoreRules.FILTER_REDUCE_EXPRESSIONS_RULE);
          }
        }
      } else {
        b.add(
            DremioCoreRules.FILTER_INTO_JOIN_CALCITE_RULE,
            DremioCoreRules.JOIN_CONDITION_PUSH_CALCITE_RULE,
            DremioCoreRules.JOIN_PUSH_EXPRESSIONS_RULE);
      }
      return RuleSets.ofList(b.build());
    }
  },

  FILESYSTEM_PROJECT_PUSHDOWN("FileSystem Project Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {

      ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
      if (context.getPlannerSettings().pushArrayColumnsIntoScan()) {
        b.add(PushProjectIntoFilesystemScanRule.INSTANCE);
      } else {
        b.add(PushProjectIntoFilesystemScanRule.PUSH_ONLY_FIELD_ACCESS_INSTANCE);
      }
      b.add(PushFilterPastProjectRule.LOGICAL_INSTANCE);
      return RuleSets.ofList(b.build());
    }
  },

  PRE_LOGICAL("Pre-Logical Filter Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
      ImmutableList<RelOptRule> commonRules = getPreLogicalCommonRules(context);
      b.addAll(commonRules);
      if (context
          .getPlannerSettings()
          .options
          .getOption(PlannerSettings.ENABLE_COUNT_STAR_OPTIMIZATION)) {
        b.add(CountOnScanToValuesRule.AGG_ON_PROJ_ON_SCAN_INSTANCE);
        b.add(CountOnScanToValuesRule.AGG_ON_SCAN_INSTANCE);
      }
      if (context
          .getPlannerSettings()
          .options
          .getOption(PlannerSettings.USE_ENHANCED_FILTER_JOIN_GUARDRAIL_FOR_JOIN)) {
        b.add(JoinFilterCanonicalizationRule.INSTANCE);
      }
      b.add(DremioCoreRules.PUSH_PROJECT_PAST_JOIN_CALCITE_RULE);
      return RuleSets.ofList(b.build());
    }
  },

  PRE_LOGICAL_TRANSITIVE("Pre-Logical Transitive Filter Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      return RuleSets.ofList(getPreLogicalCommonRules(context));
    }
  },

  POST_SUBSTITUTION("Post-substitution normalization") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      return PRE_LOGICAL.getRules(context);
    }
  },

  AGG_PUSHDOWN("Agg-Join Pushdown") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {

      OptionResolver optionResolver = context.getOptions();

      if (!optionResolver.getOption(AGG_PUSH_DOWN_PHASE)) {
        return RuleSets.ofList();
      }

      List<RelOptRule> rules = new ArrayList<>();
      rules.add(DremioCoreRules.PROJECT_REMOVE_DRULE);
      rules.add(DremioCoreRules.AGGREGATE_PROJECT_MERGE_RULE);

      if (optionResolver.getOption(AGG_PUSH_DOWN_SIMPLE_JOIN_RULE)) {
        rules.add(DremioCoreRules.AGGREGATE_JOIN_TRANSPOSE_RULE);
      }
      if (optionResolver.getOption(AGG_PUSH_DOWN_MERGE_RULE)) {
        rules.add(DremioCoreRules.AGGREGATE_MERGE_DRULE);
      }

      return RuleSets.ofList(rules);
    }
  },
  /** Initial phase of join planning */
  JOIN_PLANNING_MULTI_JOIN("Multi-Join analysis") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      // Check if multi-join optimization has been disabled

      RelOptRule joinToMultiJoinRule;

      if (context.getPlannerSettings().isJoinOptimizationEnabled()
          && context.getPlannerSettings().isExperimentalBushyJoinOptimizerEnabled()) {
        // bushy join optimizer doesn't currently handle outer joins
        joinToMultiJoinRule = DremioCoreRules.JOIN_TO_MULTIJOIN_RULE_NO_OUTER;
      } else {
        joinToMultiJoinRule = DremioCoreRules.JOIN_TO_MULTIJOIN_RULE;
      }

      return RuleSets.ofList(
          DremioCoreRules.MULTIJOIN_BOTH_PROJECTS_TRANSPOSE_RULE,
          DremioCoreRules.MULTIJOIN_LEFT_PROJECT_TRANSPOSE_RULE,
          DremioCoreRules.MULTIJOIN_RIGHT_PROJECT_TRANSPOSE_RULE,
          joinToMultiJoinRule,
          DremioCoreRules.PROJECT_MULTIJOIN_MERGE_RULE,
          DremioCoreRules.FILTER_MULTIJOIN_MERGE_RULE,
          MergeProjectRule.LOGICAL_INSTANCE,
          DremioCoreRules.PROJECT_REMOVE_DRULE,
          DremioCoreRules.FILTER_MERGE_DRULE_ALLOW_CORRELATIONS);
    }
  },

  /** Finalizing phase of join planning */
  JOIN_PLANNING_OPTIMIZATION("LOPT Join Planning") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      // add these rules because the MultiJoin produced in Multi-join analysis phase may have
      // expressions like cast,
      // but HashJoinPrule requires simple references
      ImmutableList.Builder<RelOptRule> builder = ImmutableList.<RelOptRule>builder();

      builder
          .add(DremioCoreRules.JOIN_PUSH_EXPRESSIONS_LOGICAL_RULE)
          .add(MergeProjectRule.LOGICAL_INSTANCE)
          .add(PushJoinFilterIntoProjectRule.INSTANCE);

      if (context.getPlannerSettings().isJoinPlanningProjectPushdownEnabled()) {
        builder
            .add(DremioCoreRules.PUSH_PROJECT_INPUT_REF_PAST_FILTER_LOGICAL_INSTANCE)
            .add(DremioCoreRules.PUSH_PROJECT_INPUT_REF_PAST_JOIN_RULE);
      }

      if (context.getPlannerSettings().isJoinBooleanRewriteEnabled()) {
        builder.add(JoinBooleanRewriteRule.INSTANCE);
      }

      // Check if multi-join optimization has been enabled
      if (context.getPlannerSettings().isJoinOptimizationEnabled()) {
        if (context.getPlannerSettings().isExperimentalBushyJoinOptimizerEnabled()) {
          builder.add(DremioCoreRules.MULTI_JOIN_OPTIMIZE_BUSHY_RULE);
          builder.add(DremioJoinCommuteRule.INSTANCE);
        } else {
          boolean useKey = context.getPlannerSettings().joinUseKeyForNextFactor();
          boolean rotateFactors = context.getPlannerSettings().joinRotateFactors();
          builder.add(
              DremioLoptOptimizeJoinRule.Config.DEFAULT
                  .withFindOnlyOneOrdering(false)
                  .withUseCardinalityForNextFactor(useKey)
                  .withRotateFactors(rotateFactors)
                  .withRelBuilderFactory(DremioRelFactories.LOGICAL_BUILDER)
                  .toRule());
        }
      } else {
        builder.add(DremioCoreRules.LOPT_UNOPTIMIZE_JOIN_RULE);
      }

      return RuleSets.ofList(builder.add(JoinNormalizationRule.INSTANCE).build());
    }
  },

  REDUCE_EXPRESSIONS("Reduce Expressions") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      throw new RuntimeException();
    }
  },

  LOGICAL("Logical Planning", true) {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {

      List<RelOptRule> moreRules = new ArrayList<>();

      if (context.getPlannerSettings().isTransposeProjectFilterLogicalEnabled()) {
        moreRules.add(DremioCoreRules.PUSH_PROJECT_PAST_FILTER_CALCITE_RULE);
      }

      if (context.getPlannerSettings().isFilterFlattenTransposeEnabled()) {
        moreRules.add(FilterFlattenTransposeRule.INSTANCE);
      }

      if (context.getPlannerSettings().isProjectLogicalCleanupEnabled()) {
        moreRules.add(MergeProjectRule.CALCITE_INSTANCE);
        moreRules.add(CoreRules.PROJECT_REMOVE);
      }

      moreRules.add(ExternalQueryScanRule.INSTANCE);
      moreRules.add(MFunctionQueryScanRule.INSTANCE);
      moreRules.add(CopyErrorsRule.INSTANCE);
      moreRules.add(ClusteringInfoRule.INSTANCE);

      if (context.getPlannerSettings().pushArrayColumnsIntoScan()) {
        moreRules.add(PushProjectIntoScanRule.INSTANCE);
      } else {
        moreRules.add(PushProjectIntoScanRule.PUSH_ONLY_FIELD_ACCESS_INSTANCE);
      }

      return PlannerPhase.mergedRuleSets(LOGICAL_RULE_SET, RuleSets.ofList(moreRules));
    }

    @Override
    public boolean forceVerbose() {
      return true;
    }
  },

  RELATIONAL_PLANNING("Relational Planning") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      final ImmutableList.Builder<RelOptRule> rules = ImmutableList.builder();
      rules.add(DremioAggregateReduceFunctionsRule.NO_REDUCE_SUM);

      if (context
          .getPlannerSettings()
          .getOptions()
          .getOption(PlannerSettings.JDBC_PUSH_DOWN_PLUS)) {
        rules.add(
            SimpleFilterJoinRule.LOGICAL_INSTANCE,
            DremioCoreRules.JOIN_CONDITION_PUSH_LOGICAL_RULE,
            PushFilterPastProjectRule.INSTANCE);
      }

      return RuleSets.ofList(rules.build());
    }
  },

  POST_JOIN_OPTIMIZATION("Post Join Optimization") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      final ImmutableList.Builder<RelOptRule> rules = ImmutableList.builder();
      rules.add(InClauseCommonSubexpressionEliminationRule.INSTANCE);
      rules.add(RollupWithBridgeExchangeRule.INSTANCE);
      if (context.getOptions().getOption(AGG_PUSH_DOWN_PHASE)) {
        rules
            .add(DremioCoreRules.PROJECT_REMOVE_DRULE)
            .add(DremioCoreRules.PUSH_PROJECT_INPUT_REF_PAST_FILTER_LOGICAL_INSTANCE)
            .add(DremioCoreRules.PUSH_PROJECT_INPUT_REF_PAST_JOIN_RULE);
      }
      return RuleSets.ofList(rules.build());
    }
  },

  PHYSICAL("Physical Planning") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      return PlannerPhase.getPhysicalRules(context);
    }
  },

  PHYSICAL_HEP("Physical Heuristic Planning") {
    @Override
    public RuleSet getRules(OptimizerRulesContext context) {
      ImmutableList.Builder<RelOptRule> builder = ImmutableList.builder();
      builder.add(FilterProjectTransposePRule.FILTER_PROJECT_NLJ);
      builder.add(FilterNestedLoopJoinPRule.INSTANCE);
      if (context.getPlannerSettings().options.getOption(PlannerSettings.ENABlE_PROJCT_NLJ_MERGE)) {
        builder.add(MergeProjectsPRule.PROJECT_PROJECT_JOIN);
      }
      if (context.getPlannerSettings().options.getOption(PlannerSettings.NLJ_PUSHDOWN)) {
        builder.add(NestedLoopJoinComputationExtractionRule.INSTANCE);
      }
      if (context.getPlannerSettings().options.getOption(PlannerSettings.HASH_JOIN_PUSHDOWN)) {
        builder.add(HashJoinComputationExtractionRule.INSTANCE);
      }

      if (context
          .getPlannerSettings()
          .options
          .getOption(PlannerSettings.ENABLE_FILTER_WINDOW_OPTIMIZER)) {
        builder.add(AddFilterWindowBelowExchangeRule.INSTANCE);
      }
      return RuleSets.ofList(builder.build());
    }
  };

  public final String description;
  public final boolean useMaterializations;

  PlannerPhase(String description) {
    this(description, false);
  }

  PlannerPhase(String description, boolean useMaterializations) {
    this.description = description;
    this.useMaterializations = useMaterializations;
  }

  public abstract RuleSet getRules(OptimizerRulesContext context);

  public boolean forceVerbose() {
    return false;
  }

  static ImmutableList<RelOptRule> getPreLogicalCommonRules(OptimizerRulesContext context) {
    ImmutableList.Builder<RelOptRule> b = ImmutableList.builder();
    b.add(
        DremioCoreRules.AGGREGATE_PROJECT_PULL_UP_CONSTANTS,
        LogicalAggregateGroupKeyFixRule.RULE,

        // Need to remove this rule as it has already been applied in the filter pushdown phase.
        // However, while removing this rule, some acceleration tests are failing. DX-64115
        PushFilterPastProjectRule.CALCITE_NO_CHILD_CHECK,
        CoreRules.INTERSECT_TO_DISTINCT,
        MinusToJoin.RULE,
        DremioSortMergeRule.INSTANCE,
        CoreRules.PROJECT_WINDOW_TRANSPOSE,
        CoreRules.PROJECT_SET_OP_TRANSPOSE,
        MergeProjectRule.CALCITE_INSTANCE,
        RemoveEmptyScansRule.INSTANCE);

    if (context
        .getPlannerSettings()
        .getOptions()
        .getOption(PlannerSettings.FILTER_EXTRACT_CONJUNCTIONS)) {
      b.add(ExtractCommonConjunctionInFilterRule.INSTANCE);
      b.add(ExtractCommonConjunctionInJoinRule.INSTANCE);
    }

    return b.build();
  }

  // These logical rules don't require any context, so singleton instances can be used.
  static final RuleSet LOGICAL_RULE_SET =
      RuleSets.ofList(
          ImmutableSet.<RelOptRule>builder()
              .add(

                  // remove expansion nodes when converting to logical.
                  ExpansionDrule.INSTANCE,

                  /*
                   * Aggregate optimization rules
                   */
                  CoreRules.UNION_TO_DISTINCT,
                  CoreRules.AGGREGATE_REMOVE,
                  DremioAggregateReduceFunctionsRule.INSTANCE,
                  DremioExpandDistinctAggregatesRule.INSTANCE,

                  // Add support for WHERE style joins.
                  DremioCoreRules.FILTER_INTO_JOIN_CALCITE_RULE,
                  DremioCoreRules.JOIN_CONDITION_PUSH_CALCITE_RULE,
                  DremioCoreRules.JOIN_PUSH_EXPRESSIONS_RULE,
                  // End support for WHERE style joins.

                  DremioCoreRules.FILTER_SET_OP_TRANSPOSE_CALCITE_RULE,
                  DremioCoreRules.FILTER_AGGREGATE_TRANSPOSE_CALCITE_RULE,
                  DremioCoreRules.FILTER_MERGE_CRULE_ALLOW_CORRELATIONS,

                  /*
                   * Project pushdown rules.
                   */
                  MergeProjectRule.LOGICAL_INSTANCE,

                  // Not used.
                  // SortRemoveRule.INSTANCE,

                  /*
                   * Trait Conversion Rules
                   */
                  ExpandConversionRule.INSTANCE,

                  /*
                  Rewrite flatten rules
                  This is a workaround for interactions between other rules in the logical phase
                  */
                  DremioCoreRules.REWRITE_PROJECT_TO_FLATTEN_RULE,

                  // Disabled as it causes infinite loops with MergeProjectRule,
                  // ProjectFilterTranspose (with Expression preservation) and
                  // FilterProjectTranspose
                  // PlannerPhase.PUSH_PROJECT_PAST_JOIN_CALCITE_RULE,

                  /*
                   * Crel => Drel
                   */
                  ProjectRule.INSTANCE,
                  FilterRule.INSTANCE,
                  WindowRule.INSTANCE,
                  AggregateRule.INSTANCE,
                  LimitRule.INSTANCE,
                  SortRule.INSTANCE,
                  JoinRule.INSTANCE,
                  UnionAllRule.INSTANCE,
                  ValuesRule.INSTANCE,
                  FlattenRule.INSTANCE,
                  EmptyRule.INSTANCE,
                  EmptyValuesRule.INSTANCE,
                  CorrelateRule.INSTANCE,
                  TableModifyRule.INSTANCE,
                  TableOptimizeRule.INSTANCE,
                  CopyIntoTableRule.INSTANCE,
                  VacuumTableRule.INSTANCE,
                  VacuumCatalogRule.INSTANCE)
              .build());

  static final RuleSet getPhysicalRules(OptimizerRulesContext optimizerRulesContext) {
    final List<RelOptRule> ruleList = new ArrayList<>();
    final PlannerSettings ps = optimizerRulesContext.getPlannerSettings();

    ruleList.add(BridgeExchangePrule.INSTANCE);
    ruleList.add(BridgeReaderPrule.INSTANCE);
    ruleList.add(SortConvertPrule.INSTANCE);
    ruleList.add(SortPrule.INSTANCE);
    ruleList.add(ProjectPrule.INSTANCE);
    ruleList.add(FlattenPrule.INSTANCE);
    ruleList.add(ScreenPrule.INSTANCE);
    ruleList.add(ExpandConversionRule.INSTANCE);
    ruleList.add(FilterPrule.INSTANCE);
    ruleList.add(LimitPrule.INSTANCE);
    ruleList.add(SamplePrule.INSTANCE);
    ruleList.add(SampleToLimitPrule.INSTANCE);
    ruleList.add(WriterPrule.INSTANCE);
    ruleList.add(
        ResultWriterPrule.Config.DEFAULT
            .withOptionResolver(optimizerRulesContext.getOptions())
            .toRule());
    ruleList.add(IncrementalRefreshByPartitionWriterPrule.INSTANCE);
    ruleList.add(WindowPrule.INSTANCE);
    ruleList.add(PushLimitToTopN.INSTANCE);
    ruleList.add(LimitUnionExchangeTransposeRule.INSTANCE);
    ruleList.add(UnionAllPrule.INSTANCE);
    ruleList.add(ValuesPrule.INSTANCE);
    ruleList.add(EmptyPrule.INSTANCE);
    ruleList.add(ExternalQueryScanPrule.INSTANCE);
    ruleList.add(MFunctionQueryScanPrule.INSTANCE);

    if (ps.isHashAggEnabled()) {
      ruleList.add(HashAggPrule.INSTANCE);
    }

    if (ps.isStreamAggEnabled()) {
      ruleList.add(StreamAggPrule.INSTANCE);
    }

    if (ps.isHashJoinEnabled()) {
      ruleList.add(HashJoinPrule.DIST_INSTANCE);

      if (ps.isBroadcastJoinEnabled()) {
        ruleList.add(HashJoinPrule.BROADCAST_INSTANCE);
      }
    }

    if (ps.isMergeJoinEnabled()) {
      ruleList.add(MergeJoinPrule.DIST_INSTANCE);

      if (ps.isBroadcastJoinEnabled()) {
        ruleList.add(MergeJoinPrule.BROADCAST_INSTANCE);
      }
    }

    // NLJ plans consist of broadcasting the right child, hence we need
    // broadcast join enabled.
    if (ps.isNestedLoopJoinEnabled() && ps.isBroadcastJoinEnabled()) {
      ruleList.add(NestedLoopJoinPrule.INSTANCE);
    }

    ruleList.add(new FileSystemTableModifyPrule(optimizerRulesContext));
    ruleList.add(new CopyIntoTablePrule(optimizerRulesContext));
    ruleList.add(new CopyErrorsPrule(optimizerRulesContext));
    ruleList.add(new ClusteringInfoPrule(optimizerRulesContext));

    return RuleSets.ofList(ImmutableSet.copyOf(ruleList));
  }

  public static RuleSet mergedRuleSets(RuleSet... ruleSets) {
    final ImmutableSet.Builder<RelOptRule> relOptRuleSetBuilder = ImmutableSet.builder();
    for (final RuleSet ruleSet : ruleSets) {
      relOptRuleSetBuilder.addAll(ruleSet);
    }
    return RuleSets.ofList(relOptRuleSetBuilder.build());
  }

  /** Phase names during planning */
  public static final String PLAN_REFRESH_DECISION = "Refresh Decision";

  public static final String PLAN_RESOURCES_PLANNED = "Execution Resources Planned";
  public static final String PLAN_RESOURCES_ALLOCATED = "Execution Resources Allocated";
  public static final String PLAN_CONVERTED_SCAN = "Convert Scan";
  public static final String PLAN_VALIDATED = "Validation";
  public static final String PLAN_CONVERTED_TO_REL = "Convert To Rel";
  public static final String PLAN_FIND_MATERIALIZATIONS = "Find Materializations";
  public static final String PLAN_NORMALIZED =
      "Normalize User Query Alternatives and Materializations";
  public static final String PLAN_MATCH_MATERIALIZATIONS = "Generate Replacements";
  public static final String PLAN_FINAL_PHYSICAL = "Final Physical Transformation";
}
