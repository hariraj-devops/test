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
package com.dremio.exec.planner.serializer.core;

import com.dremio.exec.planner.serializer.RelCollationSerde;
import com.dremio.exec.planner.serializer.SqlOperatorSerde;
import com.dremio.plan.serialization.PAggregateCall;
import com.dremio.plan.serialization.PGroupSet;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.StringValue;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.SqlAggFunction;

public final class AggregateCallSerde {

  private AggregateCallSerde() {}

  public static List<PAggregateCall> toProtoAggregateCall(
      Aggregate aggregate, SqlOperatorSerde sqlOperatorSerde) {
    return aggregate.getAggCallList().stream()
        .map(c -> AggregateCallSerde.toProto(c, sqlOperatorSerde))
        .collect(ImmutableList.toImmutableList());
  }

  public static PAggregateCall toProto(AggregateCall c, SqlOperatorSerde sqlOperatorSerde) {
    PAggregateCall.Builder builder =
        PAggregateCall.newBuilder()
            .setApproximate(c.isApproximate())
            .addAllArg(c.getArgList())
            .setDistinct(c.isDistinct())
            .setFilterArg(c.filterArg)
            .setRelCollation(RelCollationSerde.toProto(c.collation))
            .setOperator(sqlOperatorSerde.toProto(c.getAggregation()));
    if (c.getName() != null) {
      builder.setName(StringValue.of(c.getName()));
    }

    return builder.build();
  }

  public static List<AggregateCall> fromProto(
      List<PAggregateCall> aggregateCall,
      PGroupSet pGroupSet,
      RelNode input,
      SqlOperatorSerde sqlOperatorSerde) {
    int groupSetSize = pGroupSet.getGroupCount();
    return aggregateCall.stream()
        .map(call -> fromProto(call, input, groupSetSize, sqlOperatorSerde))
        .collect(Collectors.toList());
  }

  public static AggregateCall fromProto(
      PAggregateCall pAggregateCall,
      RelNode input,
      int cardinality,
      SqlOperatorSerde sqlOperatorSerde) {
    final String name = pAggregateCall.hasName() ? pAggregateCall.getName().getValue() : null;

    RelCollation relCollation;
    if (pAggregateCall.hasRelCollationLegacy()) {
      assert !pAggregateCall.hasRelCollation();
      relCollation = RelCollationSerde.fromProto(pAggregateCall.getRelCollationLegacy());
    } else if (pAggregateCall.hasRelCollation()) {
      assert !pAggregateCall.hasRelCollationLegacy();
      relCollation = RelCollationSerde.fromProto(pAggregateCall.getRelCollation());
    } else {
      relCollation = RelCollations.EMPTY;
    }

    return AggregateCall.create(
        (SqlAggFunction) sqlOperatorSerde.fromProto(pAggregateCall.getOperator()),
        pAggregateCall.getDistinct(),
        pAggregateCall.getApproximate(),
        pAggregateCall.getArgList(),
        -1,
        relCollation,
        cardinality,
        input,
        null,
        name);
  }
}
