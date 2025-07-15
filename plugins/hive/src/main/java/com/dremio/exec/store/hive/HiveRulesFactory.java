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
package com.dremio.exec.store.hive;

import static com.dremio.exec.store.dfs.FileSystemRulesFactory.IcebergMetadataFilesystemScanPrule.getInternalIcebergTableMetadata;
import static com.dremio.exec.store.dfs.FileSystemRulesFactory.IcebergMetadataFilesystemScanPrule.supportsConvertedIcebergDataset;
import static com.dremio.exec.store.dfs.FileSystemRulesFactory.isDeltaLakeDataset;
import static com.dremio.exec.store.dfs.FileSystemRulesFactory.isDeltaLakeHistoryFunction;
import static com.dremio.exec.store.dfs.FileSystemRulesFactory.isIcebergMetadata;
import static com.dremio.exec.store.dfs.FileSystemRulesFactory.isTableFilesMetadataFunction;
import static com.dremio.exec.store.iceberg.IncrementalReflectionByPartitionUtils.isUnlimitedSplitIncrementalRefresh;
import static com.dremio.service.namespace.DatasetHelper.isConvertedIcebergDataset;
import static com.dremio.service.namespace.DatasetHelper.supportsPruneFilter;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.ops.SnapshotDiffContext;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.config.ManifestScanFilters;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.logical.EmptyRel;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.logical.TableModifyRel;
import com.dremio.exec.planner.logical.TableOptimizeRel;
import com.dremio.exec.planner.logical.VacuumTableRel;
import com.dremio.exec.planner.logical.partition.PruneFilterCondition;
import com.dremio.exec.planner.logical.partition.PruneScanRuleBase;
import com.dremio.exec.planner.logical.partition.PruneScanRuleBase.PruneScanRuleFilterOnProject;
import com.dremio.exec.planner.logical.partition.PruneScanRuleBase.PruneScanRuleFilterOnScan;
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.ScanPrelBase;
import com.dremio.exec.planner.physical.TableModifyPruleBase;
import com.dremio.exec.planner.physical.TableOptimizePruleBase;
import com.dremio.exec.planner.physical.VacuumTablePruleBase;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RelOptNamespaceTable;
import com.dremio.exec.store.ScanFilter;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.deltalake.DeltaLakeHistoryScanPlanBuilder;
import com.dremio.exec.store.deltalake.HiveDeltaLakeScanPrel;
import com.dremio.exec.store.deltalake.HiveDeltaScanTableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.exec.store.dfs.PruneableScan;
import com.dremio.exec.store.hive.orc.ORCFilterPushDownRule;
import com.dremio.exec.store.iceberg.HiveIcebergScanTableMetadata;
import com.dremio.exec.store.iceberg.IcebergManifestFileContentScanPrel;
import com.dremio.exec.store.iceberg.IcebergScanPlanBuilder;
import com.dremio.exec.store.iceberg.IcebergScanPrel;
import com.dremio.exec.store.iceberg.InternalIcebergScanTableMetadata;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.parquet.ParquetScanFilter;
import com.dremio.exec.store.parquet.ParquetScanRowGroupFilter;
import com.dremio.hive.proto.HiveReaderProto;
import com.dremio.hive.proto.HiveReaderProto.HiveTableXattr;
import com.dremio.options.TypeValidators;
import com.dremio.service.namespace.file.proto.FileType;
import com.github.slugify.Slugify;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rules factory for Hive. This must be instance based because any matches
 * on Hive nodes are ClassLoader-specific, which are tied to a plugin.
 *
 * Note that Calcite uses the description field in RelOptRule to uniquely
 * identify different rules so the description must be based on the plugin for
 * all rules.
 */
public class HiveRulesFactory implements StoragePluginRulesFactory {
  private static final Slugify SLUGIFY = new Slugify();
  private static final Logger logger = LoggerFactory.getLogger(HiveRulesFactory.class);

  private static class HiveScanDrule extends ConverterRule {
    private final String pluginName;
    public HiveScanDrule(StoragePluginId pluginId) {
      super(ScanCrel.class, Convention.NONE, Rel.LOGICAL, pluginId.getType().value() + ".HiveScanDrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
      pluginName = pluginId.getName();
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      ScanCrel scan = call.rel(0);
      return scan.getPluginId().getName().equals(pluginName);
    }

    @Override
    public final Rel convert(RelNode rel) {
      final ScanCrel crel = (ScanCrel) rel;
      return new HiveScanDrel(crel.getCluster(), crel.getTraitSet().plus(Rel.LOGICAL), crel.getTable(),
                              crel.getPluginId(), crel.getTableMetadata(), crel.getProjectedColumns(),
                              crel.getObservedRowcountAdjustment(), crel.getHintsAsList(),
                              crel.getSnapshotDiffContext());
    }

  }

  public static class HiveScanDrel extends FilterableScan implements Rel, PruneableScan {

    private final ScanFilter filter;
    private final ParquetScanRowGroupFilter rowGroupFilter;
    private final HiveReaderProto.ReaderType readerType;
    private final PruneFilterCondition partitionFilter;
    private final Long survivingRowCount;
    private final Long survivingFileCount;
    private final boolean partitionValuesEnabled;

    public HiveScanDrel(RelOptCluster cluster, RelTraitSet traitSet, RelOptTable table, StoragePluginId pluginId,
                        TableMetadata dataset, List<SchemaPath> projectedColumns, double observedRowcountAdjustment,
                        List<RelHint> hints, SnapshotDiffContext snapshotDiffContext) {
      this(cluster, traitSet, table, pluginId, dataset, projectedColumns, observedRowcountAdjustment, hints, null, null,
           null, null, null, null, snapshotDiffContext, false);
    }

    private HiveScanDrel(RelOptCluster cluster, RelTraitSet traitSet, RelOptTable table, StoragePluginId pluginId,
                         TableMetadata dataset, List<SchemaPath> projectedColumns, double observedRowcountAdjustment,
                         List<RelHint> hints, ScanFilter filter, ParquetScanRowGroupFilter rowGroupFilter,
                         HiveReaderProto.ReaderType readerType, PruneFilterCondition partitionFilter, Long survivingRowCount,
                         Long survivingFileCount, SnapshotDiffContext snapshotDiffContext, boolean partitionValuesEnabled) {
      super(cluster, traitSet, table, pluginId, dataset, projectedColumns,
          observedRowcountAdjustment, hints, snapshotDiffContext, PartitionStatsStatus.NONE);
      assert traitSet.getTrait(ConventionTraitDef.INSTANCE) == Rel.LOGICAL;
      this.filter = filter;
      this.rowGroupFilter = rowGroupFilter;
      this.readerType = Optional.ofNullable(readerType)
        .orElseGet(() -> {
          try {
            return HiveTableXattr.parseFrom(getTableMetadata().getReadDefinition().getExtendedProperty().asReadOnlyByteBuffer()).getReaderType();
          } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Unable to retrieve the reader type table attribute.", e);
          }
        });
      this.partitionFilter = partitionFilter;
      this.survivingFileCount = survivingFileCount;
      this.survivingRowCount = survivingRowCount;
      this.partitionValuesEnabled = partitionValuesEnabled;
    }

    // Clone with partition filter
    private HiveScanDrel(HiveScanDrel that, PruneFilterCondition partitionFilter, Long survivingRowCount, Long survivingFileCount) {
      super(that.getCluster(), that.getTraitSet(), that.getTable(), that.getPluginId(),
          that.getTableMetadata(), that.getProjectedColumns(), that.getObservedRowcountAdjustment(),
          that.getHintsAsList(), that.getSnapshotDiffContext(), that.getPartitionStatsStatus());
      assert traitSet.getTrait(ConventionTraitDef.INSTANCE) == Rel.LOGICAL;
      this.filter = that.getFilter();
      this.rowGroupFilter = that.getRowGroupFilter();
      this.readerType = that.readerType;
      this.partitionFilter = partitionFilter;
      this.survivingRowCount = survivingRowCount;
      this.survivingFileCount = survivingFileCount;
      this.partitionValuesEnabled = that.isPartitionValuesEnabled();
    }

    @Override
    public RelOptCost computeSelfCost(final RelOptPlanner planner, final RelMetadataQuery mq) {
      if (partitionValuesEnabled) {
        return planner.getCostFactory().makeTinyCost();
      } else if(tableMetadata.getSplitCount() == 0){
        return planner.getCostFactory().makeInfiniteCost();
      }
      return super.computeSelfCost(planner, mq);
    }

    @Override
    public ScanFilter getFilter() {
      return filter;
    }

    @Override
    public PruneFilterCondition getPartitionFilter() {
      return partitionFilter;
    }

    @Override
    public ParquetScanRowGroupFilter getRowGroupFilter() {
      return rowGroupFilter;
    }

    public HiveReaderProto.ReaderType getReaderType() {
      return readerType;
    }

    @Override
    public double getCostAdjustmentFactor() {
      return filter != null ? filter.getCostAdjustment() : super.getCostAdjustmentFactor();
    }

    @Override
    public Long getSurvivingRowCount() {
      return survivingRowCount;
    }

    @Override
    public Long getSurvivingFileCount() {
      return survivingFileCount;
    }

    @Override
    public double getFilterReduction() {
      if(filter != null){
        double selectivity = 0.15d;

        double max = PrelUtil.getPlannerSettings(getCluster()).getFilterMaxSelectivityEstimateFactor();
        double min = PrelUtil.getPlannerSettings(getCluster()).getFilterMinSelectivityEstimateFactor();

        if(selectivity < min) {
          selectivity = min;
        }
        if(selectivity > max) {
          selectivity = max;
        }

        return selectivity;
      }else {
        return 1d;
      }
    }

    @Override
    public FilterableScan applyRowGroupFilter(ParquetScanRowGroupFilter rowGroupFilter) {
      return new HiveScanDrel(getCluster(), traitSet, table, pluginId, tableMetadata, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), getFilter(), rowGroupFilter, readerType, partitionFilter, survivingRowCount,
        survivingFileCount, snapshotDiffContext, partitionValuesEnabled);
    }

    @Override
    public FilterableScan applyFilter(ScanFilter filter) {
      return new HiveScanDrel(getCluster(), traitSet, table, pluginId, tableMetadata, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), filter, getRowGroupFilter(), readerType, partitionFilter, survivingRowCount,
        survivingFileCount, snapshotDiffContext, partitionValuesEnabled);
    }

    @Override
    public HiveScanDrel applyPartitionFilter(PruneFilterCondition partitionFilter, Long survivingRowCount, Long survivingFileCount) {
      Preconditions.checkArgument(supportsPruneFilter(getTableMetadata().getDatasetConfig()));
      return new HiveScanDrel(this, partitionFilter, survivingRowCount, survivingFileCount);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new HiveScanDrel(getCluster(), traitSet, getTable(), pluginId, tableMetadata, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter, readerType, partitionFilter, survivingRowCount,
        survivingFileCount, snapshotDiffContext, partitionValuesEnabled);
    }

    @Override
    public RelNode applyDatasetPointer(TableMetadata newDatasetPointer) {
      return new HiveScanDrel(getCluster(), traitSet, new RelOptNamespaceTable(newDatasetPointer, getCluster()),
        pluginId, newDatasetPointer, getProjectedColumns(), observedRowcountAdjustment, getHintsAsList(), filter,
        rowGroupFilter, readerType, partitionFilter, survivingRowCount, survivingFileCount, snapshotDiffContext,
        partitionValuesEnabled);
    }

    @Override
    public HiveScanDrel cloneWithProject(List<SchemaPath> projection, boolean preserveFilterColumns) {
      if (filter != null && preserveFilterColumns) {
        final List<SchemaPath> newProjection = new ArrayList<>(projection);
        final Set<SchemaPath> projectionSet = new HashSet<>(projection);
        final List<SchemaPath> paths = filter.getPaths();
        if (paths != null) {
          for (SchemaPath col : paths) {
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
    public HiveScanDrel cloneWithProject(List<SchemaPath> projection) {
      return new HiveScanDrel(getCluster(), getTraitSet(), getTable(), pluginId, tableMetadata, projection,
        observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter == null ? null :
        rowGroupFilter.applyProjection(projection, rowType, getCluster(), getBatchSchema()),
        readerType, partitionFilter == null ? null : partitionFilter.applyProjection(projection,
        rowType, getCluster(), getBatchSchema()), survivingRowCount, survivingFileCount, snapshotDiffContext,
        partitionValuesEnabled);
    }
    @Override
    public StoragePluginId getIcebergStatisticsPluginId(OptimizerRulesContext context) {
      if (isHiveIcebergDataset(this)) {
        return getPluginId();
      } else if (supportsConvertedIcebergDataset(context, getTableMetadata())) { // internal
        return getInternalIcebergTableMetadata(getTableMetadata(), context).getIcebergTableStoragePlugin();
      }
      return null;
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
      pw = super.explainTerms(pw);
      return pw.itemIf("filters",  filter, filter != null)
        .itemIf("partitionFilters",  partitionFilter, partitionFilter != null)
        .itemIf("rowGroupFilter",  rowGroupFilter, rowGroupFilter != null)
        .itemIf("partitionValuesEnabled",  partitionValuesEnabled, partitionValuesEnabled);
    }

    @Override
    public boolean canUsePartitionStats(){
      return isConvertedIcebergDataset(getTableMetadata().getDatasetConfig());
    }

    public HiveScanDrel applyPartitionValuesEnabled(final boolean partitionValuesEnabled) {
      return new HiveScanDrel(getCluster(), traitSet, getTable(), pluginId, tableMetadata,
        getProjectedColumns(), observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter, readerType,
        partitionFilter, survivingRowCount, survivingFileCount, snapshotDiffContext,
        partitionValuesEnabled);
    }

    public boolean isPartitionValuesEnabled() {
      return partitionValuesEnabled;
    }
  }

  private static class EliminateEmptyScans extends RelOptRule {

    public EliminateEmptyScans(StoragePluginId pluginId) {
      // Note: matches to HiveScanDrel.class with this rule instance are guaranteed to be local to the same plugin
      // because this match implicitly ensures the classloader is the same.
      super(RelOptHelper.any(HiveScanDrel.class), "Hive::eliminate_empty_scans."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      HiveScanDrel scan = call.rel(0);
      if(scan.getTableMetadata().getSplitCount() == 0){
        call.transformTo(new EmptyRel(scan.getCluster(), scan.getTraitSet(), scan.getRowType(), scan.getProjectedSchema()));
      }
    }
  }

  public static class HiveScanPrel extends ScanPrelBase implements PruneableScan {

    private final ScanFilter filter;
    private final ParquetScanRowGroupFilter rowGroupFilter;
    private final HiveReaderProto.ReaderType readerType;
    public static final TypeValidators.BooleanValidator C3_RUNTIME_AFFINITY = new TypeValidators.BooleanValidator("c3.runtime.affinity", false);

    public HiveScanPrel(RelOptCluster cluster, RelTraitSet traitSet, RelOptTable table, StoragePluginId pluginId,
                        TableMetadata dataset, List<SchemaPath> projectedColumns, double observedRowcountAdjustment,
                        List<RelHint> hints, ScanFilter filter, ParquetScanRowGroupFilter rowGroupFilter,
                        HiveReaderProto.ReaderType readerType, List<Info> runtimeFilters) {
      super(cluster, traitSet, table, pluginId, dataset, projectedColumns, observedRowcountAdjustment, hints, runtimeFilters);
      this.filter = filter;
      this.rowGroupFilter = rowGroupFilter;
      this.readerType = readerType;
    }

    @Override
    public boolean hasFilter() {
      return filter != null;
    }

    @Override
    public ScanFilter getFilter() {
      return filter;
    }

    public ParquetScanRowGroupFilter getRowGroupFilter() {
      return rowGroupFilter;
    }

    @Override
    public RelNode applyDatasetPointer(TableMetadata newDatasetPointer) {
      return new HiveScanPrel(getCluster(), traitSet, getTable(), pluginId, newDatasetPointer, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter, readerType, getRuntimeFilters());
    }

    @Override
    public HiveScanPrel cloneWithProject(List<SchemaPath> projection) {
      return new HiveScanPrel(getCluster(), getTraitSet(), getTable(), pluginId, tableMetadata, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter, readerType, getRuntimeFilters());
    }

    @Override
    public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
      final BatchSchema schema = tableMetadata.getSchema().maskAndReorder(getProjectedColumns());
      HiveSettings hiveSettings = new HiveSettings(null, HiveCommonUtilities.isHive2WrappingSourceType(pluginId));
      return new HiveGroupScan(
        creator.props(this, tableMetadata.getUser(), schema, hiveSettings.getReserveValidator(), hiveSettings.getLimitValidator()),
        tableMetadata, getProjectedColumns(), filter, creator.getContext(),
        creator.getContext().getOptions().getOption(C3_RUNTIME_AFFINITY));
    }

    @Override
    public double getCostAdjustmentFactor(){
      return filter != null ? filter.getCostAdjustment() : super.getCostAdjustmentFactor();
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
      pw = super.explainTerms(pw);
      pw = pw.item("mode", readerType.name());
      return pw.itemIf("filters", filter, filter != null)
              .itemIf("rowGroupFilters", rowGroupFilter, rowGroupFilter != null);
    }

    @Override
    public double getFilterReduction(){
      if(filter != null){
        return 0.15d;
      }else {
        return 1d;
      }
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new HiveScanPrel(getCluster(), traitSet, getTable(), pluginId, tableMetadata, getProjectedColumns(),
        observedRowcountAdjustment, getHintsAsList(), filter, rowGroupFilter, readerType, getRuntimeFilters());
    }

  }

  private static class HiveScanPrule extends ConverterRule {
    private final OptimizerRulesContext context;

    public HiveScanPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      // Note: matches to HiveScanDrel.class with this rule instance are guaranteed to be local to the same plugin
      // because this match implicitly ensures the classloader is the same.
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "HiveScanPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
      this.context = context;
    }

    @Override
    public RelNode convert(RelNode rel) {
      HiveScanDrel drel = (HiveScanDrel) rel;
      return new HiveScanPrel(drel.getCluster(), drel.getTraitSet().plus(Prel.PHYSICAL), drel.getTable(),
                              drel.getPluginId(), drel.getTableMetadata(), drel.getProjectedColumns(),
                              drel.getObservedRowcountAdjustment(), drel.getHintsAsList(), drel.getFilter(),
                              drel.getRowGroupFilter(), drel.getReaderType(), ImmutableList.of());
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return !isTableFilesMetadataFunction(drel.getTableMetadata())
        && !isDeltaLakeHistoryFunction(drel.getTableMetadata())
        && !isIcebergMetadata(drel.getTableMetadata())
        && !isHiveIcebergDataset(drel)
        && !isDeltaLakeDataset(drel.getTableMetadata())
        && !isUnlimitedSplitIncrementalRefresh(drel.getSnapshotDiffContext(), drel.getTableMetadata());
    }
  }

  private static class InternalIcebergScanPrule extends ConverterRule {
    private final OptimizerRulesContext context;

    public InternalIcebergScanPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "IcebergScanPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
      this.context = context;
    }

    @Override
    public RelNode convert(RelNode relNode) {
      HiveScanDrel drel = (HiveScanDrel) relNode;
      InternalIcebergScanTableMetadata icebergTableMetadata = getInternalIcebergTableMetadata(drel.getTableMetadata(), context);
      IcebergScanPrel icebergScanPrel = new IcebergScanPrel(drel.getCluster(), drel.getTraitSet().plus(Prel.PHYSICAL),
        drel.getTable(), icebergTableMetadata.getIcebergTableStoragePlugin(), icebergTableMetadata, drel.getProjectedColumns(),
        drel.getObservedRowcountAdjustment(), drel.getHintsAsList(), drel.getFilter(), drel.getRowGroupFilter(), false, /* TODO enable */
        drel.getPartitionFilter(), context, true, drel.getSurvivingRowCount(), drel.getSurvivingFileCount(),
        drel.canUsePartitionStats(), ManifestScanFilters.empty(), drel.getSnapshotDiffContext(), drel.isPartitionValuesEnabled(),
          drel.getPartitionStatsStatus());
      //generate query plans for cases when we are querying the data changes between snapshots
      //for example Incremental Refresh by Snapshot (Append only or By Partition)
      if(drel.getSnapshotDiffContext().isEnabled()) {
        final IcebergScanPlanBuilder icebergScanPlanBuilder = new IcebergScanPlanBuilder(icebergScanPrel);
        return icebergScanPlanBuilder.build();
      }
      return icebergScanPrel;
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return !isTableFilesMetadataFunction(drel.getTableMetadata()) && supportsConvertedIcebergDataset(context, drel.getTableMetadata()) && !isHiveIcebergDataset(drel);
    }
  }

  private static class HiveIcebergScanPrule extends ConverterRule {
    private final OptimizerRulesContext context;
    private final String storagePluginName;

    public HiveIcebergScanPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "HiveIcebergScanPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
      this.context = context;
      this.storagePluginName = pluginId.getName();
    }

    @Override
    public RelNode convert(RelNode relNode) {
      HiveScanDrel drel = (HiveScanDrel) relNode;
      HiveIcebergScanTableMetadata icebergScanTableMetadata = new HiveIcebergScanTableMetadata(
        drel.getTableMetadata(), context.getCatalogService().getSource(storagePluginName));
      return IcebergScanPlanBuilder.fromDrel(
          drel,
          context,
          icebergScanTableMetadata,
          false,
          false,
          drel.canUsePartitionStats(),
          drel.isPartitionValuesEnabled())
        .build();
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return !isTableFilesMetadataFunction(drel.getTableMetadata()) && isHiveIcebergDataset(drel);
    }
  }

  private static class HiveDeltaScanPrule extends ConverterRule {
    private final OptimizerRulesContext context;
    private final String storagePluginName;

    public HiveDeltaScanPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "HiveDeltaScanPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID().toString());
      this.context = context;
      this.storagePluginName = pluginId.getName();
    }

    @Override
    public RelNode convert(RelNode relNode) {
      HiveScanDrel drel = (HiveScanDrel) relNode;
      HiveDeltaScanTableMetadata deltaScanTableMetadata = new HiveDeltaScanTableMetadata(drel.getTableMetadata());
      ParquetScanFilter parquetScanFilter = drel.getFilter() instanceof ParquetScanFilter ? (ParquetScanFilter)drel.getFilter() : null;
      return new HiveDeltaLakeScanPrel(drel.getCluster(), drel.getTraitSet().plus(Prel.PHYSICAL),
        drel.getTable(), drel.getPluginId(), deltaScanTableMetadata, drel.getProjectedColumns(),
        drel.getObservedRowcountAdjustment(), drel.getHintsAsList(), parquetScanFilter, drel.getRowGroupFilter(),
        true, drel.getPartitionFilter());
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return isDeltaLakeDataset(drel.getTableMetadata());
    }
  }

  private static class HiveDeltaLakeHistoryScanPrule extends ConverterRule {
    public HiveDeltaLakeHistoryScanPrule(StoragePluginId pluginId) {
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "HiveDeltaLakeHistoryScanPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID());
    }

    @Override
    public RelNode convert(RelNode relNode) {
      HiveScanDrel drel = (HiveScanDrel) relNode;
      return DeltaLakeHistoryScanPlanBuilder.fromDrel(drel).build();
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return drel.getTableMetadata() != null && isDeltaLakeHistoryFunction(drel.getTableMetadata());
    }
  }

  private static class HiveIcebergTableFileFunctionPrule extends ConverterRule {
    private final OptimizerRulesContext context;
    private final String storagePluginName;

    /**
     * A rule for hive/glue iceberg table metadata functions.
     * Ref: FileSystemRulesFactory.TableFilesFunctionScanPrule
     */
    public HiveIcebergTableFileFunctionPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(HiveScanDrel.class, Rel.LOGICAL, Prel.PHYSICAL, pluginId.getType().value() + "HiveIcebergTableFileFunctionPrule."
        + SLUGIFY.slugify(pluginId.getName()) + "." + UUID.randomUUID());
      this.context = context;
      this.storagePluginName = pluginId.getName();
    }

    @Override
    public RelNode convert(RelNode relNode) {
      HiveScanDrel drel = (HiveScanDrel) relNode;
      HiveIcebergScanTableMetadata icebergScanTableMetadata = new HiveIcebergScanTableMetadata(drel.getTableMetadata(),
        context.getCatalogService().getSource(storagePluginName));

      return new IcebergManifestFileContentScanPrel(drel.getCluster(), drel.getTraitSet().plus(Prel.PHYSICAL),
        drel.getTable(), icebergScanTableMetadata, drel.getProjectedColumns(),
        drel.getObservedRowcountAdjustment(), drel.getHintsAsList());
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      HiveScanDrel drel = call.rel(0);
      return drel.getTableMetadata() != null && isTableFilesMetadataFunction(drel.getTableMetadata());
    }

  }

  public class HiveTableModifyPrule extends TableModifyPruleBase {

    public HiveTableModifyPrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(RelOptHelper.some(TableModifyRel.class, Rel.LOGICAL, RelOptHelper.any(RelNode.class)),
        String.format("%sHiveTableModifyPrule.%s.%s",
          pluginId.getType().value(), SLUGIFY.slugify(pluginId.getName()), UUID.randomUUID()), context);
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      return call.<TableModifyRel>rel(0).getCreateTableEntry().getPlugin() instanceof BaseHiveStoragePlugin;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      final TableModifyRel tableModify = call.rel(0);

      onMatch(call,
        new HiveIcebergScanTableMetadata(
          ((DremioPrepareTable) tableModify.getTable()).getTable().getDataset(),
          (SupportsIcebergRootPointer) tableModify.getCreateTableEntry().getPlugin()));
    }
  }

  public static class HiveTableOptimzePrule extends TableOptimizePruleBase {

    public HiveTableOptimzePrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(RelOptHelper.some(TableOptimizeRel.class, Rel.LOGICAL, RelOptHelper.any(RelNode.class)),
        String.format("%sHiveTableOptimzePrule.%s.%s",
          pluginId.getType().value(), SLUGIFY.slugify(pluginId.getName()), UUID.randomUUID()), context);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      final TableOptimizeRel optimizeRel = call.rel(0);
      call.transformTo(getPhysicalPlan(optimizeRel,call.rel(1), new HiveIcebergScanTableMetadata(
        ((DremioPrepareTable) optimizeRel.getTable()).getTable().getDataset(),
        (SupportsIcebergRootPointer) optimizeRel.getCreateTableEntry().getPlugin())));
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      return call.<TableOptimizeRel>rel(0).getCreateTableEntry().getPlugin() instanceof BaseHiveStoragePlugin;
    }
  }

  public static class HiveVacuumTablePrule extends VacuumTablePruleBase {

    public HiveVacuumTablePrule(StoragePluginId pluginId, OptimizerRulesContext context) {
      super(RelOptHelper.some(VacuumTableRel.class, Rel.LOGICAL, RelOptHelper.any(RelNode.class)),
        String.format("%sHiveVacuumTablePrule.%s.%s",
          pluginId.getType().value(), SLUGIFY.slugify(pluginId.getName()), UUID.randomUUID()), context);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      final VacuumTableRel vacuumRel = call.rel(0);
      call.transformTo(getPhysicalPlan(vacuumRel));
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
      return call.<VacuumTableRel>rel(0).getCreateTableEntry().getPlugin() instanceof BaseHiveStoragePlugin;
    }
  }

  @Override
  public Set<RelOptRule> getRules(OptimizerRulesContext optimizerContext, PlannerPhase phase, SourceType pluginType) {
    return ImmutableSet.of();
  }

  @Override
  public Set<RelOptRule> getRules(OptimizerRulesContext optimizerContext, PlannerPhase phase, StoragePluginId pluginId) {
    switch(phase){
      case LOGICAL:
        ImmutableSet.Builder<RelOptRule> builder = ImmutableSet.builder();
        builder.add(new HiveScanDrule(pluginId));
        builder.add(new EliminateEmptyScans(pluginId));

        final PlannerSettings plannerSettings = optimizerContext.getPlannerSettings();

        if (plannerSettings.isPartitionPruningEnabled()) {
          builder.add(new PruneScanRuleFilterOnProject<>(pluginId, HiveScanDrel.class, optimizerContext));
          builder.add(new PruneScanRuleFilterOnScan<>(pluginId, HiveScanDrel.class, optimizerContext));
          builder.add(new PruneScanRuleBase.PruneScanRuleFilterOnSampleScan<>(pluginId, HiveScanDrel.class, optimizerContext));
        }

        final HiveSettings hiveSettings = new HiveSettings(plannerSettings.getOptions(),
          HiveCommonUtilities.isHive2WrappingSourceType(pluginId));
        if (hiveSettings.vectorizeOrcReaders() && hiveSettings.enableOrcFilterPushdown()) {
          builder.add(new ORCFilterPushDownRule(pluginId));
        }

        return builder.build();

      case PHYSICAL:
        return ImmutableSet.of(
          new HiveScanPrule(pluginId, optimizerContext),
          new InternalIcebergScanPrule(pluginId, optimizerContext),
          new HiveDeltaScanPrule(pluginId, optimizerContext),
          new HiveDeltaLakeHistoryScanPrule(pluginId),
          new HiveIcebergScanPrule(pluginId, optimizerContext),
          new HiveIcebergTableFileFunctionPrule(pluginId, optimizerContext),
          new HiveTableModifyPrule(pluginId, optimizerContext),
          new HiveTableOptimzePrule(pluginId, optimizerContext),
          new HiveVacuumTablePrule(pluginId, optimizerContext)
        );

      default:
        return ImmutableSet.of();

    }
  }

  private static boolean isHiveIcebergDataset(HiveScanDrel drel) {
    if (drel == null ||
          drel.getTableMetadata() == null ||
          drel.getTableMetadata().getDatasetConfig() == null ||
          drel.getTableMetadata().getDatasetConfig().getPhysicalDataset() == null ||
          drel.getTableMetadata().getDatasetConfig().getPhysicalDataset().getIcebergMetadata() == null ||
          drel.getTableMetadata().getDatasetConfig().getPhysicalDataset().getIcebergMetadata().getFileType() == null) {
      return false;
    }

    return drel.getTableMetadata().getDatasetConfig().getPhysicalDataset().getIcebergMetadata().getFileType() == FileType.ICEBERG;
  }
}
