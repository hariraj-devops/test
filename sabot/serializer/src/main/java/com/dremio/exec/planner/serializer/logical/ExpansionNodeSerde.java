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

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.serializer.RelNodeSerde;
import com.dremio.plan.serialization.PExpansionNode;
import com.dremio.service.namespace.NamespaceKey;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.rel.RelNode;

/** Serde for {@link ExpansionNode} */
public final class ExpansionNodeSerde implements RelNodeSerde<ExpansionNode, PExpansionNode> {
  @Override
  public PExpansionNode serialize(ExpansionNode expansionNode, RelToProto s) {
    List<String> path = expansionNode.getPath().getPathComponents();
    PExpansionNode.Builder builder =
        PExpansionNode.newBuilder()
            .setInput(s.toProto(expansionNode.getInput()))
            .addAllPath(path)
            .setContextSensitive(false)
            .setIsDefault(expansionNode.isDefault());

    if (expansionNode.getVersionContext() != null) {
      builder.setVersionContext(expansionNode.getVersionContext().serialize());
    }

    // We don't need to serialize ViewTable, since it's only used ephemerally from view expansion to
    // drr matching.

    return builder.build();
  }

  @Override
  public ExpansionNode deserialize(PExpansionNode node, RelFromProto s) {
    List<String> path = new ArrayList<>(node.getPathList());
    RelNode input = s.toRel(node.getInput());
    return (ExpansionNode)
        ExpansionNode.wrap(
            new NamespaceKey(path),
            input,
            input.getRowType(),
            node.getIsDefault(),
            node.getVersionContext() == null
                ? null
                : TableVersionContext.deserialize(node.getVersionContext()),
            null /*We don't need to deserialize ViewTable, since it's only used ephemerally from view expansion to drr matching.*/);
  }
}
