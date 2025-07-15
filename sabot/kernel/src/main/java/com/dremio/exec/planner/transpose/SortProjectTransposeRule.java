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
package com.dremio.exec.planner.transpose;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

public class SortProjectTransposeRule extends RelRule<SortProjectTransposeRule.Config>
    implements TransformationRule {

  protected SortProjectTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Sort sort = call.rel(0);
    Project project = call.rel(1);

    List<RelFieldCollation> adjustedRelFieldCollations = new ArrayList<>();
    for (RelFieldCollation relFieldCollation : sort.getCollation().getFieldCollations()) {
      int oldFieldIndex = relFieldCollation.getFieldIndex();
      RexNode projectIndex = project.getProjects().get(oldFieldIndex);
      if (!(projectIndex instanceof RexInputRef)) {
        // We can't sort on a non input ref
        return;
      }

      RexInputRef projectInputRef = (RexInputRef) projectIndex;
      RelFieldCollation adjustedFieldCollation =
          relFieldCollation.withFieldIndex(projectInputRef.getIndex());
      adjustedRelFieldCollations.add(adjustedFieldCollation);
    }

    RelCollation adjustedRelCollation = RelCollations.of(adjustedRelFieldCollations);

    RelNode transposed =
        call.builder()
            .push(project.getInput())
            .sortLimit(adjustedRelCollation, sort.offset, sort.fetch)
            .project(project.getProjects(), project.getRowType().getFieldNames())
            .build();

    call.transformTo(transposed);
  }

  public interface Config extends RelRule.Config {
    SortProjectTransposeRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription("SortProjectTransposeRule")
            .withOperandSupplier(
                op ->
                    op.operand(Sort.class)
                        .oneInput(project -> project.operand(Project.class).anyInputs()))
            .as(SortProjectTransposeRule.Config.class);

    @Override
    default SortProjectTransposeRule toRule() {
      return new SortProjectTransposeRule(this);
    }
  }
}
