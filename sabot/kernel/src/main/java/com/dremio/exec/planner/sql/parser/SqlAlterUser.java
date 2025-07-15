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

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SimpleDirectHandler;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.parser.SqlParserPos;

/** ALTER USER username SET PASSWORD password/ UNSET PASSWORD */
public class SqlAlterUser extends SqlCall implements SimpleDirectHandler.Creator {
  private final SqlIdentifier username;
  private final SqlNode password;

  public static final SqlSpecialOperator OPERATOR =
      new SqlSpecialOperator("ALTER", SqlKind.OTHER) {
        @Override
        public SqlCall createCall(
            SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
          Preconditions.checkArgument(
              operands.length == 1, "SqlAlterUser.createCall() has to get at least 1 operands!");
          if (operands.length == 2) {
            return new SqlAlterUser(pos, (SqlIdentifier) operands[0], (SqlNode) operands[1]);
          }
          return new SqlAlterUser(pos, (SqlIdentifier) operands[0]);
        }
      };

  public SqlAlterUser(SqlParserPos pos, SqlIdentifier username, SqlNode password) {
    super(pos);
    this.username = username;
    this.password = password;
  }

  public SqlAlterUser(SqlParserPos pos, SqlIdentifier username) {
    super(pos);
    this.username = username;
    this.password = null;
  }

  @Override
  public SimpleDirectHandler toDirectHandler(QueryContext context) {
    try {
      final Class<?> cl = Class.forName("com.dremio.exec.planner.sql.handlers.UserAlterHandler");
      final Constructor<?> ctor = cl.getConstructor(QueryContext.class);
      return (SimpleDirectHandler) ctor.newInstance(context);
    } catch (ClassNotFoundException e) {
      // Assume failure to find class means that we aren't running Enterprise Edition
      throw UserException.unsupportedError(e)
          .message("ALTER USER action is only supported in Enterprise Edition.")
          .buildSilently();
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> ops = Lists.newArrayList();
    ops.add(username);
    return ops;
  }

  public SqlIdentifier getUsername() {
    return username;
  }

  public SqlNode getPassword() {
    return password;
  }
}
