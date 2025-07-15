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

package com.dremio.sabot.op.aggregate.vectorized.arrayagg;

import com.dremio.exec.expr.fn.impl.ByteArrayWrapper;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseValueVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.MutableVarcharVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;

public final class VarbinaryArrayAggAccumulator extends ArrayAggAccumulator<ByteArrayWrapper> {
  private final int maxArrayAggSize;

  public VarbinaryArrayAggAccumulator(
      FieldVector input,
      FieldVector transferVector,
      BaseValueVector tempAccumulatorHolder,
      BufferAllocator computationVectorAllocator,
      int maxArrayAggSize,
      int initialVectorSize) {
    super(
        input,
        transferVector,
        tempAccumulatorHolder,
        computationVectorAllocator,
        maxArrayAggSize,
        initialVectorSize);
    this.maxArrayAggSize = maxArrayAggSize;
  }

  @Override
  public int getDataBufferSize() {
    // Max allowed field size in Dremio
    return maxArrayAggSize;
  }

  @Override
  protected int getFieldWidth() {
    throw new UnsupportedOperationException("Field width is not defined for VARBINARY type.");
  }

  @Override
  protected void writeItem(UnionListWriter writer, ByteArrayWrapper item) {
    try (ArrowBuf buffer = getAllocator().buffer(item.getLength())) {
      buffer.setBytes(0, item.getBytes());
      writer.writeVarBinary(0, item.getLength(), buffer);
    }
  }

  @Override
  protected ArrayAggAccumulatorHolder<ByteArrayWrapper> getAccumulatorHolder(
      BufferAllocator allocator, int initialCapacity) {
    return new VarbinaryArrayAggAccumulatorHolder(allocator, initialCapacity);
  }

  @Override
  protected ByteArrayWrapper getElement(
      long offHeapMemoryAddress, int itemIndex, ArrowBuf dataBuffer, ArrowBuf offsetBuffer) {
    final int startOffset =
        offsetBuffer.getInt((long) itemIndex * MutableVarcharVector.OFFSET_WIDTH);
    final int endOffset =
        offsetBuffer.getInt((long) (itemIndex + 1) * MutableVarcharVector.OFFSET_WIDTH);
    final int len = endOffset - startOffset;
    byte[] data = new byte[len];
    dataBuffer.getBytes(startOffset, data);
    return new ByteArrayWrapper(data);
  }
}
