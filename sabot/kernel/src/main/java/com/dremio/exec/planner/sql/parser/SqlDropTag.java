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
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.handlers.direct.SqlDirectHandler;
import com.dremio.options.OptionResolver;
import com.dremio.sabot.rpc.user.UserSession;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.lang.reflect.Constructor;
import java.util.List;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/**
 * Drops a tag under a source.
 *
 * <p>DROP TAG [ IF EXISTS ] tagName [ AT COMMIT commitHash | FORCE ] [ IN sourceName ]
 */
public final class SqlDropTag extends SqlVersionBase {
  public static final SqlSpecialOperator OPERATOR =
      new SqlSpecialOperator("DROP_TAG", SqlKind.OTHER) {
        @Override
        public SqlCall createCall(
            SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
          Preconditions.checkArgument(
              operands.length == 5, "SqlDropTag.createCall() has to get 2 operands!");
          return new SqlDropTag(
              pos,
              (SqlLiteral) operands[0],
              (SqlIdentifier) operands[1],
              (SqlIdentifier) operands[2],
              (SqlLiteral) operands[3],
              (SqlIdentifier) operands[4]);
        }
      };

  private final SqlLiteral shouldErrorIfTagDoesNotExist;
  private final SqlIdentifier tagName;
  private final SqlIdentifier commitHash;
  private final SqlLiteral forceDrop;

  public SqlDropTag(
      SqlParserPos pos,
      SqlLiteral shouldErrorIfTagDoesNotExist,
      SqlIdentifier tagName,
      SqlIdentifier commitHash,
      SqlLiteral forceDrop,
      SqlIdentifier sourceName) {
    super(pos, sourceName);
    this.shouldErrorIfTagDoesNotExist = Preconditions.checkNotNull(shouldErrorIfTagDoesNotExist);
    this.tagName = Preconditions.checkNotNull(tagName);
    this.commitHash = commitHash;
    this.forceDrop = Preconditions.checkNotNull(forceDrop);
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> ops = Lists.newArrayList();
    ops.add(shouldErrorIfTagDoesNotExist);
    ops.add(tagName);
    ops.add(commitHash);
    ops.add(forceDrop);
    ops.add(getSourceName());

    return ops;
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("DROP");
    writer.keyword("TAG");

    if (shouldErrorIfTagDoesNotExist.booleanValue()) {
      writer.keyword("IF");
      writer.keyword("EXISTS");
    }

    tagName.unparse(writer, leftPrec, rightPrec);

    if (commitHash != null) {
      writer.keyword("AT");
      commitHash.unparse(writer, leftPrec, rightPrec);
    } else if (forceDrop.booleanValue()) {
      writer.keyword("FORCE");
    }

    unparseSourceName(writer, leftPrec, rightPrec);
  }

  @Override
  public SqlDirectHandler<?> toDirectHandler(QueryContext context) {
    try {
      final Class<?> cl = Class.forName("com.dremio.exec.planner.sql.handlers.DropTagHandler");
      final Constructor<?> ctor =
          cl.getConstructor(Catalog.class, OptionResolver.class, UserSession.class);

      return (SqlDirectHandler<?>)
          ctor.newInstance(context.getCatalog(), context.getOptions(), context.getSession());
    } catch (ClassNotFoundException e) {
      throw UserException.unsupportedError(e)
          .message("DROP TAG action is not supported.")
          .buildSilently();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  public SqlLiteral shouldErrorIfTagDoesNotExist() {
    return shouldErrorIfTagDoesNotExist;
  }

  public SqlIdentifier getTagName() {
    return tagName;
  }

  public SqlIdentifier getCommitHash() {
    return commitHash;
  }

  public SqlLiteral getForceDrop() {
    return forceDrop;
  }
}
