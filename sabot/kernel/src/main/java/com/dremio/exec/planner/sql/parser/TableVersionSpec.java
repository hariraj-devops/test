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

import static org.apache.calcite.util.Static.RESOURCE;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.planner.sql.evaluator.FunctionEvaluatorUtil;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.sabot.exec.context.ContextInformation;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCharStringLiteral;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlTimestampLiteral;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.util.TimestampString;

/**
 * Representation of a table version specification, which may be associated with a table identifier
 * or a TABLE() function call. A TableVersionSpec must be resolved to convert constant expressions
 * into literal values.
 */
public class TableVersionSpec {

  private final TableVersionType tableVersionType;
  private final SqlNode versionSpecifier;
  private final @Nullable SqlNode timestamp;
  private SqlLiteral resolvedVersionSpecifier;

  public TableVersionSpec(
      TableVersionType tableVersionType, SqlNode versionSpecifier, @Nullable SqlNode timestamp) {
    this.tableVersionType = tableVersionType;
    this.versionSpecifier = versionSpecifier;
    this.timestamp = timestamp;
  }

  public TableVersionContext getResolvedTableVersionContext() {
    Preconditions.checkNotNull(resolvedVersionSpecifier);

    Object value = null;
    switch (tableVersionType) {
      case COMMIT:
        Preconditions.checkState(resolvedVersionSpecifier instanceof SqlCharStringLiteral);
        value = resolvedVersionSpecifier.getValueAs(String.class);
        if (timestamp != null) {
          throw UserException.validationError()
              .message("Reference type 'Commit' cannot be used with AS OF syntax.")
              .buildSilently();
        }
        break;
      case BRANCH:
      case TAG:
      case REFERENCE:
      case SNAPSHOT_ID:
        Preconditions.checkState(resolvedVersionSpecifier instanceof SqlCharStringLiteral);
        value = resolvedVersionSpecifier.getValueAs(String.class);
        break;
      case TIMESTAMP:
        Preconditions.checkState(resolvedVersionSpecifier instanceof SqlTimestampLiteral);
        value = resolvedVersionSpecifier.getValueAs(Calendar.class).getTimeInMillis();
        break;
      case NOT_SPECIFIED:
      default:
        break;
    }

    Preconditions.checkNotNull(value);
    return new TableVersionContext(tableVersionType, value, getTimestampAsMillis());
  }

  public TableVersionContext getTableVersionContext() {
    Preconditions.checkNotNull(versionSpecifier);
    Object value = null;
    switch (tableVersionType) {
      case BRANCH:
      case TAG:
      case COMMIT:
      case REFERENCE:
      case NOT_SPECIFIED:
      case SNAPSHOT_ID:
        Preconditions.checkState(versionSpecifier instanceof SqlCharStringLiteral);
        value = ((SqlCharStringLiteral) versionSpecifier).getValueAs(String.class);
        break;
      case TIMESTAMP:
        Preconditions.checkState(versionSpecifier instanceof SqlTimestampLiteral);
        value =
            ((SqlTimestampLiteral) versionSpecifier).getValueAs(Calendar.class).getTimeInMillis();
        break;
    }

    Preconditions.checkNotNull(value);
    return new TableVersionContext(tableVersionType, value);
  }

  /**
   * Resolves a TableVersionSpec by performing constant folding on the versionSpecifier. An error
   * will be reported if the expression provided is not resolvable to a constant value of the
   * appropriate type.
   */
  public void resolve(
      SqlValidator validator,
      SqlToRelConverter converter,
      RexExecutor rexExecutor,
      RexBuilder rexBuilder,
      ContextInformation contextInformation) {
    SqlNode validatedSpecifier = validator.validate(versionSpecifier);

    // Nothing to resolve if the version specifier is already a literal - this should always be the
    // case for non-TIMESTAMP types
    if (validatedSpecifier instanceof SqlLiteral) {
      resolvedVersionSpecifier = (SqlLiteral) validatedSpecifier;
      return;
    }
    // TIMESTAMP is the only type that allows non-literal constant expressions, guaranteed by the
    // grammar
    Preconditions.checkState(tableVersionType == TableVersionType.TIMESTAMP);
    RexNode expr = converter.convertExpression(validatedSpecifier, new HashMap<>());
    expr = FunctionEvaluatorUtil.evaluateAll(expr, rexBuilder, contextInformation, null);

    List<RexNode> reducedExprs = new ArrayList<>();
    rexExecutor.reduce(rexBuilder, ImmutableList.of(expr), reducedExprs);
    RexNode finalExpr = reducedExprs.get(0);

    if (finalExpr instanceof RexLiteral) {
      RexLiteral literal = (RexLiteral) finalExpr;
      if (literal.getTypeName() != SqlTypeName.TIMESTAMP) {
        throw resolveError("Expected timestamp literal or constant timestamp expression");
      }

      resolvedVersionSpecifier =
          SqlLiteral.createTimestamp(
              TimestampString.fromCalendarFields(literal.getValueAs(Calendar.class)),
              3,
              versionSpecifier.getParserPosition());
    }
  }

  private CalciteContextException resolveError(String message) {
    SqlValidatorException validatorEx = new SqlValidatorException(message, null);
    SqlParserPos pos = versionSpecifier.getParserPosition();
    int line = pos.getLineNum();
    int col = pos.getColumnNum();
    int endLine = pos.getEndLineNum();
    int endCol = pos.getEndColumnNum();
    CalciteContextException contextEx =
        (line == endLine && col == endCol
                ? RESOURCE.validatorContextPoint(line, col)
                : RESOURCE.validatorContext(line, col, endLine, endCol))
            .ex(validatorEx);
    contextEx.setPosition(line, col, endLine, endCol);
    return contextEx;
  }

  public TableVersionType getTableVersionType() {
    return tableVersionType;
  }

  public SqlNode getVersionSpecifier() {
    return versionSpecifier;
  }

  public @Nullable SqlNode getTimestamp() {
    return timestamp;
  }

  public @Nullable Long getTimestampAsMillis() {
    if (timestamp == null) {
      return null;
    }
    return SqlHandlerUtil.convertToTimeInMillis(
        ((SqlLiteral) timestamp).getValueAs(String.class), timestamp.getParserPosition());
  }

  public void unparseVersionSpec(SqlWriter writer, int leftPrec, int rightPrec) {
    switch (tableVersionType) {
      case BRANCH:
      case TAG:
      case COMMIT:
      case REFERENCE:
      case SNAPSHOT_ID:
        Preconditions.checkState(getVersionSpecifier() instanceof SqlCharStringLiteral);
        writer.keyword(getTableVersionType().toSqlRepresentation());

        final SqlCharStringLiteral versionCharSpecLiteral =
            (SqlCharStringLiteral) getVersionSpecifier();
        final String value = versionCharSpecLiteral.getNlsString().getValue();
        writer.print((tableVersionType == TableVersionType.COMMIT) ? '"' + value + '"' : value);

        break;
      case TIMESTAMP:
        SqlNode versionSpecifier = getVersionSpecifier();
        Preconditions.checkState(
            versionSpecifier instanceof SqlTimestampLiteral
                || versionSpecifier instanceof SqlBasicCall);
        if (versionSpecifier instanceof SqlTimestampLiteral) {
          writer.keyword(getTableVersionType().toSqlRepresentation());
          SqlTimestampLiteral versionTimeSpecLiteral = (SqlTimestampLiteral) getVersionSpecifier();
          writer.print(String.valueOf(versionTimeSpecLiteral.getValue()));
        } else if (versionSpecifier instanceof SqlBasicCall) {
          SqlBasicCall call = (SqlBasicCall) versionSpecifier;
          call.getOperator().unparse(writer, call, leftPrec, rightPrec);
        }
        break;
    }
    if (timestamp != null) {
      writer.keyword("AS");
      writer.keyword("OF");
      writer.keyword(timestamp.toString());
    }
  }
}
