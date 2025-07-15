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
package com.dremio.sabot.op.windowframe;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.ErrorCollector;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.ValueExpressions;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.exec.compile.sig.GeneratorMapping;
import com.dremio.exec.compile.sig.MappingSet;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.expr.ClassGenerator;
import com.dremio.exec.expr.ClassGenerator.BlockCreateMode;
import com.dremio.exec.expr.ClassProducer;
import com.dremio.exec.expr.ExpressionTreeMaterializer;
import com.dremio.exec.expr.ValueVectorReadExpression;
import com.dremio.exec.expr.ValueVectorWriteExpression;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.physical.config.WindowPOP;
import com.dremio.exec.physical.config.WindowPOP.BoundType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorContainer;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JConditional;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JVar;
import org.apache.arrow.vector.types.pojo.Field;

public abstract class WindowFunction {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(WindowFunction.class);

  public enum Type {
    ROW_NUMBER,
    RANK,
    DENSE_RANK,
    PERCENT_RANK,
    CUME_DIST,
    LEAD,
    LAG,
    FIRST_VALUE,
    LAST_VALUE,
    NTILE,
    AGGREGATE
  }

  final Type type;

  WindowFunction(Type type) {
    this.type = type;
  }

  public static WindowFunction fromExpression(final NamedExpression expr) {
    if (!(expr.getExpr() instanceof FunctionCall)) {
      throw UserException.functionError()
          .message("Unsupported window function '%s'", expr.getExpr())
          .build(logger);
    }

    final FunctionCall call = (FunctionCall) expr.getExpr();
    final String name = call.getName();
    Type type;
    try {
      type = Type.valueOf(name.toUpperCase());
    } catch (IllegalArgumentException e) {
      type = Type.AGGREGATE;
    }

    switch (type) {
      case AGGREGATE:
        return new WindowAggregate();
      case LEAD:
        return new Lead();
      case LAG:
        return new Lag();
      case FIRST_VALUE:
        return new FirstValue();
      case LAST_VALUE:
        return new LastValue();
      case NTILE:
        return new Ntile();
      default:
        return new Ranking(type);
    }
  }

  abstract void generateCode(final ClassGenerator<WindowFramer> cg);

  abstract boolean supportsCustomFrames();

  /**
   * @param pop window group definition
   * @return true if this window function requires all batches of current partition to be available
   *     before processing the first batch
   */
  public boolean requiresFullPartition(final WindowPOP pop) {
    return true;
  }

  /**
   * @param numBatchesAvailable number of batches available for current partition
   * @param pop window group definition
   * @param frameEndReached we found the last row of the first batch's frame
   * @param partitionEndReached all batches of current partition are available
   * @return true if this window function can process the first batch immediately
   */
  public boolean canDoWork(
      final int numBatchesAvailable,
      final WindowPOP pop,
      final boolean frameEndReached,
      final boolean partitionEndReached) {
    return partitionEndReached;
  }

  abstract boolean materialize(
      final NamedExpression ne, final VectorContainer batch, ClassProducer producer);

  public abstract Field materialize(
      final NamedExpression ne,
      final BatchSchema schema,
      ErrorCollector collector,
      FunctionLookupContext context);

  static class WindowAggregate extends WindowFunction {

    private ValueVectorWriteExpression writeAggregationToOutput;

    WindowAggregate() {
      super(Type.AGGREGATE);
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer) {
      final LogicalExpression aggregate = producer.materialize(ne.getExpr(), batch);
      if (aggregate == null) {
        return false;
      }

      // add corresponding ValueVector to container
      final Field output = aggregate.getCompleteType().toField(ne.getRef());
      batch.addOrGet(output);
      TypedFieldId outputId = batch.getValueVectorId(ne.getRef());
      writeAggregationToOutput = new ValueVectorWriteExpression(outputId, aggregate, true);

      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      final LogicalExpression aggregate =
          ExpressionTreeMaterializer.materialize(ne.getExpr(), schema, collector, context);
      if (aggregate == null) {
        return null;
      }

      return aggregate.getCompleteType().toField(ne.getRef());
    }

    @SuppressWarnings("checkstyle:LocalFinalVariableName")
    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      final GeneratorMapping EVAL_INSIDE =
          GeneratorMapping.create("setupEvaluatePeer", "evaluatePeer", null, null);
      final GeneratorMapping EVAL_OUTSIDE =
          GeneratorMapping.create("setupPartition", "outputRow", "resetValues", "cleanup");
      final MappingSet mappingSet =
          new MappingSet("index", "outIndex", EVAL_INSIDE, EVAL_OUTSIDE, EVAL_INSIDE);

      cg.setMappingSet(mappingSet);
      cg.addExpr(writeAggregationToOutput);
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      return pop.getOrderings().isEmpty()
          || pop.getUpperBound().isUnbounded()
          || (pop.isFrameUnitsRows() && pop.getUpperBound().getType().equals(BoundType.FOLLOWING));
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      return partitionEndReached || (!requiresFullPartition(pop) && frameEndReached);
    }

    @Override
    boolean supportsCustomFrames() {
      return true;
    }
  }

  static class Ranking extends WindowFunction {

    protected TypedFieldId fieldId;

    Ranking(final Type type) {
      super(type);
    }

    private MajorType getMajorType() {
      if (type == Type.CUME_DIST || type == Type.PERCENT_RANK) {
        return Types.required(MinorType.FLOAT8);
      }
      return Types.required(MinorType.BIGINT);
    }

    private String getName() {
      return type.name().toLowerCase();
    }

    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      final GeneratorMapping mapping =
          GeneratorMapping.create("setupPartition", "outputRow", "resetValues", "cleanup");
      final MappingSet mappingSet = new MappingSet(null, "outIndex", mapping, mapping);

      cg.setMappingSet(mappingSet);
      final JVar vv =
          cg.declareVectorValueSetupAndMember(cg.getMappingSet().getOutgoing(), fieldId);
      final JExpression outIndex = cg.getMappingSet().getValueWriteIndex();
      JInvocation setMethod =
          vv.invoke("setSafe").arg(outIndex).arg(JExpr.direct("partition." + getName()));

      cg.getEvalBlock().add(setMethod);
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, ClassProducer producer)
        throws SchemaChangeException {
      final Field outputField =
          MajorTypeHelper.getFieldForNameAndMajorType(
              ne.getRef().getAsNamePart().getName(), getMajorType());
      batch.addOrGet(outputField).allocateNew();
      fieldId = batch.getValueVectorId(ne.getRef());
      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      return MajorTypeHelper.getFieldForNameAndMajorType(
          ne.getRef().getAsNamePart().getName(), getMajorType());
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      // CUME_DIST, PERCENT_RANK and NTILE require the length of current partition before processing
      // it's first batch
      return type == Type.CUME_DIST || type == Type.PERCENT_RANK || type == Type.NTILE;
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        final WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      assert numBatchesAvailable > 0
          : "canDoWork() should not be called when numBatchesAvailable == 0";
      if (type == Type.ROW_NUMBER) {
        // row_number doesn't need to wait for anything
        return true;
      }
      if (type == Type.RANK) {
        // rank only works if we know how many rows we have in the current frame
        // we could avoid this, but it requires more refactoring
        return frameEndReached;
      }

      // for CUME_DIST, PERCENT_RANK and NTILE we need the full partition
      return partitionEndReached;
    }

    @Override
    boolean supportsCustomFrames() {
      return false;
    }
  }

  static class Ntile extends Ranking {

    private int numTiles;

    public Ntile() {
      super(Type.NTILE);
    }

    private int numTilesFromExpression(LogicalExpression numTilesExpr) {
      if ((numTilesExpr instanceof ValueExpressions.IntExpression)) {
        int nt = ((ValueExpressions.IntExpression) numTilesExpr).getInt();
        if (nt > 0) {
          return nt;
        }
      }

      throw UserException.functionError()
          .message("NTILE only accepts positive integer argument")
          .build(logger);
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer)
        throws SchemaChangeException {
      if (!super.materialize(ne, batch, producer)) {
        return false;
      }

      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression argument = call.args.get(0);
      numTiles = numTilesFromExpression(argument);
      return true;
    }

    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      final GeneratorMapping mapping =
          GeneratorMapping.create("setupPartition", "outputRow", "resetValues", "cleanup");
      final MappingSet mappingSet = new MappingSet(null, "outIndex", mapping, mapping);

      cg.setMappingSet(mappingSet);
      final JVar vv =
          cg.declareVectorValueSetupAndMember(cg.getMappingSet().getOutgoing(), fieldId);
      final JExpression outIndex = cg.getMappingSet().getValueWriteIndex();
      JInvocation setMethod =
          vv.invoke("setSafe").arg(outIndex).arg(JExpr.direct("partition.ntile(" + numTiles + ")"));
      cg.getEvalBlock().add(setMethod);
    }
  }

  static class Lead extends WindowFunction {
    private LogicalExpression writeInputToLead;
    private int offset;

    public Lead() {
      super(Type.LEAD);
    }

    private int offsetExpression(FunctionCall call) {
      if ((call.args.size() < 2)) {
        return 1;
      }
      LogicalExpression offsetExpr = call.args.get(1);
      return ((ValueExpressions.IntExpression) offsetExpr).getInt();
    }

    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      {
        final GeneratorMapping mapping =
            GeneratorMapping.create("setupCopyNext", "copyNext", null, null);
        final MappingSet eval = new MappingSet("inIndex", "outIndex", mapping, mapping);

        cg.setMappingSet(eval);
        final JExpression remaining = JExpr.direct("partition.getRemaining()");
        /*
          if remaining rows >= offset that means - value for row will be in same batch
        */
        JConditional ifCondition = cg.getEvalBlock()._if(remaining.gte(JExpr.lit(offset)));
        ifCondition
            ._then()
            .block()
            .directStatement("inIndex = partition.getCurrentRowInPartition() + " + offset + ";");
        cg.nestEvalBlock(ifCondition._then());
        cg.addExpr(writeInputToLead, BlockCreateMode.MERGE);
        cg.unNestEvalBlock();
      }

      {
        final GeneratorMapping mapping =
            GeneratorMapping.create("setupCopyFromNextBatch", "copyFromNextBatch", null, null);
        final MappingSet copyFromFirstMapping =
            new MappingSet("inIndex", "outIndex", mapping, mapping);

        cg.setMappingSet(copyFromFirstMapping);
        final JExpression remaining = JExpr.direct("partition.getRemaining()");
        /*
          if remaining rows < offset that means - value for row will be in next batch batch
        */
        final JExpression indexCondition =
            JExpr.direct("inIndex")
                .gte(JExpr.lit(0))
                .cand(JExpr.direct("inIndex").lt(JExpr.direct("b2.getRecordCount()")));
        final JExpression samePartition =
            JExpr.direct(
                "isSamePartition(inIndex, incoming, partition.getCurrentRowInPartition(), b2)");
        cg.getEvalBlock()
            .directStatement(
                "inIndex = partition.leadIndex (" + offset + ", b2.getRecordCount());");

        JConditional ifCondition =
            cg.getEvalBlock()
                ._if(remaining.lt(JExpr.lit(offset)).cand(indexCondition).cand(samePartition));
        cg.nestEvalBlock(ifCondition._then());
        cg.addExpr(writeInputToLead, BlockCreateMode.MERGE);
        cg.unNestEvalBlock();
      }
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer)
        throws SchemaChangeException {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input = producer.materialize(call.args.get(0), batch);
      if (input == null) {
        return false;
      }
      offset = offsetExpression(call);

      // add corresponding ValueVector to container
      final Field output = input.getCompleteType().toField(ne.getRef());
      batch.addOrGet(output).allocateNew();
      final TypedFieldId outputId = batch.getValueVectorId(ne.getRef());
      writeInputToLead = new ValueVectorWriteExpression(outputId, input, true);
      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input =
          ExpressionTreeMaterializer.materialize(call.args.get(0), schema, collector, context);
      if (input == null) {
        return null;
      }

      // add corresponding ValueVector to container
      return input.getCompleteType().toField(ne.getRef());
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      return false;
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        final WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      return partitionEndReached;
    }

    @Override
    boolean supportsCustomFrames() {
      return false;
    }
  }

  static class Lag extends WindowFunction {
    private LogicalExpression writeInputToLag;
    private int offset;

    Lag() {
      super(Type.LAG);
    }

    private int offsetExpression(FunctionCall call) {
      if ((call.args.size() < 2)) {
        return 1;
      }
      LogicalExpression offsetExpr = call.args.get(1);
      return ((ValueExpressions.IntExpression) offsetExpr).getInt();
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input = producer.materialize(call.args.get(0), batch);
      if (input == null) {
        return false;
      }

      // add lag output ValueVector to container
      final Field output = input.getCompleteType().toField(ne.getRef());
      batch.addOrGet(output);
      final TypedFieldId outputId = batch.getValueVectorId(ne.getRef());

      writeInputToLag = new ValueVectorWriteExpression(outputId, input, true);
      offset = offsetExpression(call);
      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input =
          ExpressionTreeMaterializer.materialize(call.args.get(0), schema, collector, context);
      if (input == null) {
        return null;
      }

      return input.getCompleteType().toField(ne.getRef());
    }

    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      {
        final GeneratorMapping mapping =
            GeneratorMapping.create("setupCopyFromPastBatch", "copyFromPastBatch", null, null);
        final MappingSet mappingSet = new MappingSet("inIndex", "outIndex", mapping, mapping);

        cg.setMappingSet(mappingSet);
        final JExpression currentIndex = JExpr.direct("partition.getCurrentRowInPartition()");
        final JExpression partitionStart = JExpr.direct("partition.getFirstRowInPartition()");
        final JExpression indexCondition =
            JExpr.direct("inIndex")
                .gte(JExpr.lit(0))
                .cand(JExpr.direct("inIndex").lt(JExpr.direct("incoming.getRecordCount()")));
        final JExpression samePartition =
            JExpr.direct(
                "isSamePartition(inIndex, incoming, partition.getCurrentRowInPartition(), b2)");
        //  (currentIndex - offset) > start of partition, copy value (if exist) from internal holder
        cg.getEvalBlock()
            .directStatement(
                "inIndex = partition.lagIndex (" + offset + ", incoming.getRecordCount());");
        JConditional ifCondition =
            cg.getEvalBlock()
                ._if(
                    currentIndex
                        .minus(JExpr.lit(offset))
                        .lt(partitionStart)
                        .cand(indexCondition)
                        .cand(samePartition));
        cg.nestEvalBlock(ifCondition._then());
        cg.addExpr(writeInputToLag, BlockCreateMode.MERGE);
        cg.unNestEvalBlock();
      }

      {
        // generating lag copyPrev
        final GeneratorMapping mapping =
            GeneratorMapping.create("setupCopyPrev", "copyPrev", null, null);
        final MappingSet eval = new MappingSet("inIndex", "outIndex", mapping, mapping);

        cg.setMappingSet(eval);
        final JExpression currentIndex = JExpr.direct("partition.getCurrentRowInPartition()");
        final JExpression partitionStart = JExpr.direct("partition.getFirstRowInPartition()");
        // previous row position (currentIndex - offset) must be in the current partition
        JConditional ifCondition =
            cg.getEvalBlock()._if(currentIndex.minus(JExpr.lit(offset)).gte(partitionStart));
        JBlock jbThen = ifCondition._then().block();
        jbThen.directStatement("inIndex = partition.getCurrentRowInPartition() - " + offset + ";");
        cg.nestEvalBlock(ifCondition._then());
        cg.addExpr(writeInputToLag, BlockCreateMode.MERGE);
        cg.unNestEvalBlock();
      }
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      return false;
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        final WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      assert numBatchesAvailable > 0
          : "canDoWork() should not be called when numBatchesAvailable == 0";
      return partitionEndReached;
    }

    @Override
    boolean supportsCustomFrames() {
      return false;
    }
  }

  static class LastValue extends WindowFunction {

    private LogicalExpression writeSourceToLastValue;

    LastValue() {
      super(Type.LAST_VALUE);
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input = producer.materialize(call.args.get(0), batch);
      if (input == null) {
        return false;
      }

      final Field output = input.getCompleteType().toField(ne.getRef());
      batch.addOrGet(output);
      final TypedFieldId outputId = batch.getValueVectorId(ne.getRef());

      // write incoming.source[inIndex] to outgoing.last_value[outIndex]
      writeSourceToLastValue = new ValueVectorWriteExpression(outputId, input, true);
      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input =
          ExpressionTreeMaterializer.materialize(call.args.get(0), schema, collector, context);
      if (input == null) {
        return null;
      }

      return input.getCompleteType().toField(ne.getRef());
    }

    @Override
    void generateCode(ClassGenerator<WindowFramer> cg) {
      // in DefaultFrameTemplate we call setupReadLastValue:
      //   setupReadLastValue(current, container)
      // and readLastValue:
      //   writeLastValue(frameLastRow, row)
      //
      // this will generate the the following, pseudo, code:
      //   write current.source_last_value[frameLastRow] to container.last_value[row]

      final GeneratorMapping mapping =
          GeneratorMapping.create("setupReadLastValue", "writeLastValue", "resetValues", "cleanup");
      final MappingSet mappingSet = new MappingSet("index", "outIndex", mapping, mapping);

      cg.setMappingSet(mappingSet);
      cg.addExpr(writeSourceToLastValue);
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      return pop.getOrderings().isEmpty()
          || pop.getUpperBound().isUnbounded()
          || (pop.isFrameUnitsRows() && pop.getUpperBound().getType().equals(BoundType.FOLLOWING));
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      return partitionEndReached || (!requiresFullPartition(pop) && frameEndReached);
    }

    @Override
    boolean supportsCustomFrames() {
      return true;
    }
  }

  static class FirstValue extends WindowFunction {

    private LogicalExpression writeInputToFirstValue;

    private LogicalExpression writeFirstValueToFirstValue;

    FirstValue() {
      super(Type.FIRST_VALUE);
    }

    @Override
    boolean materialize(
        final NamedExpression ne, final VectorContainer batch, final ClassProducer producer) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input = producer.materialize(call.args.get(0), batch);
      if (input == null) {
        return false;
      }

      final Field output = input.getCompleteType().toField(ne.getRef());
      batch.addOrGet(output);
      final TypedFieldId outputId = batch.getValueVectorId(ne.getRef());

      // write incoming.first_value[inIndex] to outgoing.first_value[outIndex]
      writeFirstValueToFirstValue =
          new ValueVectorWriteExpression(outputId, new ValueVectorReadExpression(outputId), true);
      // write incoming.source[inIndex] to outgoing.first_value[outIndex]
      writeInputToFirstValue = new ValueVectorWriteExpression(outputId, input, true);
      return true;
    }

    @Override
    public Field materialize(
        final NamedExpression ne,
        final BatchSchema schema,
        ErrorCollector collector,
        FunctionLookupContext context) {
      final FunctionCall call = (FunctionCall) ne.getExpr();
      final LogicalExpression input =
          ExpressionTreeMaterializer.materialize(call.args.get(0), schema, collector, context);
      if (input == null) {
        return null;
      }

      return input.getCompleteType().toField(ne.getRef());
    }

    @Override
    void generateCode(final ClassGenerator<WindowFramer> cg) {
      {
        // in DefaultFrameTemplate we call setupSaveFirstValue:
        //   setupSaveFirstValue(current, internal)
        // and saveFirstValue:
        //   saveFirstValue(currentRow, 0)
        //
        // this will generate the the following, pseudo, code:
        //   write current.source[currentRow] to internal.first_value[0]
        //
        // so it basically copies the first value of current partition into the first row of
        // internal.first_value
        // this is especially useful when handling multiple batches for the same partition where we
        // need to keep
        // the first value of the partition somewhere after we release the first batch
        final GeneratorMapping mapping =
            GeneratorMapping.create("setupSaveFirstValue", "saveFirstValue", null, null);
        final MappingSet mappingSet = new MappingSet("index", "outIndex", mapping, mapping);

        cg.setMappingSet(mappingSet);
        cg.addExpr(writeInputToFirstValue);
      }

      {
        // in DefaultFrameTemplate we call setupWriteFirstValue:
        //   setupWriteFirstValue(internal, container)
        // and outputRow:
        //   outputRow(outIndex)
        //
        // this will generate the the following, pseudo, code:
        //   write internal.first_value[0] to container.first_value[outIndex]
        //
        // so it basically copies the value stored in internal.first_value's first row into all rows
        // of container.first_value
        final GeneratorMapping mapping =
            GeneratorMapping.create(
                "setupWriteFirstValue", "writeFirstValue", "resetValues", "cleanup");
        final MappingSet mappingSet = new MappingSet("index", "outIndex", mapping, mapping);
        cg.setMappingSet(mappingSet);
        cg.addExpr(writeFirstValueToFirstValue);
      }
    }

    @Override
    public boolean requiresFullPartition(final WindowPOP pop) {
      return pop.getUpperBound().isUnbounded()
          || (pop.isFrameUnitsRows() && pop.getUpperBound().getType().equals(BoundType.FOLLOWING));
    }

    @Override
    public boolean canDoWork(
        int numBatchesAvailable,
        WindowPOP pop,
        boolean frameEndReached,
        boolean partitionEndReached) {
      assert numBatchesAvailable > 0
          : "canDoWork() should not be called when numBatchesAvailable == 0";
      return partitionEndReached || (!requiresFullPartition(pop) && frameEndReached);
    }

    @Override
    boolean supportsCustomFrames() {
      return true;
    }
  }
}
