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
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/** Base class for CREATE BRANCH/TAG with some shared existence check logic. */
public abstract class SqlCreateVersionBase extends SqlVersionSourceRefBase {

  private final SqlLiteral shouldErrorIfVersionExists;

  protected SqlCreateVersionBase(
      SqlParserPos pos,
      SqlLiteral shouldErrorIfVersionExists,
      ReferenceType refType,
      SqlIdentifier refValue,
      SqlNode timestamp,
      SqlIdentifier sourceName) {
    super(pos, sourceName, refType, refValue, timestamp);
    this.shouldErrorIfVersionExists = Preconditions.checkNotNull(shouldErrorIfVersionExists);
  }

  public SqlLiteral shouldErrorIfVersionExists() {
    return shouldErrorIfVersionExists;
  }

  public void unparseExistenceCheck(SqlWriter writer) {
    if (shouldErrorIfVersionExists.booleanValue()) {
      writer.keyword("IF");
      writer.keyword("NOT");
      writer.keyword("EXISTS");
    }
  }
}
