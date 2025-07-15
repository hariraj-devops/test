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
package com.dremio.exec.planner.logical;

import static com.dremio.exec.expr.fn.impl.ConcatFunctions.CONCAT_MAX_ARGS;
import static com.dremio.exec.planner.physical.AggregatePrel.findDelimiterInListAgg;
import static org.apache.calcite.sql.type.SqlTypeName.BIGINT;
import static org.apache.calcite.sql.type.SqlTypeName.DECIMAL;
import static org.apache.calcite.sql.type.SqlTypeName.DOUBLE;
import static org.apache.calcite.sql.type.SqlTypeName.FLOAT;
import static org.apache.calcite.sql.type.SqlTypeName.INTEGER;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.ArrayLiteralExpression;
import com.dremio.common.expression.CaseExpression;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.FunctionCallFactory;
import com.dremio.common.expression.IfExpression;
import com.dremio.common.expression.InputReference;
import com.dremio.common.expression.ListAggExpression;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.NullExpression;
import com.dremio.common.expression.Ordering;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.expression.TypedNullConstant;
import com.dremio.common.expression.ValueExpressions;
import com.dremio.common.expression.ValueExpressions.QuotedString;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.types.TypeProtos.DataMode;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.exec.planner.StarColumnHelper;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.work.ExecErrorConstants;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexRangeRef;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.BitSets;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;

/** Utilities for Dremio's planner. */
public final class RexToExpr {

  private RexToExpr() {
    // utility class
  }

  public static final String UNSUPPORTED_REX_NODE_ERROR =
      "Cannot convert RexNode to equivalent Dremio expression. ";
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RexToExpr.class);
  private static boolean warnDecimal = true;

  private static final List<SqlTypeName> SUPPORTED_ARRAY_SUM_MAX_TYPES =
      ImmutableList.of(INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL);

  /** Converts a tree of {@link RexNode} operators into a scalar expression in Dremio syntax. */
  public static LogicalExpression toExpr(
      ParseContext context, RelDataType rowType, RexBuilder rexBuilder, RexNode expr) {
    return toExpr(context, rowType, rexBuilder, expr, true);
  }

  public static LogicalExpression toExpr(
      ParseContext context,
      RelDataType rowType,
      RexBuilder rexBuilder,
      RexNode expr,
      boolean throwUserException) {
    return toExpr(
        context, rowType, rexBuilder, expr, throwUserException, (i) -> Optional.<Integer>empty());
  }

  /**
   * Convert a Calcite RexNode expression tree into a LogicalExpression
   *
   * @param context The context for converting this tree.
   * @param rowType The rowtype of the input the expressions points at.
   * @param rexBuilder A RexBuilder for building intermediate expressions
   * @param expr The RexNode to convert
   * @param throwUserException Whether to throw a user exception when we fail to convert.
   * @param inputFunction A function which maps a field ordinal to a particular input side. If maps
   *     to a non-absent value, FieldReferences are replaced by InputReferences that include the
   *     provided index.
   * @return The converted expression tree.
   */
  public static LogicalExpression toExpr(
      ParseContext context,
      RelDataType rowType,
      RexBuilder rexBuilder,
      RexNode expr,
      boolean throwUserException,
      IntFunction<Optional<Integer>> inputFunction) {
    final Visitor visitor =
        new Visitor(context, rowType, rexBuilder, throwUserException, inputFunction);
    return expr.accept(visitor);
  }

  public static List<NamedExpression> projectToExpr(
      ParseContext context, List<Pair<RexNode, String>> projects, RelNode input) {
    List<NamedExpression> expressions = new ArrayList<>();

    Map<String, String> starColPrefixes = new HashMap<>();

    // T1.* will subsume T1.*0, but will not subsume any regular column/expression.
    // Select *, col1, *, col2 : the intermediate will output one set of regular columns expanded
    // from star with prefix,
    // plus col1 and col2 without prefix.
    // This will allow us to differentiate the regular expanded from *, and the regular column
    // referenced in the query.
    for (Pair<RexNode, String> pair : projects) {
      if (StarColumnHelper.isPrefixedStarColumn(pair.right)) {
        String prefix = StarColumnHelper.extractStarColumnPrefix(pair.right);

        if (!starColPrefixes.containsKey(prefix)) {
          starColPrefixes.put(prefix, pair.right);
        }
      }
    }

    for (Pair<RexNode, String> pair : projects) {
      if (!StarColumnHelper.subsumeColumn(starColPrefixes, pair.right)) {
        LogicalExpression expr =
            toExpr(context, input.getRowType(), input.getCluster().getRexBuilder(), pair.left);
        expressions.add(new NamedExpression(expr, FieldReference.getWithQuotedRef(pair.right)));
      }
    }
    return expressions;
  }

  public static List<NamedExpression> groupSetToExpr(RelNode input, ImmutableBitSet groupSet) {
    final List<String> childFields = input.getRowType().getFieldNames();
    final List<NamedExpression> keys = new ArrayList<>();

    for (int group : BitSets.toIter(groupSet)) {
      FieldReference fr = FieldReference.getWithQuotedRef(childFields.get(group));
      keys.add(new NamedExpression(fr, fr));
    }
    return keys;
  }

  public static List<NamedExpression> aggCallsToExpr(
      RelDataType rowType, RelNode input, ImmutableBitSet groupSet, List<AggregateCall> aggCalls) {
    final List<String> fields = rowType.getFieldNames();
    final List<String> childFields = input.getRowType().getFieldNames();
    final List<NamedExpression> aggExprs = new ArrayList<>();
    for (Ord<AggregateCall> aggCall : Ord.zip(aggCalls)) {
      int aggExprOrdinal = groupSet.cardinality() + aggCall.i;
      FieldReference ref = FieldReference.getWithQuotedRef(fields.get(aggExprOrdinal));
      RelOptCluster cluster = input.getCluster();
      final Visitor visitor =
          new Visitor(
              new ParseContext(PrelUtil.getPlannerSettings(cluster)),
              rowType,
              cluster.getRexBuilder(),
              true,
              (i) -> Optional.<Integer>empty());
      LogicalExpression expr = aggCallToExpr(aggCall.e, childFields, input, visitor);
      NamedExpression ne = new NamedExpression(expr, ref);
      aggExprs.add(ne);
    }
    return aggExprs;
  }

  private static LogicalExpression aggCallToExpr(
      AggregateCall call, List<String> fn, RelNode input, Visitor visitor) {
    try {
      if (call.isApproximate()) {
        throw new UnsupportedOperationException(
            "FunctionCall does not support 'approximate' flag.");
      }

      if (call.hasFilter()) {
        throw new UnsupportedOperationException("FunctionCall does not support filter.");
      }

      if (call.ignoreNulls()) {
        throw new UnsupportedOperationException("FunctionCall does not support ignore nulls flag.");
      }

      return call.getAggregation().getKind() == SqlKind.LISTAGG
          ? aggCallToListAggExpression(call, fn, input, visitor)
          : aggCallToFunctionCall(call, fn);
    } catch (Throwable ex) {
      // Avoiding bubbling up a raw exception that the planner is going to wrap in a nonsense
      // exception message
      // to provide user an easier to read message.
      // This is temporary until we figure out a better exception handling story for this utility
      // class.
      throw UserException.systemError(ex)
          .message(
              "An unexpected internal error was hit stopping this request from being served. "
                  + "No action can be taken to mitigate this scenario. "
                  + "Contact customer support with a profile and the issue will be resolved internally.")
          .buildSilently();
    }
  }

  private static ListAggExpression aggCallToListAggExpression(
      AggregateCall call, List<String> fn, RelNode input, Visitor visitor) {
    Preconditions.checkArgument(call.getAggregation().getKind() == SqlKind.LISTAGG);
    boolean isDistinct = call.isDistinct();

    // RelDataType type can be ignored, since it gets determined by ListAggExpression.

    String name = call.getAggregation().getName();

    List<LogicalExpression> args =
        call.getArgList().stream()
            .map(i -> FieldReference.getWithQuotedRef(fn.get(i)))
            .collect(Collectors.toList());

    List<Ordering> orderings =
        call.getCollation().getFieldCollations().stream()
            .map(
                relFieldCollation -> {
                  FieldReference fieldReference =
                      new FieldReference(fn.get(relFieldCollation.getFieldIndex()));
                  return new Ordering(
                      fieldReference,
                      relFieldCollation.direction.toString(),
                      relFieldCollation.nullDirection.toString());
                })
            .collect(Collectors.toList());

    List<LogicalExpression> extraExpressions = new ArrayList<>();
    if (call.getArgList().size() == 2) {
      RexLiteral literal = findDelimiterInListAgg(call, input);
      if (literal == null) {
        throw new AssertionError(
            "Delimiter argument in list_agg call should be a constant varchar value.");
      }

      LogicalExpression delimiterExpression = literal.accept(visitor);
      if (!(delimiterExpression instanceof ValueExpressions.QuotedString)) {
        throw new AssertionError(
            "Delimiter argument in list_agg call should be a constant varchar value");
      }

      extraExpressions.add(delimiterExpression);
    }

    return new ListAggExpression(name, args, isDistinct, orderings, extraExpressions);
  }

  private static FunctionCall aggCallToFunctionCall(AggregateCall call, List<String> fn) {
    Preconditions.checkArgument(call.getAggregation().getKind() != SqlKind.LISTAGG);

    if (call.isDistinct()) {
      throw new UnsupportedOperationException("FunctionCall does not support 'distinct' flag.");
    }

    // RelDataType type does not get passed to FunctionCall, since it is not a materialized type.

    String name = call.getAggregation().getName().toLowerCase();

    List<LogicalExpression> args =
        call.getArgList().stream()
            .map(i -> FieldReference.getWithQuotedRef(fn.get(i)))
            .collect(Collectors.toList());

    // Converts COUNT(*) to COUNT(1) as a simple workaround.
    if (args.isEmpty()) {
      args.add(new ValueExpressions.LongExpression(1L));
    }

    if (!call.getCollation().getFieldCollations().isEmpty()) {
      throw new UnsupportedOperationException("FunctionCall does not support ordering.");
    }

    return new FunctionCall(name, args);
  }

  private static class Visitor extends RexVisitorImpl<LogicalExpression> {
    private final ParseContext context;
    private final RelDataType rowType;
    private final RexBuilder rexBuilder;
    private final boolean throwUserException;
    private final long caseExpressionsThreshold;
    private final IntFunction<Optional<Integer>> inputFunction;

    private int totalCaseCount;

    Visitor(
        ParseContext context,
        RelDataType rowType,
        RexBuilder rexBuilder,
        boolean throwUserException,
        IntFunction<Optional<Integer>> inputFunction) {
      super(true);
      this.context = context;
      this.throwUserException = throwUserException;
      this.rowType = rowType;
      this.rexBuilder = rexBuilder;
      this.inputFunction = inputFunction;
      this.caseExpressionsThreshold = context.getPlannerSettings().getCaseExpressionsThreshold();
      this.totalCaseCount = 0;
    }

    @Override
    public LogicalExpression visitInputRef(RexInputRef inputRef) {
      final int index = inputRef.getIndex();
      Optional<Integer> input = inputFunction.apply(index);

      final RelDataTypeField field = rowType.getFieldList().get(index);
      FieldReference reference = FieldReference.getWithQuotedRef(field.getName());

      return input.map(i -> (LogicalExpression) new InputReference(i, reference)).orElse(reference);
    }

    @Override
    public LogicalExpression visitCall(RexCall call) {
      final SqlSyntax syntax = call.getOperator().getSyntax();
      switch (syntax) {
        case BINARY:
          final String funcName = call.getOperator().getName().toLowerCase();
          return doFunction(call, funcName);
        case FUNCTION:
        case FUNCTION_ID:
          return getFunction(call);
        case POSTFIX:
          switch (call.getKind()) {
            case IS_NOT_NULL:
            case IS_NOT_TRUE:
            case IS_NOT_FALSE:
            case IS_NULL:
            case IS_TRUE:
            case IS_FALSE:
            case OTHER:
              return FunctionCallFactory.createExpression(
                  call.getOperator().getName().toLowerCase(),
                  call.getOperands().get(0).accept(this));
            default:
              throw new AssertionError("todo: implement syntax " + syntax + "(" + call + ")");
          }
        case PREFIX:
          LogicalExpression arg = call.getOperands().get(0).accept(this);
          switch (call.getKind()) {
            case NOT:
              return FunctionCallFactory.createExpression(
                  call.getOperator().getName().toLowerCase(), arg);
            case MINUS_PREFIX:
              final List<RexNode> operands = new ArrayList<>();
              operands.add(rexBuilder.makeExactLiteral(new BigDecimal(-1)));
              operands.add(call.getOperands().get(0));

              return visitCall(
                  (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, operands));
            default:
              throw new AssertionError("todo: implement syntax " + syntax + "(" + call + ")");
          }
        case SPECIAL:
          switch (call.getKind()) {
            case ARRAY_VALUE_CONSTRUCTOR:
              return new ArrayLiteralExpression(
                  ImmutableList.copyOf(this.visitList(call.getOperands())));
            case CAST:
              return getCastFunction(call);
            case LIKE:
            case SIMILAR:
              return getFunction(call);
            case CASE:
              if (totalCaseCount == 0) {
                // do only at top level, to avoid mixed case and if at different nesting levels.
                totalCaseCount = getNestedCaseCount(call);
              }
              List<LogicalExpression> caseArgs = new ArrayList<>();
              for (RexNode r : call.getOperands()) {
                caseArgs.add(r.accept(this));
              }

              // number of arguments are always going to be odd, because
              // Calcite adds "null" for the missing else expression at the end
              assert caseArgs.size() % 2 == 1;
              final int last = caseArgs.size() - 1;
              LogicalExpression elseExpression = caseArgs.get(last);
              if (totalCaseCount > caseExpressionsThreshold) {
                // for case, process in order to maintain query order
                List<CaseExpression.CaseConditionNode> caseConditions = new ArrayList<>();
                for (int i = 0; i < last; i = i + 2) {
                  caseConditions.add(
                      new CaseExpression.CaseConditionNode(caseArgs.get(i), caseArgs.get(i + 1)));
                }
                elseExpression =
                    CaseExpression.newBuilder()
                        .setCaseConditions(caseConditions)
                        .setElseExpr(elseExpression)
                        .build();
              } else {
                // for if/else, process in reverse to maintain query order
                for (int i = last - 1; i > 0; i = i - 2) {
                  elseExpression =
                      IfExpression.newBuilder()
                          .setElse(elseExpression)
                          .setIfCondition(
                              new IfExpression.IfCondition(caseArgs.get(i - 1), caseArgs.get(i)))
                          .build();
                }
              }
              return elseExpression;
            default:
              break;
          }

          if (call.getOperator() == SqlStdOperatorTable.ITEM) {
            final RexNode rexNode = call.getOperands().get(0);
            final SchemaPath path = getSchemaPath(this, rexNode);

            // Convert expr of item[*, 'abc'] into column expression 'abc'
            String rootSegName = path.getRootSegment().getPath();
            if (StarColumnHelper.isStarColumn(rootSegName)) {
              rootSegName = rootSegName.substring(0, rootSegName.indexOf("*"));
              final RexLiteral literal = (RexLiteral) call.getOperands().get(1);
              return SchemaPath.getSimplePath(rootSegName + literal.getValue2().toString());
            }

            RexNode indexer = call.getOperands().get(1);
            if (indexer instanceof RexLiteral) {
              final RexLiteral literal = (RexLiteral) indexer;
              switch (literal.getType().getSqlTypeName()) {
                case DECIMAL:
                case INTEGER:
                case BIGINT:
                  return path.getChild(((BigDecimal) literal.getValue()).intValue());
                case CHAR:
                case VARCHAR:
                  return path.getChild(literal.getValue2().toString());
                default:
                  throw UserException.validationError()
                      .message("Unknown index kind: " + literal.getType().getSqlTypeName())
                      .buildSilently();
              }
            } else if (indexer instanceof RexInputRef
                && (rexNode.getType().getSqlTypeName() == SqlTypeName.ARRAY
                    || rexNode.getType().getSqlTypeName() == SqlTypeName.MAP)) {
              FieldReference ref = (FieldReference) call.getOperands().get(1).accept(this);
              return path.getChild(ref.getRootSegment().getPath(), true);
            } else {
              throw UserException.validationError()
                  .message("Indexing Into an Struct must be a literal or reference value")
                  .buildSilently();
            }
          }

          if (call.getOperator() == SqlStdOperatorTable.DOT) {
            final RexNode rexNode = call.getOperands().get(0);
            final SchemaPath path = getSchemaPath(this, rexNode);
            final RexLiteral literal = (RexLiteral) call.getOperands().get(1);
            switch (literal.getType().getSqlTypeName()) {
              case CHAR:
              case VARCHAR:
                return path.getChild(literal.getValue2().toString());
              case DECIMAL:
              case INTEGER:
              default:
                break;
            }
          }

          if (call.getOperator() == SqlStdOperatorTable.DATETIME_PLUS) {
            final LogicalExpression dtPlus = doFunction(call, "+");

            // If this was a date, and we added an interval, we need to
            // return a date.
            // TODO(DX-11952): Fix return types for datetime_plus to match
            // the operator definition.
            if (call.getOperands().get(0).getType().getSqlTypeName() == SqlTypeName.DATE) {
              switch (call.getOperands().get(1).getType().getSqlTypeName()) {
                case INTERVAL_YEAR_MONTH:
                case INTERVAL_YEAR:
                case INTERVAL_MONTH:
                case INTERVAL_DAY:
                  return FunctionCallFactory.createCast(Types.required(MinorType.DATE), dtPlus);
                default:
                  break;
              }
            }

            return dtPlus;
          }

          if (MoreRelOptUtil.isDatetimeMinusInterval(call)) {
            return doFunction(call, "-");
          }
        // fall through
        default:
          throw new UnsupportedOperationException(
              "Unimplemented syntax: " + syntax + " for (" + call + ")");
      }
    }

    private SchemaPath getSchemaPath(Visitor visitor, RexNode rexNode) {
      LogicalExpression expression = rexNode.accept(visitor);
      if (expression instanceof SchemaPath) {
        return (SchemaPath) expression;
      } else {
        throw new UnsupportedOperationException("Unimplemented support for (" + rexNode + ")");
      }
    }

    private LogicalExpression doFunction(RexCall call, String funcName) {
      List<LogicalExpression> args = new ArrayList<>();
      for (RexNode r : call.getOperands()) {
        args.add(r.accept(this));
      }

      if (FunctionCallFactory.isBooleanOperator(funcName)) {
        LogicalExpression func = FunctionCallFactory.createBooleanOperator(funcName, args);
        return func;
      } else {
        Collections.reverse(args);
        LogicalExpression lastArg = args.get(0);
        for (int i = 1; i < args.size(); i++) {
          lastArg =
              FunctionCallFactory.createExpression(funcName, Arrays.asList(args.get(i), lastArg));
        }

        return lastArg;
      }
    }

    private LogicalExpression doUnknown(RexNode o) {
      final String message =
          String.format(
              UNSUPPORTED_REX_NODE_ERROR + "RexNode Class: %s, RexNode Digest: %s",
              o.getClass().getName(),
              o.toString());
      if (throwUserException) {
        throw UserException.planError().message(message).build(logger);
      } else {
        throw new IllegalStateException(message);
      }
    }

    @Override
    public LogicalExpression visitLocalRef(RexLocalRef localRef) {
      return doUnknown(localRef);
    }

    @Override
    public LogicalExpression visitOver(RexOver over) {
      return doUnknown(over);
    }

    @Override
    public LogicalExpression visitSubQuery(RexSubQuery rexSubQuery) {
      return doUnknown(rexSubQuery);
    }

    @Override
    public LogicalExpression visitCorrelVariable(RexCorrelVariable correlVariable) {
      return doUnknown(correlVariable);
    }

    @Override
    public LogicalExpression visitDynamicParam(RexDynamicParam dynamicParam) {
      return doUnknown(dynamicParam);
    }

    @Override
    public LogicalExpression visitRangeRef(RexRangeRef rangeRef) {
      return doUnknown(rangeRef);
    }

    @Override
    public LogicalExpression visitFieldAccess(RexFieldAccess fieldAccess) {
      SchemaPath reference = (SchemaPath) fieldAccess.getReferenceExpr().accept(this);
      return reference.getChild(fieldAccess.getField().getName());
    }

    @SuppressWarnings(
        "FallThrough") // FIXME: remove suppression by properly handling switch fallthrough
    private LogicalExpression getCastFunction(RexCall call) {
      Preconditions.checkArgument(call.getOperands().size() == 1);

      final RexNode sourceExpression = call.getOperands().get(0);
      LogicalExpression arg = sourceExpression.accept(this);
      MajorType castType = null;

      final SqlTypeName argType = call.getOperands().get(0).getType().getSqlTypeName();

      boolean isTargetNumber = false;
      switch (call.getType().getSqlTypeName()) {
        case VARCHAR:
        case CHAR:
          castType =
              MajorType.newBuilder()
                  .setMinorType(MinorType.VARCHAR)
                  .setMode(DataMode.OPTIONAL)
                  .setWidth(call.getType().getPrecision())
                  .build();
          break;

        case BIGINT:
          castType = Types.required(MinorType.BIGINT);
          isTargetNumber = true;
          break;
        case INTEGER:
          castType = Types.required(MinorType.INT);
          isTargetNumber = true;
          break;
        case FLOAT:
          castType = Types.required(MinorType.FLOAT4);
          isTargetNumber = true;
          break;
        case DOUBLE:
          castType = Types.required(MinorType.FLOAT8);
          isTargetNumber = true;
          break;
        case DECIMAL:
          if (!context
              .getPlannerSettings()
              .getOptions()
              .getOption(PlannerSettings.ENABLE_DECIMAL_DATA_TYPE)) {
            throw UserException.unsupportedError()
                .message(ExecErrorConstants.DECIMAL_DISABLE_ERR_MSG)
                .build(logger);
          }

          int precision = call.getType().getPrecision();
          int scale = call.getType().getScale();
          isTargetNumber = true;

          if (precision <= 38) {
            castType =
                MajorType.newBuilder()
                    .setMinorType(MinorType.DECIMAL)
                    .setMode(DataMode.REQUIRED)
                    .setPrecision(precision)
                    .setScale(scale)
                    .build();
          } else {
            throw new UnsupportedOperationException(
                "Only Decimal types with precision range 0 - 38 is supported");
          }
          break;

        case INTERVAL_YEAR:
          if (argType == BIGINT || argType == INTEGER) {
            // Casting from numeric to interval, convert to months for storage in INTERVALYEAR
            arg =
                FunctionCallFactory.createExpression(
                    "multiply",
                    arg,
                    ValueExpressions.getBigInt(
                        org.apache.arrow.vector.util.DateUtility.yearsToMonths));
          }
          castType = Types.required(MinorType.INTERVALYEAR);
          break;
        case INTERVAL_YEAR_MONTH:
        case INTERVAL_MONTH:
          castType = Types.required(MinorType.INTERVALYEAR);
          break;
        case INTERVAL_DAY:
        case INTERVAL_HOUR:
        case INTERVAL_MINUTE:
        case INTERVAL_SECOND:
          if (argType == BIGINT || argType == INTEGER) {
            // Casting from numeric to interval, convert to miliseconds for storage in INTERVALDAY
            long multiplier = 1;
            switch (call.getType().getSqlTypeName()) {
              case INTERVAL_DAY:
                multiplier = org.apache.arrow.vector.util.DateUtility.daysToStandardMillis;
                break;
              case INTERVAL_HOUR:
                multiplier = org.apache.arrow.vector.util.DateUtility.hoursToMillis;
                break;
              case INTERVAL_MINUTE:
                multiplier = org.apache.arrow.vector.util.DateUtility.minutesToMillis;
                break;
              case INTERVAL_SECOND:
                multiplier = org.apache.arrow.vector.util.DateUtility.secondsToMillis;
                break;
              default:
                break;
            }
            arg =
                FunctionCallFactory.createExpression(
                    "multiply", arg, ValueExpressions.getBigInt(multiplier));
          }
          castType = Types.required(MinorType.INTERVALDAY);
          break;
        case INTERVAL_DAY_HOUR:
        case INTERVAL_DAY_MINUTE:
        case INTERVAL_DAY_SECOND:
        case INTERVAL_HOUR_MINUTE:
        case INTERVAL_HOUR_SECOND:
        case INTERVAL_MINUTE_SECOND:
          castType = Types.required(MinorType.INTERVALDAY);
          break;
        case BOOLEAN:
          castType = Types.required(MinorType.BIT);
          break;
        case BINARY:
          castType =
              MajorType.newBuilder()
                  .setMinorType(MinorType.VARBINARY)
                  .setMode(DataMode.OPTIONAL)
                  .setPrecision(call.getType().getPrecision())
                  .build();
          break;
        case TIMESTAMP:
          castType = Types.required(MinorType.TIMESTAMPMILLI);
          break;
        case ARRAY:
          castType = Types.required(MinorType.LIST);
        case ANY:
          return arg; // Type will be same as argument.
        default:
          castType = Types.required(MinorType.valueOf(call.getType().getSqlTypeName().getName()));
      }

      switch (sourceExpression.getType().getSqlTypeName().getFamily()) {
        case INTERVAL_DAY_TIME:
        case INTERVAL_YEAR_MONTH:
          if (isTargetNumber) {
            // Intervals need to be scaled according to their trailing field when they are
            // cast to numbers. The cast function evaluates to milliseconds for
            // IntervalDayHolders and months for IntervalYearHolders.
            final LogicalExpression divider =
                ValueExpressions.getInt(
                    sourceExpression.getType().getSqlTypeName().getEndUnit().multiplier.intValue());

            if (call.getType().getSqlTypeName() == INTEGER) {
              // To avoid overflow errors when casting larger intervals to their smallest field,
              // then scaling:
              // 1. Promote the CAST to BIGINT
              // 2. Do the division, and cast back
              // 3. Cast the result back to the original target type.
              final LogicalExpression castExpr =
                  FunctionCallFactory.createCast(Types.required(MinorType.BIGINT), arg);
              final List<LogicalExpression> args = ImmutableList.of(castExpr, divider);
              final LogicalExpression divisionOp =
                  FunctionCallFactory.createExpression("divide", args);
              return FunctionCallFactory.createCast(Types.required(MinorType.INT), divisionOp);
            }

            // If we're casting intervals to bigint we can't promote to another type
            // (interval to high precision numeric is not implemented).
            final LogicalExpression castExpr = FunctionCallFactory.createCast(castType, arg);
            final List<LogicalExpression> args = ImmutableList.of(castExpr, divider);
            return FunctionCallFactory.createExpression("divide", args);
          }
          break;
        default:
          break;
      }

      return FunctionCallFactory.createCast(castType, arg);
    }

    @SuppressWarnings("checkstyle:EqualsAvoidNull") // "functionName" is never null
    private LogicalExpression getFunction(RexCall call) {
      List<LogicalExpression> args = new ArrayList<>();

      for (RexNode n : call.getOperands()) {
        args.add(n.accept(this));
      }

      int argsSize = args.size();
      String functionName = call.getOperator().getName().toLowerCase();

      // TODO: once we have more function rewrites and a pattern emerges from different rewrites,
      // factor this out in a better fashion
      /* Rewrite extract functions in the following manner
       * extract(year, date '2008-2-23') ---> extractYear(date '2008-2-23')
       */
      if (functionName.equals("extract")) {
        return handleExtractFunction(args);
      } else if (functionName.equals("trim")) {
        String trimFunc = null;
        List<LogicalExpression> trimArgs = new ArrayList<>();

        assert args.get(0) instanceof ValueExpressions.QuotedString;
        String trimType = ((QuotedString) args.get(0)).value.toUpperCase();
        switch (trimType) {
          case "LEADING":
            trimFunc = "ltrim";
            break;
          case "TRAILING":
            trimFunc = "rtrim";
            break;
          case "BOTH":
            trimFunc = "btrim";
            break;
          default:
            throw new IllegalArgumentException("Unrecognized trim: " + trimType);
        }

        trimArgs.add(args.get(2));
        trimArgs.add(args.get(1));

        return FunctionCallFactory.createExpression(trimFunc, trimArgs);
      } else if (functionName.equals("concat")) {

        if (argsSize == 1) {
          /*
           * We treat concat with one argument as a special case. Since we don't have a function
           * implementation of concat that accepts one argument. We simply add another dummy argument
           * (empty string literal) to the list of arguments.
           */
          List<LogicalExpression> concatArgs = new LinkedList<>(args);
          concatArgs.add(new QuotedString(""));

          return FunctionCallFactory.createExpression(functionName, concatArgs);

        } else if (argsSize > CONCAT_MAX_ARGS) {
          List<LogicalExpression> concatArgs = new ArrayList<>(args.subList(0, CONCAT_MAX_ARGS));
          /* stack concat functions on top of each other if we have more than 10 arguments
           * Eg: concat(c1, c2, c3, ..., c10, c11, c12) => concat(concat(c1, c2, ..., c10), c11, c12)
           */

          LogicalExpression first = FunctionCallFactory.createExpression(functionName, concatArgs);

          for (int i = CONCAT_MAX_ARGS; i < argsSize; i = i + (CONCAT_MAX_ARGS - 1)) {
            concatArgs = new ArrayList<>();
            concatArgs.add(first);
            concatArgs.addAll(args.subList(i, Math.min(argsSize, i + (CONCAT_MAX_ARGS - 1))));
            first = FunctionCallFactory.createExpression(functionName, concatArgs);
          }

          return first;
        }
      } else if (functionName.equals("length")) {

        if (argsSize == 2) {

          // Second argument should always be a literal specifying the encoding format
          assert args.get(1) instanceof ValueExpressions.QuotedString;

          String encodingType = ((ValueExpressions.QuotedString) args.get(1)).value;
          functionName +=
              encodingType.substring(0, 1).toUpperCase() + encodingType.substring(1).toLowerCase();

          return FunctionCallFactory.createExpression(functionName, args.subList(0, 1));
        }
      } else if (argsSize == 2
          && (functionName.equals("convert_from") || functionName.equals("convert_to"))
          && args.get(1) instanceof QuotedString) {
        // 2-argument convert_{from,to}
        return FunctionCallFactory.createConvert(
            functionName, ((QuotedString) args.get(1)).value, args.get(0));
      } else if (argsSize == 3
          && functionName.equals("convert_from")
          && args.get(1) instanceof QuotedString
          && args.get(2) instanceof QuotedString) {
        // 3-argument convert_from, with the third argument being the replacement string for illegal
        // conversion values
        return FunctionCallFactory.createConvertReplace(
            functionName, (QuotedString) args.get(1), args.get(0), (QuotedString) args.get(2));
      } else if (functionName.equals("date_trunc")) {
        return handleDateTruncFunction(args);
      } else if (functionName.equals("timestampdiff") || functionName.equals("timestampadd")) {
        return handleTimestampArithmanticFunction(functionName, args);
      } else if (functionName.equals("array_sum")
          || functionName.equals("array_max")
          || functionName.equals("array_min")) {
        return handleArrayFunction(functionName, args, call.getType().getSqlTypeName());
      }

      return FunctionCallFactory.createExpression(functionName, args);
    }

    private LogicalExpression handleExtractFunction(final List<LogicalExpression> args) {
      // Assert that the first argument to extract is a QuotedString
      assert args.get(0) instanceof ValueExpressions.QuotedString;

      // Get the unit of time to be extracted
      String timeUnitStr = ((ValueExpressions.QuotedString) args.get(0)).value.toUpperCase();

      switch (timeUnitStr) {
        case ("YEAR"):
        case ("MONTH"):
        case ("DAY"):
        case ("HOUR"):
        case ("MINUTE"):
        case ("SECOND"):
        case ("DOW"):
        case ("DOY"):
        case ("WEEK"):
        case ("DECADE"):
        case ("CENTURY"):
        case ("MILLENNIUM"):
        case ("QUARTER"):
        case ("EPOCH"):
          String functionPostfix =
              timeUnitStr.substring(0, 1).toUpperCase() + timeUnitStr.substring(1).toLowerCase();
          String functionName = "extract" + functionPostfix;
          return FunctionCallFactory.createExpression(functionName, args.subList(1, 2));
        default:
          throw new UnsupportedOperationException(
              "extract function supports the following time units: "
                  + "YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, WEEK, DOW, DOY, DECADE, CENTURY, MILLENNIUM, QUARTER, EPOCH");
      }
    }

    private LogicalExpression handleDateTruncFunction(final List<LogicalExpression> args) {
      if (!(args.get(0) instanceof ValueExpressions.QuotedString)) {
        throw new UnsupportedOperationException(
            "The first argument of date_trunc function must be time units. Expecting   "
                + "YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, WEEK, QUARTER, DECADE, CENTURY, MILLENNIUM");
      }
      // Get the unit of time to be extracted
      String timeUnitStr = ((ValueExpressions.QuotedString) args.get(0)).value.toUpperCase();

      switch (timeUnitStr) {
        case ("YEAR"):
        case ("MONTH"):
        case ("DAY"):
        case ("HOUR"):
        case ("MINUTE"):
        case ("SECOND"):
        case ("WEEK"):
        case ("QUARTER"):
        case ("DECADE"):
        case ("CENTURY"):
        case ("MILLENNIUM"):
          final String functionPostfix =
              timeUnitStr.substring(0, 1).toUpperCase() + timeUnitStr.substring(1).toLowerCase();
          return FunctionCallFactory.createExpression(
              "date_trunc_" + functionPostfix, args.subList(1, 2));
        default:
          break;
      }
      throw new UnsupportedOperationException(
          "date_trunc function supports the following time units: "
              + "YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, WEEK, QUARTER, DECADE, CENTURY, MILLENNIUM");
    }

    private LogicalExpression handleTimestampArithmanticFunction(
        final String funcName, final List<LogicalExpression> args) {
      // Assert that the first argument to TIMESTAMPADD/TIMESTAMPDIFF is a QuotedString
      assert args.get(0) instanceof ValueExpressions.QuotedString;

      // Get the output unit
      String timeUnitStr = ((ValueExpressions.QuotedString) args.get(0)).value;

      switch (timeUnitStr) {
        case ("YEAR"):
        case ("QUARTER"):
        case ("MONTH"):
        case ("WEEK"):
        case ("DAY"):
        case ("HOUR"):
        case ("MINUTE"):
        case ("SECOND"):
          String functionPostfix =
              timeUnitStr.substring(0, 1).toUpperCase() + timeUnitStr.substring(1).toLowerCase();
          String functionName = funcName + functionPostfix;
          return FunctionCallFactory.createExpression(functionName, args.subList(1, 3));
        // TODO (DX-11268): Fix TIMESTAMPADD(SQL_TSI_FRAC_SECOND, ..., ...) function
        // case ("NANOSECOND"):
        default:
          throw UserException.unsupportedError()
              .message(
                  funcName.toUpperCase()
                      + " function supports the following "
                      + "time units: YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND; and not "
                      + timeUnitStr)
              .build(logger);
      }
    }

    private LogicalExpression handleArrayFunction(
        String functionName, final List<LogicalExpression> args, SqlTypeName elementType) {
      if (SUPPORTED_ARRAY_SUM_MAX_TYPES.contains(elementType)) {
        final String functionPostfix = elementType.getName();
        return FunctionCallFactory.createExpression(functionName + "_" + functionPostfix, args);
      }
      throw new UnsupportedOperationException(
          String.format(
              "%s function supports the following types: %s",
              functionName,
              SUPPORTED_ARRAY_SUM_MAX_TYPES.stream()
                  .map(SqlTypeName::getName)
                  .collect(Collectors.joining(", "))));
    }

    @Override
    public LogicalExpression visitLiteral(RexLiteral literal) {
      switch (literal.getType().getSqlTypeName()) {
        case BIGINT:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.BIGINT);
          }
          long l =
              (((BigDecimal) literal.getValue()).setScale(0, RoundingMode.HALF_UP)).longValue();
          return ValueExpressions.getBigInt(l);
        case BOOLEAN:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.BIT);
          }
          return ValueExpressions.getBit(((Boolean) literal.getValue()));
        case CHAR:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.VARCHAR);
          }
          return ValueExpressions.getChar(((NlsString) literal.getValue()).getValue());
        case DOUBLE:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.FLOAT8);
          }
          double d = ((BigDecimal) literal.getValue()).doubleValue();
          return ValueExpressions.getFloat8(d);
        case FLOAT:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.FLOAT4);
          }
          float f = ((BigDecimal) literal.getValue()).floatValue();
          return ValueExpressions.getFloat4(f);
        case INTEGER:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.INT);
          }
          int a = (((BigDecimal) literal.getValue()).setScale(0, RoundingMode.HALF_UP)).intValue();
          return ValueExpressions.getInt(a);

        case DECIMAL:
          if (context
              .getPlannerSettings()
              .getOptions()
              .getOption(PlannerSettings.ENABLE_DECIMAL_V2)) {
            if (isLiteralNull(literal)) {
              return new TypedNullConstant(
                  CompleteType.fromDecimalPrecisionScale(
                      literal.getType().getPrecision(), literal.getType().getScale()));
            }
            BigDecimal literalValue = (BigDecimal) literal.getValue();
            return ValueExpressions.getDecimal(
                literalValue, literal.getType().getPrecision(), literal.getType().getScale());
          } else {
            if (isLiteralNull(literal)) {
              return createNullExpr(MinorType.FLOAT8);
            }
            double dbl = ((BigDecimal) literal.getValue()).doubleValue();
            if (warnDecimal) {
              logger.warn(
                  "Converting exact decimal into approximate decimal.  Should be fixed once decimal is implemented.");
              warnDecimal = false;
            } else {
              logger.debug(
                  "Converting exact decimal into approximate decimal.  Should be fixed once decimal is implemented.");
            }
            return ValueExpressions.getFloat8(dbl);
          }
        case VARCHAR:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.VARCHAR);
          }
          return ValueExpressions.getChar(((NlsString) literal.getValue()).getValue());
        case SYMBOL:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.VARCHAR);
          }
          return ValueExpressions.getChar(literal.getValue().toString());
        case DATE:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.DATE);
          }
          return (ValueExpressions.getDate(literal.getValueAs(DateString.class)));
        case TIME:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.TIME);
          }
          return (ValueExpressions.getTime(literal.getValueAs(TimeString.class)));
        case TIMESTAMP:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.TIMESTAMPMILLI);
          }
          return (ValueExpressions.getTimeStamp(literal.getValueAs(TimestampString.class)));
        case INTERVAL_YEAR:
        case INTERVAL_YEAR_MONTH:
        case INTERVAL_MONTH:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.INTERVALYEAR);
          }
          return (ValueExpressions.getIntervalYear(((BigDecimal) (literal.getValue())).intValue()));
        case INTERVAL_DAY:
        case INTERVAL_DAY_HOUR:
        case INTERVAL_DAY_MINUTE:
        case INTERVAL_DAY_SECOND:
        case INTERVAL_HOUR:
        case INTERVAL_HOUR_MINUTE:
        case INTERVAL_HOUR_SECOND:
        case INTERVAL_MINUTE:
        case INTERVAL_MINUTE_SECOND:
        case INTERVAL_SECOND:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.INTERVALDAY);
          }
          return (ValueExpressions.getIntervalDay(((BigDecimal) (literal.getValue())).longValue()));
        case NULL:
          return NullExpression.INSTANCE;
        case ANY:
        case ROW:
        case ARRAY:
          if (isLiteralNull(literal)) {
            return NullExpression.INSTANCE;
          }
          break;
        case VARBINARY:
          if (isLiteralNull(literal)) {
            return createNullExpr(MinorType.VARBINARY);
          }
          break;
        default:
          break;
      }
      throw new UnsupportedOperationException(
          String.format(
              "Unable to convert the value of %s and type %s to a Dremio constant expression.",
              literal, literal.getType().getSqlTypeName()));
    }
  }

  private static int getNestedCaseCount(RexCall call) {
    final RexVisitorImpl<Integer> countVisitor =
        new RexVisitorImpl<Integer>(true) {
          @Override
          public Integer visitCall(RexCall call) {
            if (!call.getKind().equals(SqlKind.CASE)) {
              return super.visitCall(call);
            }

            int topLevelSum = call.getOperands().size();
            int nestedLevelSum =
                call.getOperands().stream()
                    .mapToInt(
                        (r) -> {
                          Integer val = r.accept(this);
                          return (val != null) ? val : 0;
                        })
                    .sum();
            return topLevelSum + nestedLevelSum;
          }
        };
    return call.accept(countVisitor);
  }

  private static final TypedNullConstant createNullExpr(MinorType type) {
    return new TypedNullConstant(CompleteType.fromMinorType(type));
  }

  public static boolean isLiteralNull(RexLiteral literal) {
    return literal.isNull();
  }
}
