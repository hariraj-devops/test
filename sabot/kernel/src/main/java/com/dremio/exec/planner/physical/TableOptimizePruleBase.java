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
package com.dremio.exec.planner.physical;

import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.OptimizePlanGeneratorBase;
import com.dremio.exec.planner.logical.TableOptimizeRel;
import com.dremio.exec.store.TableMetadata;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.RelNode;

/** A base physical plan generator for OPTIMIZE */
public abstract class TableOptimizePruleBase extends Prule {

  private final OptimizerRulesContext context;

  public TableOptimizePruleBase(
      RelOptRuleOperand operand, String description, OptimizerRulesContext context) {
    super(operand, description);
    this.context = context;
  }

  public Prel getPhysicalPlan(
      TableOptimizeRel optimizeRel, RelNode input, TableMetadata tableMetadata) {
    OptimizePlanGeneratorBase planGenerator =
        context.getOptimizePlanGenerator(
            optimizeRel.getTable(),
            optimizeRel.getCluster(),
            optimizeRel.getTraitSet().plus(Prel.PHYSICAL),
            convert(input, input.getTraitSet().plus(Prel.PHYSICAL)),
            tableMetadata,
            optimizeRel.getCreateTableEntry(),
            context,
            optimizeRel.getOptimizeOptions(),
            optimizeRel.getPartitionFilter());
    return planGenerator.getPlan();
  }
}
