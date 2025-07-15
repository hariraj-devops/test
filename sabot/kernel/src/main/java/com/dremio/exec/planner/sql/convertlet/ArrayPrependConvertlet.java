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
package com.dremio.exec.planner.sql.convertlet;

import static com.dremio.exec.planner.sql.DremioSqlOperatorTable.ARRAY_CONCAT;
import static com.dremio.exec.planner.sql.DremioSqlOperatorTable.ARRAY_PREPEND;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR;

import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;

public final class ArrayPrependConvertlet extends RexCallConvertlet {
  public static final RexCallConvertlet INSTANCE =
      new NullableArrayRexCallConvertlet(new ArrayPrependConvertlet());

  private ArrayPrependConvertlet() {}

  @Override
  public boolean matchesCall(RexCall call) {
    return call.getOperator() == ARRAY_PREPEND;
  }

  @Override
  public RexCall convertCall(ConvertletContext cx, RexCall call) {
    // ARRAY_PREPEND(elem, arr) -> ARRAY_CONCAT([elem], arr)
    RexNode elem = call.getOperands().get(0);
    RexNode array = call.getOperands().get(1);

    RexBuilder rexBuilder = cx.getRexBuilder();
    RexNode arrayWithJustElem = rexBuilder.makeCall(ARRAY_VALUE_CONSTRUCTOR, elem);
    return (RexCall) rexBuilder.makeCall(ARRAY_CONCAT, arrayWithJustElem, array);
  }
}
