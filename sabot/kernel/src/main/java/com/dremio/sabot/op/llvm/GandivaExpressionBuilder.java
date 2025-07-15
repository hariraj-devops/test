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
package com.dremio.sabot.op.llvm;

import com.dremio.common.expression.BooleanOperator;
import com.dremio.common.expression.CaseExpression;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.ExpressionStringBuilder;
import com.dremio.common.expression.FunctionHolderExpression;
import com.dremio.common.expression.IfExpression;
import com.dremio.common.expression.InExpression;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.TypedNullConstant;
import com.dremio.common.expression.ValueExpressions.BooleanExpression;
import com.dremio.common.expression.ValueExpressions.DecimalExpression;
import com.dremio.common.expression.ValueExpressions.DoubleExpression;
import com.dremio.common.expression.ValueExpressions.FloatExpression;
import com.dremio.common.expression.ValueExpressions.IntExpression;
import com.dremio.common.expression.ValueExpressions.LongExpression;
import com.dremio.common.expression.ValueExpressions.QuotedString;
import com.dremio.common.expression.visitors.AbstractExprVisitor;
import com.dremio.exec.compile.sig.ConstantExpressionIdentifier;
import com.dremio.exec.expr.OrInConverter;
import com.dremio.exec.expr.ValueVectorReadExpression;
import com.dremio.exec.expr.fn.AbstractFunctionHolder;
import com.dremio.exec.expr.fn.BaseFunctionHolder;
import com.dremio.exec.expr.fn.GandivaFunctionHolder;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.sabot.exec.context.FunctionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.arrow.gandiva.expression.Condition;
import org.apache.arrow.gandiva.expression.ExpressionTree;
import org.apache.arrow.gandiva.expression.TreeBuilder;
import org.apache.arrow.gandiva.expression.TreeNode;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.types.pojo.Field;

/** Convert LogicalExpression to Gandiva Expressions. */
public class GandivaExpressionBuilder
    extends AbstractExprVisitor<TreeNode, Void, RuntimeException> {
  private final VectorAccessible incoming;
  private final Set<ReferencedField> referencedFields;
  private final Set<LogicalExpression> constantSet;
  private final FunctionContext functionContext;
  private final boolean enableOrOptimization;
  private final int minConversionSize;
  private final int minConversionSizeForVarchars;
  private final AtomicInteger fieldNameCreator = new AtomicInteger(0);
  private static final List<CompleteType> supportedInTypesInGandiva =
      Lists.newArrayList(CompleteType.BIGINT, CompleteType.INT, CompleteType.VARCHAR);
  private static final List<Class<? extends LogicalExpression>> supportedExpressionTypes =
      Lists.newArrayList(ValueVectorReadExpression.class);
  private boolean isComplex = false;

  private GandivaExpressionBuilder(
      VectorAccessible incoming,
      Set<ReferencedField> referencedFields,
      Set<LogicalExpression> constantSet,
      FunctionContext functionContext) {
    this.incoming = incoming;
    this.referencedFields = referencedFields;
    this.constantSet = constantSet;
    this.functionContext = functionContext;
    this.enableOrOptimization = functionContext.getCompilationOptions().enableOrOptimization();
    this.minConversionSize =
        functionContext.getCompilationOptions().getOrOptimizationThresholdForGandiva();
    this.minConversionSizeForVarchars =
        functionContext.getCompilationOptions().getVarcharOrOptimizationThresholdForGandiva();
  }

  /** Take an expression tree and convert it into a Gandiva Expression. */
  public static ExpressionTree serializeExpr(
      VectorAccessible incoming,
      LogicalExpression ex,
      FieldVector out,
      Set<ReferencedField> referencedFields,
      FunctionContext functionContext) {
    GandivaExpressionBuilder serializer =
        new GandivaExpressionBuilder(
            incoming,
            referencedFields,
            ConstantExpressionIdentifier.getConstantExpressions(ex),
            functionContext);
    TreeNode expr = ex.accept(serializer, null);
    return TreeBuilder.makeExpression(expr, out.getField());
  }

  /**
   * Converts a logical expression into a filter condition
   *
   * @param incoming the Schema for the incoming batch
   * @param expr logical expression to serialize
   * @return Condition for the expression.
   */
  public static Condition serializeExprToCondition(
      VectorAccessible incoming,
      LogicalExpression expr,
      Set<ReferencedField> referencedFields,
      FunctionContext functionContext) {
    GandivaExpressionBuilder serializer =
        new GandivaExpressionBuilder(
            incoming,
            referencedFields,
            ConstantExpressionIdentifier.getConstantExpressions(expr),
            functionContext);
    TreeNode expression = expr.accept(serializer, null);
    return TreeBuilder.makeCondition(expression);
  }

  /*
   * work-around for older JDK compilers (see DX-13315).
   */
  private TreeNode acceptExpression(LogicalExpression expression) throws RuntimeException {
    try {
      return expression.accept(GandivaExpressionBuilder.this, null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public TreeNode visitFunctionHolderExpression(FunctionHolderExpression holder, Void value)
      throws RuntimeException {
    AbstractFunctionHolder definition = (AbstractFunctionHolder) holder.getHolder();
    Preconditions.checkState(
        definition instanceof BaseFunctionHolder || definition instanceof GandivaFunctionHolder);

    List<TreeNode> children =
        holder.args.stream().map(this::acceptExpression).collect(Collectors.toList());

    if (holder.getName().equals("castDECIMAL")
        || holder.getName().equals("castDECIMALNullOnOverflow")) {
      // remove the dummy args added for precision/scale. They are implicitly specified in the
      // return type.
      int size = children.size();

      Preconditions.checkState(
          size >= 3, "expected atleast three args for castDECIMAL/castDECIMALNullOnOverflow");

      Preconditions.checkState(
          holder.args.get(size - 1) instanceof LongExpression, "expected long type for precision");
      Preconditions.checkState(
          holder.args.get(size - 2) instanceof LongExpression, "expected long type for scale");
      children.remove(size - 1);
      children.remove(size - 2);
    }
    CompleteType returnType = definition.getReturnType(holder.args);
    if (returnType.getChildren().size() > 0) {
      return TreeBuilder.makeFunction(
          holder.getName(),
          children,
          returnType.getType(),
          returnType.getChildren().get(0).getType());
    }
    return TreeBuilder.makeFunction(
        holder.getName(), children, definition.getReturnType(holder.args).getType());
  }

  @Override
  public TreeNode visitIfExpression(IfExpression inExpr, Void value) {
    TreeNode ifCondition = inExpr.ifCondition.condition.accept(GandivaExpressionBuilder.this, null);
    TreeNode thenE = inExpr.ifCondition.expression.accept(GandivaExpressionBuilder.this, null);
    TreeNode elseE = inExpr.elseExpression.accept(GandivaExpressionBuilder.this, null);

    return TreeBuilder.makeIf(ifCondition, thenE, elseE, inExpr.getCompleteType().getType());
  }

  @Override
  public TreeNode visitBooleanOperator(BooleanOperator operator, Void value) {
    List<LogicalExpression> expressions = operator.args;

    if (enableOrOptimization && "booleanOr".equals(operator.getName())) {
      expressions =
          OrInConverter.optimizeMultiOrs(
              expressions,
              constantSet,
              minConversionSize,
              minConversionSizeForVarchars,
              supportedInTypesInGandiva,
              supportedExpressionTypes);
      if (expressions.size() == 1) {
        return visitUnknown(expressions.get(0), null);
      }
    }

    List<TreeNode> children =
        expressions.stream().map(this::acceptExpression).collect(Collectors.toList());

    if (operator.isAnd()) {
      return TreeBuilder.makeAnd(children);
    } else if (operator.isOr()) {
      return TreeBuilder.makeOr(children);
    } else {
      throw new UnsupportedOperationException(
          "BooleanOperator can only be booleanAnd, booleanOr. You are using " + operator.getName());
    }
  }

  @Override
  public TreeNode visitUnknown(LogicalExpression e, Void value) {
    if (e instanceof ValueVectorReadExpression) {
      return visitValueVectorReadExpression((ValueVectorReadExpression) e, value);
    } else if (e instanceof InExpression) {
      InExpression in = (InExpression) e;
      ValueVectorReadExpression read = (ValueVectorReadExpression) in.getEval();
      ReferencedField referencedField = setReferencedFields(read);
      referencedFields.add(referencedField);
      Field xformed = referencedField.getModifiedField();
      CompleteType constantType = in.getConstants().get(0).getCompleteType();
      if (CompleteType.INT.equals(constantType)) {
        Set<Integer> intValues =
            in.getConstants().stream()
                .map(constant -> ((IntExpression) constant).getInt())
                .collect(Collectors.toSet());
        return TreeBuilder.makeInExpressionInt32(TreeBuilder.makeField(xformed), intValues);
      } else if (CompleteType.BIGINT.equals(constantType)) {
        Set<Long> longValues = Sets.newHashSet();
        for (LogicalExpression constant : in.getConstants()) {
          if (constant instanceof LongExpression) {
            longValues.add(((LongExpression) constant).getLong());
          } else if (constant instanceof FunctionHolderExpression) {
            LogicalExpression logicalExpression = ((FunctionHolderExpression) constant).args.get(0);
            if (logicalExpression instanceof IntExpression) {
              IntExpression expr = (IntExpression) logicalExpression;
              longValues.add(Long.valueOf(expr.getInt()));
            } else if (!(logicalExpression instanceof TypedNullConstant)) {
              throw new UnsupportedOperationException(
                  "Supports only int or null in IN expression" + ".");
            }
          }
        }
        return TreeBuilder.makeInExpressionBigInt(TreeBuilder.makeField(xformed), longValues);
      } else if (CompleteType.VARCHAR.equals(constantType)) {
        Set<String> stringValues = Sets.newHashSet();
        for (LogicalExpression constant : in.getConstants()) {
          if (constant instanceof QuotedString) {
            stringValues.add(((QuotedString) constant).getString());
          } else if (constant instanceof FunctionHolderExpression) {
            String val = ((FunctionHolderExpression) constant).args.get(0).toString();
            stringValues.add(val);
          }
        }
        return TreeBuilder.makeInExpressionString(TreeBuilder.makeField(xformed), stringValues);
      } else {
        // Should not reach here since the or-in conversion happens only for valid types
        throw new UnsupportedOperationException(
            "In not supported in Gandiva. Was trying to create an in expression of "
                + ((InExpression) e)
                    .getConstants().stream()
                        .map(expr -> ExpressionStringBuilder.toString(expr))
                        .collect(Collectors.joining(", ")));
      }
    } else {
      return super.visitUnknown(e, value);
    }
  }

  @Override
  public TreeNode visitCaseExpression(CaseExpression caseExpression, Void value)
      throws RuntimeException {
    TreeNode elseE = caseExpression.elseExpr.accept(this, null);
    for (int i = caseExpression.caseConditions.size() - 1; i >= 0; i--) {
      final CaseExpression.CaseConditionNode node = caseExpression.caseConditions.get(i);
      TreeNode ifCondition = node.whenExpr.accept(this, null);
      TreeNode thenE = node.thenExpr.accept(this, null);
      elseE =
          TreeBuilder.makeIf(ifCondition, thenE, elseE, caseExpression.getCompleteType().getType());
    }
    return elseE;
  }

  private TreeNode visitValueVectorReadExpression(ValueVectorReadExpression readExpr, Void value) {
    ReferencedField referencedField = setReferencedFields(readExpr);
    referencedFields.add(referencedField);
    Field xformed = referencedField.getModifiedField();
    return TreeBuilder.makeField(xformed);
  }

  private ReferencedField setReferencedFields(ValueVectorReadExpression readExp) {
    TypedFieldId typedFieldId = readExp.getTypedFieldId();
    isComplex = typedFieldId.getFieldIds().length > 1;
    FieldVector vector =
        incoming
            .getValueAccessorById(FieldVector.class, typedFieldId.getFieldIds())
            .getValueVector();
    ReferencedField referencedField;
    if (isComplex) {
      VectorWrapper<FieldVector> parent =
          incoming.getValueAccessorById(
              FieldVector.class, readExp.getTypedFieldId().getFieldIds()[0]);
      referencedField = new ReferencedField(vector, typedFieldId, parent);
    } else {
      referencedField = new ReferencedField(vector, typedFieldId);
    }
    referencedFields.add(referencedField);
    return referencedField;
  }

  @Override
  public TreeNode visitQuotedStringConstant(QuotedString e, Void value) {
    return TreeBuilder.makeStringLiteral(e.getString());
  }

  @Override
  public TreeNode visitIntConstant(IntExpression intExpr, Void value) {
    return TreeBuilder.makeLiteral(intExpr.getInt());
  }

  @Override
  public TreeNode visitLongConstant(LongExpression longExpr, Void value) {
    return TreeBuilder.makeLiteral(longExpr.getLong());
  }

  @Override
  public TreeNode visitFloatConstant(FloatExpression floatExpr, Void value) {
    return TreeBuilder.makeLiteral(floatExpr.getFloat());
  }

  @Override
  public TreeNode visitDoubleConstant(DoubleExpression doubleExpr, Void value) {
    return TreeBuilder.makeLiteral(doubleExpr.getDouble());
  }

  @Override
  public TreeNode visitBooleanConstant(BooleanExpression booleanExpr, Void value) {
    return TreeBuilder.makeLiteral(booleanExpr.getBoolean());
  }

  @Override
  public TreeNode visitNullConstant(TypedNullConstant constant, Void value) {
    return TreeBuilder.makeNull(constant.getCompleteType().getType());
  }

  @Override
  public TreeNode visitDecimalConstant(DecimalExpression decimalExpression, Void value) {
    BigInteger unScaledValue = decimalExpression.getDecimal().unscaledValue();
    return TreeBuilder.makeDecimalLiteral(
        unScaledValue.toString(), decimalExpression.getPrecision(), decimalExpression.getScale());
  }
}
