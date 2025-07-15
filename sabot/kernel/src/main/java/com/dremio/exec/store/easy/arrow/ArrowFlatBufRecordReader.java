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
package com.dremio.exec.store.easy.arrow;

import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.cache.VectorAccessibleFlatBufSerializable;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.RecordReader;
import com.dremio.io.FSInputStream;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.base.Preconditions;
import io.netty.util.internal.PlatformDependent;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VectorContainerHelper;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.commons.io.IOUtils;

/** {@link RecordReader} implementation for Arrow format files using flatbuffer serialization */
public class ArrowFlatBufRecordReader implements RecordReader {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ArrowFlatBufRecordReader.class);
  private static final String MAGIC_STRING = "DREMARROWFLATBUF";
  private static final int MAGIC_STRING_LENGTH = MAGIC_STRING.getBytes().length;
  private static final int FOOTER_OFFSET_SIZE = Long.BYTES;
  private static final long INT_SIZE = 4;

  private final OperatorContext context;
  private FSInputStream inputStream;
  private final long size;
  private final int rowSizeLimit;
  private final boolean rowSizeLimitEnabled;

  private BufferAllocator allocator;
  private List<Long> batchOffsets = new ArrayList<>();
  private List<Integer> batchSizes = new ArrayList<>();

  private int nextBatchIndex;

  private List<ValueVector> vectors = new ArrayList<>();

  private VectorAccessibleFlatBufSerializable serializable;
  private ArrowBuf rowSizeAccumulator;
  private int fixedDataLenPerRow;
  private CombinedSizer variableVectorSizer;
  private boolean rowSizeLimitEnabledForThisOperator;

  public ArrowFlatBufRecordReader(
      final OperatorContext context, final FSInputStream inputStream, final long size) {
    this.context = context;
    this.inputStream = inputStream;
    this.size = size;
    this.serializable = new VectorAccessibleFlatBufSerializable();
    this.rowSizeLimitEnabled =
        context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
    this.rowSizeLimitEnabledForThisOperator = rowSizeLimitEnabled;
    this.rowSizeLimit =
        Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
  }

  @Override
  public void setup(OutputMutator output) {
    try {
      allocator = context.getAllocator();

      if (size < 2 * MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE) {
        throw UserException.dataReadError()
            .message("File is too small to be an Arrow format file")
            .build(logger);
      }

      final long tailSizeGuess = MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE;
      final int tailSize = (int) Math.min(size, tailSizeGuess);
      final byte[] tailBytes = new byte[tailSize];

      try (OperatorStats.WaitRecorder waitRecorder =
          OperatorStats.getWaitRecorder(context.getStats())) {
        inputStream.setPosition(size - tailSize);
        IOUtils.readFully(inputStream, tailBytes);
      }

      // read magic word
      byte[] magic = Arrays.copyOfRange(tailBytes, tailSize - MAGIC_STRING_LENGTH, tailSize);
      // Make sure magic word matches
      if (!Arrays.equals(magic, MAGIC_STRING.getBytes())) {
        throw UserException.dataReadError()
            .message("Invalid magic word. File is not an Arrow format file")
            .build(logger);
      }

      // read footer offset
      final byte[] footerOffsetBytes =
          Arrays.copyOfRange(
              tailBytes,
              tailSize - MAGIC_STRING_LENGTH - FOOTER_OFFSET_SIZE,
              tailSize - MAGIC_STRING_LENGTH);
      final long footerOffset = PlatformDependent.getLong(footerOffsetBytes, 0);
      // Make sure the footer offset is valid
      if (footerOffset < MAGIC_STRING_LENGTH
          || footerOffset >= (size - (MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE))) {
        throw UserException.dataReadError()
            .message("Invalid footer offset")
            .addContext("invalid footer offset", String.valueOf(footerOffset))
            .build(logger);
      }

      // read footer
      int footerSize = (int) (size - MAGIC_STRING_LENGTH - FOOTER_OFFSET_SIZE - footerOffset);
      byte[] footer;
      if (footerSize > tailSize - MAGIC_STRING_LENGTH - FOOTER_OFFSET_SIZE) {
        footer = new byte[footerSize];
        try (OperatorStats.WaitRecorder waitRecorder =
            OperatorStats.getWaitRecorder(context.getStats())) {
          inputStream.setPosition(footerOffset);
          IOUtils.readFully(inputStream, footer);
        }
      } else {
        footer =
            Arrays.copyOfRange(
                tailBytes,
                tailSize - footerSize - MAGIC_STRING_LENGTH - FOOTER_OFFSET_SIZE,
                tailSize - MAGIC_STRING_LENGTH - FOOTER_OFFSET_SIZE);
      }

      // read schema
      int index = 0;
      int schemaLen = PlatformDependent.getInt(footer, index);
      index += Integer.BYTES;

      org.apache.arrow.flatbuf.Schema schemafb =
          org.apache.arrow.flatbuf.Schema.getRootAsSchema(
              ByteBuffer.wrap(footer, index, schemaLen));
      Schema schema = Schema.convertSchema(schemafb);

      // create vectors
      for (Field field : schema.getFields()) {
        vectors.add(
            output.addField(
                field, (Class<? extends ValueVector>) TypeHelper.getValueVectorClass(field)));
      }
      index += schemaLen;

      // read offsets
      int numBatches = PlatformDependent.getInt(footer, index);
      index += Integer.BYTES;

      for (int i = 0; i < numBatches; ++i, index += Long.BYTES) {
        batchOffsets.add(PlatformDependent.getLong(footer, index));
      }

      // read batch size information
      for (int i = 0; i < numBatches; ++i, index += Integer.BYTES) {
        batchSizes.add(PlatformDependent.getInt(footer, index));
      }

      // Reset to beginning of the file
      inputStream.setPosition(0);
      nextBatchIndex = 0;
      createNewRowLengthAccumulatorIfRequired(context.getTargetBatchSize());
      this.variableVectorSizer = VectorContainerHelper.createSizer(vectors, false);
      for (ValueVector vv : vectors) {
        if (vv instanceof BaseFixedWidthVector) {
          fixedDataLenPerRow += ((BaseFixedWidthVector) vv).getTypeWidth();
        }
      }
      if (rowSizeLimitEnabled) {
        if (fixedDataLenPerRow <= rowSizeLimit && variableVectorSizer.getVectorCount() == 0) {
          rowSizeLimitEnabledForThisOperator = false;
        }
        if (fixedDataLenPerRow > rowSizeLimit) {
          throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
              rowSizeLimit, RowSizeLimitExceptionType.READ, logger);
        }
      }
    } catch (Exception e) {
      throw UserException.dataReadError(e)
          .message("Failed to read the Arrow formatted file.")
          .build(logger);
    }
  }

  @Override
  public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
    // no-op as this allocates buffers based on the size of the buffers in file.
  }

  @Override
  public int next() {
    if (nextBatchIndex >= batchOffsets.size()) {
      return 0;
    }

    try {
      long batchOffset = batchOffsets.get(nextBatchIndex);
      inputStream.setPosition(batchOffset);

      VectorContainer container = new VectorContainer();
      container.addCollection(vectors);
      container.buildSchema();

      serializable.clear();
      serializable.setup(container, allocator, context.getStats());
      serializable.readFromStream(inputStream);
      int recordCount = container.getRecordCount();
      checkForRowSizeOverLimit(recordCount);
      nextBatchIndex++;

      return recordCount;
    } catch (final Exception e) {
      throw UserException.dataReadError(e)
          .message("Failed to read data from Arrow format file.")
          .addContext("currentBatchIndex", nextBatchIndex)
          .build(logger);
    }
  }

  public boolean hasNext() {
    return nextBatchIndex < batchOffsets.size();
  }

  @Override
  public void close() throws Exception {
    if (rowSizeAccumulator != null) {
      rowSizeAccumulator.close();
      rowSizeAccumulator = null;
    }
  }

  /**
   * Return size of the record batch
   *
   * @param batchIndex: Index of the record batch
   * @return size of the record batch
   */
  public int getRecordBatchSize(int batchIndex) {
    return this.batchSizes.get(batchIndex);
  }

  /**
   * Return total number of batches present in the file
   *
   * @return batch count
   */
  public int getBatchCount() {
    return this.batchSizes.size();
  }

  /**
   * Returns index of next batch to read. This is helpful in implementing delta reader that can skip
   * record batches
   *
   * @return
   */
  public int getNextBatchIndex() {
    return nextBatchIndex;
  }

  /**
   * This method sets the next batch index. Calling next() after setting next batch index results in
   * reading the batch This is useful in implementing delta reader that can skip record batches
   *
   * @param batchIndex
   */
  public void setNextBatchIndex(int batchIndex) {
    Preconditions.checkArgument(batchIndex < this.batchSizes.size(), "Invalid record batch index");
    nextBatchIndex = batchIndex;
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

  private void checkForRowSizeOverLimit(int recordCount) {
    if (!rowSizeLimitEnabledForThisOperator) {
      return;
    }
    createNewRowLengthAccumulatorIfRequired(recordCount);
    VectorContainerHelper.checkForRowSizeOverLimit(
        vectors,
        recordCount,
        rowSizeLimit - fixedDataLenPerRow,
        rowSizeLimit,
        rowSizeAccumulator,
        variableVectorSizer,
        RowSizeLimitExceptionType.PROCESSING,
        logger);
  }
}
