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

import static org.apache.calcite.plan.RelOptUtil.conjunctions;

import com.dremio.common.utils.PathUtils;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.FlattenRel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.ScanPrelBase;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.ValuesPrel;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.exec.store.sys.statistics.StatisticsService;
import com.dremio.reflection.rules.ReplacementPointer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelOptUtil.InputFinder;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableBitSet.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelMdDistinctRowCount extends org.apache.calcite.rel.metadata.RelMdDistinctRowCount {
  private static final Logger logger = LoggerFactory.getLogger(RelMdDistinctRowCount.class);

  private static final RelMdDistinctRowCount INSTANCE =
      new RelMdDistinctRowCount(StatisticsService.NO_OP);

  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(
          BuiltInMethod.DISTINCT_ROW_COUNT.method, INSTANCE);

  private final StatisticsService statisticsService;
  private boolean isNoOp;

  public RelMdDistinctRowCount(StatisticsService statisticsService) {
    this.statisticsService = statisticsService;
    this.isNoOp = statisticsService == StatisticsService.NO_OP;
  }

  public Double getDistinctRowCount(
      HepRelVertex vertex, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    return mq.getDistinctRowCount(vertex.getCurrentRel(), groupKey, predicate);
  }

  public Double getDistinctRowCount(
      ReplacementPointer pointer,
      RelMetadataQuery mq,
      ImmutableBitSet groupKey,
      RexNode predicate) {
    return mq.getDistinctRowCount(pointer.getSubTree(), groupKey, predicate);
  }

  @Override
  public Double getDistinctRowCount(
      Aggregate rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      return super.getDistinctRowCount(rel, mq, groupKey, predicate);
    } else if ((predicate == null || predicate.isAlwaysTrue()) && groupKey.isEmpty()) {
      return 1D;
    }

    final ImmutableBitSet allGroupSet = rel.getGroupSet().union(groupKey);
    return getDistinctRowCountFromEstimateRowCount(rel.getInput(), mq, allGroupSet, predicate);
  }

  @Override
  public Double getDistinctRowCount(
      Join rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if ((predicate == null || predicate.isAlwaysTrue()) && groupKey.isEmpty()) {
      return 1D;
    } else if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      return getDistinctRowCountInternal(rel, mq, groupKey, predicate);
    }
    return getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
  }

  // Relnode's distinct row count given the grouping key and predicate should depend on a few
  // things.
  // 1.  proportional to the number of records in the table
  // 2.  inversely proportional to the number of grouping keys (group by A should be fewer rows than
  // group by A, B, C)
  // 3.  proportional the filter/predicate selectivity
  static Double getDistinctRowCountFromEstimateRowCount(
      RelNode rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (isKey(rel, groupKey)) {
      return rel.estimateRowCount(mq) * RelMdUtil.guessSelectivity(predicate);
    }
    return rel.estimateRowCount(mq)
        * (1.0D - Math.pow(0.9D, (double) groupKey.cardinality()))
        * RelMdUtil.guessSelectivity(predicate);
  }

  public Double getDistinctRowCount(
      Window rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    return super.getDistinctRowCount(rel, mq, groupKey, predicate);
  }

  public Double getDistinctRowCount(
      SingleRel rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      return mq.getDistinctRowCount(rel.getInput(), groupKey, predicate);
    }
    return super.getDistinctRowCount(rel, mq, groupKey, predicate);
  }

  /**
   * Computes distinct row count for a flatten rel node. If the given predicate contains any of the
   * flatten field, and the statistics are enabled, we will just return estimate row count *
   * selectivity of the predicate.
   */
  public Double getDistinctRowCount(
      FlattenRel flatten, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(flatten.getCluster().getPlanner(), isNoOp)) {
      if (containsFlattenField(ImmutableBitSet.of(flatten.getFlattenedIndices()), predicate)) {
        return flatten.estimateRowCount(mq) * RelMdUtil.guessSelectivity(predicate);
      } else {
        return mq.getDistinctRowCount(flatten.getInput(), groupKey, predicate);
      }
    }
    return super.getDistinctRowCount(flatten, mq, groupKey, predicate);
  }

  /**
   * @param indices: Flatten indices.
   * @param predicate: predicate to check for flatten indices.
   * @return true if the predicate contains any of the flatten indices.
   */
  private boolean containsFlattenField(ImmutableBitSet indices, RexNode predicate) {
    RelOptUtil.InputFinder inputFinder = RelOptUtil.InputFinder.analyze(predicate);
    ImmutableBitSet filterBitSet = inputFinder.build();
    return indices.intersects(filterBitSet);
  }

  public Double getDistinctRowCount(
      ValuesPrel rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      if ((predicate == null || predicate.isAlwaysTrue()) && groupKey.isEmpty()) {
        return 1.0D;
      } else {
        Double selectivity = RelMdUtil.guessSelectivity(predicate);
        Double nRows = rel.estimateRowCount(mq) / 2.0D;
        return RelMdUtil.numDistinctVals(nRows, nRows * selectivity);
      }
    }
    return super.getDistinctRowCount(rel, mq, groupKey, predicate);
  }

  static Double getDistinctRowCountFromTableMetadata(
      RelNode rel,
      RelMetadataQuery mq,
      ImmutableBitSet groupKey,
      RexNode predicate,
      StatisticsService statisticsService) {
    TableMetadata tableMetadata = null;
    if (rel instanceof TableFunctionPrel) {
      tableMetadata = ((TableFunctionPrel) rel).getTableMetadata();
    } else if (rel instanceof ScanRelBase) {
      tableMetadata = ((ScanRelBase) rel).getTableMetadata();
    }

    final double selectivity = mq.getSelectivity(rel, predicate);
    final double rowCount = mq.getRowCount(rel);

    if (groupKey.length() == 0) {
      return selectivity * rowCount;
    }

    if (tableMetadata == null) {
      return getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
    }

    double estRowCnt = 1.0;
    String colName = "";
    boolean allColsHaveNDV = true;

    for (int i = 0; i < groupKey.length(); i++) {
      colName = rel.getRowType().getFieldNames().get(i);
      if (!groupKey.get(i)) {
        continue;
      }
      try {
        Long ndv = statisticsService.getNDV(colName, tableMetadata.getName());
        if (ndv != null) {
          estRowCnt *= ndv;
        } else {
          allColsHaveNDV = false;
          break;
        }
      } catch (Exception e) {
        allColsHaveNDV = false;
        break;
      }
      final double gbyColPredSel = getPredSelectivityContainingInputRef(predicate, i, mq, rel);
      /* If predicate is on group-by column, scale down the NDV by selectivity. Consider the query
       * select a, b from t where a = 10 group by a, b. Here, NDV(a) will be scaled down by SEL(a)
       * whereas NDV(b) will not.
       */
      if (gbyColPredSel > 0) {
        estRowCnt *= gbyColPredSel;
      }
    }

    if (!allColsHaveNDV) {
      if (logger.isDebugEnabled()) {
        logger.debug(
            String.format(
                "NDV not available for %s(%s). Using default rowcount for group-by %s",
                (tableMetadata != null ? tableMetadata.getName().toString() : ""),
                colName,
                groupKey.toString()));
      }
      // Could not get any NDV estima te from stats - probably stats not present for GBY cols. So
      // Guess!
      return getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
    } else {
      // Estimated NDV should not exceed number of rows after applying the filters
      if (estRowCnt
          > selectivity
              * rowCount
              * (1.0D
                  + PrelUtil.getPlannerSettings(rel.getCluster())
                      .getColumnUniquenessEstimationFactor())) {
        estRowCnt = getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
      }
      /* rowCount maybe less than NDV(different source), sanity check OR NDV not used at all */
      return estRowCnt;
    }
  }

  public Double getDistinctRowCount(
      TableFunctionPrel rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      return getDistinctRowCountFromTableMetadata(rel, mq, groupKey, predicate, statisticsService);
    }
    return super.getDistinctRowCount(rel, mq, groupKey, predicate);
  }

  public Double getDistinctRowCount(
      TableScan rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      RexNode relFilter = null;
      if (rel instanceof FilterableScan && ((FilterableScan) rel).getFilter() != null) {
        relFilter = ((FilterableScan) rel).getFilter().getExactRexFilter();
      } else if (rel instanceof ScanPrelBase
          && ((ScanPrelBase) rel).hasFilter()
          && ((ScanPrelBase) rel).getFilter() != null) {
        relFilter = ((ScanPrelBase) rel).getFilter().getExactRexFilter();
      }
      RexNode unionPreds =
          RelMdUtil.unionPreds(rel.getCluster().getRexBuilder(), predicate, relFilter);
      return Optional.ofNullable(
              getDistinctRowCountFromTableMetadata(
                  rel, mq, groupKey, unionPreds, statisticsService))
          .orElse(getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, unionPreds));
    }
    return getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
  }

  public static boolean isKey(RelNode rel, ImmutableBitSet groupKey) {
    if (!(rel instanceof ScanRelBase)) {
      return false;
    }
    ScanRelBase scan = (ScanRelBase) rel;
    if (scan.getTable() == null || scan.getTable().getQualifiedName() == null) {
      return false;
    }
    Set<String> groupColumns =
        rel.getRowType().getFieldList().stream()
            .filter(f -> groupKey.get(f.getIndex()))
            .map(f -> f.getName().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    ImmutableBitSet.Builder b = ImmutableBitSet.builder();
    scan.getTable()
        .getRowType()
        .getFieldList()
        .forEach(
            f -> {
              if (groupColumns.contains(f.getName().toLowerCase(Locale.ROOT))) {
                b.set(f.getIndex());
              }
            });
    ImmutableBitSet tableKey = b.build();
    boolean isKey = rel.getTable().isKey(tableKey);
    logger.debug(
        "{}:{} {}",
        PathUtils.constructFullPath(rel.getTable().getQualifiedName()),
        tableKey,
        isKey ? "is key" : "is not key");
    return isKey;
  }

  private Double getDistinctRowCountInternal(
      Join joinRel,
      RelMetadataQuery mq,
      ImmutableBitSet groupKey,
      RexNode
          predicate) { // Assume NDV is unaffected by the join when groupKey comes from one side of
    // the join
    // Alleviates NDV over-estimates
    Builder leftMask = ImmutableBitSet.builder();
    Builder rightMask = ImmutableBitSet.builder();
    JoinRelType joinType = joinRel.getJoinType();
    RelNode left = joinRel.getLeft();
    RelNode right = joinRel.getRight();
    RelMdUtil.setLeftRightBitmaps(groupKey, leftMask, rightMask, left.getRowType().getFieldCount());
    RexNode leftPred = null;
    RexNode rightPred = null;
    // Identify predicates which can be pushed onto the left and right sides of the join
    if (predicate != null) {
      List<RexNode> leftFilters = new ArrayList<>();
      List<RexNode> rightFilters = new ArrayList<>();
      List<RexNode> joinFilters = new ArrayList();
      List<RexNode> predList = conjunctions(predicate);
      RelOptUtil.classifyFilters(
          joinRel,
          predList,
          joinType,
          joinType == JoinRelType.INNER,
          !joinType.generatesNullsOnLeft(),
          !joinType.generatesNullsOnRight(),
          joinFilters,
          leftFilters,
          rightFilters);
      RexBuilder rexBuilder = joinRel.getCluster().getRexBuilder();
      leftPred = RexUtil.composeConjunction(rexBuilder, leftFilters, true);
      rightPred = RexUtil.composeConjunction(rexBuilder, rightFilters, true);
    }
    double distRowCount = 1;
    // int gbyCols = 0;
    //  PlannerSettings plannerSettings =
    // PrelUtil.getPlannerSettings(joinRel.getCluster().getPlanner());
    /*
     * The NDV for a multi-column GBY key past a join is determined as follows:
     * GBY(s1, s2, s3) = CNDV(s1)*CNDV(s2)*CNDV(s3)
     * where CNDV is determined as follows:
     * A) If sX is present as a join column (sX = tX) CNDV(sX) = MIN(NDV(sX), NDV(tX)) where X =1, 2, 3, etc
     * B) Otherwise, based on independence assumption CNDV(sX) = NDV(sX)
     */
    Set<ImmutableBitSet> joinFiltersSet = new HashSet<>();
    // Note: projected fields do not affect this as the join conditions are based on the join's
    // inputs and not projected fields
    for (RexNode filter : conjunctions(joinRel.getCondition())) {
      final InputFinder inputFinder = InputFinder.analyze(filter);
      ImmutableBitSet bitsSet = inputFinder.build();
      joinFiltersSet.add(bitsSet);
    }
    for (int idx = 0; idx < groupKey.length(); idx++) {
      if (groupKey.get(idx)) {
        // GBY key is present in some filter - now try options A) and B) as described above
        double ndvSGby = Double.MAX_VALUE;
        Double ndv;
        boolean presentInFilter = false;
        ImmutableBitSet sGby = getSingleGbyKey(groupKey, idx);
        if (sGby != null) {
          // If we see any NULL ndv i.e. cant process ..we bail out!
          for (ImmutableBitSet jFilter : joinFiltersSet) {
            if (jFilter.contains(sGby)) {
              presentInFilter = true;
              // Found join condition containing this GBY key. Pick min NDV across all columns in
              // this join
              for (int filterIdx : jFilter) {
                if (filterIdx < left.getRowType().getFieldCount()) {
                  ndv = mq.getDistinctRowCount(left, ImmutableBitSet.of(filterIdx), leftPred);
                  if (ndv == null) {
                    return getDistinctRowCountFromEstimateRowCount(
                        joinRel, mq, ImmutableBitSet.of(filterIdx), predicate);
                  }
                  ndvSGby = Math.min(ndvSGby, ndv);
                } else {
                  ndv =
                      mq.getDistinctRowCount(
                          right,
                          ImmutableBitSet.of(filterIdx - left.getRowType().getFieldCount()),
                          rightPred);
                  if (ndv == null) {
                    return getDistinctRowCountFromEstimateRowCount(
                        joinRel,
                        mq,
                        ImmutableBitSet.of(filterIdx - left.getRowType().getFieldCount()),
                        predicate);
                  }
                  ndvSGby = Math.min(ndvSGby, ndv);
                }
              }
              break;
            }
          }
          // Did not find it in any join condition(s)
          if (!presentInFilter) {
            for (int sidx : sGby) {
              if (sidx < left.getRowType().getFieldCount()) {
                ndv = mq.getDistinctRowCount(left, ImmutableBitSet.of(sidx), leftPred);
                if (ndv == null) {
                  return getDistinctRowCountFromEstimateRowCount(
                      joinRel, mq, ImmutableBitSet.of(sidx), predicate);
                }
                ndvSGby = ndv;
              } else {
                ndv =
                    mq.getDistinctRowCount(
                        right,
                        ImmutableBitSet.of(sidx - left.getRowType().getFieldCount()),
                        rightPred);
                if (ndv == null) {
                  return getDistinctRowCountFromEstimateRowCount(
                      joinRel,
                      mq,
                      ImmutableBitSet.of(sidx - left.getRowType().getFieldCount()),
                      predicate);
                }
                ndvSGby = ndv;
              }
            }
          }
          // Multiply NDV(s) of different GBY cols to determine the overall NDV
          distRowCount *= ndvSGby;
        }
      }
    }
    double joinRowCount = mq.getRowCount(joinRel);
    // Cap NDV to join row count
    distRowCount = Math.min(distRowCount, joinRowCount);
    return RelMdUtil.numDistinctVals(distRowCount, joinRowCount);
  }

  private ImmutableBitSet getSingleGbyKey(ImmutableBitSet groupKey, int idx) {
    if (groupKey.get(idx)) {
      return ImmutableBitSet.builder().set(idx, idx + 1).build();
    } else {
      return null;
    }
  }

  private static double getPredSelectivityContainingInputRef(
      RexNode predicate, int inputRef, RelMetadataQuery mq, RelNode rel) {
    if (predicate instanceof RexCall) {
      double sel = 0.0;
      switch (predicate.getKind()) {
        case AND:
          double andSel = 1.0;
          for (RexNode op : ((RexCall) predicate).getOperands()) {
            sel = getPredSelectivityContainingInputRef(op, inputRef, mq, rel);
            if (sel > 0) {
              andSel *= sel;
            }
          }
          return andSel;
        case OR:
          double orSel = 1.0;
          for (RexNode op : ((RexCall) predicate).getOperands()) {
            sel = getPredSelectivityContainingInputRef(op, inputRef, mq, rel);
            if (sel > 0) {
              orSel += sel;
            }
          }
          return orSel;
        default:
          for (RexNode op : ((RexCall) predicate).getOperands()) {
            if (op instanceof RexInputRef && inputRef != ((RexInputRef) op).getIndex()) {
              return -1.0;
            }
          }
          return mq.getSelectivity(rel, predicate);
      }
    } else {
      return -1.0;
    }
  }

  @Override
  public Double getDistinctRowCount(
      RelSubset rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (DremioRelMdUtil.isStatisticsEnabled(rel.getCluster().getPlanner(), isNoOp)) {
      final RelNode best = rel.getBest();
      if (best != null) {
        return mq.getDistinctRowCount(best, groupKey, predicate);
      }
      final RelNode original = rel.getOriginal();
      if (original != null) {
        return mq.getDistinctRowCount(original, groupKey, predicate);
      }
      return super.getDistinctRowCount(rel, mq, groupKey, predicate);
    }
    return super.getDistinctRowCount(rel, mq, groupKey, predicate);
  }

  @Override
  public Double getDistinctRowCount(
      Project rel, RelMetadataQuery mq, ImmutableBitSet groupKey, RexNode predicate) {
    if (predicate == null || predicate.isAlwaysTrue()) {
      if (groupKey.isEmpty()) {
        return 1D;
      }
    }
    Builder baseCols = ImmutableBitSet.builder();
    Builder projCols = ImmutableBitSet.builder();
    List<RexNode> projExprs = rel.getProjects();
    RelMdUtil.splitCols(projExprs, groupKey, baseCols, projCols);

    final List<RexNode> notPushable = new ArrayList<>();
    final List<RexNode> pushable = new ArrayList<>();
    splitFilters(
        rel,
        ImmutableBitSet.range(rel.getRowType().getFieldCount()),
        predicate,
        pushable,
        notPushable);
    final RexBuilder rexBuilder = rel.getCluster().getRexBuilder();

    // get the distinct row count of the child input, passing in the
    // columns and filters that only reference the child; convert the
    // filter to reference the children projection expressions
    RexNode childPred = RexUtil.composeConjunction(rexBuilder, pushable, true);
    RexNode modifiedPred;
    if (childPred == null) {
      modifiedPred = null;
    } else {
      modifiedPred = RelOptUtil.pushPastProject(childPred, rel);
    }
    Double distinctRowCount =
        mq.getDistinctRowCount(rel.getInput(), baseCols.build(), modifiedPred);

    if (distinctRowCount == null) {
      return null;
    } else if (!notPushable.isEmpty()) {
      RexNode preds = RexUtil.composeConjunction(rexBuilder, notPushable, true);
      distinctRowCount *= RelMdUtil.guessSelectivity(preds);
    }

    // No further computation required if the projection expressions
    // are all column references
    if (projCols.cardinality() == 0) {
      return distinctRowCount;
    }

    // multiply by the cardinality of the non-child projection expressions
    for (int bit : projCols.build()) {
      Double subRowCount = RelMdUtil.cardOfProjExpr(mq, rel, projExprs.get(bit));
      if (subRowCount == null) {
        return null;
      }
      distinctRowCount *= subRowCount;
    }

    Double res = RelMdUtil.numDistinctVals(distinctRowCount, mq.getRowCount(rel));
    if (res > 0.0) {
      return res;
    }
    return getDistinctRowCountFromEstimateRowCount(rel, mq, groupKey, predicate);
  }

  public static void splitFilters(
      Project rel,
      ImmutableBitSet childBitmap,
      RexNode predicate,
      List<RexNode> pushable,
      List<RexNode> notPushable) {
    // for each filter, if the filter only references the child inputs,
    // then it can be pushed
    conjunctions(predicate)
        .forEach(
            filter -> {
              ImmutableBitSet filterRefs = InputFinder.bits(filter);
              for (int i = filterRefs.nextSetBit(0); i >= 0; i = filterRefs.nextSetBit(i + 1)) {
                if ((rel.getProjects().get(i) instanceof RexFieldAccess)) {
                  notPushable.add(filter);
                  return;
                }
              }
              if (childBitmap.contains(filterRefs)) {
                pushable.add(filter);
              } else {
                notPushable.add(filter);
              }
            });
  }
}
