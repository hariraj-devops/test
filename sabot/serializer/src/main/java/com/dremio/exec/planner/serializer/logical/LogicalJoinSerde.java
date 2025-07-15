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
import com.dremio.plan.serialization.PLogicalJoin;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalJoin;

/** Serde for LogicalJoin */
public final class LogicalJoinSerde implements RelNodeSerde<LogicalJoin, PLogicalJoin> {
  @Override
  public PLogicalJoin serialize(LogicalJoin join, RelToProto s) {
    return PLogicalJoin.newBuilder()
        .setLeftInput(s.toProto(join.getLeft()))
        .setRightInput(s.toProto(join.getRight()))
        .setJoinType(s.toProto(join.getJoinType()))
        .setCondition(s.toProto(join.getCondition()))
        .build();
  }

  @Override
  public LogicalJoin deserialize(PLogicalJoin node, RelFromProto s) {
    RelNode left = s.toRel(node.getLeftInput());
    RelNode right = s.toRel(node.getRightInput());
    return LogicalJoin.create(
        left,
        right,
        ImmutableList.of(),
        s.toRex(node.getCondition()),
        ImmutableSet.of(),
        s.fromProto(node.getJoinType()));
  }
}
