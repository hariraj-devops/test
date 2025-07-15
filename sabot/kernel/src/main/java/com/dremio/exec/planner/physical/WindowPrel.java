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

package com.dremio.exec.planner.physical;

import static com.google.common.base.Preconditions.checkState;

import com.dremio.common.expression.ErrorCollector;
import com.dremio.common.expression.ErrorCollectorImpl;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.ValueExpressions;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.logical.data.Order;
import com.dremio.exec.expr.ExpressionTreeMaterializer;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.config.WindowPOP;
import com.dremio.exec.planner.common.WindowRelBase;
import com.dremio.exec.planner.logical.ParseContext;
import com.dremio.exec.planner.logical.RexToExpr;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.LongValidator;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.dremio.sabot.op.windowframe.WindowFunction;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexWindowBound;
import org.apache.calcite.util.BitSets;

@Options
public class WindowPrel extends WindowRelBase implements Prel {

  public static final LongValidator RESERVE =
      new PositiveLongValidator("planner.op.window.reserve_bytes", Long.MAX_VALUE, DEFAULT_RESERVE);
  public static final LongValidator LIMIT =
      new PositiveLongValidator("planner.op.window.limit_bytes", Long.MAX_VALUE, DEFAULT_LIMIT);
  // The index of the first constant in the constants list. Needed to get the offset for the bound
  private final Integer startConstantsIndex;

  private WindowPrel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      List<RexLiteral> constants,
      RelDataType rowType,
      Group window,
      Integer startConstantsIndex) {
    super(cluster, traits, child, constants, rowType, Collections.singletonList(window));
    this.startConstantsIndex = startConstantsIndex;
  }

  public static WindowPrel create(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      List<RexLiteral> constants,
      RelDataType rowType,
      Group window,
      Integer startConstantsIndex) {
    final RelTraitSet traits =
        adjustTraits(cluster, child, Collections.singletonList(window), traitSet)
            // At first glance, Dremio window operator does not preserve distribution
            .replaceIf(DistributionTraitDef.INSTANCE, () -> DistributionTrait.DEFAULT);
    return new WindowPrel(cluster, traits, child, constants, rowType, window, startConstantsIndex);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    final RelDataType copiedRowType = deriveCopiedRowTypeFromInput(sole(inputs));
    return new WindowPrel(
        getCluster(),
        traitSet,
        sole(inputs),
        constants,
        copiedRowType,
        groups.get(0),
        startConstantsIndex);
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    Prel child = (Prel) this.getInput();

    PhysicalOperator childPOP = child.getPhysicalOperator(creator);

    final List<String> childFields = getInput().getRowType().getFieldNames();

    // We don't support distinct partitions
    checkState(groups.size() == 1, "Only one window is expected in WindowPrel");

    Group window = groups.get(0);
    List<NamedExpression> withins = Lists.newArrayList();
    List<NamedExpression> aggs = Lists.newArrayList();
    List<Order.Ordering> orderings = Lists.newArrayList();

    for (int group : BitSets.toIter(window.keys)) {
      FieldReference fr = new FieldReference(childFields.get(group));
      withins.add(new NamedExpression(fr, fr));
    }

    for (AggregateCall aggCall : window.getAggregateCalls(this)) {
      FieldReference ref = new FieldReference(aggCall.getName());
      LogicalExpression expr = toExpr(aggCall, childFields);
      aggs.add(new NamedExpression(expr, ref));
    }

    for (RelFieldCollation fieldCollation : window.orderKeys.getFieldCollations()) {
      orderings.add(
          new Order.Ordering(
              fieldCollation.getDirection(),
              new FieldReference(childFields.get(fieldCollation.getFieldIndex())),
              fieldCollation.nullDirection));
    }

    final BatchSchema childSchema = childPOP.getProps().getSchema();
    List<NamedExpression> exprs = new ArrayList<>();
    for (Field f : childSchema) {
      exprs.add(
          new NamedExpression(new FieldReference(f.getName()), new FieldReference(f.getName())));
    }
    SchemaBuilder schemaBuilder =
        ExpressionTreeMaterializer.materializeFields(
                exprs, childSchema, creator.getFunctionLookupContext())
            .setSelectionVectorMode(childSchema.getSelectionVectorMode());
    try (ErrorCollector collector = new ErrorCollectorImpl()) {
      for (NamedExpression expr : aggs) {
        WindowFunction func = WindowFunction.fromExpression(expr);
        schemaBuilder.addField(
            func.materialize(expr, childSchema, collector, creator.getFunctionLookupContext()));
      }
    }
    // TODO: DX-98085 - Remove the workaround.
    // The following is a workaround until the implementation of CompleteType
    // with nullability constraints is completed.
    // At which point the following code block which tries to set nullability in Field
    // via child schema while constructing schema is to be removed and replaced with
    // BatchSchema schema = schemaBuilder.build();
    BatchSchema schema =
        new BatchSchema(
            schemaBuilder.build().getFields().stream()
                .map(
                    f -> {
                      Optional<Field> field =
                          childSchema.getFields().stream()
                              .filter(cf -> cf.getName().equalsIgnoreCase(f.getName()))
                              .findFirst();
                      return field.isPresent()
                          ? new Field(f.getName(), field.get().getFieldType(), f.getChildren())
                          : new Field(f.getName(), f.getFieldType(), f.getChildren());
                    })
                .collect(Collectors.toList()));

    return new WindowPOP(
        creator.props(this, null, schema, RESERVE, LIMIT),
        childPOP,
        withins,
        aggs,
        orderings,
        window.isRows,
        getBound(window.lowerBound),
        getBound(window.upperBound));
  }

  protected LogicalExpression toExpr(AggregateCall call, List<String> fn) {
    ParseContext context = new ParseContext(PrelUtil.getSettings(getCluster()));

    List<LogicalExpression> args = Lists.newArrayList();
    for (Integer i : call.getArgList()) {
      final int indexInConstants = i - fn.size();
      if (i < fn.size()) {
        args.add(new FieldReference(fn.get(i)));
      } else {
        final RexLiteral constant = constants.get(indexInConstants);
        LogicalExpression expr =
            RexToExpr.toExpr(
                context, getInput().getRowType(), getCluster().getRexBuilder(), constant);
        args.add(expr);
      }
    }

    // for count(1).
    if (args.isEmpty()) {
      args.add(new ValueExpressions.LongExpression(1L));
    }

    return new FunctionCall(call.getAggregation().getName().toLowerCase(), args);
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitPrel(this, value);
  }

  @Override
  public BatchSchema.SelectionVectorMode[] getSupportedEncodings() {
    return BatchSchema.SelectionVectorMode.DEFAULT;
  }

  @Override
  public BatchSchema.SelectionVectorMode getEncoding() {
    return BatchSchema.SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return false;
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getInput());
  }

  /**
   * Derive rowType for the copied WindowPrel based on input. When copy() is called, the input might
   * be different from the current one's input. We have to use the new input's field in the copied
   * WindowPrel.
   */
  private RelDataType deriveCopiedRowTypeFromInput(final RelNode input) {
    final RelDataType inputRowType = input.getRowType();
    final RelDataType windowRowType = this.getRowType();

    final List<RelDataTypeField> fieldList = new ArrayList<>(inputRowType.getFieldList());
    final int inputFieldCount = inputRowType.getFieldCount();
    final int windowFieldCount = windowRowType.getFieldCount();

    for (int i = inputFieldCount; i < windowFieldCount; i++) {
      fieldList.add(windowRowType.getFieldList().get(i));
    }

    final RelDataType rowType =
        this.getCluster().getRexBuilder().getTypeFactory().createStructType(fieldList);

    return rowType;
  }

  /**
   * If bound is CURRENT_ROW, return 0. If bound is UNBOUNDED, return Integer.MAX_VALUE for
   * FOLLOWING or Integer.MIN_VALUE for PRECEDING. If bound is a constant, return the value of the
   * constant.
   */
  private int getOffsetForBound(RexWindowBound bound) {
    if (bound.isCurrentRow()) {
      return 0;
    }
    if (bound.isUnbounded()) {
      if (bound.isPreceding()) {
        return Integer.MIN_VALUE;
      } else {
        return Integer.MAX_VALUE;
      }
    }
    RexInputRef offset = (RexInputRef) bound.getOffset();
    if (offset != null && offset.getIndex() >= 0 && offset.getIndex() >= startConstantsIndex) {
      return this.constants.get(offset.getIndex() - startConstantsIndex).getValueAs(Integer.class);
    }
    return 0;
  }

  private WindowPOP.Bound getBound(RexWindowBound bound) {
    return WindowPOP.newBound(bound, getOffsetForBound(bound));
  }
}
