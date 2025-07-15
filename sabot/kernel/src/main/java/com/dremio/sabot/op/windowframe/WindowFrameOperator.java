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

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.logical.data.Order;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.compile.TemplateClassDefinition;
import com.dremio.exec.compile.sig.GeneratorMapping;
import com.dremio.exec.compile.sig.MappingSet;
import com.dremio.exec.exception.ClassTransformationException;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.expr.ClassGenerator;
import com.dremio.exec.expr.ClassProducer;
import com.dremio.exec.expr.fn.FunctionGenerationHelper;
import com.dremio.exec.physical.config.WindowPOP;
import com.dremio.exec.physical.config.WindowPOP.Bound;
import com.dremio.exec.physical.config.WindowPOP.BoundType;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.codemodel.JExpr;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VectorContainerHelper;
import org.apache.arrow.vector.util.TransferPair;

/**
 * support for OVER(PARTITION BY expression1,expression2,... [ORDER BY expressionA,
 * expressionB,...])
 */
public class WindowFrameOperator implements SingleInputOperator {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(WindowFrameOperator.class);

  private final OperatorContext context;
  private final List<WindowFunction> functions = Lists.newArrayList();
  private final WindowPOP config;
  private final LinkedList<VectorContainer> batches = new LinkedList<>();

  private VectorAccessible incoming;
  private VectorContainer outgoing;
  private ImmutableList<TransferPair> transfers;

  private State state = State.NEEDS_SETUP;
  private WindowFramer[] framers;
  private boolean noMoreToConsume;

  private int currentBatchIndex = 0;
  private ArrowBuf rowSizeAccumulator;
  private static final int INT_SIZE = 4;
  private int fixedDataLenPerRow;
  private CombinedSizer variableVectorSizer;
  private final boolean rowSizeLimitEnabled;
  private boolean rowSizeLimitEnabledForThisOperator;
  private final int rowSizeLimit;

  public WindowFrameOperator(OperatorContext context, WindowPOP config)
      throws OutOfMemoryException {
    this.context = context;
    this.config = config;
    this.rowSizeLimit =
        Math.toIntExact(this.context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
    this.rowSizeLimitEnabled =
        this.context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
    this.rowSizeLimitEnabledForThisOperator = rowSizeLimitEnabled;
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    state.is(State.NEEDS_SETUP);

    incoming = accessible;
    outgoing = context.createOutputVectorContainer();
    createFramers(incoming);
    outgoing.buildSchema();
    outgoing.setInitialCapacity(context.getTargetBatchSize());
    createNewRowLengthAccumulatorIfRequired(context.getTargetBatchSize());

    if (rowSizeLimitEnabled) {
      fixedDataLenPerRow = VectorContainerHelper.getFixedDataLenPerRow(outgoing);
      if (!isVarLenAggregatePresent()) {
        rowSizeLimitEnabledForThisOperator = false;
        if (fixedDataLenPerRow > rowSizeLimit) {
          throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
              rowSizeLimit, RowSizeLimitExceptionType.PROCESSING, logger);
        }
      } else {
        // do row size check only in case when EvalMode is Complex or Eval (for VarChar and
        // VarBinary
        // output type)
        this.variableVectorSizer = VectorContainerHelper.createSizer(outgoing, false);
        createNewRowLengthAccumulatorIfRequired(context.getTargetBatchSize());
      }
    }
    state = State.CAN_CONSUME;
    return outgoing;
  }

  private boolean isVarLenAggregatePresent() {
    for (final NamedExpression ne : config.getAggregations()) {
      final TypedFieldId outputId = outgoing.getValueVectorId(ne.getRef());
      if (!(outputId.getFinalType().isFixedWidthType())) {
        return true;
      }
    }
    return false;
  }

  private void createNewRowLengthAccumulatorIfRequired(int batchSize) {
    if (rowSizeAccumulator != null) {
      if (rowSizeAccumulator.capacity() < (long) batchSize * INT_SIZE) {
        rowSizeAccumulator.close();
        rowSizeAccumulator = null;
      } else {
        return;
      }
    }
    rowSizeAccumulator = context.getAllocator().buffer((long) batchSize * INT_SIZE);
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public void consumeData(int records) throws Exception {
    state.is(State.CAN_CONSUME);
    batches.add(VectorContainer.getTransferClone(incoming, context.getAllocator()));
    if (canDoWork()) {
      state = State.CAN_PRODUCE;
    }
  }

  @Override
  public void noMoreToConsume() throws Exception {
    state.is(State.CAN_CONSUME);
    noMoreToConsume = true;
    if (!batches.isEmpty()) {
      state = State.CAN_PRODUCE;
    } else {
      state = State.DONE;
    }
  }

  @Override
  public int outputData() throws Exception {
    state.is(State.CAN_PRODUCE);
    doWork();

    if (batches.isEmpty()) {
      state = State.DONE;
    } else if (!noMoreToConsume && !canDoWork()) {
      state = State.CAN_CONSUME;
    }
    int outCount = outgoing.getRecordCount();
    outgoing.setAllCount(outCount);
    checkForRowSizeOverLimit(outCount);
    return outCount;
  }

  private void checkForRowSizeOverLimit(int recordCount) {
    if (!rowSizeLimitEnabledForThisOperator) {
      return;
    }
    createNewRowLengthAccumulatorIfRequired(recordCount);
    VectorContainerHelper.checkForRowSizeOverLimit(
        outgoing,
        recordCount,
        rowSizeLimit - fixedDataLenPerRow,
        rowSizeLimit,
        rowSizeAccumulator,
        variableVectorSizer,
        RowSizeLimitExceptionType.PROCESSING,
        logger);
  }

  private int doWork() throws Exception {
    final VectorContainer current = batches.get(currentBatchIndex);
    final int recordCount = current.getRecordCount();

    logger.trace(
        "WindowFramer.doWork() START, num batches {}, current batch has {} rows",
        batches.size(),
        recordCount);

    // allocate outgoing vectors
    outgoing.allocateNew();

    for (WindowFramer framer : framers) {
      framer.doWork(currentBatchIndex);
    }

    Bound lowerBound = config.getLowerBound();
    Bound upperBound = config.getUpperBound();
    boolean isLagFunction =
        config.getAggregations().stream()
            .map(
                e ->
                    e.getExpr() instanceof FunctionCall
                        ? ((FunctionCall) e.getExpr()).getName()
                        : null)
            .anyMatch("lag"::equals);

    // if lower bound is PRECEDING it's possible that partition could be located in several batches.
    if ((lowerBound.getType().equals(WindowPOP.BoundType.PRECEDING)
            && !upperBound.getType().equals(BoundType.CURRENT_ROW))
        || isLagFunction) {
      // if last row from previous batch not in same partition with last row in current, we can
      // close it
      transferVectors(current, recordCount, tp -> tp.splitAndTransfer(0, recordCount));
      if (isPartitionEndReachedInPrevBatch(current, recordCount)) {
        closeUnneededBatches();
      } else {
        currentBatchIndex++;
      }
      // if frame is RANGE and upper bound is FOLLOWING it's possible that partition could be
      // located in several batches.
    } else if (!config.isFrameUnitsRows() && upperBound.getType().equals(BoundType.FOLLOWING)) {
      // check if we need to close previous batches
      transferVectors(current, recordCount, tp -> tp.splitAndTransfer(0, recordCount));
      if (isFrameEndReachedInPrevBatch(current, recordCount)) {
        closeUnneededBatches();
      } else {
        currentBatchIndex++;
      }
    } else {
      transferVectors(current, recordCount, TransferPair::transfer);
      current.close();
      batches.remove(currentBatchIndex);
      currentBatchIndex = 0;
    }

    logger.trace("doWork() END");
    return recordCount;
  }

  private void transferVectors(
      VectorContainer current, int recordCount, Consumer<TransferPair> transferFunction) {
    for (VectorWrapper<?> vw : current) {
      ValueVector v = outgoing.addOrGet(vw.getField());
      TransferPair tp = vw.getValueVector().makeTransferPair(v);
      /* we shouldn't clear batch for case when start bound is PRECEDING
       because for case partition located in several batches,
      it's possible that we will need to get access to the previous batch */
      transferFunction.accept(tp);
    }

    if (recordCount > 0) {
      try {
        outgoing.setAllCount(recordCount);
      } catch (RuntimeException ex) {
        throw ex;
      }
    }
  }

  private void closeUnneededBatches() {
    boolean isLastBatch = isLastBatch();
    // if current batch is the last one - close all batches, otherwise close all batches before
    // current
    // if current batch is the last one - close all batches, otherwise close all batches before
    // current
    Iterator<VectorContainer> iterator = batches.iterator();
    int limit = isLastBatch ? currentBatchIndex : currentBatchIndex - 1;
    int index = 0;
    while (iterator.hasNext() && index <= limit) {
      VectorContainer current = iterator.next();
      current.close();
      iterator.remove();
      currentBatchIndex--;
      index++;
    }
    if (!isLastBatch) {
      // if current batch is not the last one, we need to increment currentBatchIndex to point to
      // the next batch
      currentBatchIndex++;
    }
  }

  private boolean isLastBatch() {
    return currentBatchIndex == batches.size() - 1;
  }

  private boolean isPartitionEndReachedInPrevBatch(
      VectorAccessible current, int currentRecordCount) {
    // if it's the first batch, we can't compare it with the previous one
    if (currentBatchIndex == 0) {
      return isLastBatch();
    }
    // if it's the last batch, we can close all batches
    if (noMoreToConsume && isLastBatch()) {
      return true;
    }
    final VectorAccessible previous = batches.get(currentBatchIndex - 1);
    final int prevRecordCount = previous.getRecordCount();
    return !framers[0].isSamePartition(
        currentRecordCount - 1, current, prevRecordCount - 1, previous);
  }

  private boolean isFrameEndReachedInPrevBatch(VectorAccessible current, int currentRecordCount) {
    // if it's the first batch, we can't compare it with the previous one
    if (currentBatchIndex == 0) {
      return isLastBatch();
    }
    // if it's the last batch, we can close all batches
    if (noMoreToConsume && isLastBatch()) {
      return true;
    }
    final VectorAccessible previous = batches.get(currentBatchIndex - 1);
    final int prevRecordCount = previous.getRecordCount();
    boolean partitionEndReached =
        !framers[0].isSamePartition(currentRecordCount - 1, current, prevRecordCount - 1, previous);
    return partitionEndReached
        || !framers[0].isPeer(currentRecordCount - 1, current, prevRecordCount - 1, previous);
  }

  /**
   * @return true when all window functions are ready to process the current batch (it's the first
   *     batch currently held in memory)
   */
  private boolean canDoWork() {
    if (batches.size() < 2) {
      // we need at least 2 batches even when window functions only need one batch, so we can detect
      // the end of the
      // current partition
      return false;
    }

    final VectorAccessible current = batches.get(currentBatchIndex);
    final int currentSize = current.getRecordCount();
    final VectorAccessible last = batches.getLast();
    final int lastSize = last.getRecordCount();

    final boolean partitionEndReached =
        !framers[0].isSamePartition(currentSize - 1, current, lastSize - 1, last);
    final boolean frameEndReached =
        partitionEndReached || !framers[0].isPeer(currentSize - 1, current, lastSize - 1, last);

    for (final WindowFunction function : functions) {
      if (!function.canDoWork(batches.size(), config, frameEndReached, partitionEndReached)) {
        return false;
      }
    }

    return true;
  }

  private void createFramers(VectorAccessible batch)
      throws SchemaChangeException, IOException, ClassTransformationException {
    assert framers == null : "createFramer should only be called once";

    logger.trace("creating framer(s)");

    final List<LogicalExpression> keyExprs = Lists.newArrayList();
    final List<LogicalExpression> orderExprs = Lists.newArrayList();
    boolean requireFullPartition = false;

    boolean useDefaultFrame = false; // at least one window function uses the DefaultFrameTemplate
    boolean useCustomFrame = false; // at least one window function uses the CustomFrameTemplate

    // all existing vectors will be transferred to the outgoing container in framer.doWork()

    List<TransferPair> transfers = new ArrayList<>();
    for (final VectorWrapper<?> wrapper : batch) {
      ValueVector valueVector = wrapper.getValueVector();
      TransferPair pair =
          valueVector.getTransferPair(valueVector.getField(), context.getAllocator());
      outgoing.add(pair.getTo());
      transfers.add(pair);
    }
    this.transfers = ImmutableList.copyOf(transfers);

    final ClassProducer producer = context.getClassProducer();
    // add aggregation vectors to the container, and materialize corresponding expressions
    for (final NamedExpression ne : config.getAggregations()) {
      final WindowFunction winfun = WindowFunction.fromExpression(ne);

      // build the schema before each pass since we're going to use the outbound schema for value
      // resolution.
      outgoing.buildSchema();

      if (winfun.materialize(ne, outgoing, producer)) {
        functions.add(winfun);
        requireFullPartition |= winfun.requiresFullPartition(config);

        if (winfun.supportsCustomFrames()) {
          useCustomFrame = true;
        } else {
          useDefaultFrame = true;
        }
      }
    }

    outgoing.buildSchema();

    // materialize partition by expressions
    for (final NamedExpression ne : config.getWithins()) {
      keyExprs.add(producer.materialize(ne.getExpr(), batch));
    }

    // materialize order by expressions
    for (final Order.Ordering oe : config.getOrderings()) {
      orderExprs.add(producer.materialize(oe.getExpr(), batch));
    }

    // count how many framers we need
    int numFramers = useDefaultFrame ? 1 : 0;
    numFramers += useCustomFrame ? 1 : 0;
    assert numFramers > 0 : "No framer was needed!";

    framers = new WindowFramer[numFramers];
    int index = 0;
    if (useDefaultFrame) {
      framers[index] = generateFramer(keyExprs, orderExprs, functions, false);
      framers[index].setup(
          batches, outgoing, context, requireFullPartition, config, context.getFunctionContext());
      index++;
    }

    if (useCustomFrame) {
      framers[index] = generateFramer(keyExprs, orderExprs, functions, true);
      framers[index].setup(
          batches, outgoing, context, requireFullPartition, config, context.getFunctionContext());
    }
  }

  private WindowFramer generateFramer(
      final List<LogicalExpression> keyExprs,
      final List<LogicalExpression> orderExprs,
      final List<WindowFunction> functions,
      boolean useCustomFrame)
      throws IOException, ClassTransformationException {

    TemplateClassDefinition<WindowFramer> definition =
        useCustomFrame
            ? WindowFramer.FRAME_TEMPLATE_DEFINITION
            : WindowFramer.NOFRAME_TEMPLATE_DEFINITION;
    final ClassGenerator<WindowFramer> cg =
        context.getClassProducer().createGenerator(definition).getRoot();

    {
      // generating framer.isSamePartition()
      @SuppressWarnings("checkstyle:LocalFinalVariableName")
      final GeneratorMapping IS_SAME_PARTITION_READ =
          GeneratorMapping.create("isSamePartition", "isSamePartition", null, null);
      final MappingSet isaB1 =
          new MappingSet(
              "b1Index", null, "b1", null, IS_SAME_PARTITION_READ, IS_SAME_PARTITION_READ);
      final MappingSet isaB2 =
          new MappingSet(
              "b2Index", null, "b2", null, IS_SAME_PARTITION_READ, IS_SAME_PARTITION_READ);
      setupIsFunction(cg, keyExprs, isaB1, isaB2);
    }

    {
      // generating framer.isPeer()
      @SuppressWarnings("checkstyle:LocalFinalVariableName")
      final GeneratorMapping IS_SAME_PEER_READ =
          GeneratorMapping.create("isPeer", "isPeer", null, null);
      final MappingSet isaP1 =
          new MappingSet("b1Index", null, "b1", null, IS_SAME_PEER_READ, IS_SAME_PEER_READ);
      final MappingSet isaP2 =
          new MappingSet("b2Index", null, "b2", null, IS_SAME_PEER_READ, IS_SAME_PEER_READ);
      // isPeer also checks if it's the same partition
      setupIsFunction(cg, Iterables.concat(keyExprs, orderExprs), isaP1, isaP2);
    }

    for (final WindowFunction function : functions) {
      // only generate code for the proper window functions
      if (function.supportsCustomFrames() == useCustomFrame) {
        function.generateCode(cg);
      }
    }

    cg.getBlock("resetValues")._return(JExpr.TRUE);

    return cg.getCodeGenerator().getImplementationClass();
  }

  /** setup comparison functions isSamePartition and isPeer */
  private void setupIsFunction(
      final ClassGenerator<WindowFramer> cg,
      final Iterable<LogicalExpression> exprs,
      final MappingSet leftMapping,
      final MappingSet rightMapping) {
    cg.setMappingSet(leftMapping);
    for (LogicalExpression expr : exprs) {
      if (expr == null) {
        continue;
      }

      cg.setMappingSet(leftMapping);
      ClassGenerator.HoldingContainer first =
          cg.addExpr(expr, ClassGenerator.BlockCreateMode.MERGE);
      cg.setMappingSet(rightMapping);
      ClassGenerator.HoldingContainer second =
          cg.addExpr(expr, ClassGenerator.BlockCreateMode.MERGE);

      final LogicalExpression fh =
          FunctionGenerationHelper.getOrderingComparatorNullsHigh(
              first, second, context.getClassProducer());
      final ClassGenerator.HoldingContainer out =
          cg.addExpr(fh, ClassGenerator.BlockCreateMode.MERGE);
      cg.getEvalBlock()._if(out.getValue().ne(JExpr.lit(0)))._then()._return(JExpr.FALSE);
    }
    cg.getEvalBlock()._return(JExpr.TRUE);
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitSingleInput(this, value);
  }

  @Override
  public void close() throws Exception {
    List<AutoCloseable> closeables = new ArrayList<>();
    closeables.add(outgoing);
    if (framers != null) {
      closeables.addAll(Arrays.asList(framers));
    }
    closeables.addAll(batches);
    AutoCloseables.close(closeables);
    if (rowSizeAccumulator != null) {
      rowSizeAccumulator.close();
      rowSizeAccumulator = null;
    }
  }

  public static class Creator implements SingleInputOperator.Creator<WindowPOP> {

    @Override
    public SingleInputOperator create(OperatorContext context, WindowPOP operator)
        throws ExecutionSetupException {
      return new WindowFrameOperator(context, operator);
    }
  }
}
