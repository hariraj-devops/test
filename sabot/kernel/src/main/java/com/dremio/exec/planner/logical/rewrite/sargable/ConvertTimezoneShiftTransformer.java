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
package com.dremio.exec.planner.logical.rewrite.sargable;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.commons.lang3.function.TriFunction;

/**
 *
 *
 * <pre>
 * Extract each component of a filter expr with SARGableStandardForm for
 * - CONVERT_TIMEZONE([sourceTimezone string], destinationTimezone string, timestamp date, timestamp, or string in ISO 8601 format) = rhsNode
 * </pre>
 */
public class ConvertTimezoneShiftTransformer extends ShiftTransformer {

  public ConvertTimezoneShiftTransformer(
      RelOptCluster relOptCluster, StandardForm stdForm, SqlOperator sqlOperator) {
    super(relOptCluster, stdForm, sqlOperator);
  }

  @Override
  RexNode getColumn() {
    return getLhsCall().operands.get(getLhsCall().operands.size() - 1);
  }

  @Override
  RexNode getRhsNode() {
    RexNode rhs = super.getRhsNode();
    // Cast string date/time literal to date/time type
    if (SqlTypeName.CHAR_TYPES.contains(rhs.getType().getSqlTypeName())) {
      return rexBuilder.makeCast(
          rexBuilder.getTypeFactory().createTypeWithNullability(getColumn().getType(), false), rhs);
    }
    return rhs;
  }

  @Override
  RexNode getRhsParam() {
    return getLhsCall().operands.get(0);
  }

  @Override
  RexNode getRhsParam2() {
    return getLhsCall().operands.get(1);
  }

  @Override
  TriFunction<RexNode, RexNode, RexNode, ImmutableList<RexNode>> getTransformationFunction() {
    return (col, param1, param2) -> ImmutableList.of(param2, param1, col);
  }
}
