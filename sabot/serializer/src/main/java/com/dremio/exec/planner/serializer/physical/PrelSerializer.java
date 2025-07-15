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
package com.dremio.exec.planner.serializer.physical;

import com.dremio.exec.planner.physical.AdaptiveHashExchangePrel;
import com.dremio.exec.planner.physical.BridgeExchangePrel;
import com.dremio.exec.planner.physical.BroadcastExchangePrel;
import com.dremio.exec.planner.physical.FilterPrel;
import com.dremio.exec.planner.physical.HashAggPrel;
import com.dremio.exec.planner.physical.HashJoinPrel;
import com.dremio.exec.planner.physical.HashToMergeExchangePrel;
import com.dremio.exec.planner.physical.HashToRandomExchangePrel;
import com.dremio.exec.planner.physical.MergeJoinPrel;
import com.dremio.exec.planner.physical.NestedLoopJoinPrel;
import com.dremio.exec.planner.physical.OrderedPartitionExchangePrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.ResultWriterCommiterPrel;
import com.dremio.exec.planner.physical.ResultWriterPrel;
import com.dremio.exec.planner.physical.RoundRobinExchangePrel;
import com.dremio.exec.planner.physical.ScreenPrel;
import com.dremio.exec.planner.physical.SingleMergeExchangePrel;
import com.dremio.exec.planner.physical.SortPrel;
import com.dremio.exec.planner.physical.StreamAggPrel;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.UnionExchangePrel;
import com.dremio.exec.planner.physical.UnorderedDeMuxExchangePrel;
import com.dremio.exec.planner.physical.UnorderedMuxExchangePrel;
import com.dremio.exec.planner.physical.ValuesPrel;
import com.dremio.exec.planner.serializer.RexDeserializer;
import com.dremio.exec.planner.serializer.RexSerializer;
import com.dremio.exec.planner.serializer.SqlOperatorSerde;
import com.dremio.exec.planner.serializer.TypeSerde;
import com.dremio.exec.planner.serializer.catalog.CatalogFromProto;
import com.dremio.exec.planner.serializer.catalog.CatalogSerializer;
import com.dremio.exec.planner.serializer.catalog.CatalogToProto;
import com.dremio.exec.planner.serializer.core.RelTraitSetSerde;
import com.dremio.exec.store.iceberg.IcebergManifestListPrel;
import com.dremio.plan.serialization.PPhyscialRels.PAdaptiveHashExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PBridgeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PBroadcastExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PFilterPrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashAggPrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashJoinPrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashToMergeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashToRandomExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PIcebergManifestListPrel;
import com.dremio.plan.serialization.PPhyscialRels.PMergeJoinPrel;
import com.dremio.plan.serialization.PPhyscialRels.PNestedLoopJoinPrel;
import com.dremio.plan.serialization.PPhyscialRels.POrderedPartitionExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PPhysicalRelNode;
import com.dremio.plan.serialization.PPhyscialRels.PProjectPrel;
import com.dremio.plan.serialization.PPhyscialRels.PResultWriterCommiterPrel;
import com.dremio.plan.serialization.PPhyscialRels.PResultWriterPrel;
import com.dremio.plan.serialization.PPhyscialRels.PRoundRobinExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PScreenPrel;
import com.dremio.plan.serialization.PPhyscialRels.PSingleMergeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PSortPrel;
import com.dremio.plan.serialization.PPhyscialRels.PStreamAggPrel;
import com.dremio.plan.serialization.PPhyscialRels.PTableFunctionPrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnionExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnorderedDeMuxExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnorderedMuxExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PValuesPrel;
import com.dremio.plan.serialization.PRelDataTypeField;
import com.dremio.plan.serialization.PRelTraitSet;
import com.dremio.plan.serialization.PRexLiteral;
import com.dremio.plan.serialization.PRexNode;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;

public class PrelSerializer implements PrelToProto, ProtoToPrel {

  private final RexSerializer rexSerializer;
  private final RexDeserializer rexDeserializer;

  private final TypeSerde typeSerde;
  private final RelOptCluster relOptCluster;
  private final SqlOperatorSerde sqlOperatorSerde;
  private final CatalogSerializer catalogSerializer;

  public PrelSerializer(
      RelOptCluster relOptCluster,
      RexSerializer rexSerializer,
      RexDeserializer rexDeserializer,
      TypeSerde typeSerde,
      SqlOperatorSerde sqlOperatorSerde,
      CatalogSerializer catalogSerializer) {
    this.relOptCluster = relOptCluster;
    this.rexSerializer = rexSerializer;
    this.rexDeserializer = rexDeserializer;
    this.typeSerde = typeSerde;
    this.sqlOperatorSerde = sqlOperatorSerde;
    this.catalogSerializer = catalogSerializer;
  }

  @Override
  public Prel fromProto(PPhysicalRelNode proto) {
    switch (proto.getDerivedClassCase()) {
      case HASH_AGGREGATE:
        return AggPrelSerde.fromProto(proto.getHashAggregate(), this);
      case STREAM_AGGREGATE:
        return AggPrelSerde.fromProto(proto.getStreamAggregate(), this);
      case FILTER:
        return FilterPrelSerde.fromProto(proto.getFilter(), this);
      case HASH_JOIN:
        return JoinPrelSerde.fromProto(proto.getHashJoin(), this);
      case MERGE_JOIN:
        return JoinPrelSerde.fromProto(proto.getMergeJoin(), this);
      case NESTED_LOOP_JOIN:
        return JoinPrelSerde.fromProto(proto.getNestedLoopJoin(), this);
      case PROJECT:
        return ProjectPrelSerde.fromProto(proto.getProject(), this);
      case RESULT_WRITER:
        return ResultWriterPrelSerde.fromProto(proto.getResultWriter(), this);
      case RESULT_WRITER_COMMITER:
        return ResultWriterCommiterPrelSerde.fromProto(proto.getResultWriterCommiter(), this);
      case SCREEN:
        return ScreenPrelSerde.fromProto(proto.getScreen(), this);
      case SORT:
        return SortPrelSerde.fromProto(proto.getSort(), this);
      case VALUES:
        return ValuesPrelSerde.fromProto(proto.getValues(), this);

      // exchanges
      case ADAPTIVE_HASH_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getAdaptiveHashExchange(), this);
      case BRIDGE_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getBridgeExchange(), this);
      case BROADCAST_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getBroadcastExchange(), this);
      case HASH_TO_MERGE_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getHashToMergeExchange(), this);
      case HASH_TO_RANDOM_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getHashToRandomExchange(), this);
      case ORDERED_PARTITION_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getOrderedPartitionExchange(), this);
      case ROUND_ROBIN_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getRoundRobinExchange(), this);
      case SINGLE_MERGE_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getSingleMergeExchange(), this);
      case UNION_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getUnionExchange(), this);
      case UNORDERED_DEMUX_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getUnorderedDemuxExchange(), this);
      case UNORDERED_MUX_EXCHANGE:
        return ExchangePrelSerde.fromProto(proto.getUnorderedMuxExchange(), this);
      // Scan
      case TABLE_FUNCTION:
        return TableFunctionPrelSerde.fromProto(proto.getTableFunction(), this);
      case ICEBERG_MANIFEST_LIST:
        return IcebergManifestListPrelSerde.fromProto(proto.getIcebergManifestList(), this);

      case DERIVEDCLASS_NOT_SET:
      default:
        throw new UnsupportedOperationException(proto.getDerivedClassCase().name());
    }
  }

  @Override
  public PPhysicalRelNode toProto(Prel prel) {
    if (prel instanceof IcebergManifestListPrel) {
      PIcebergManifestListPrel icebergManifestListPrel =
          IcebergManifestListPrelSerde.toProto((IcebergManifestListPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setIcebergManifestList(icebergManifestListPrel).build();
    } else if (prel.getClass() == TableFunctionPrel.class) {
      PTableFunctionPrel pTableFunctionPrel =
          TableFunctionPrelSerde.toProto((TableFunctionPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setTableFunction(pTableFunctionPrel).build();
    } else if (prel instanceof StreamAggPrel) {
      PStreamAggPrel streamAggPrel = AggPrelSerde.toProto((StreamAggPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setStreamAggregate(streamAggPrel).build();
    } else if (prel instanceof HashAggPrel) {
      PHashAggPrel hashAggPrel = AggPrelSerde.toProto((HashAggPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setHashAggregate(hashAggPrel).build();
    } else if (prel instanceof HashJoinPrel) {
      PHashJoinPrel hashJoinPrel = JoinPrelSerde.toProto((HashJoinPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setHashJoin(hashJoinPrel).build();
    } else if (prel instanceof MergeJoinPrel) {
      PMergeJoinPrel mergeJoinPrel = JoinPrelSerde.toProto((MergeJoinPrel) prel, this);
      return PPhysicalRelNode.newBuilder().mergeMergeJoin(mergeJoinPrel).build();
    } else if (prel instanceof NestedLoopJoinPrel) {
      PNestedLoopJoinPrel nestedLoopJoinPrel =
          JoinPrelSerde.toProto((NestedLoopJoinPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setNestedLoopJoin(nestedLoopJoinPrel).build();
    } else if (prel instanceof FilterPrel) {
      PFilterPrel pFilterPrel = FilterPrelSerde.toProto((FilterPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setFilter(pFilterPrel).build();
    } else if (prel instanceof ProjectPrel) {
      PProjectPrel pProject = ProjectPrelSerde.toProto((ProjectPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setProject(pProject).build();
    } else if (prel instanceof ResultWriterCommiterPrel) {
      PResultWriterCommiterPrel commiter =
          ResultWriterCommiterPrelSerde.toProto((ResultWriterCommiterPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setResultWriterCommiter(commiter).build();
    } else if (prel instanceof ResultWriterPrel) {
      PResultWriterPrel pResultWriterPrel =
          ResultWriterPrelSerde.toProto((ResultWriterPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setResultWriter(pResultWriterPrel).build();
    } else if (prel instanceof ScreenPrel) {
      PScreenPrel pScreenPrel = ScreenPrelSerde.toProto((ScreenPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setScreen(pScreenPrel).build();
    } else if (prel instanceof SortPrel) {
      PSortPrel pSortPrel = SortPrelSerde.toProto((SortPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setSort(pSortPrel).build();
    } else if (prel instanceof ValuesPrel) {
      PValuesPrel pPhysicalValues = ValuesPrelSerde.toProto((ValuesPrel) prel, this);
      return PPhysicalRelNode.newBuilder().setValues(pPhysicalValues).build();
    } else if (prel instanceof AdaptiveHashExchangePrel) {
      PAdaptiveHashExchangePrel pAdaptiveHashExchange =
          ExchangePrelSerde.toProto((AdaptiveHashExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setAdaptiveHashExchange(pAdaptiveHashExchange).build();
    } else if (prel instanceof BridgeExchangePrel) {
      PBridgeExchangePrel pBridgeExchange =
          ExchangePrelSerde.toProto((BridgeExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setBridgeExchange(pBridgeExchange).build();
    } else if (prel instanceof BroadcastExchangePrel) {
      PBroadcastExchangePrel pBroadcastExchange =
          ExchangePrelSerde.toProto((BroadcastExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setBroadcastExchange(pBroadcastExchange).build();
    } else if (prel instanceof HashToMergeExchangePrel) {
      PHashToMergeExchangePrel pHashToMergeExchange =
          ExchangePrelSerde.toProto((HashToMergeExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setHashToMergeExchange(pHashToMergeExchange).build();
    } else if (prel instanceof HashToRandomExchangePrel) {
      PHashToRandomExchangePrel pHashToRandomExchange =
          ExchangePrelSerde.toProto((HashToRandomExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setHashToRandomExchange(pHashToRandomExchange).build();
    } else if (prel instanceof OrderedPartitionExchangePrel) {
      POrderedPartitionExchangePrel pOrderedPartitionExchange =
          ExchangePrelSerde.toProto((OrderedPartitionExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder()
          .setOrderedPartitionExchange(pOrderedPartitionExchange)
          .build();
    } else if (prel instanceof RoundRobinExchangePrel) {
      PRoundRobinExchangePrel pRoundRobinExchange =
          ExchangePrelSerde.toProto((RoundRobinExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setRoundRobinExchange(pRoundRobinExchange).build();
    } else if (prel instanceof SingleMergeExchangePrel) {
      PSingleMergeExchangePrel pSingleMergeExchange =
          ExchangePrelSerde.toProto((SingleMergeExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setSingleMergeExchange(pSingleMergeExchange).build();
    } else if (prel instanceof UnionExchangePrel) {
      PUnionExchangePrel pUnionExchange = ExchangePrelSerde.toProto((UnionExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setUnionExchange(pUnionExchange).build();
    } else if (prel instanceof UnorderedDeMuxExchangePrel) {
      PUnorderedDeMuxExchangePrel pUnorderedDeMuxExchange =
          ExchangePrelSerde.toProto((UnorderedDeMuxExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder()
          .setUnorderedDemuxExchange(pUnorderedDeMuxExchange)
          .build();
    } else if (prel instanceof UnorderedMuxExchangePrel) {
      PUnorderedMuxExchangePrel pUnorderedMuxExchange =
          ExchangePrelSerde.toProto((UnorderedMuxExchangePrel) prel, this);
      return PPhysicalRelNode.newBuilder().setUnorderedMuxExchange(pUnorderedMuxExchange).build();
    } else {
      throw new UnsupportedOperationException(prel.getClass().getName());
    }
  }

  @Override
  public Iterable<PRelDataTypeField> toProto(List<RelDataTypeField> fieldList) {
    return typeSerde.toProto(fieldList);
  }

  @Override
  public PRelTraitSet toProto(RelTraitSet traitSet) {
    return RelTraitSetSerde.toProto(traitSet);
  }

  @Override
  public PRexNode toProto(RexNode node) {
    if (null == node) {
      return null;
    }
    return node.accept(rexSerializer);
  }

  @Override
  public TypeSerde getTypeSerde() {
    return typeSerde;
  }

  @Override
  public RexLiteral toRex(PRexLiteral literal) {
    return rexDeserializer.convertLiteral(literal);
  }

  @Override
  public RelTraitSet fromProto(PRelTraitSet traitSet) {
    return RelTraitSetSerde.fromProto(traitSet);
  }

  @Override
  public RelDataType fromProto(List<PRelDataTypeField> fieldsList) {
    return typeSerde.fromProto(fieldsList);
  }

  @Override
  public RexNode fromProto(PRexNode pRexNode) {
    if (pRexNode.getRexTypeCase() == PRexNode.RexTypeCase.REXTYPE_NOT_SET) {
      return null;
    }
    return rexDeserializer.convert(pRexNode);
  }

  @Override
  public PRexLiteral toProto(RexLiteral rexLiteral) {
    return rexSerializer.toProto(rexLiteral);
  }

  @Override
  public RelOptCluster getCluster() {
    return relOptCluster;
  }

  @Override
  public SqlOperatorSerde sqlOperatorSerde() {
    return sqlOperatorSerde;
  }

  @Override
  public CatalogToProto getCatalogToProto() {
    return catalogSerializer;
  }

  @Override
  public CatalogFromProto getCatalogFromProto() {
    return catalogSerializer;
  }
}
