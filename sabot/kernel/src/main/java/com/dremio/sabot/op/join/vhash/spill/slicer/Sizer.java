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
package com.dremio.sabot.op.join.vhash.spill.slicer;

import com.dremio.common.expression.CompleteType;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.exec.record.selection.SelectionVector2;
import com.dremio.exec.util.RoundUtil;
import java.util.Collection;
import java.util.List;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.ZeroVector;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.UnionVector;

/**
 * A tool that determines size of an operation and then provides a copier to later do that
 * operation.
 */
public interface Sizer {
  int BYTE_SIZE_BITS = 8;
  int VALID_SIZE_BITS = 1;
  int OFFSET_SIZE_BYTES = 4;
  int OFFSET_SIZE_BITS = 32;
  int SV2_SIZE_BYTES = SelectionVector2.RECORD_SIZE;

  /** Reset any caching. */
  void reset();

  /**
   * Compute size in bits required to copy the specified sv2 entries from incoming
   *
   * @param sv2 selection vector buffer - contains indices of records in the vector that we have to
   *     consider for size computation
   * @param startIdx pick records from this index in sv2 buffer
   * @param count number of entries that we pick from sv2
   * @return The amount of space consumed in bits for given count of entries
   */
  int computeBitsNeeded(ArrowBuf sv2, int startIdx, int count);

  /**
   * Estimate the number of BITS per record.
   *
   * @return the size in bits.
   */
  int getEstimatedRecordSizeInBits();

  /**
   * Get data length of certain number of entries starting from a given index
   *
   * @param startIndex start offset from where caller wants the data length for
   * @param numberOfEntries number of entries for which caller wants the length for
   * @return
   */
  int getDataLengthFromIndex(int startIndex, int numberOfEntries);

  /**
   * Get a copier that will copy data to a new set of vectors. As part of the creation of this
   * copier, add the new vector to the provided vectorOuput list.
   *
   * @param allocator buffer allocator
   * @param sv2 buffer that has the ordinals to copy.
   * @param startIdx start index in sv2 buffer
   * @param count number of entries in the sv2 to be considered
   * @param vectorOutput Output variable populated by copiers.
   * @return A copier that will copy the provided data (once it is allocated).
   */
  Copier getCopier(
      BufferAllocator allocator,
      ArrowBuf sv2,
      int startIdx,
      int count,
      List<FieldVector> vectorOutput);

  /**
   * Size required for 'len' number of records starting from 'ordinal'th record in the vector.
   *
   * @param ordinal
   * @param len
   * @return
   */
  int getSizeInBitsStartingFromOrdinal(int ordinal, int len);

  /**
   * Size in bytes required for 'len' number of records starting from 'ordinal'th record in the
   * vector.
   *
   * @param ordinal
   * @param len
   * @return
   */
  default int getSizeInBytesStartingFromOrdinal(int ordinal, int len) {
    return getSizeInBitsStartingFromOrdinal(ordinal, len) / BYTE_SIZE_BITS;
  }

  void accumulateFieldSizesInABuffer(ArrowBuf arrowBuf, int recordCount);

  /**
   * Bits required to store offset values for given number of records
   *
   * @param numberOfRecords
   * @return
   */
  static int getOffsetBufferSizeInBits(final int numberOfRecords) {

    return RoundUtil.round64up(OFFSET_SIZE_BITS * (numberOfRecords + 1));
  }

  /**
   * Bits required to store validity bits for given number of records
   *
   * @param numberOfRecords
   * @return
   */
  static int getValidityBufferSizeInBits(final int numberOfRecords) {
    return RoundUtil.round64up(VALID_SIZE_BITS * numberOfRecords);
  }

  /**
   * Bits required to store type data (in struct vectors) for given number of records
   *
   * @param numberOfRecords
   * @return
   */
  static int getTypeBufferSizeInBits(final int numberOfRecords) {
    return RoundUtil.round64up(numberOfRecords * BYTE_SIZE_BITS);
  }

  static Sizer get(ValueVector vector) {
    if (vector instanceof BaseFixedWidthVector) {
      return new FixedSizer((BaseFixedWidthVector) vector);
    } else if (vector instanceof BaseVariableWidthVector) {
      return new VariableSizer((BaseVariableWidthVector) vector);
    } else if (vector instanceof ListVector) {
      return new ListSizer((ListVector) vector);
    } else if (vector instanceof FixedSizeListVector) {
      return new FixedListSizer((FixedSizeListVector) vector);
    } else if (vector instanceof StructVector) {
      return new StructSizer((StructVector) vector);
    } else if (vector instanceof DenseUnionVector) {
      return new DenseUnionSizer((DenseUnionVector) vector);
    } else if (vector instanceof UnionVector) {
      return new SparseUnionSizer((UnionVector) vector);
    } else if (vector instanceof ZeroVector) {
      return new ZeroSizer((ZeroVector) vector);
    } else if (vector instanceof NullVector) {
      return new NullSizer((NullVector) vector);
    } else {
      throw new UnsupportedOperationException(
          String.format(
              "Vectors for field %s of type %s not yet supported.",
              vector.getField().getName(), CompleteType.fromField(vector.getField()).toString()));
    }
  }

  static int getAverageRowSize(VectorAccessible vectorContainer, int recordCount) {
    if (recordCount < 1) {
      return 0;
    }
    int totalVectorSize = 0;
    for (VectorWrapper<?> vectorWrapper : vectorContainer) {
      totalVectorSize += (vectorWrapper.getValueVector().getBufferSize());
    }
    return totalVectorSize / recordCount;
  }

  static int getBatchSizeInBytes(VectorContainer vectorContainer) {
    int totalVectorSize = 0;
    for (VectorWrapper<?> vectorWrapper : vectorContainer) {
      totalVectorSize += (vectorWrapper.getValueVector().getBufferSize());
    }
    return totalVectorSize;
  }

  static int getBatchSizeInBytes(Collection<ValueVector> valueVectors) {
    int totalVectorSize = 0;
    for (ValueVector vector : valueVectors) {
      totalVectorSize += (vector.getBufferSize());
    }
    return totalVectorSize;
  }
}
