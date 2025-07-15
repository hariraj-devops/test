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

import io.netty.util.internal.PlatformDependent;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseValueVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;

public final class DoubleArrayAggAccumulator extends ArrayAggAccumulator<Double> {
  public DoubleArrayAggAccumulator(
      FieldVector input,
      FieldVector transferVector,
      BaseValueVector tempAccumulatorHolder,
      BufferAllocator allocator,
      int maxArrayAggSize,
      int initialVectorSize) {
    super(
        input,
        transferVector,
        tempAccumulatorHolder,
        allocator,
        maxArrayAggSize,
        initialVectorSize);
  }

  @Override
  protected int getFieldWidth() {
    return Float8Vector.TYPE_WIDTH;
  }

  @Override
  protected void writeItem(UnionListWriter writer, Double item) {
    writer.writeFloat8(item);
  }

  @Override
  protected ArrayAggAccumulatorHolder<Double> getAccumulatorHolder(
      BufferAllocator allocator, int initialCapacity) {
    return new DoubleArrayAggAccumulatorHolder(allocator, initialCapacity);
  }

  @Override
  protected Double getElement(
      long baseAddress, int itemIndex, ArrowBuf dataBuffer, ArrowBuf offsetBuffer) {
    long offHeapMemoryAddress = getOffHeapAddressForFixedWidthTypes(baseAddress, itemIndex);
    return Double.longBitsToDouble(PlatformDependent.getLong(offHeapMemoryAddress));
  }
}
