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
package com.dremio.exec.planner.cost;

import com.dremio.exec.planner.common.FlattenRelBase;
import com.dremio.exec.planner.common.JdbcRelBase;
import com.dremio.exec.planner.common.JoinRelBase;
import com.dremio.exec.planner.common.LimitRelBase;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.physical.AggregatePrel;
import com.dremio.exec.planner.physical.BridgeReaderPrel;
import com.dremio.exec.planner.physical.BroadcastExchangePrel;
import com.dremio.exec.planner.physical.FlattenPrel;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.ScanPrelBase;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.store.ScanFilter;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.deltalake.DeltaLakeCommitLogScanPrel;
import com.dremio.exec.store.deltalake.DeltaLakeHistoryScanTableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.exec.store.dfs.RowCountEstimator;
import com.dremio.exec.store.iceberg.IcebergManifestListPrel;
import com.dremio.exec.store.mfunctions.TableFilesFunctionTableMetadata;
import com.dremio.exec.store.sys.statistics.StatisticsService;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rules.MultiJoin;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.NumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelMdRowCount extends org.apache.calcite.rel.metadata.RelMdRowCount {
  private static final Logger logger = LoggerFactory.getLogger(RelMdRowCount.class);
  private static final RelMdRowCount INSTANCE = new RelMdRowCount(StatisticsService.NO_OP);
  private static final Double decreaseSelectivityForInexactFilters = 0.999;
  private static final Double aggregateUpperBoundFactor = 0.9;
  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(BuiltInMethod.ROW_COUNT.method, INSTANCE);

  private final StatisticsService statisticsService;
  private boolean isNoOp;

  public RelMdRowCount(StatisticsService statisticsService) {
    this.statisticsService = statisticsService;
    this.isNoOp = statisticsService == StatisticsService.NO_OP;
  }

  @Override
  public Double getRowCount(Aggregate rel, RelMetadataQuery mq) {
    ImmutableBitSet groupKey = rel.getGroupSet();
    if (groupKey.isEmpty()) {
      return 1.0;
    } else if (!DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)
        || !isDistinctCountStatCollected(mq, rel.getInput(), rel.getGroupSet())) {
      return rel.estimateRowCount(mq);
    }

    if (rel instanceof AggregatePrel
        && ((AggregatePrel) rel).getOperatorPhase() == AggregatePrel.OperatorPhase.PHASE_2of2) {
      return mq.getRowCount(rel.getInput());
    }

    try {
      Double distinctRowCount = mq.getDistinctRowCount(rel.getInput(), groupKey, null);
      if (distinctRowCount == null) {
        return rel.estimateRowCount(mq);
      }

      Double rowCount = (double) distinctRowCount * (double) rel.getGroupSets().size();
      if (rowCount >= mq.getRowCount(rel.getInput()) * aggregateUpperBoundFactor) {
        // our estimation has failed completely
        return rel.estimateRowCount(mq);
      }
      return rowCount;
    } catch (Exception ex) {
      logger.trace("Failed to get row count of aggregate. Fallback to default estimation", ex);
      return rel.estimateRowCount(mq);
    }
  }

  @Override
  public Double getRowCount(Join rel, RelMetadataQuery mq) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      Double rowCount = estimateJoinRowCountWithStatistics(rel, mq);
      if (rowCount != null) {
        return rowCount;
      }
    }
    return estimateRowCount(rel, mq);
  }

  public Double getRowCount(BridgeReaderPrel rel, RelMetadataQuery mq) {
    return rel.estimateRowCount(mq);
  }

  private Double getDistinctCountForJoinChild(
      RelMetadataQuery mq, RelNode rel, ImmutableBitSet cols) {
    if (isDistinctCountStatCollected(mq, rel, cols) || isKey(rel, cols, mq)) {
      return mq.getDistinctRowCount(rel, cols, null);
    }
    return null;
  }

  private boolean isRowCountStatCollected(RelMetadataQuery mq, RelNode rel) {
    RelOptTable tableOrigin = mq.getTableOrigin(rel);
    if (tableOrigin == null
        || tableOrigin.getQualifiedName() == null
        || DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      return false;
    }
    return (statisticsService.getRowCount(new NamespaceKey(tableOrigin.getQualifiedName()))
        != null);
  }

  private boolean isDistinctCountStatCollected(
      RelMetadataQuery mq, RelNode rel, ImmutableBitSet cols) {
    return cols.asList().stream()
        .anyMatch(
            col -> {
              Set<RelColumnOrigin> columnOrigins = mq.getColumnOrigins(rel, col);
              if (columnOrigins != null) {
                for (RelColumnOrigin columnOrigin : columnOrigins) {
                  final RelOptTable originTable = columnOrigin.getOriginTable();
                  final List<String> fieldNames = originTable.getRowType().getFieldNames();
                  final String columnName = fieldNames.get(columnOrigin.getOriginColumnOrdinal());
                  if (statisticsService.getNDV(
                          columnName, new NamespaceKey(originTable.getQualifiedName()))
                      != null) {
                    return true;
                  }
                }
              }
              return false;
            });
  }

  public Double estimateJoinRowCountWithStatistics(Join rel, RelMetadataQuery mq) {
    final RexNode condition = rel.getCondition();
    if (condition.isAlwaysTrue()) {
      return null;
    }

    final List<Integer> leftKeys = new ArrayList<>();
    final List<Integer> rightKeys = new ArrayList<>();
    final RexNode remaining =
        RelOptUtil.splitJoinCondition(
            rel.getLeft(), rel.getRight(), condition, leftKeys, rightKeys, new ArrayList<>());
    final ImmutableBitSet.Builder leftBuilder = ImmutableBitSet.builder();
    final ImmutableBitSet.Builder rightBuilder = ImmutableBitSet.builder();
    leftKeys.forEach(leftBuilder::set);
    rightKeys.forEach(rightBuilder::set);
    final ImmutableBitSet leftCols = leftBuilder.build();
    final ImmutableBitSet rightCols = rightBuilder.build();

    final RelNode left = rel.getLeft();
    final RelNode right = rel.getRight();
    final Double leftNdv = getDistinctCountForJoinChild(mq, left, leftCols);
    final Double rightNdv = getDistinctCountForJoinChild(mq, right, rightCols);
    final Double leftRowCount = mq.getRowCount(left);
    final Double rightRowCount = mq.getRowCount(right);
    PlannerSettings settings = PrelUtil.getSettings(rel.getCluster());
    boolean semiJoinCosting = settings.semiJoinCosting();
    new_join:
    if (semiJoinCosting) {
      try {
        RelNode dim;
        ImmutableBitSet dimKeys;
        Double dimNdv;

        if (leftRowCount >= rightRowCount) {
          dim = right;
          dimKeys = rightCols;
          dimNdv = rightNdv;
        } else {
          dim = left;
          dimKeys = leftCols;
          dimNdv = leftNdv;
        }

        if (!isKey(dim, dimKeys, mq) && !mq.areColumnsUnique(dim, dimKeys)) {
          break new_join;
        }

        double filterMinSelectivityEstimateFactor =
            settings.getFilterMinSelectivityEstimateFactor();
        Double dimPop = mq.getPopulationSize(dim, dimKeys);
        Double selectivity = NumberUtil.divide(dimNdv, dimPop);
        if (selectivity == null) {
          break new_join;
        }
        Double effectiveSelectivity =
            filterMinSelectivityEstimateFactor
                + (selectivity
                    * mq.getSelectivity(rel, remaining)
                    * (1 - filterMinSelectivityEstimateFactor));
        return leftRowCount * effectiveSelectivity;
      } catch (Exception e) {
        logger.trace("Exception while computing semijoin selectivity", e);
      }
    }

    if (leftNdv == null
        || rightNdv == null
        || leftNdv == 0
        || rightNdv == 0
        || leftRowCount == null
        || rightRowCount == null) {
      if (!isRowCountStatCollected(mq, right) || !isRowCountStatCollected(mq, left)) {
        return estimateRowCount(rel, mq);
      }
      // fallback to largest estimate
      return RelMdUtil.getJoinRowCount(mq, rel, condition);
    }

    final Double selectivity = mq.getSelectivity(rel, remaining);
    double remainingSelectivity = selectivity == null ? 1.0D : selectivity;

    final double minNdv = Math.min(leftNdv, rightNdv);
    double leftSelectivity = (minNdv / leftNdv) * remainingSelectivity;
    double rightSelectivity = (minNdv / rightNdv) * remainingSelectivity;
    double innerJoinCardinality =
        ((minNdv * leftRowCount * rightRowCount) / (leftNdv * rightNdv)) * remainingSelectivity;
    double leftMismatchCount = (1 - leftSelectivity) * leftRowCount;
    double rightMismatchCount = (1 - rightSelectivity) * rightRowCount;
    switch (rel.getJoinType()) {
      case INNER:
        if (leftNdv * rightNdv == 0) {
          return null;
        }
        return innerJoinCardinality;
      case LEFT:
        double rightMatches = rightRowCount / rightNdv;
        return (leftSelectivity * leftRowCount * rightMatches) + leftMismatchCount;
      case RIGHT:
        double leftMatches = leftRowCount / leftNdv;
        return (rightSelectivity * rightRowCount * leftMatches) + rightMismatchCount;
      case FULL:
        return innerJoinCardinality + leftMismatchCount + rightMismatchCount;
      default:
        return null;
    }
  }

  public static boolean isKey(RelNode dim, ImmutableBitSet dimKeys, RelMetadataQuery mq) {
    List<RelColumnOrigin> columnOrigins =
        dimKeys.asList().stream()
            .map(k -> mq.getColumnOrigin(dim, k))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (columnOrigins.size() != dimKeys.cardinality()) {
      return false;
    }
    Set<RelOptTable> tables =
        columnOrigins.stream()
            .filter(Objects::nonNull)
            .map(RelColumnOrigin::getOriginTable)
            .collect(Collectors.toSet());
    if (tables.size() != 1) {
      return false;
    }
    RelOptTable table = Iterables.getOnlyElement(tables);
    ImmutableBitSet.Builder keyBuilder = ImmutableBitSet.builder();
    columnOrigins.forEach(o -> keyBuilder.set(o.getOriginColumnOrdinal()));
    return table != null && table.isKey(keyBuilder.build());
  }

  /**
   * DX-35733: Need to better estimate join row count for self joins, which is usually not a key-
   * foreign key join, so for self joins we adopt Calcite's default implementation.
   *
   * <p>DX-3859: Need to make sure that join row count is calculated in a reasonable manner.
   * Calcite's default implementation is leftRowCount * rightRowCount * discountBySelectivity, which
   * is too large (cartesian join). Since we do not support cartesian join, by default we assume a
   * join is key-foreign key join. {@link #estimateForeignKeyJoinRowCount(Join, RelMetadataQuery)}
   */
  public static double estimateRowCount(Join rel, RelMetadataQuery mq) {
    final PlannerSettings plannerSettings =
        PrelUtil.getPlannerSettings(rel.getCluster().getPlanner());
    if (isSelfJoin(rel, mq)
        && plannerSettings != null
        && plannerSettings.isNewSelfJoinCostEnabled()) {
      // Calcite's default implementation
      // factor to reduce rowCount of hash self joins so that they are still preferred over NLJ
      double selfJoinFactor = plannerSettings.getSelfJoinRowCountFactor();
      return Math.max(
          estimateForeignKeyJoinRowCount(rel, mq),
          selfJoinFactor * RelMdUtil.getJoinRowCount(mq, rel, rel.getCondition()));
    } else {
      // Dremio's default implementation
      return estimateForeignKeyJoinRowCount(rel, mq);
    }
  }

  /** A join is self join if all join keys are originated from the same table. */
  private static boolean isSelfJoin(Join rel, RelMetadataQuery mq) {
    // Find left keys and right keys of equi-join condition
    List<Integer> leftKeys = new ArrayList<>();
    List<Integer> rightKeys = new ArrayList<>();
    RelOptUtil.splitJoinCondition(
        rel.getLeft(), rel.getRight(), rel.getCondition(), leftKeys, rightKeys, new ArrayList<>());
    if (leftKeys.isEmpty()) {
      return false;
    }

    // Check each pair of join key
    for (int i = 0; i < leftKeys.size(); ++i) {
      int leftKey = leftKeys.get(i);
      int rightKey = rightKeys.get(i);
      RelColumnOrigin leftColumnOrigin = mq.getColumnOrigin(rel.getLeft(), leftKey);
      RelColumnOrigin rightColumnOrigin = mq.getColumnOrigin(rel.getRight(), rightKey);
      if (leftColumnOrigin == null || rightColumnOrigin == null) {
        return false;
      }
      List<String> leftTableName = leftColumnOrigin.getOriginTable().getQualifiedName();
      List<String> rightTableName = rightColumnOrigin.getOriginTable().getQualifiedName();
      if (!leftTableName.equals(rightTableName)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Estimate join row count for a key-foreign key join, basically just take the maximum of the two
   * join input row counts.
   */
  private static Double estimateForeignKeyJoinRowCount(Join rel, RelMetadataQuery mq) {
    double rightJoinFactor = 1.0;

    RexNode condition = rel.getCondition();
    if (condition.isAlwaysTrue()) {
      // Cartesian join is only supported for NLJ. If join type is right, make it more expensive
      if (rel.getJoinType() == JoinRelType.RIGHT) {
        rightJoinFactor = 2.0;
      }
      return RelMdUtil.getJoinRowCount(mq, rel, condition) * rightJoinFactor;
    }

    final PlannerSettings plannerSettings =
        PrelUtil.getPlannerSettings(rel.getCluster().getPlanner());
    double filterMinSelectivityEstimateFactor =
        plannerSettings == null
            ? PlannerSettings.DEFAULT_FILTER_MIN_SELECTIVITY_ESTIMATE_FACTOR
            : plannerSettings.getFilterMinSelectivityEstimateFactor();
    double filterMaxSelectivityEstimateFactor =
        plannerSettings == null
            ? PlannerSettings.DEFAULT_FILTER_MAX_SELECTIVITY_ESTIMATE_FACTOR
            : plannerSettings.getFilterMaxSelectivityEstimateFactor();

    final RexNode remaining;
    if (rel instanceof JoinRelBase) {
      remaining = ((JoinRelBase) rel).getRemaining();
    } else {
      remaining =
          RelOptUtil.splitJoinCondition(
              rel.getLeft(),
              rel.getRight(),
              condition,
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>());
    }

    double selectivity = mq.getSelectivity(rel, remaining);
    if (!remaining.isAlwaysFalse()) {
      // Cap selectivity at filterMinSelectivityEstimateFactor unless it is always FALSE
      if (selectivity < filterMinSelectivityEstimateFactor) {
        selectivity = filterMinSelectivityEstimateFactor;
      }
    }

    if (!remaining.isAlwaysTrue()) {
      // Cap selectivity at filterMaxSelectivityEstimateFactor unless it is always TRUE
      if (selectivity > filterMaxSelectivityEstimateFactor) {
        selectivity = filterMaxSelectivityEstimateFactor;
      }
      // Make right join more expensive for inequality join condition (logical phase)
      if (rel.getJoinType() == JoinRelType.RIGHT) {
        rightJoinFactor = 2.0;
      }
    }

    return selectivity
        * Math.max(mq.getRowCount(rel.getLeft()), mq.getRowCount(rel.getRight()))
        * rightJoinFactor;
  }

  public Double getRowCount(MultiJoin rel, RelMetadataQuery mq) {
    if (rel.getJoinFilter().isAlwaysTrue()
        && RexUtil.composeConjunction(
                rel.getCluster().getRexBuilder(), rel.getOuterJoinConditions(), false)
            .isAlwaysTrue()) {
      double rowCount = 1;
      for (RelNode input : rel.getInputs()) {
        rowCount *= mq.getRowCount(input);
      }
      return rowCount;
    } else {
      double max = 1;
      for (RelNode input : rel.getInputs()) {
        max = Math.max(max, mq.getRowCount(input));
      }
      return max;
    }
  }

  public Double getRowCount(FlattenRelBase flatten, RelMetadataQuery mq) {
    return flatten.estimateRowCount(mq);
  }

  public Double getRowCount(FlattenPrel flatten, RelMetadataQuery mq) {
    return flatten.estimateRowCount(mq);
  }

  public Double getRowCount(LimitRelBase limit, RelMetadataQuery mq) {
    return limit.estimateRowCount(mq);
  }

  public Double getRowCount(JdbcRelBase jdbc, RelMetadataQuery mq) {
    return jdbc.getSubTree().estimateRowCount(mq);
  }

  public Double getRowCount(BroadcastExchangePrel rel, RelMetadataQuery mq) {
    return rel.estimateRowCount(mq);
  }

  @Override
  public Double getRowCount(Filter rel, RelMetadataQuery mq) {
    return rel.estimateRowCount(mq);
  }

  public Double getRowCount(ScanRelBase rel, RelMetadataQuery mq) {
    if (rel instanceof RowCountEstimator) {
      return ((RowCountEstimator) rel).getEstimateRowCountFn().apply(mq);
    }

    try {
      double splitRatio =
          rel.getTableMetadata() != null ? rel.getTableMetadata().getSplitRatio() : 1.0d;
      double pruneFilterDiscountFactor = 1.0d;
      if (rel instanceof FilterableScan) {
        FilterableScan filterableScan = ((FilterableScan) rel);
        if (filterableScan.getSurvivingRowCount() != null) {
          return rel.getFilterReduction()
              * splitRatio
              * ((FilterableScan) rel).getSurvivingRowCount()
              * rel.getObservedRowcountAdjustment();
        } else if (filterableScan.getPartitionFilter() != null) {
          final PlannerSettings plannerSettings =
              PrelUtil.getPlannerSettings(rel.getCluster().getPlanner());
          pruneFilterDiscountFactor =
              plannerSettings == null
                  ? PlannerSettings.DEFAULT_PARTITION_FILTER_FACTOR
                  : plannerSettings.getPartitionFilterFactor();
        }
      }

      if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
        return pruneFilterDiscountFactor
            * getRowCount((TableScan) rel, mq)
            * splitRatio
            * rel.getObservedRowcountAdjustment();
      }

      double rowCount = rel.getTable().getRowCount();
      if (DremioRelMdUtil.isRowCountStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
        Double rowCountFromStat = getRowCountFromTableMetadata(rel);
        if (rowCountFromStat != null) {
          rowCount = rowCountFromStat;
        }
      }

      double partitionFilterFactor = 1.0d;
      if ((rel instanceof FilterableScan) && ((FilterableScan) rel).getPartitionFilter() != null) {
        final PlannerSettings plannerSettings =
            PrelUtil.getPlannerSettings(rel.getCluster().getPlanner());
        partitionFilterFactor *=
            plannerSettings == null
                ? PlannerSettings.DEFAULT_PARTITION_FILTER_FACTOR
                : plannerSettings.getPartitionFilterFactor();
      }

      return rel.getFilterReduction()
          * rowCount
          * pruneFilterDiscountFactor
          * splitRatio
          * rel.getObservedRowcountAdjustment()
          * partitionFilterFactor;
    } catch (NamespaceException ex) {
      logger.trace(
          "Failed to get split ratio from table metadata, {}", rel.getTableMetadata().getName());
      throw Throwables.propagate(ex);
    }
  }

  private Double getSelectivityFromTableScanWithScanFilter(
      TableScan rel, RelMetadataQuery mq, ScanFilter scanFilter) {
    Double selectivity = mq.getSelectivity(rel, scanFilter.getExactRexFilter());
    if (scanFilter.getExactRexFilter() == null
        || !scanFilter.getExactRexFilter().equals(scanFilter.getRexFilter())) {
      // We make the selectivity with inExact Filters lower so that the planner chooses the plan
      // with the InExact Filters pushed to the TableScan
      // ToDo: Handle this part in TableScan costing
      selectivity = selectivity * decreaseSelectivityForInexactFilters;
    }
    return selectivity;
  }

  public Double getRowCount(DeltaLakeCommitLogScanPrel rel, RelMetadataQuery mq) {
    return rel.estimateRowCount(mq);
  }

  public Double getRowCount(IcebergManifestListPrel rel, RelMetadataQuery mq) {
    return rel.estimateRowCount(mq);
  }

  @Override
  public Double getRowCount(TableScan rel, RelMetadataQuery mq) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      Double rowCount = getRowCountFromTableMetadata(rel);
      double selectivity = 1.0;

      if (rel instanceof FilterableScan) {
        FilterableScan filterableScan = (FilterableScan) rel;
        if (filterableScan.getFilter() != null) { // apply filter selectivity
          selectivity *=
              getSelectivityFromTableScanWithScanFilter(
                  rel, mq, ((FilterableScan) rel).getFilter());
        } else if (filterableScan.getPartitionFilter()
            != null) { // apply partition filter selectivity
          // todo DX-38641 - get selectivity through stat
          final PlannerSettings plannerSettings =
              PrelUtil.getPlannerSettings(rel.getCluster().getPlanner());
          selectivity *=
              plannerSettings == null
                  ? PlannerSettings.DEFAULT_PARTITION_FILTER_FACTOR
                  : plannerSettings.getPartitionFilterFactor();
        }
      } else if (rel instanceof ScanPrelBase
          && ((ScanPrelBase) rel).hasFilter()
          && ((ScanPrelBase) rel).getFilter() != null) {
        selectivity =
            getSelectivityFromTableScanWithScanFilter(rel, mq, ((ScanPrelBase) rel).getFilter());
      }

      if (rowCount == null) {
        // we do not have correct estimates so use the lowerBound value without stat
        // ToDO : Because of FILTER_MAX_SELECTIVITY_ESTIMATE_FACTOR being different with stat on,
        //  sometimes we underestimate with stats on and not collected
        double filterReduction = 1;
        if (rel instanceof ScanRelBase) {
          filterReduction = ((ScanRelBase) rel).getFilterReduction();
        }
        return rel.getTable().getRowCount() * filterReduction;
      } else {
        return selectivity * rowCount;
      }
    }

    return rel.estimateRowCount(mq);
  }

  public Double getRowCount(TableFunctionPrel rel, RelMetadataQuery mq) {
    // Metadata functions have this information with tableMetadata.
    // Does it require any other factors for consideration?
    if (rel.getTableMetadata() instanceof TableFilesFunctionTableMetadata
        || rel.getTableMetadata() instanceof DeltaLakeHistoryScanTableMetadata) {
      return (double) rel.getTableMetadata().getApproximateRecordCount();
    }
    return rel.getEstimateRowCountFn().apply(mq);
  }

  private Double getRowCountFromTableMetadata(RelNode rel) {
    TableMetadata tableMetadata = null;
    if (rel instanceof TableFunctionPrel) {
      tableMetadata = ((TableFunctionPrel) rel).getTableMetadata();
    } else if (rel instanceof ScanRelBase) {
      tableMetadata = ((ScanRelBase) rel).getTableMetadata();
    }

    if (tableMetadata == null) {
      return null;
    }

    try {
      Long rowCount = statisticsService.getRowCount(tableMetadata.getName());
      if (rowCount != null) {
        return rowCount.doubleValue();
      } else {
        return null;
      }
    } catch (Exception ex) {
      logger.trace("Failed to get row count. Fallback to default estimation", ex);
      return null;
    }
  }
}
