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
import com.dremio.exec.planner.serializer.core.RelFieldCollationSerde;
import com.dremio.plan.serialization.PLogicalSort;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rex.RexNode;

/** Serde for LogicalSort */
public final class LogicalSortSerde implements RelNodeSerde<LogicalSort, PLogicalSort> {
  @Override
  public PLogicalSort serialize(LogicalSort sort, RelToProto s) {
    PLogicalSort.Builder builder =
        PLogicalSort.newBuilder()
            .setInput(s.toProto(sort.getInput()))
            .addAllCollation(s.toProtoRelFieldCollation(sort.collation.getFieldCollations()));

    if (sort.fetch != null) {
      builder.setFetch(s.toProto(sort.fetch));
    }

    if (sort.offset != null) {
      builder.setOffset(s.toProto(sort.offset));
    }

    return builder.build();
  }

  @Override
  public LogicalSort deserialize(PLogicalSort node, RelFromProto s) {
    RelNode input = s.toRel(node.getInput());
    List<RelFieldCollation> fieldCollations =
        node.getCollationList().stream()
            .map(RelFieldCollationSerde::fromProto)
            .collect(Collectors.toList());
    RexNode offset = node.hasOffset() ? s.toRex(node.getOffset()) : null;
    RexNode fetch = node.hasFetch() ? s.toRex(node.getFetch()) : null;
    return LogicalSort.create(input, RelCollations.of(fieldCollations), offset, fetch);
  }
}
