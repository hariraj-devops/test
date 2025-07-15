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
package com.dremio.exec.planner.serializer;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.catalog.DremioTranslatableTable;
import com.dremio.exec.planner.serializer.core.AggregateCallSerde;
import com.dremio.exec.planner.serializer.core.CommonRelSerde;
import com.dremio.exec.planner.serializer.core.RelFieldCollationSerde;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.plan.serialization.PAggregateCall;
import com.dremio.plan.serialization.PGroupSet;
import com.dremio.plan.serialization.PJoinType;
import com.dremio.plan.serialization.PRelDataType;
import com.dremio.plan.serialization.PRelDataTypeField;
import com.dremio.plan.serialization.PRelFieldCollation;
import com.dremio.plan.serialization.PRexLiteral;
import com.dremio.plan.serialization.PRexNode;
import com.dremio.plan.serialization.PRexWindowBound;
import com.dremio.plan.serialization.PSqlOperator;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable.ToRelContext;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Interface that defines how to move to and from a particular RelNode.
 *
 * @param <REL_NODE> The RelNode implementation.
 * @param <PROTO_NODE> The Message implementation mapped to the RelNode
 */
public interface RelNodeSerde<REL_NODE extends RelNode, PROTO_NODE extends Message> {

  @SuppressWarnings("unchecked")
  default Message serializeGeneric(RelNode node, RelToProto s) {
    return serialize((REL_NODE) node, s);
  }

  /**
   * How to serialize this RelNode
   *
   * @param node The node to serialize
   * @param s Contextual utility to help with serialization.
   * @return The serialized Protobuf message
   */
  PROTO_NODE serialize(REL_NODE node, RelToProto s);

  /**
   * How to deserialize a RelNode from the corresponding Protobuf Message.
   *
   * @param node The Protobuf Message to deserialize
   * @param s Contextual utility used to help with deserialization.
   * @return
   */
  REL_NODE deserialize(PROTO_NODE node, RelFromProto s);

  @SuppressWarnings("unchecked")
  default REL_NODE deserialize(Any any, RelFromProto s) {
    try {
      return deserialize((PROTO_NODE) any.unpack(getDefaultInstance().getClass()), s);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  default PROTO_NODE getDefaultInstance() {
    return (PROTO_NODE)
        MessageTools.getDefaultInstance(MessageTools.getClassArg(this, Message.class, 1));
  }

  @SuppressWarnings("unchecked")
  default Class<REL_NODE> getRelClass() {
    return (Class<REL_NODE>) MessageTools.getClassArg(this, RelNode.class, 0);
  }

  /** Utility to help with RelNode serialization. */
  interface RelToProto {

    default PJoinType toProto(JoinRelType joinType) {
      return CommonRelSerde.toProto(joinType);
    }

    default List<PAggregateCall> toProtoAggregateCall(Aggregate aggregate) {
      return AggregateCallSerde.toProtoAggregateCall(aggregate, getSqlOperatorSerde());
    }

    default PGroupSet toProtoGroupSet(ImmutableBitSet immutableBitSet) {
      return CommonRelSerde.toProtoGroupSet(immutableBitSet);
    }

    default List<PRelFieldCollation> toProtoRelFieldCollation(
        List<RelFieldCollation> relFieldCollationList) {
      return relFieldCollationList.stream()
          .map(RelFieldCollationSerde::toProto)
          .collect(ImmutableList.toImmutableList());
    }

    /**
     * Convert a RelNode to a proto
     *
     * @param node The node to serialize
     * @return The identifier for that RelNode within the serialized data.
     */
    int toProto(RelNode node);

    /**
     * Convert a RexNode to a proto
     *
     * @param node The RexNode to convert
     * @return The converted protobuf message.
     */
    PRexNode toProto(RexNode node);

    /**
     * Convert a RelDataType to a proto.
     *
     * @param type The RelDataType to convert.
     * @return The converted protobuf message.
     */
    PRelDataType toProto(RelDataType type);

    /**
     * Converts a SqlOperator to a proto.
     *
     * @param op The SqlOperator to convert.
     * @return The converted protobuf message.
     */
    PSqlOperator toProto(SqlOperator op);

    /**
     * Get the Serde for SqlOperators
     *
     * @return SqlOperatorConverter instance
     */
    SqlOperatorSerde getSqlOperatorSerde();

    /**
     * Converts a rexWindowBound to a proto.
     *
     * @param RexWindowBound The RexWinAggCall to convert.
     * @return The converted protobuf message.
     */
    PRexWindowBound toProto(RexWindowBound rexWindowBound);

    /**
     * Method for converting Row Type fields.
     *
     * @param fieldList list of fields for row type.
     * @return List of proto representations of field types.
     */
    ImmutableList<PRelDataTypeField> toProto(List<RelDataTypeField> fieldList);

    /**
     * Converts a rex to a proto.
     *
     * @param rexLiteral
     * @return Proto for literal
     */
    PRexLiteral toProtoLiteral(RexLiteral rexLiteral);
  }

  /** Utility to help with RelNode deserialization */
  interface RelFromProto {

    SqlOperatorSerde sqlOperatorSerde();

    /**
     * Get the ToRelContext
     *
     * @return The ToRelContext to use when attempting to convert values into RelNodes.
     */
    ToRelContext toRelContext();

    /**
     * Retrieve a RelNode based on its serialized identifier
     *
     * @param index Identifier for a RelNode.
     * @return The associated RelNode.
     */
    RelNode toRel(int index);

    /**
     * Retrieve a RexNode based on its serialized form.
     *
     * @param rex The serialized node.
     * @return The hydrated RexNode.
     */
    RexNode toRex(PRexNode rex);

    RexLiteral toRex(PRexLiteral literal);

    /**
     * Retrieve a RexWindowBound based on its serialized form.
     *
     * @param PRexWindowBound The serialized node.
     * @return The hydrated RexNode.
     */
    RexWindowBound toRex(PRexWindowBound pRexWindowBound);

    /**
     * Retrieve a RexNode based on its serialized form and incoming rowType.
     *
     * @param rex The serialized node.
     * @param RelDataType incoming row type.
     * @return The hydrated RexNode.
     */
    RexNode toRex(PRexNode rex, RelDataType rowType);

    /**
     * Retrieve a RelDataType from it's serialized form.
     *
     * @param type The serialized type.
     * @return The deserialized RelDataType.
     */
    RelDataType toRelDataType(PRelDataType type);

    /**
     * Create a new RelBuilder for purpose of generating RelNodes.
     *
     * @return The new RelBuilder.
     */
    RelBuilder builder();

    /**
     * Get an interface for retrieving tables.
     *
     * @return A TableRetriever.
     */
    TableRetriever tables();

    /**
     * Get an interface for retrieving plugins.
     *
     * @return A PluginRetriever.
     */
    PluginRetriever plugins();

    /**
     * Get the cluster associated with this deserialization.
     *
     * @return A RelOptCluster.
     */
    RelOptCluster cluster();

    /**
     * Converts a fields list to RowType.
     *
     * @param fieldsList Proto list of rowType.
     * @return {@link RelDataType} that is row type.
     */
    RelDataType toRowType(List<PRelDataTypeField> fieldsList);

    /**
     * Convert a to {@link RexLiteral}.
     *
     * @param literal Proto to convert.
     * @return Converted RexLiteral.
     */
    RexLiteral toRexLiteral(PRexLiteral literal);

    default JoinRelType fromProto(PJoinType joinType) {
      return CommonRelSerde.fromProto(joinType);
    }

    default ImmutableBitSet fromProto(PGroupSet pGroupSet) {
      return CommonRelSerde.fromProtoGroupSet(pGroupSet);
    }

    default List<AggregateCall> fromProto(
        List<PAggregateCall> aggregateCall, PGroupSet pGroupSet, RelNode input) {
      return AggregateCallSerde.fromProto(aggregateCall, pGroupSet, input, sqlOperatorSerde());
    }
  }

  /** Simplified interface used for retrieving tables from a context. */
  public interface TableRetriever {

    /**
     * For a particular namespace key, get the associated DremioPrepareTable.
     *
     * @param key Key to lookup
     * @return Table object.
     */
    DremioPrepareTable getTable(NamespaceKey key);

    DremioTranslatableTable getTableSnapshot(CatalogEntityKey catalogEntityKey);
  }

  /** Simplified interface used for retrieving plugin from a context. */
  public interface PluginRetriever {

    /**
     * For a particular namespace key, get the associated DremioPrepareTable.
     *
     * @param name Name to lookup
     * @return StoragePluginId object.
     */
    StoragePlugin getPlugin(String name);
  }
}
