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
package com.dremio.exec.store.iceberg.deletes;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.arrow.vector.SimpleIntVector;

/**
 * A filter which converts positional deletes provided by a PositionalDeleteIterator to a delta
 * vector that encodes the rows to be skipped.
 */
public class PositionalDeleteFilter implements AutoCloseable {

  private final Supplier<PositionalDeleteIterator> iteratorSupplier;
  private final OperatorStats operatorStats;
  private PositionalDeleteIterator iterator;
  private long currentRowPos;
  private long nextDeletePos;
  private int refCount;
  private DiagnosticState diagnosticState;

  public PositionalDeleteFilter(
      Supplier<PositionalDeleteIterator> iteratorSupplier,
      int initialRefCount,
      OperatorStats operatorStats) {
    this.iteratorSupplier = Preconditions.checkNotNull(iteratorSupplier);
    this.currentRowPos = 0;
    this.nextDeletePos = -1;
    this.refCount = initialRefCount;
    this.operatorStats = operatorStats;
    this.diagnosticState = new DiagnosticState();
  }

  public void retain() {
    retain(1);
  }

  public void retain(int count) {
    refCount += count;
  }

  public void release(int count) {
    Preconditions.checkState(refCount >= count);
    refCount -= count;
    if (refCount == 0) {
      AutoCloseables.close(RuntimeException.class, this);
    }
  }

  public void release() {
    release(1);
  }

  public int refCount() {
    return refCount;
  }

  public void seek(long rowPos) {
    Preconditions.checkArgument(
        rowPos >= currentRowPos, "Positional delete filtering is forward-only.");
    Preconditions.checkState(refCount > 0, "PositionalDeleteFilter has already been released.");
    diagnosticState.reset(rowPos);
    if (nextDeletePos != PositionalDeleteIterator.END_POS && iterator == null) {
      iterator = iteratorSupplier.get();
    }
    currentRowPos = rowPos;
    while (nextDeletePos < currentRowPos) {
      advance();
    }
  }

  public int applyToDeltas(long endRowPos, int maxEvalCount, SimpleIntVector deltas) {
    Preconditions.checkState(refCount > 0, "PositionalDeleteFilter has already been released.");
    Preconditions.checkNotNull(deltas);

    long initialRowPos = currentRowPos;
    int outputIndex = 0;
    int currentDelta = 0;
    int deleteCount = 0;
    while (outputIndex < maxEvalCount && currentRowPos < endRowPos) {
      long cmp = currentRowPos - nextDeletePos;
      if (cmp < 0) {
        // row not deleted, output the current delta and reset it
        deltas.set(outputIndex++, currentDelta);
        currentRowPos++;
        currentDelta = 0;

        // add as many additional zero deltas as possible in a single call
        int zeroCount =
            (int)
                Math.min(
                    Math.min(maxEvalCount - outputIndex, endRowPos - currentRowPos), -(cmp + 1));
        // ArrowBuf.setZero has a zero length check so we can skip it here
        deltas.setZero(outputIndex, zeroCount);
        outputIndex += zeroCount;
        currentRowPos += zeroCount;
      } else if (cmp == 0) {
        // deleted row, increment current delta
        currentRowPos++;
        currentDelta++;
        deleteCount++;
        advance();
      } else {
        throw new IllegalStateException(
            "Current row position should never be greater than next delete position."
                + "  Positional delete files may be invalid with unsorted positions.");
      }
    }

    diagnosticState.batchEvaluated(
        currentRowPos, endRowPos, currentRowPos - initialRowPos, deleteCount);
    if (operatorStats != null) {
      operatorStats.addLongStat(TableFunctionOperator.Metric.NUM_POS_DELETED_ROWS, deleteCount);
    }

    deltas.setValueCount(outputIndex);
    return outputIndex;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(iterator);
    iterator = null;
  }

  private void advance() {
    long lastDeletePos = nextDeletePos;
    while (nextDeletePos != PositionalDeleteIterator.END_POS && nextDeletePos == lastDeletePos) {
      if (iterator.hasNext()) {
        nextDeletePos = iterator.next();
      } else {
        nextDeletePos = PositionalDeleteIterator.END_POS;
        // close iterators as soon as they reach their end so that underlying readers get released
        // ASAP
        AutoCloseables.close(RuntimeException.class, iterator);
        iterator = null;
      }
    }
  }

  public UserException addDiagnosticStateToException(Exception cause) {
    UserException.Builder builder =
        UserException.dataReadError(cause)
            .message("Failed to read record batch with PositionalDeleteFilter");
    diagnosticState.addToExceptionContext(builder);
    return builder.buildSilently();
  }

  private static class DiagnosticState {
    private long startPos;
    private long currentPos;
    private long endPos;
    private long totalBatchesEvaluated;
    private long totalRowsEvaluated;
    private long totalRowsDeleted;
    private long rowsEvaluatedLastBatch;
    private long rowsDeletedLastBatch;

    public void reset(long startPos) {
      this.startPos = startPos;
      endPos = -1;
      totalBatchesEvaluated = 0;
      totalRowsEvaluated = 0;
      totalRowsDeleted = 0;
      rowsEvaluatedLastBatch = 0;
      rowsDeletedLastBatch = 0;
    }

    public void batchEvaluated(long currentPos, long endPos, long rowsEvaluated, long rowsDeleted) {
      totalBatchesEvaluated++;
      this.currentPos = currentPos;
      this.endPos = endPos;
      totalRowsEvaluated += rowsEvaluated;
      totalRowsDeleted += rowsDeleted;
      rowsEvaluatedLastBatch = rowsEvaluated;
      rowsDeletedLastBatch = rowsDeleted;
    }

    public void addToExceptionContext(UserException.Builder builder) {
      builder.addContext("PositionalDeleteFilter start position", startPos);
      builder.addContext("PositionalDeleteFilter end position", endPos);
      builder.addContext("PositionalDeleteFilter current position", currentPos);
      builder.addContext("PositionalDeleteFilter total batches evaluated", totalBatchesEvaluated);
      builder.addContext("PositionalDeleteFilter total rows evaluated", totalRowsEvaluated);
      builder.addContext("PositionalDeleteFilter total rows deleted", totalRowsDeleted);
      builder.addContext(
          "PositionalDeleteFilter rows evaluated last batch", rowsEvaluatedLastBatch);
      builder.addContext("PositionalDeleteFilter rows deleted last batch", rowsDeletedLastBatch);
    }
  }
}
