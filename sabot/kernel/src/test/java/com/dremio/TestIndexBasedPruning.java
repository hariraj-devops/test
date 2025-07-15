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
package com.dremio;

import static org.apache.calcite.sql.fun.SqlStdOperatorTable.AND;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.GREATER_THAN;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.expression.SchemaPath;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.exec.catalog.AbstractSplitsPointer;
import com.dremio.exec.catalog.MaterializedSplitsPointer;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.TableMetadataImpl;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.EmptyRel;
import com.dremio.exec.planner.logical.FilterRel;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.SampleRel;
import com.dremio.exec.planner.logical.partition.PruneScanRuleBase;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.types.JavaTypeFactoryImpl;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.PruneableScan;
import com.dremio.options.OptionResolver;
import com.dremio.resource.ClusterResourceInformation;
import com.dremio.service.namespace.PartitionChunkMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.Affinity;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.DatasetSplit;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.NormalizedPartitionInfo;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.PartitionValue;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.test.DremioTest;
import com.dremio.test.specs.OptionResolverSpec;
import com.dremio.test.specs.OptionResolverSpecBuilder;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.plan.RelHintsPropagator;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.DateString;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test for index based pruning in {@code PruneScanRuleBase}. This test uses custom splits pointer
 * that verifies if expected search query is passed.
 */
public class TestIndexBasedPruning extends DremioTest {

  private static final ImmutableList<SchemaPath> PROJECTED_COLUMNS =
      ImmutableList.of(SchemaPath.getSimplePath("date_col"), SchemaPath.getSimplePath("int_col"));
  private static final TestPartitionChunkMetadata TEST_PARTITION_CHUNK_METADATA_1 =
      new TestPartitionChunkMetadata(100);
  private static final TestPartitionChunkMetadata TEST_PARTITION_CHUNK_METADATA_2 =
      new TestPartitionChunkMetadata(200);

  private final BatchSchema SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable("date_col", new ArrowType.Date(DateUnit.MILLISECOND)))
          .addField(Field.nullable("int_col", new ArrowType.Int(32, true)))
          .build();

  private final DatasetConfig DATASET_CONFIG =
      new DatasetConfig()
          .setId(new EntityId(UUID.randomUUID().toString()))
          .setFullPathList(Arrays.asList("test", "foo"))
          .setName("foo")
          .setOwner("testuser")
          .setReadDefinition(
              new ReadDefinition()
                  .setSplitVersion(0L)
                  .setPartitionColumnsList(ImmutableList.of("date_col", "int_col")))
          .setRecordSchema(SCHEMA.toByteString());

  /** Prunable SplitsPointer for testing */
  public class TestSplitsPointer extends AbstractSplitsPointer {
    private final long splitVersion;
    private final int totalSplitCount;
    private final List<PartitionChunkMetadata> materializedPartitionChunks;

    TestSplitsPointer(
        long splitVersion, Iterable<PartitionChunkMetadata> partitionChunks, int totalSplitCount) {
      this.splitVersion = splitVersion;
      this.materializedPartitionChunks = ImmutableList.copyOf(partitionChunks);
      this.totalSplitCount = totalSplitCount;
    }

    @Override
    public SplitsPointer prune(SearchTypes.SearchQuery partitionFilterQuery) {
      assertTrue(partitionFilterQuery.equals(expected), "Given search query is not expected");
      indexPruned = true;
      // return an empty pointer after pruning
      return MaterializedSplitsPointer.of(0, Arrays.asList(), 0);
    }

    @Override
    public long getSplitVersion() {
      return splitVersion;
    }

    @Override
    public Iterable<PartitionChunkMetadata> getPartitionChunks() {
      return materializedPartitionChunks;
    }

    @Override
    public int getTotalSplitsCount() {
      return totalSplitCount;
    }
  }

  /** A mock partition chunk metadata implementation */
  private static final class TestPartitionChunkMetadata implements PartitionChunkMetadata {
    final int rowCount;

    private TestPartitionChunkMetadata(int rowCount) {
      this.rowCount = rowCount;
    }

    @Override
    public long getSize() {
      return 0;
    }

    @Override
    public long getRowCount() {
      return rowCount;
    }

    @Override
    public Iterable<PartitionValue> getPartitionValues() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSplitKey() {
      return "key";
    }

    @Override
    public int getSplitCount() {
      return 1;
    }

    @Override
    public Iterable<DatasetSplit> getDatasetSplits() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ByteString getPartitionExtendedProperty() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<Affinity> getAffinities() {
      throw new UnsupportedOperationException();
    }

    @Override
    public NormalizedPartitionInfo getNormalizedPartitionInfo() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean checkPartitionChunkMetadataConsistency() {
      return true;
    }
  }

  /** A pruneable physical scan */
  private static final class TestScanRel extends ScanRelBase implements PruneableScan {
    private final boolean hasFilter;

    public TestScanRel(
        RelOptCluster cluster,
        RelTraitSet traitSet,
        RelOptTable table,
        StoragePluginId pluginId,
        TableMetadata dataset,
        List<SchemaPath> projectedColumns,
        double observedRowcountAdjustment,
        List<RelHint> hints,
        boolean hasFilter) {
      super(
          cluster,
          traitSet,
          table,
          pluginId,
          dataset,
          projectedColumns,
          observedRowcountAdjustment,
          hints);
      this.hasFilter = hasFilter;
    }

    @Override
    public RelNode applyDatasetPointer(TableMetadata newDatasetPointer) {
      return new TestScanRel(
          getCluster(),
          traitSet,
          getTable(),
          pluginId,
          newDatasetPointer,
          getProjectedColumns(),
          observedRowcountAdjustment,
          hints,
          hasFilter);
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new TestScanRel(
          getCluster(),
          traitSet,
          getTable(),
          pluginId,
          tableMetadata,
          getProjectedColumns(),
          observedRowcountAdjustment,
          hints,
          hasFilter);
    }

    @Override
    public ScanRelBase cloneWithProject(List<SchemaPath> projection) {
      return new TestScanRel(
          getCluster(),
          traitSet,
          getTable(),
          pluginId,
          tableMetadata,
          getProjectedColumns(),
          observedRowcountAdjustment,
          hints,
          hasFilter);
    }
  }

  private static final RelTraitSet TRAITS = RelTraitSet.createEmpty().plus(Rel.LOGICAL);
  private static final RelDataTypeFactory TYPE_FACTORY = JavaTypeFactoryImpl.INSTANCE;
  private static final RexBuilder REX_BUILDER = new RexBuilder(TYPE_FACTORY);

  @Mock private OptimizerRulesContext optimizerRulesContext;
  @Mock private StoragePluginId pluginId;
  @Mock private RelOptTable table;
  @Mock private RelOptPlanner planner;
  private TestScanRel indexPrunableScan;
  private FilterRel filterAboveScan;
  private PruneScanRuleBase scanRule;
  private FilterRel filterAboveSample;
  private SampleRel sampleRel;
  private PruneScanRuleBase sampleScanRule;
  private RexNode rexNode;
  private SearchTypes.SearchQuery expected;
  private boolean indexPruned;
  private PlannerSettings plannerSettings;

  private static Stream<Arguments> getTestCases() {
    RexInputRef dateCol =
        REX_BUILDER.makeInputRef(
            TYPE_FACTORY.createTypeWithNullability(
                TYPE_FACTORY.createSqlType(SqlTypeName.DATE), true),
            0);
    RexNode dateLiteral = REX_BUILDER.makeDateLiteral(new DateString("2010-01-01"));
    RexInputRef intCol =
        REX_BUILDER.makeInputRef(
            TYPE_FACTORY.createTypeWithNullability(
                TYPE_FACTORY.createSqlType(SqlTypeName.INTEGER), true),
            1);
    RexNode intLiteral =
        REX_BUILDER.makeLiteral(2, TYPE_FACTORY.createSqlType(SqlTypeName.INTEGER), false);
    RexNode castDate = REX_BUILDER.makeCast(dateLiteral.getType(), intLiteral);

    long longVal = ((GregorianCalendar) ((RexLiteral) dateLiteral).getValue()).getTimeInMillis();
    SearchTypes.SearchQuery q1 =
        SearchQueryUtils.and(
            SearchQueryUtils.newRangeLong("$D$::LONG-date_col", longVal, longVal, true, true));
    RexNode cond1 = REX_BUILDER.makeCall(EQUALS, dateCol, dateLiteral);

    int intVal =
        ((BigDecimal) ((RexLiteral) intLiteral).getValue())
            .setScale(0, RoundingMode.HALF_UP)
            .intValue();
    SearchTypes.SearchQuery q2 =
        SearchQueryUtils.and(
            SearchQueryUtils.newRangeInt("$D$::INTEGER-int_col", intVal, intVal, true, true));
    RexNode cond2 = REX_BUILDER.makeCall(EQUALS, intCol, intLiteral);

    RexNode cond3 = REX_BUILDER.makeCall(EQUALS, dateCol, castDate);

    RexNode cond4 = REX_BUILDER.makeCall(GREATER_THAN, dateCol, castDate);

    // equivalent to where $0 = "2010-01-01" and $1 = 1 => both filters can be index pruned
    RexNode testCondition1 = REX_BUILDER.makeCall(AND, cond1, cond2);

    // equivalent to where $0 = CAST(1 as DATE) and $1 = 1 => only the second filter can be index
    // pruned
    RexNode testCondition2 = REX_BUILDER.makeCall(AND, cond3, cond2);

    // equivalent to where $0 = CAST(1 as DATE) and $0 > CAST(1 as DATE) => none of them can be
    // index pruned
    RexNode testCondition3 = REX_BUILDER.makeCall(AND, cond3, cond4);

    return Stream.of(
        Arguments.of(testCondition1, SearchQueryUtils.and(ImmutableList.of(q2, q1))),
        Arguments.of(testCondition2, SearchQueryUtils.and(ImmutableList.of(q2))),
        Arguments.of(testCondition3, null));
  }

  public AutoCloseable setUp(RexNode rexNode, SearchTypes.SearchQuery expected) {
    AutoCloseable mockCloseable = MockitoAnnotations.openMocks(this);
    OptionResolver optionResolver = OptionResolverSpecBuilder.build(new OptionResolverSpec());

    ClusterResourceInformation info = mock(ClusterResourceInformation.class);
    when(info.getExecutorNodeCount()).thenReturn(1);

    plannerSettings =
        new PlannerSettings(DremioTest.DEFAULT_SABOT_CONFIG, optionResolver, () -> info);

    when(optimizerRulesContext.getPlannerSettings()).thenReturn(plannerSettings);

    RelOptCluster cluster = RelOptCluster.create(new VolcanoPlanner(plannerSettings), REX_BUILDER);
    SplitsPointer splitsPointer =
        new TestSplitsPointer(
            0, Arrays.asList(TEST_PARTITION_CHUNK_METADATA_1, TEST_PARTITION_CHUNK_METADATA_2), 2);

    TableMetadata indexPrunableMetadata =
        new TableMetadataImpl(pluginId, DATASET_CONFIG, "testuser", splitsPointer, null);
    SourceType newType = mock(SourceType.class);
    when(newType.value()).thenReturn("TestSource");
    when(pluginId.getType()).thenReturn(newType);

    this.rexNode = rexNode;
    this.expected = expected;
    indexPrunableScan =
        new TestScanRel(
            cluster,
            TRAITS,
            table,
            pluginId,
            indexPrunableMetadata,
            PROJECTED_COLUMNS,
            0,
            ImmutableList.of(),
            false);
    filterAboveScan = new FilterRel(cluster, TRAITS, indexPrunableScan, rexNode);
    filterAboveScan = new FilterRel(cluster, TRAITS, indexPrunableScan, rexNode);
    scanRule =
        new PruneScanRuleBase.PruneScanRuleFilterOnScan<>(
            pluginId.getType(), TestScanRel.class, optimizerRulesContext);
    sampleRel = new SampleRel(cluster, TRAITS, indexPrunableScan);
    filterAboveSample = new FilterRel(cluster, TRAITS, sampleRel, rexNode);
    sampleScanRule =
        new PruneScanRuleBase.PruneScanRuleFilterOnSampleScan<>(
            pluginId.getType(), TestScanRel.class, optimizerRulesContext);

    return mockCloseable;
  }

  @ParameterizedTest(
      name =
          "{index}: Doing index pruning on {0}. Following condition is expected to be passed: {1}")
  @MethodSource("getTestCases")
  public void testIndexPruning(RexNode rexNode, SearchTypes.SearchQuery expected) throws Exception {
    try (AutoCloseable ignored = setUp(rexNode, expected)) {
      indexPruned = false;
      RelOptRuleCall pruneCall = newCall(scanRule, filterAboveScan, indexPrunableScan);
      when(planner.getContext()).thenReturn(plannerSettings);
      scanRule.onMatch(pruneCall);
      if (expected == null) {
        assertTrue(!indexPruned, "Index pruned for a wrong condition");
      }
    }
  }

  @ParameterizedTest(
      name =
          "{index}: Doing index pruning on {0}. Following condition is expected to be passed: {1}")
  @MethodSource("getTestCases")
  public void testIndexPruningSampleScan(RexNode rexNode, SearchTypes.SearchQuery expected)
      throws Exception {
    try (AutoCloseable ignored = setUp(rexNode, expected)) {
      indexPruned = false;
      RelOptRuleCall pruneCall =
          newCall(sampleScanRule, filterAboveScan, sampleRel, indexPrunableScan);
      when(planner.getContext()).thenReturn(plannerSettings);
      sampleScanRule.onMatch(pruneCall);
      if (expected == null) {
        assertTrue(!indexPruned, "Index pruned for a wrong condition");
      } else {
        assertTrue(indexPruned, "Index not pruned");
      }
    }
  }

  private RelOptRuleCall newCall(PruneScanRuleBase rule, RelNode... operands) {
    return new RelOptRuleCall(null, rule.getOperand(), operands, Collections.emptyMap()) {
      @Override
      public void transformTo(
          RelNode rel, Map<RelNode, RelNode> equiv, RelHintsPropagator handler) {
        if (rule instanceof PruneScanRuleBase.PruneScanRuleFilterOnSampleScan) {
          assertTrue(
              rel instanceof SampleRel && ((SampleRel) rel).getInput() instanceof EmptyRel,
              "SampleRel is expected after pruning");
        } else {
          assertTrue(rel instanceof EmptyRel, "EmptyRel is expected after pruning");
        }
      }

      @Override
      public RelOptPlanner getPlanner() {
        return planner;
      }
    };
  }
}
