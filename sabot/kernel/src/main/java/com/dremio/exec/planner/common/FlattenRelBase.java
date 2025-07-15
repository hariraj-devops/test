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
package com.dremio.exec.planner.common;

import com.dremio.exec.planner.cost.DremioCost;
import com.dremio.exec.planner.cost.DremioCost.Factory;
import com.dremio.exec.planner.physical.PrelUtil;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory.Builder;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexSlot;

/** Flatten rel base (can be any convention) */
public abstract class FlattenRelBase extends SingleRel {

  protected final List<RexInputRef> toFlatten;
  protected final List<String> aliases;
  protected final int numProjectsPushed;

  public FlattenRelBase(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      List<RexInputRef> toFlatten,
      List<String> aliases,
      int numProjectsPushed) {
    super(cluster, traits, child);
    Preconditions.checkArgument(!toFlatten.isEmpty(), "Must have at least one flatten input.");
    this.toFlatten = toFlatten;
    this.aliases = aliases;
    this.numProjectsPushed = numProjectsPushed;
  }

  public int getNumProjectsPushed() {
    return numProjectsPushed;
  }

  public List<RexInputRef> getToFlatten() {
    return toFlatten;
  }

  public List<String> getAliases() {
    return aliases;
  }

  public abstract FlattenRelBase copy(List<RelNode> inputs, List<RexInputRef> toFlatten);

  @Override
  protected RelDataType deriveRowType() {
    RelDataType rowType = input.getRowType();
    List<RelDataTypeField> inputFields = rowType.getFieldList();
    List<RelDataTypeField> outputFields = new ArrayList<>();
    Map<Integer, String> indexToAlias = getFlattenIndicesToAlias();
    for (int i = 0; i < inputFields.size(); i++) {
      RelDataTypeField field = inputFields.get(i);
      if (!indexToAlias.containsKey(i)) {
        outputFields.add(field);
      } else {
        RelDataType newType = field.getType().getComponentType();
        if (newType == null) {
          outputFields.add(field);
        } else {
          String alias = indexToAlias.get(i);
          if (alias == null) {
            alias = field.getName();
          }

          outputFields.add(new RelDataTypeFieldImpl(alias, i, newType));
        }
      }
    }

    final Builder builder = getCluster().getTypeFactory().builder();
    for (RelDataTypeField field : outputFields) {
      builder.add(field);
    }

    return builder.build();
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    // We expect for flattens output to be expanding. Use a constant to expand the data.
    return mq.getRowCount(input)
        * toFlatten.size()
        * PrelUtil.getPlannerSettings(getCluster().getPlanner()).getFlattenExpansionAmount();
  }

  public Set<Integer> getFlattenedIndices() {
    return getToFlatten().stream().map(RexSlot::getIndex).collect(Collectors.toSet());
  }

  public Map<Integer, String> getFlattenIndicesToAlias() {
    Map<Integer, String> indicesToAlias = new HashMap<>();
    for (int i = 0; i < toFlatten.size(); i++) {
      RexInputRef rexInputRef = toFlatten.get(i);
      Integer index = rexInputRef.getIndex();
      String alias = null;
      if (aliases != null && !aliases.isEmpty()) {
        alias = aliases.get(i);
      }

      indicesToAlias.put(index, alias);
    }

    return indicesToAlias;
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    if (PrelUtil.getSettings(getCluster()).useDefaultCosting()) {
      return super.computeSelfCost(planner, mq).multiplyBy(.1);
    }

    // cost is proportional to the number of rows and number of columns being projected
    double rowCount = this.estimateRowCount(mq);
    double cpuCost = DremioCost.PROJECT_CPU_COST * rowCount * getRowType().getFieldCount();

    Factory costFactory = (Factory) planner.getCostFactory();

    if (numProjectsPushed > 0) {
      return costFactory.makeCost(rowCount, cpuCost, 0, 0).multiplyBy(1 / numProjectsPushed);
    } else {
      return costFactory.makeCost(rowCount, cpuCost, 0, 0);
    }
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw).item("flattenField", this.toFlatten);
  }
}
