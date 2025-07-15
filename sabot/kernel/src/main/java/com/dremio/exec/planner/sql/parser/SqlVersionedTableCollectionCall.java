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
package com.dremio.exec.planner.sql.parser;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.List;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

public class SqlVersionedTableCollectionCall extends SqlCall {

  public static final SqlSpecialOperator OPERATOR =
      new SqlSpecialOperator("VERSIONED TABLE COLLECTION", SqlKind.COLLECTION_TABLE) {
        @Override
        public SqlCall createCall(
            SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
          Preconditions.checkArgument(
              operands.length == 1,
              "SqlVersionedTableCollectionCall.createCall() has to get 1 operands!");
          return new SqlVersionedTableCollectionCall(pos, (SqlVersionedTableMacroCall) operands[0]);
        }
      };

  private final SqlVersionedTableMacroCall versionedTableMacroCall;

  public SqlVersionedTableCollectionCall(
      SqlParserPos pos, SqlVersionedTableMacroCall versionedTableCall) {
    super(pos);
    versionedTableMacroCall = versionedTableCall;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> ops = Lists.newArrayList();
    ops.add(versionedTableMacroCall);

    return ops;
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    Preconditions.checkState(getOperandList().size() == 1);
    Preconditions.checkState(getOperator().getName() == OPERATOR.getName());
    this.versionedTableMacroCall.unparse(writer, leftPrec, rightPrec);
  }

  public SqlVersionedTableMacroCall getVersionedTableMacroCall() {
    return versionedTableMacroCall;
  }
}
