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
package com.dremio.exec.planner.serializer.logical;

import com.dremio.exec.planner.serializer.RelNodeSerde;
import com.dremio.plan.serialization.PLogicalAggregate;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.util.ImmutableBitSet;

/** Serde for LogicalAggregate */
public final class LogicalAggregateSerde
    implements RelNodeSerde<LogicalAggregate, PLogicalAggregate> {
  @Override
  public PLogicalAggregate serialize(LogicalAggregate aggregate, RelToProto s) {
    return PLogicalAggregate.newBuilder()
        .setInput(s.toProto(aggregate.getInput()))
        .addAllAggregateCall(s.toProtoAggregateCall(aggregate))
        .setGroupSet(s.toProtoGroupSet(aggregate.getGroupSet()))
        .addAllGroupSets(
            aggregate.getGroupSets().stream()
                .map(s::toProtoGroupSet)
                .collect(ImmutableList.toImmutableList()))
        .setIndicator(false)
        .build();
  }

  @Override
  public LogicalAggregate deserialize(PLogicalAggregate node, RelFromProto s) {
    RelNode input = s.toRel(node.getInput());
    List<AggregateCall> calls = s.fromProto(node.getAggregateCallList(), node.getGroupSet(), input);
    ImmutableBitSet groupSet = s.fromProto(node.getGroupSet());
    List<ImmutableBitSet> groupSets =
        node.getGroupSetsList().stream().map(s::fromProto).collect(Collectors.toList());
    return LogicalAggregate.create(input, groupSet, groupSets, calls);
  }
}
