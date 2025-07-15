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
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.ExecConstants.DATA_SCAN_PARALLELISM;
import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_SPEC_EVOL_TRANFORMATION;
import static com.dremio.exec.store.RecordReader.COL_IDS;
import static com.dremio.exec.store.RecordReader.SPLIT_IDENTITY;
import static com.dremio.exec.store.RecordReader.SPLIT_INFORMATION;
import static com.dremio.exec.store.SystemSchemas.SYSTEM_COLUMNS;
import static com.dremio.exec.store.iceberg.IcebergUtils.getUsedIndices;

import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.ops.SnapshotDiffContext;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.config.ImmutableManifestScanFilters;
import com.dremio.exec.physical.config.ManifestScanFilters;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.acceleration.IncrementalUpdateUtils;
import com.dremio.exec.planner.logical.partition.PruneFilterCondition;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.FilterPrel;
import com.dremio.exec.planner.physical.HashToRandomExchangePrel;
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.TableFunctionUtil;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.planner.sql.handlers.PrelFinalizable;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.store.DelegatingTableMetadata;
import com.dremio.exec.store.ExpressionInputRewriter;
import com.dremio.exec.store.MinMaxRewriter;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.ScanFilter;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.SystemSchemas.SystemColumn;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.exec.store.iceberg.model.ImmutableManifestScanOptions;
import com.dremio.exec.store.iceberg.model.ManifestScanOptions;
import com.dremio.exec.store.parquet.ParquetFilterCondition;
import com.dremio.exec.store.parquet.ParquetScanFilter;
import com.dremio.exec.store.parquet.ParquetScanRowGroupFilter;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.iceberg.expressions.Expression;

/** Iceberg dataset prel */
public class IcebergScanPrel extends FilterableScan implements Prel, PrelFinalizable {
  private final ScanFilter filter;
  private final ParquetScanRowGroupFilter rowGroupFilter;
  private final boolean arrowCachingEnabled;
  private final PruneFilterCondition pruneCondition;
  private final OptimizerRulesContext context;
  private final long survivingRowCount;
  private final long survivingFileCount;
  private final boolean isConvertedIcebergDataset;
  private final boolean isPruneConditionOnImplicitCol;
  private final boolean canUsePartitionStats;
  private final ManifestScanFilters manifestScanFilters;
  private final boolean partitionValuesEnabled;

  public IcebergScanPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata dataset,
      List<SchemaPath> projectedColumns,
      double observedRowcountAdjustment,
      List<RelHint> hints,
      ScanFilter filter,
      ParquetScanRowGroupFilter rowGroupFilter,
      boolean arrowCachingEnabled,
      PruneFilterCondition pruneCondition,
      OptimizerRulesContext context,
      boolean isConvertedIcebergDataset,
      Long survivingRowCount,
      Long survivingFileCount,
      boolean canUsePartitionStats,
      ManifestScanFilters manifestScanFilters,
      SnapshotDiffContext snapshotDiffContext,
      PartitionStatsStatus partitionStats) {
    super(
        cluster,
        traitSet,
        table,
        pluginId,
        dataset,
        projectedColumns,
        observedRowcountAdjustment,
        hints,
        snapshotDiffContext,
        partitionStats);
    this.filter = filter;
    this.rowGroupFilter = rowGroupFilter;
    this.arrowCachingEnabled = arrowCachingEnabled;
    this.pruneCondition = pruneCondition;
    this.context = context;
    this.isConvertedIcebergDataset = isConvertedIcebergDataset;
    this.isPruneConditionOnImplicitCol = pruneCondition != null && isConditionOnImplicitCol();
    this.survivingRowCount =
        survivingRowCount == null ? tableMetadata.getApproximateRecordCount() : survivingRowCount;
    this.survivingFileCount =
        survivingFileCount == null
            ? tableMetadata
                .getDatasetConfig()
                .getReadDefinition()
                .getManifestScanStats()
                .getRecordCount()
            : survivingFileCount;
    this.canUsePartitionStats = canUsePartitionStats;
    this.manifestScanFilters = manifestScanFilters;
    this.partitionValuesEnabled = false;
  }

  public IcebergScanPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata dataset,
      List<SchemaPath> projectedColumns,
      double observedRowcountAdjustment,
      List<RelHint> hints,
      ScanFilter filter,
      ParquetScanRowGroupFilter rowGroupFilter,
      boolean arrowCachingEnabled,
      PruneFilterCondition pruneCondition,
      OptimizerRulesContext context,
      boolean isConvertedIcebergDataset,
      Long survivingRowCount,
      Long survivingFileCount,
      boolean canUsePartitionStats,
      ManifestScanFilters manifestScanFilters,
      SnapshotDiffContext snapshotDiffContext,
      boolean partitionValuesEnabled,
      PartitionStatsStatus partitionStats) {
    super(
        cluster,
        traitSet,
        table,
        pluginId,
        dataset,
        projectedColumns,
        observedRowcountAdjustment,
        hints,
        snapshotDiffContext,
        partitionStats);
    this.filter = filter;
    this.rowGroupFilter = rowGroupFilter;
    this.arrowCachingEnabled = arrowCachingEnabled;
    this.pruneCondition = pruneCondition;
    this.context = context;
    this.isConvertedIcebergDataset = isConvertedIcebergDataset;
    this.isPruneConditionOnImplicitCol = pruneCondition != null && isConditionOnImplicitCol();
    this.survivingRowCount =
        survivingRowCount == null ? tableMetadata.getApproximateRecordCount() : survivingRowCount;
    this.survivingFileCount =
        survivingFileCount == null
            ? tableMetadata
                .getDatasetConfig()
                .getReadDefinition()
                .getManifestScanStats()
                .getRecordCount()
            : survivingFileCount;
    this.canUsePartitionStats = canUsePartitionStats;
    this.manifestScanFilters = manifestScanFilters;
    this.partitionValuesEnabled = partitionValuesEnabled;
  }

  public IcebergScanPrel withParititionValuesEnabled() {
    return new IcebergScanPrel(
        getCluster(),
        traitSet,
        getTable(),
        pluginId,
        tableMetadata,
        getProjectedColumns(),
        observedRowcountAdjustment,
        hints,
        this.filter,
        this.rowGroupFilter,
        this.arrowCachingEnabled,
        this.pruneCondition,
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        true,
        getPartitionStatsStatus());
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new IcebergScanPrel(
        getCluster(),
        traitSet,
        getTable(),
        pluginId,
        tableMetadata,
        getProjectedColumns(),
        observedRowcountAdjustment,
        hints,
        this.filter,
        this.rowGroupFilter,
        this.arrowCachingEnabled,
        this.pruneCondition,
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        partitionValuesEnabled,
        getPartitionStatsStatus());
  }

  @Override
  public IcebergScanPrel cloneWithProject(List<SchemaPath> projection) {
    ScanFilter newFilter =
        (filter != null && filter instanceof ParquetScanFilter)
            ? ((ParquetScanFilter) filter)
                .applyProjection(projection, rowType, getCluster(), getBatchSchema())
            : filter;
    PruneFilterCondition pruneFilterCondition =
        pruneCondition == null
            ? null
            : pruneCondition.applyProjection(projection, rowType, getCluster(), getBatchSchema());
    ParquetScanRowGroupFilter rowGroupFilter =
        getRowGroupFilter() == null
            ? null
            : getRowGroupFilter()
                .applyProjection(projection, rowType, getCluster(), getBatchSchema());
    return new IcebergScanPrel(
        getCluster(),
        getTraitSet(),
        table,
        pluginId,
        tableMetadata,
        projection,
        observedRowcountAdjustment,
        hints,
        newFilter,
        rowGroupFilter,
        this.arrowCachingEnabled,
        pruneFilterCondition,
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        getPartitionStatsStatus());
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitPrel(this, value);
  }

  @Override
  public ScanFilter getFilter() {
    return filter;
  }

  @Override
  public PruneFilterCondition getPartitionFilter() {
    return pruneCondition;
  }

  @Override
  public ParquetScanRowGroupFilter getRowGroupFilter() {
    return rowGroupFilter;
  }

  @Override
  public FilterableScan applyRowGroupFilter(ParquetScanRowGroupFilter rowGroupFilter) {
    return new IcebergScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getObservedRowcountAdjustment(),
        hints,
        getFilter(),
        rowGroupFilter,
        arrowCachingEnabled,
        getPartitionFilter(),
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        getPartitionStatsStatus());
  }

  @Override
  public FilterableScan applyFilter(ScanFilter scanFilter) {
    return new IcebergScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getObservedRowcountAdjustment(),
        hints,
        scanFilter,
        rowGroupFilter,
        arrowCachingEnabled,
        getPartitionFilter(),
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        getPartitionStatsStatus());
  }

  @Override
  public FilterableScan applyPartitionFilter(
      PruneFilterCondition partitionFilter, Long survivingRowCount, Long survivingFileCount) {
    return new IcebergScanPrel(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getObservedRowcountAdjustment(),
        hints,
        getFilter(),
        getRowGroupFilter(),
        arrowCachingEnabled,
        partitionFilter,
        context,
        isConvertedIcebergDataset,
        survivingRowCount,
        survivingFileCount,
        canUsePartitionStats,
        manifestScanFilters,
        snapshotDiffContext,
        getPartitionStatsStatus());
  }

  @Override
  public IcebergScanPrel cloneWithProject(
      List<SchemaPath> projection, boolean preserveFilterColumns) {
    if (filter != null && filter instanceof ParquetScanFilter && preserveFilterColumns) {
      ParquetScanFilter parquetScanFilter = (ParquetScanFilter) filter;
      final List<SchemaPath> newProjection = new ArrayList<>(projection);
      final Set<SchemaPath> projectionSet = new HashSet<>(projection);
      if (parquetScanFilter.getConditions() != null) {
        for (ParquetFilterCondition f : parquetScanFilter.getConditions()) {
          final SchemaPath col = f.getPath();
          if (!projectionSet.contains(col)) {
            newProjection.add(col);
          }
        }
        return cloneWithProject(newProjection);
      }
    }
    return cloneWithProject(projection);
  }

  @Override
  public double getFilterReduction() {
    if (filter != null) {
      double selectivity = 0.15d;

      double max =
          PrelUtil.getPlannerSettings(getCluster()).getFilterMaxSelectivityEstimateFactor();
      double min =
          PrelUtil.getPlannerSettings(getCluster()).getFilterMinSelectivityEstimateFactor();

      if (selectivity < min) {
        selectivity = min;
      }
      if (selectivity > max) {
        selectivity = max;
      }

      return selectivity;
    } else {
      return 1d;
    }
  }

  @Override
  public BatchSchema.SelectionVectorMode[] getSupportedEncodings() {
    return BatchSchema.SelectionVectorMode.DEFAULT;
  }

  @Override
  public BatchSchema.SelectionVectorMode getEncoding() {
    return BatchSchema.SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return true;
  }

  private BatchSchema getManifestFileReaderSchema(
      final List<SchemaPath> manifestFileReaderColumns,
      boolean specEvolTransEnabled,
      ManifestScanOptions manifestScanOptions) {
    BatchSchema manifestFileReaderSchema;
    if (manifestScanOptions.includesSplitGen()) {
      manifestFileReaderSchema = RecordReader.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA;
    } else {
      if (manifestScanOptions.getManifestContentType() == ManifestContentType.DATA
          || manifestScanOptions.getManifestContentType() == ManifestContentType.ALL) {
        manifestFileReaderSchema = SystemSchemas.ICEBERG_MANIFEST_SCAN_SCHEMA;
      } else {
        manifestFileReaderSchema = SystemSchemas.ICEBERG_DELETE_MANIFEST_SCAN_SCHEMA;
        if (manifestScanOptions.includesIcebergPartitionInfo()) {
          Field fieldToAdd =
              Field.nullable(SystemSchemas.PARTITION_INFO, Types.MinorType.VARBINARY.getType());
          manifestFileReaderSchema = manifestFileReaderSchema.addColumn(fieldToAdd);
        }
      }

      if (manifestScanOptions.includesIcebergMetadata()) {
        manifestFileReaderSchema =
            manifestFileReaderSchema.addColumn(SystemSchemas.ICEBERG_METADATA_FIELD);
      }

      List<String> columnsWithLowerAndUpperBounds =
          manifestScanOptions.getColumnListWithLowerAndUpperBounds();
      if (manifestScanOptions.getManifestContentType() == ManifestContentType.DATA
          && columnsWithLowerAndUpperBounds.size() > 0) {
        manifestFileReaderSchema =
            getSchemaWithMinMaxColumns(
                columnsWithLowerAndUpperBounds.stream()
                    .map(f -> FieldReference.getWithQuotedRef(f))
                    .collect(Collectors.toList()),
                manifestFileReaderSchema);
      }
    }

    manifestFileReaderSchema
        .getFields()
        .forEach(f -> manifestFileReaderColumns.add(SchemaPath.getSimplePath(f.getName())));

    if (pruneCondition == null) {
      return manifestFileReaderSchema;
    }

    // add partition column filter conditions
    manifestFileReaderSchema =
        getSchemaWithUsedColumns(
            pruneCondition.getPartitionExpression(),
            manifestFileReaderColumns,
            manifestFileReaderSchema);

    // add non partition column filter conditions for native iceberg tables
    if (!isConvertedIcebergDataset() && !specEvolTransEnabled) {
      manifestFileReaderSchema =
          getSchemaWithMinMaxUsedColumns(
              pruneCondition.getNonPartitionRange(),
              manifestFileReaderColumns,
              manifestFileReaderSchema);
    }
    return manifestFileReaderSchema;
  }

  /**
   * This function will create final plan for iceberg read flow. There are two paths for iceberg
   * read flow : 1. SPEC_EVOL_TRANFORMATION is not enabled in that case plan will be: ML =>
   * Filter(on Partition Range) => MF => Filter(on Partition Expression and on non-partition range)
   * => Parquet Scan
   *
   * <p>2. With spec evolution and transformation Filter operator is not capable of handling
   * transformed partition columns so we will push down filter expression to ML Reader and MF
   * reader. and use iceberg APIs for filtering. ML(with Iceberg Expression of partition range for
   * filtering) => MF(with Iceberg Expression of partition range and non partition range for
   * filtering) => Parquet Scan
   *
   * <p>With spec evolution and transformation if expression has identity columns only and Iceberg
   * Expression won't able to form. in that case dremio Filter operator can take advantage so plan
   * will be. ML(with Iceberg Expression of partition range for filtering) => MF(with Iceberg
   * Expression of partition range and non partition range for filtering) => Filter(on Partition
   * Expression) => Parquet Scan
   */
  @Override
  public Prel finalizeRel() {
    if (partitionValuesEnabled) {
      // If partitionValuesEnabled flag is set, we can optimize plan by skipping data file scan.
      return new IcebergPartitionAggregationPlanBuilder(this)
          .buildManifestScanPlanForPartitionAggregation();
    }
    ManifestScanOptions manifestScanOptions =
        new ImmutableManifestScanOptions.Builder().setIncludesSplitGen(true).build();
    RelNode output = buildManifestScan(survivingFileCount, manifestScanOptions);
    return buildDataFileScan(output, null);
  }

  public Prel buildManifestScan(
      final Long survivingManifestRecordCount, final ManifestScanOptions manifestScanOptions) {
    final boolean specEvolTransEnabled =
        context.getPlannerSettings().getOptions().getOption(ENABLE_ICEBERG_SPEC_EVOL_TRANFORMATION);

    final List<SchemaPath> manifestFileReaderColumns = new ArrayList<>();
    final BatchSchema manifestFileReaderSchema =
        getManifestFileReaderSchema(
            manifestFileReaderColumns, specEvolTransEnabled, manifestScanOptions);

    return buildManifestScan(
        survivingManifestRecordCount,
        manifestScanOptions,
        manifestFileReaderColumns,
        manifestFileReaderSchema);
  }

  public Prel buildManifestScan(
      final Long survivingManifestRecordCount,
      final ManifestScanOptions manifestScanOptions,
      final List<SchemaPath> manifestFileReaderColumns,
      final BatchSchema manifestFileReaderSchema) {
    TableMetadata tableMetadataToUse =
        manifestScanOptions.getTableMetadata() != null
            ? manifestScanOptions.getTableMetadata()
            : tableMetadata;
    boolean specEvolTransEnabled =
        context.getPlannerSettings().getOptions().getOption(ENABLE_ICEBERG_SPEC_EVOL_TRANFORMATION);
    List<SchemaPath> manifestListReaderColumns =
        new ArrayList<>(
            Arrays.asList(
                SchemaPath.getSimplePath(SPLIT_IDENTITY),
                SchemaPath.getSimplePath(SPLIT_INFORMATION),
                SchemaPath.getSimplePath(COL_IDS)));
    BatchSchema manifestListReaderSchema = RecordReader.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA;

    if (pruneCondition != null && !isPruneConditionOnImplicitCol && !specEvolTransEnabled) {
      manifestListReaderSchema =
          getSchemaWithMinMaxUsedColumns(
              pruneCondition.getPartitionRange(),
              manifestListReaderColumns,
              manifestListReaderSchema);
    }

    DistributionTrait.DistributionField distributionField =
        new DistributionTrait.DistributionField(0);
    DistributionTrait distributionTrait =
        new DistributionTrait(
            DistributionTrait.DistributionType.HASH_DISTRIBUTED,
            ImmutableList.of(distributionField));
    RelTraitSet relTraitSet =
        getCluster().getPlanner().emptyTraitSet().plus(Prel.PHYSICAL).plus(distributionTrait);

    IcebergExpGenVisitor icebergExpGenVisitor =
        new IcebergExpGenVisitor(getRowType(), getCluster());
    Expression icebergPartitionPruneExpression = null;
    List<RexNode> mfconditions = new ArrayList<>();
    if (pruneCondition != null
        && pruneCondition.getPartitionRange() != null
        && specEvolTransEnabled
        && !isPruneConditionOnImplicitCol) {
      icebergPartitionPruneExpression =
          icebergExpGenVisitor.convertToIcebergExpression(pruneCondition.getPartitionRange());
      mfconditions.add(pruneCondition.getPartitionRange());
    }

    ManifestContentType contentType = manifestScanOptions.getManifestContentType();
    IcebergManifestListPrel manifestListPrel =
        new IcebergManifestListPrel(
            getCluster(),
            getTraitSet(),
            tableMetadataToUse,
            manifestListReaderSchema,
            manifestListReaderColumns,
            getRowTypeFromProjectedColumns(
                manifestListReaderColumns, manifestListReaderSchema, getCluster()),
            icebergPartitionPruneExpression,
            contentType);

    RelNode input = manifestListPrel;

    if (!specEvolTransEnabled) {
      RexNode manifestListCondition =
          getManifestListFilter(getCluster().getRexBuilder(), manifestListPrel);
      if (manifestListCondition != null) {
        // Manifest list filter
        input =
            new FilterPrel(getCluster(), getTraitSet(), manifestListPrel, manifestListCondition);
      }
    }

    // exchange above manifest list scan, which is a leaf level easy scan
    HashToRandomExchangePrel manifestSplitsExchange =
        new HashToRandomExchangePrel(
            getCluster(),
            relTraitSet,
            input,
            distributionTrait.getFields(),
            TableFunctionUtil.getHashExchangeTableFunctionCreator(tableMetadataToUse, true));

    ImmutableManifestScanFilters.Builder manifestScanFilterBuilder =
        new ImmutableManifestScanFilters.Builder().from(manifestScanFilters);
    if (specEvolTransEnabled) {
      if (pruneCondition != null && pruneCondition.getNonPartitionRange() != null) {
        mfconditions.add(pruneCondition.getNonPartitionRange());
      }

      IcebergExpGenVisitor icebergExpGenVisitor2 =
          new IcebergExpGenVisitor(getRowType(), getCluster());
      if (mfconditions.size() > 0) {
        RexNode manifestFileAnyColCondition =
            mfconditions.size() == 1
                ? mfconditions.get(0)
                : getCluster()
                    .getRexBuilder()
                    .makeCall(
                        SqlStdOperatorTable.AND,
                        pruneCondition.getPartitionRange(),
                        pruneCondition.getNonPartitionRange());
        Expression icebergManifestFileAnyColPruneExpression =
            icebergExpGenVisitor2.convertToIcebergExpression(manifestFileAnyColCondition);
        manifestScanFilterBuilder.setIcebergAnyColExpression(
            IcebergSerDe.serializeToByteArrayUnchecked(icebergManifestFileAnyColPruneExpression));
      }
    }

    // Manifest scan phase - use the combined manifest scan/split gen tablefunction unless caller
    // requests that
    // split gen be done separately, in which case use the scan-only tablefunction
    Prel manifestScanTF;
    if (manifestScanOptions.includesSplitGen()) {
      TableFunctionConfig manifestScanTableFunctionConfig =
          TableFunctionUtil.getSplitGenManifestScanTableFunctionConfig(
              tableMetadataToUse,
              manifestFileReaderColumns,
              manifestFileReaderSchema,
              null,
              manifestScanFilterBuilder.build());

      RelDataType rowTypeFromProjectedColumns =
          getRowTypeFromProjectedColumns(
              manifestFileReaderColumns, manifestFileReaderSchema, getCluster());
      manifestScanTF =
          new TableFunctionPrel(
              getCluster(),
              getTraitSet().plus(DistributionTrait.ANY),
              getTable(),
              manifestSplitsExchange,
              tableMetadataToUse,
              manifestScanTableFunctionConfig,
              rowTypeFromProjectedColumns,
              survivingManifestRecordCount);

    } else {
      manifestScanTF =
          new IcebergManifestScanPrel(
              getCluster(),
              getTraitSet().plus(DistributionTrait.ANY),
              getTable(),
              manifestSplitsExchange,
              tableMetadataToUse,
              manifestFileReaderSchema,
              manifestFileReaderColumns,
              manifestScanFilterBuilder.build(),
              survivingManifestRecordCount,
              manifestScanOptions.getManifestContentType(),
              tableMetadataToUse.getUser(),
              manifestScanOptions.includesIcebergPartitionInfo());
    }

    Prel input2 = manifestScanTF;

    final RexNode manifestFileCondition =
        getManifestFileFilter(getCluster().getRexBuilder(), manifestScanTF, specEvolTransEnabled);
    if (manifestFileCondition != null) {
      // Manifest file filter
      input2 = new FilterPrel(getCluster(), getTraitSet(), manifestScanTF, manifestFileCondition);
    }

    return input2;
  }

  public Prel buildDataFileScan(RelNode input2, List<SystemColumn> additionalSystemColumns) {
    return buildDataFileScanWithImplicitPartitionCols(
        input2, Collections.emptyList(), additionalSystemColumns);
  }

  public Prel buildDataFileScanWithImplicitPartitionCols(
      RelNode input2,
      List<String> implicitPartitionCols,
      List<SystemColumn> additionalSystemColumns) {
    DistributionTrait.DistributionField distributionField =
        new DistributionTrait.DistributionField(0);
    DistributionTrait distributionTrait =
        new DistributionTrait(
            DistributionTrait.DistributionType.HASH_DISTRIBUTED,
            ImmutableList.of(distributionField));
    RelTraitSet relTraitSet =
        getCluster().getPlanner().emptyTraitSet().plus(Prel.PHYSICAL).plus(distributionTrait);

    // Exchange above manifest scan phase
    HashToRandomExchangePrel parquetSplitsExchange =
        new HashToRandomExchangePrel(
            getCluster(),
            relTraitSet,
            input2,
            distributionTrait.getFields(),
            TableFunctionUtil.getHashExchangeTableFunctionCreator(tableMetadata, false));

    return buildDataFileScanTableFunction(
        parquetSplitsExchange, implicitPartitionCols, additionalSystemColumns);
  }

  public Prel buildDataFileScanTableFunction(
      RelNode input,
      List<String> implicitPartitionCols,
      List<SystemColumn> additionalSystemColumns) {
    boolean limitDataScanParallelism =
        context.getPlannerSettings().getOptions().getOption(DATA_SCAN_PARALLELISM);

    TableMetadata dataScanTableMetadata = tableMetadata;
    List<SchemaPath> dataScanProjectedColumns = getProjectedColumns();
    RelDataType dataScanRowType = getRowType();
    // add file_path and row_index system columns to the table scan
    if (CollectionUtils.isNotEmpty(additionalSystemColumns)
        && shouldAddSystemColumns(tableMetadata, additionalSystemColumns)) {
      dataScanTableMetadata =
          getExtendedTableMetadataWithSystemColumns(tableMetadata, additionalSystemColumns);
      dataScanProjectedColumns = new ArrayList<>(getProjectedColumns());
      for (SystemColumn systemColumn : additionalSystemColumns) {
        dataScanProjectedColumns.add(new SchemaPath(systemColumn.getName()));
      }
      dataScanRowType =
          getRowTypeFromProjectedColumns(
              dataScanProjectedColumns, dataScanTableMetadata.getSchema(), getCluster());
    }

    // table scan phase
    TableFunctionConfig tableFunctionConfig =
        TableFunctionUtil.getDataFileScanTableFunctionConfig(
            dataScanTableMetadata,
            filter,
            rowGroupFilter,
            dataScanProjectedColumns,
            arrowCachingEnabled,
            isConvertedIcebergDataset,
            limitDataScanParallelism,
            survivingFileCount,
            implicitPartitionCols);

    return new TableFunctionPrel(
        getCluster(),
        getTraitSet().plus(DistributionTrait.ANY),
        getTable(),
        input,
        dataScanTableMetadata,
        tableFunctionConfig,
        dataScanRowType,
        getSurvivingRowCount());
  }

  private boolean shouldAddSystemColumns(
      TableMetadata tableMetadata, List<SystemColumn> systemColumns) {
    if (CollectionUtils.isEmpty(systemColumns)) {
      return false;
    }

    BatchSchema schema = tableMetadata.getSchema();
    Set<String> systemColumnNames =
        systemColumns.stream().map(SystemColumn::getName).collect(Collectors.toSet());
    long existingColumns =
        schema.getFields().stream().filter(f -> systemColumnNames.contains(f.getName())).count();

    if (existingColumns > 0) {
      return false;
    }

    return true;
  }

  private static TableMetadata getExtendedTableMetadataWithSystemColumns(
      TableMetadata dataset, final List<SystemColumn> systemColumns) {
    if (CollectionUtils.isEmpty(systemColumns)) {
      return dataset;
    }
    return new DelegatingTableMetadata(dataset) {
      @Override
      public BatchSchema getSchema() {
        final SchemaBuilder schemaWithSystemColumns = BatchSchema.newBuilder();
        schemaWithSystemColumns.addFields(dataset.getSchema().getFields());
        systemColumns.stream()
            .forEach(
                column ->
                    schemaWithSystemColumns.addField(
                        Field.nullablePrimitive(column.getName(), column.getArrowType())));
        return schemaWithSystemColumns.build();
      }
    };
  }

  @Override
  public Iterator<Prel> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .itemIf("arrowCachingEnable", arrowCachingEnabled, arrowCachingEnabled)
        .itemIf("filter", filter, filter != null)
        .itemIf("pruneCondition", pruneCondition, pruneCondition != null)
        .itemIf("rowGroupFilter", rowGroupFilter, rowGroupFilter != null);
  }

  private boolean isConditionOnImplicitCol() {
    boolean specEvolTransEnabled =
        context.getPlannerSettings().getOptions().getOption(ENABLE_ICEBERG_SPEC_EVOL_TRANFORMATION);
    RexNode partitionExpression =
        specEvolTransEnabled
            ? pruneCondition.getPartitionRange()
            : pruneCondition.getPartitionExpression();
    if (Objects.isNull(partitionExpression)) {
      return false;
    }
    int updateColIndex =
        projectedColumns.indexOf(SchemaPath.getSimplePath(IncrementalUpdateUtils.UPDATE_COLUMN));
    final AtomicBoolean isImplicit = new AtomicBoolean(false);
    partitionExpression.accept(
        new RexVisitorImpl<Void>(true) {
          @Override
          public Void visitInputRef(RexInputRef inputRef) {
            isImplicit.set(updateColIndex == inputRef.getIndex());
            return null;
          }
        });
    return isImplicit.get();
  }

  @Override
  public Long getSurvivingRowCount() {
    return survivingRowCount;
  }

  @Override
  public StoragePluginId getIcebergStatisticsPluginId(OptimizerRulesContext context) {
    return pluginId;
  }

  @Override
  public Long getSurvivingFileCount() {
    return survivingFileCount;
  }

  @Override
  public boolean canUsePartitionStats() {
    return false;
  }

  private RexNode getFilterWithIsNullCond(RexNode cond, RexBuilder builder, RelNode input) {
    // checking for isNull with any one of the min/max col is sufficient
    int colIdx = getUsedIndices.apply(cond).stream().findFirst().get();
    RexNode isNullCond =
        builder.makeCall(SqlStdOperatorTable.IS_NULL, builder.makeInputRef(input, colIdx));
    return RexUtil.flatten(builder, builder.makeCall(SqlStdOperatorTable.OR, isNullCond, cond));
  }

  public RexNode getManifestListFilter(RexBuilder builder, RelNode input) {
    if (pruneCondition == null || isPruneConditionOnImplicitCol) {
      return null;
    }
    RexNode partitionRange = pruneCondition.getPartitionRange();
    if (partitionRange == null) {
      return null;
    }
    return getFilterWithIsNullCond(
        pruneCondition.getPartitionRange().accept(new MinMaxRewriter(builder, getRowType(), input)),
        builder,
        input);
  }

  public boolean isConvertedIcebergDataset() {
    return isConvertedIcebergDataset;
  }

  public RexNode getManifestFileFilter(
      RexBuilder builder, RelNode input, boolean specEvolTransEnabled) {
    if (pruneCondition == null) {
      return null;
    }
    List<RexNode> filters = new ArrayList<>();
    if (!specEvolTransEnabled) {
      // add non partition filter conditions for native iceberg tables
      if (!isConvertedIcebergDataset()) {
        RexNode nonPartitionRange = pruneCondition.getNonPartitionRange();
        RelDataType rowType = getRowType();
        if (nonPartitionRange != null) {
          filters.add(
              getFilterWithIsNullCond(
                  nonPartitionRange.accept(new MinMaxRewriter(builder, rowType, input)),
                  builder,
                  input));
        }
      }
    }
    RexNode partitionExpression = pruneCondition.getPartitionExpression();

    if (partitionExpression != null) {
      filters.add(
          partitionExpression.accept(new ExpressionInputRewriter(builder, rowType, input, "_val")));
    }
    return filters.size() == 0
        ? null
        : (filters.size() == 1
            ? filters.get(0)
            : RexUtil.flatten(builder, builder.makeCall(SqlStdOperatorTable.AND, filters)));
  }

  public BatchSchema getSchemaWithMinMaxUsedColumns(
      RexNode cond, List<SchemaPath> outputColumns, BatchSchema schema) {
    if (cond == null) {
      return schema;
    }

    List<SchemaPath> usedColumns =
        getUsedIndices.apply(cond).stream()
            .map(i -> FieldReference.getWithQuotedRef(rowType.getFieldNames().get(i)))
            .collect(Collectors.toList());
    // TODO only add _min, _max columns which are used
    usedColumns.forEach(
        c -> {
          List<String> nameSegments = c.getNameSegments();
          Preconditions.checkArgument(nameSegments.size() == 1);
          outputColumns.add(SchemaPath.getSimplePath(nameSegments.get(0) + "_min"));
          outputColumns.add(SchemaPath.getSimplePath(nameSegments.get(0) + "_max"));
        });

    List<Field> fields =
        tableMetadata.getSchema().maskAndReorder(usedColumns).getFields().stream()
            .flatMap(
                f ->
                    Stream.of(
                        new Field(f.getName() + "_min", f.getFieldType(), f.getChildren()),
                        new Field(f.getName() + "_max", f.getFieldType(), f.getChildren())))
            .collect(Collectors.toList());
    return schema.cloneWithFields(fields);
  }

  public BatchSchema getSchemaWithUsedColumns(
      RexNode cond, List<SchemaPath> outputColumns, BatchSchema schema) {
    if (cond == null) {
      return schema;
    }

    List<SchemaPath> usedColumns =
        getUsedIndices.apply(cond).stream()
            .map(i -> FieldReference.getWithQuotedRef(rowType.getFieldNames().get(i)))
            .collect(Collectors.toList());
    usedColumns.forEach(
        c -> {
          List<String> nameSegments = c.getNameSegments();
          Preconditions.checkArgument(nameSegments.size() == 1);
          outputColumns.add(SchemaPath.getSimplePath(nameSegments.get(0) + "_val"));
        });

    List<Field> fields =
        tableMetadata.getSchema().maskAndReorder(usedColumns).getFields().stream()
            .map(f -> new Field(f.getName() + "_val", f.getFieldType(), f.getChildren()))
            .collect(Collectors.toList());
    return schema.cloneWithFields(fields);
  }

  private BatchSchema getSchemaWithMinMaxColumns(List<SchemaPath> columns, BatchSchema schema) {
    if (columns == null || columns.isEmpty()) {
      return schema;
    }

    // system columns with min/max
    List<Field> systemFields = new ArrayList<>();
    List<SchemaPath> userColumns = new ArrayList<>();
    for (SchemaPath column : columns) {
      SystemColumn systemColumn =
          SYSTEM_COLUMNS.get(column.getLastSegment().getNameSegment().getPath());
      // system column
      if (systemColumn != null) {
        systemFields.add(Field.nullable(systemColumn.getName(), systemColumn.getArrowType()));
      } else {
        userColumns.add(column);
      }
    }

    // combined fields: user + system
    List<Field> fields =
        new ArrayList<>(tableMetadata.getSchema().maskAndReorder(userColumns).getFields());
    fields.addAll(systemFields);

    List<Field> fieldsWithMinMax =
        fields.stream()
            .flatMap(
                f ->
                    Stream.of(
                        new Field(f.getName() + "_MIN", f.getFieldType(), f.getChildren()),
                        new Field(f.getName() + "_MAX", f.getFieldType(), f.getChildren())))
            .collect(Collectors.toList());
    return schema.cloneWithFields(fieldsWithMinMax);
  }

  public OptimizerRulesContext getContext() {
    return context;
  }

  public ManifestScanFilters getManifestScanFilters() {
    return manifestScanFilters;
  }

  public boolean isPartitionValuesEnabled() {
    return partitionValuesEnabled;
  }
}
