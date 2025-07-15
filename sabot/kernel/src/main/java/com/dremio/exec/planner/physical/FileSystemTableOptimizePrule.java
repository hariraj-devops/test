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

import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.logical.TableOptimizeRel;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRestApi;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;

/** Generate physical plan for OPTIMIZE TABLE with file systems. */
public class FileSystemTableOptimizePrule extends TableOptimizePruleBase {

  public FileSystemTableOptimizePrule(OptimizerRulesContext context) {
    super(
        RelOptHelper.some(TableOptimizeRel.class, Rel.LOGICAL, RelOptHelper.any(RelNode.class)),
        "Prel.FileSystemTableOptimizePrule",
        context);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    // TODO: DX-99788 - Investigate Alternative
    final SupportsFsMutablePlugin plugin =
        call.<TableOptimizeRel>rel(0).getCreateTableEntry().getPlugin();
    return plugin instanceof FileSystemPlugin || plugin instanceof SupportsIcebergRestApi;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final TableOptimizeRel optimizeRel = call.rel(0);
    call.transformTo(
        getPhysicalPlan(
            optimizeRel,
            call.rel(1),
            ((DremioPrepareTable) optimizeRel.getTable()).getTable().getDataset()));
  }
}
