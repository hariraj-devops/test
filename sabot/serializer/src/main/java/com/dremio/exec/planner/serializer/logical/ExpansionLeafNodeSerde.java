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

import static com.dremio.catalog.model.dataset.TableVersionContext.NOT_SPECIFIED;

import com.dremio.exec.planner.acceleration.ExpansionLeafNode;
import com.dremio.exec.planner.serializer.RelNodeSerde;
import com.dremio.plan.serialization.PExpansionLeafNode;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptTable.ToRelContext;

/** Serde for {@link ExpansionLeafNode} */
public final class ExpansionLeafNodeSerde
    implements RelNodeSerde<ExpansionLeafNode, PExpansionLeafNode> {

  @Override
  public PExpansionLeafNode serialize(ExpansionLeafNode expansionNode, RelToProto s) {
    List<String> path = expansionNode.getPath().getPathComponents();

    PExpansionLeafNode.Builder builder =
        PExpansionLeafNode.newBuilder()
            .addAllPath(path)
            .setRowType(s.toProto(expansionNode.getRowType()))
            .setIsDefault(expansionNode.isDefault());

    return builder.build();
  }

  @Override
  public ExpansionLeafNode deserialize(PExpansionLeafNode node, RelFromProto s) {
    ToRelContext context = s.toRelContext();
    List<String> path = new ArrayList<>(node.getPathList());
    return new ExpansionLeafNode(
        new NamespaceKey(path),
        context.getCluster(),
        context.getCluster().traitSet(),
        s.toRelDataType(node.getRowType()),
        null, // Not needed
        node.getIsDefault(),
        NOT_SPECIFIED,
        null, // Not needed
        ImmutableList.of());
  }
}
