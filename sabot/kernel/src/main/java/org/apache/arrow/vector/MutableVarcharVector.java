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

package org.apache.arrow.vector;

import com.dremio.sabot.op.aggregate.vectorized.Accumulator;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.ReusableBuffer;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableVarCharHolder;
import org.apache.arrow.vector.holders.VarCharHolder;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.Text;
import org.apache.arrow.vector.util.TransferPair;

/**
 * MutableVarcharVector implements a variable width vector of VARCHAR values which could be NULL. A
 * validity buffer (bit vector) is maintained to track which elements in the vector are null. The
 * main difference between a VarCharVector is that the values may be mutated over time. Internally
 * the values are always appended at the end of the buffer.The index to those values is maintained
 * internally in another vector viz. 'fwdIndex'. Thus over time it may have unreferenced values
 * (called as garbage). Compaction must be preformed over time to recover space in the buffer.
 */
public class MutableVarcharVector extends BaseVariableWidthVector {
  private int garbageSizeInBytes;
  private double compactionThreshold;

  private UInt2Vector fwdIndex;
  private int head; // the index at which a new value may be appended
  private Stopwatch compactionTimer = Stopwatch.createUnstarted();
  private static int DEFAULT_MAX_VECTOR_USAGE_PERCENT = 95;
  private int maxVarWidthVecUsagePercent;
  private int valueCapacity;
  private Accumulator.AccumStats accumStats;

  /**
   * Instantiate a MutableVarcharVector. This doesn't allocate any memory for the data in vector.
   *
   * @param name name of the vector
   * @param allocator allocator for memory management.
   * @param compactionThreshold this value bounds the ratio of garbage data to capacity of the
   *     buffer. If the ratio is more than the threshold, then compaction gets triggered on next
   *     update.
   */
  public MutableVarcharVector(String name, BufferAllocator allocator, double compactionThreshold) {
    this(
        name,
        FieldType.nullable(MinorType.VARCHAR.getType()),
        allocator,
        compactionThreshold,
        DEFAULT_MAX_VECTOR_USAGE_PERCENT,
        null);
  }

  /**
   * Instantiate a MutableVarcharVector. This doesn't allocate any memory for the data in vector.
   *
   * @param name name of the vector
   * @param allocator allocator for memory management.
   * @param compactionThreshold this value bounds the ratio of garbage data to capacity of the
   *     buffer. If the ratio is more than the threshold, then compaction gets triggered on next
   *     update.
   */
  public MutableVarcharVector(
      String name,
      BufferAllocator allocator,
      double compactionThreshold,
      int maxVarWidthVecUsagePercent,
      Accumulator.AccumStats accumStats) {
    this(
        name,
        FieldType.nullable(MinorType.VARCHAR.getType()),
        allocator,
        compactionThreshold,
        maxVarWidthVecUsagePercent,
        accumStats);
  }

  /**
   * Instantiate a MutableVarcharVector. This doesn't allocate any memory for the data in vector.
   *
   * @param name name of the vector
   * @param fieldType type of Field materialized by this vector
   * @param allocator allocator for memory management.
   * @param compactionThreshold this value bounds the ratio of garbage data to capacity of the
   *     buffer. If the ratio is more than the threshold, then compaction gets triggered on next
   *     update.
   */
  public MutableVarcharVector(
      String name,
      FieldType fieldType,
      BufferAllocator allocator,
      double compactionThreshold,
      int maxVarWidthVecUsagePercent,
      Accumulator.AccumStats accumStats) {
    super(new Field(name, fieldType, null), allocator);

    assert compactionThreshold <= 1.0;
    this.compactionThreshold = compactionThreshold;
    Preconditions.checkArgument(maxVarWidthVecUsagePercent <= 100);
    this.maxVarWidthVecUsagePercent = maxVarWidthVecUsagePercent;
    fwdIndex = new UInt2Vector(name, allocator);
    this.accumStats = accumStats;
  }

  public final void setCompactionThreshold(double in) {
    assert compactionThreshold <= 1.0;
    compactionThreshold = in;
  }

  public final boolean needsCompaction() {
    /*
     * Though external facing index is fwdIndex, internally "head" points the current
     * used index, due to holes in between, can be higher than the total fwdIndex count.
     * If head index reach the end of the validity buffer, need compaction to remove the
     * holes to gain "head"room.
     *
     * XXX: Ideally, the backing (BaseVariableWidthVector) may be allocated twice in size
     * for validity and offset buffer, so that the number of compactions can be reduced.
     */
    return head >= super.getValueCapacity()
        || (getDataBuffer().capacity() > 0
            && ((garbageSizeInBytes * 1.0D) / getDataBuffer().capacity()) > compactionThreshold);
  }

  public static int getValidityBufferSizeFromCount(int count) {
    // validity bits for index + validity bits for the data buffer
    return UInt2Vector.getValidityBufferSizeFromCount(count)
        + VarCharVector.getValidityBufferSizeFromCount(count);
  }

  private static int getIndexDataBufferSizeFromCount(int count) {
    return ((2 /* fwdIndex.TYPE_WIDTH */ * count + 7) / 8) * 8;
  }

  /**
   * @param count num elements to be stored
   * @param capacity total size of those elements
   * @return the data buffer capacity required to hold such mutable vector
   */
  public static int getDataBufferSizeFromCount(int count, int capacity) {
    // Size of the elements in value buffer
    int bufSize = capacity;

    // accommodate offset-buffer size
    if (count > 0) {
      bufSize += (count + 1) * 4;
    }

    // accommodate index buffer size
    bufSize += getIndexDataBufferSizeFromCount(count);

    return bufSize;
  }

  public final int getSizeInBytes() {
    return getDataBufferSizeFromCount(head, getUsedByteCapacity());
  }

  /**
   * @return The offset at which the next valid data may be put in the buffer.
   */
  public final int getCurrentOffset() {
    return getByteCapacity() > 0 ? getStartOffset(head) : 0;
  }

  /**
   * This value gets updated as the previous values in the buffer are updated with newer ones. The
   * length of the old value is added to the garbageSizeInBytes.
   *
   * @return the garbage size accumulated so far in the buffer.
   */
  public final int getGarbageSizeInBytes() {
    return garbageSizeInBytes;
  }

  /**
   * @return the size used in the buffer
   */
  public final int getUsedByteCapacity() {
    return (getCurrentOffset() - getGarbageSizeInBytes());
  }

  /**
   * Get a reader that supports reading values from this vector.
   *
   * @return Field Reader for this vector
   */
  @Override
  protected FieldReader getReaderImpl() {
    throw new UnsupportedOperationException("not supported");
  }

  /**
   * Get minor type for this vector. The vector holds values belonging to a particular type.
   *
   * @return {@link org.apache.arrow.vector.types.Types.MinorType}
   */
  @Override
  public MinorType getMinorType() {
    return MinorType.VARCHAR;
  }

  /**
   * Reset the vector to initial state. Same as {@link #zeroVector()}. Note that this method doesn't
   * release any memory.
   */
  @Override
  public void reset() {
    super.reset();

    fwdIndex.reset();
    head = 0;
    garbageSizeInBytes = 0;
  }

  /** Close the vector and release the associated buffers. */
  @Override
  public void close() {
    this.clear();
  }

  /** Same as {@link #close()}. */
  @Override
  public void clear() {
    super.clear();

    fwdIndex.clear();
    head = 0;
    garbageSizeInBytes = 0;
  }

  /*----------------------------------------------------------------*
  |                                                                |
  |          vector value retrieval methods                        |
  |                                                                |
  *----------------------------------------------------------------*/

  /**
   * Get the variable length element at specified index as byte array.
   *
   * @param index position of element to get
   * @return array of bytes for non-null element, null otherwise
   */
  @Override
  public byte[] get(int index) {
    assert index >= 0;

    final int actualIndex = fwdIndex.get(index);
    assert actualIndex >= 0;

    if (super.isSet(actualIndex) == 0) {
      throw new IllegalStateException("Value at index is null");
    }
    return super.get(getDataBuffer(), getOffsetBuffer(), actualIndex);
  }

  /**
   * Get the variable length element at specified index as Text.
   *
   * @param index position of element to get
   * @return Text object for non-null element, null otherwise
   */
  @Override
  public Text getObject(int index) {
    Text result = new Text();
    byte[] b;
    try {
      b = get(index);
    } catch (IllegalStateException e) {
      return null;
    }
    result.set(b);
    return result;
  }

  /**
   * Get the variable length element at specified index and sets the state in provided holder.
   *
   * @param index position of element to get
   * @param holder data holder to be populated by this function
   */
  public void get(int index, NullableVarCharHolder holder) {
    assert index >= 0;

    if (fwdIndex.isSet(index) == 0) {
      holder.isSet = 0;
      return;
    }

    final int actualIndex = fwdIndex.get(index);
    assert actualIndex >= 0;

    if (super.isSet(actualIndex) == 0) {
      throw new IllegalStateException("Value at actualIndex is null ");
    }

    holder.isSet = 1;
    holder.start = getStartOffset(actualIndex);
    holder.end = getStartOffset(actualIndex + 1);
    holder.buffer = getDataBuffer();
  }

  /*----------------------------------------------------------------*
  |                                                                |
  |          vector value setter methods                           |
  |                                                                |
  *----------------------------------------------------------------*/

  /**
   * Copy a cell value from a particular index in source vector to a particular position in this
   * vector.
   *
   * @param fromIndex position to copy from in source vector
   * @param thisIndex position to copy to in this vector
   * @param from source vector
   */
  public void copyFrom(int fromIndex, int thisIndex, VarCharVector from) {
    /*
    final int start = from.offsetBuffer.getInt(fromIndex * OFFSET_WIDTH);
    final int end = from.offsetBuffer.getInt((fromIndex + 1) * OFFSET_WIDTH);
    final int length = end - start;
    fillHoles(thisIndex);
    BitVectorHelper.setValidityBit(this.validityBuffer, thisIndex, from.isSet(fromIndex));
    final int copyStart = offsetBuffer.getInt(thisIndex * OFFSET_WIDTH);
    from.valueBuffer.getBytes(start, this.valueBuffer, copyStart, length);
    offsetBuffer.setInt((thisIndex + 1) * OFFSET_WIDTH, copyStart + length);
    lastSet = thisIndex;
    */
    throw new UnsupportedOperationException("not supported");
  }

  /**
   * Same as {@link #copyFrom(int, int, VarCharVector)} except that it handles the case when the
   * capacity of the vector needs to be expanded before copy.
   *
   * @param fromIndex position to copy from in source vector
   * @param thisIndex position to copy to in this vector
   * @param from source vector
   */
  public void copyFromSafe(int fromIndex, int thisIndex, VarCharVector from) {
    /*
    final int start = from.offsetBuffer.getInt(fromIndex * OFFSET_WIDTH);
    final int end = from.offsetBuffer.getInt((fromIndex + 1) * OFFSET_WIDTH);
    final int length = end - start;
    handleSafe(thisIndex, length);
    fillHoles(thisIndex);
    BitVectorHelper.setValidityBit(this.validityBuffer, thisIndex, from.isSet(fromIndex));
    final int copyStart = offsetBuffer.getInt(thisIndex * OFFSET_WIDTH);
    from.valueBuffer.getBytes(start, this.valueBuffer, copyStart, length);
    offsetBuffer.setInt((thisIndex + 1) * OFFSET_WIDTH, copyStart + length);
    lastSet = thisIndex;
    */
    throw new UnsupportedOperationException("not supported");
  }

  /**
   * If 'numOfRecords' number of records could fit in this vector, return free space left. If these
   * records don't fit (no space in offset buffer/validity buffer), return -1.
   *
   * @param numOfRecords
   * @return
   */
  private int returnFreeSpaceIfRecordsFit(final int numOfRecords) {
    if (head + numOfRecords <= this.valueCapacity) {
      return super.getByteCapacity() - getCurrentOffset();
    } else {
      /* Return -1 instead 0 so that the caller cannot even insert a null value */
      return -1;
    }
  }

  public boolean checkHasAvailableSpace(final int space, final int numOfRecords) {
    final int freeSpace = returnFreeSpaceIfRecordsFit(numOfRecords);

    /* Check if free space, without compacting, is sufficient. */
    if (freeSpace >= space) {
      return true;
    }

    /*
       If freeSpace is -1 - we are out of offsets/validity bits. return false
       if freeSpace + garbageSizeInBytes is less than required space, we don't have space even after accounting for garbage values. - return false
       freeSpace + garbageSizeInBytes > required space but this available free space is less than 5% (default value of maxVarWidthVecUsagePercent) of total space, return false
       if all above conditions are false, return true.
    */
    return freeSpace != -1
        && (freeSpace + garbageSizeInBytes) >= space
        && !isFreeSpaceLessThanGivenPercentage(freeSpace + garbageSizeInBytes);
  }

  private boolean isFreeSpaceLessThanGivenPercentage(int freeSpace) {
    return (((freeSpace) * 1.0D) / getDataBuffer().capacity())
        < (((100 - maxVarWidthVecUsagePercent) * 1.0D) / 100);
  }

  /**
   * This api is invoked during the 'set/setSafe' operations. If the index is already valid, then
   * increment the garbageSizeInBytes, as the value is being overwritten. If then the compaction
   * threshold is met, then trigger the compaction.
   *
   * @param index the position at which the set operation is being done.
   */
  private final void updateGarbageAndCompact(final int index, final int newLength) {
    final boolean isUpdate = (fwdIndex.isSafe(index) && !fwdIndex.isNull(index));
    final boolean compact = (returnFreeSpaceIfRecordsFit(1) < newLength);

    if (isUpdate) {
      final int actualIndex = fwdIndex.get(index);
      final int dataOffset = getStartOffset(actualIndex);
      final int dataLength = getStartOffset(actualIndex + 1) - dataOffset;
      garbageSizeInBytes += dataLength;

      // mark the value in buffer as invalid
      super.setNull(actualIndex);

      // treat this index also as invalid, as its going to get overwritten
      fwdIndex.setNull(index);
    }

    if (compact || needsCompaction()) {
      compactInternal();
    }
  }

  /*
   * Forces the compaction to run, even if the threshold
   * may not have been met.
   */
  public void forceCompact() {
    compactInternal();
  }

  /* Actual api that does compaction */
  private final void compactInternal() {
    compactionTimer.start();

    // maps valid offset to its corresponding index
    final Map<Integer, Integer> validOffsetMap = new HashMap<>();

    // populate the mapping
    final int idxCapacity = fwdIndex.getValueCapacity();
    for (int i = 0; i < idxCapacity; ++i) {
      if (!fwdIndex.isNull(i)) {
        final int actualIndex = fwdIndex.get(i);
        validOffsetMap.put(actualIndex, i);
      }
    }

    int current = 0;
    int target = 0;

    while (current < head) {
      // check the validity bitmap
      final boolean isValid = (super.isSet(current) != 0);

      if (!isValid) {
        ++current;
        continue;
      }

      if (current != target) {
        final int validOffset = getStartOffset(current);
        final int validLen = getStartOffset(current + 1) - validOffset;

        // update entry at target
        super.set(target, validOffset, validLen, getDataBuffer());

        // update the index to point to new position
        fwdIndex.set(validOffsetMap.get(current), target);

        // reset old position
        super.setNull(current);
      }

      ++target;
      ++current;
    } // while

    head = target;

    // only valid data remains in the buffer now.
    garbageSizeInBytes = 0;
    compactionTimer.stop();
    if (accumStats != null) {
      accumStats.incNumCompactions();
      accumStats.updateTotalCompactionTimeBy(compactionTimer.elapsed(TimeUnit.NANOSECONDS));
    }
    compactionTimer.reset();
  }

  /**
   * Set the variable length element at the specified index to the data buffer supplied in the
   * holder. Internally appends the data at the end.
   *
   * @param index position of the element to set
   * @param holder holder that carries data buffer.
   */
  public void set(int index, VarCharHolder holder) {
    assert index >= 0;

    updateGarbageAndCompact(index, holder.end - holder.start);

    // update the index
    fwdIndex.set(index, head);

    // append at the end
    super.set(head, holder.start, holder.end - holder.start, holder.buffer);
    ++head;
  }

  /**
   * Same as {@link #set(int, VarCharHolder)} except that it handles the case where index and length
   * of new element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set
   * @param holder holder that carries data buffer.
   */
  public void setSafe(int index, VarCharHolder holder) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // append at the end
    super.setSafe(head, holder.start, holder.end - holder.start, holder.buffer);

    ++head;
  }

  /**
   * Set the variable length element at the specified index to the data buffer supplied in the
   * holder.
   *
   * @param index position of the element to set
   * @param holder holder that carries data buffer.
   */
  public void set(int index, NullableVarCharHolder holder) {
    assert index >= 0;

    updateGarbageAndCompact(index, holder.end - holder.start);

    // update the index
    fwdIndex.set(index, head);

    // append at the end
    super.set(head, holder.start, holder.end - holder.start, holder.buffer);

    ++head;
  }

  /**
   * Same as {@link #set(int, NullableVarCharHolder)} except that it handles the case where index
   * and length of new element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set
   * @param holder holder that carries data buffer.
   */
  public void setSafe(int index, NullableVarCharHolder holder) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // append at the end
    super.setSafe(head, holder.start, holder.end - holder.start, holder.buffer);

    ++head;
  }

  /**
   * Set the variable length element at the specified index to the content in supplied Text.
   *
   * @param index position of the element to set
   * @param text Text object with data
   */
  public void set(int index, Text text) {
    set(index, text.getBytes(), 0, (int) text.getLength());
  }

  /**
   * Same as {@link #set(int, NullableVarCharHolder)} except that it handles the case where index
   * and length of new element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set.
   * @param text Text object with data
   */
  public void setSafe(int index, Text text) {
    setSafe(index, text.getBytes(), 0, (int) text.getLength());
  }

  /**
   * Set the variable length element at the specified index to the supplied byte array. This is same
   * as using {@link #set(int, byte[], int, int)} with start as 0 and length as value.length
   *
   * @param index position of the element to set
   * @param value array of bytes to write
   */
  @Override
  public void set(int index, byte[] value) {
    assert index >= 0;

    updateGarbageAndCompact(index, value.length);

    // update the index
    fwdIndex.set(index, head);

    // append at the end
    super.set(head, value);

    ++head;
  }

  /**
   * Same as {@link #set(int, byte[])} except that it handles the case where index and length of new
   * element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set
   * @param value array of bytes to write
   */
  @Override
  public void setSafe(int index, byte[] value) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // append at the end
    super.setSafe(head, value);

    ++head;
  }

  /**
   * Set the variable length element at the specified index to the supplied byte array.
   *
   * @param index position of the element to set
   * @param value array of bytes to write
   * @param start start index in array of bytes
   * @param length length of data in array of bytes
   */
  @Override
  public void set(int index, byte[] value, int start, int length) {
    assert index >= 0;

    updateGarbageAndCompact(index, length);

    // update the index
    fwdIndex.set(index, head);

    // append at the end
    super.set(head, value, start, length);

    ++head;
  }

  /**
   * Same as {@link #set(int, byte[], int, int)} except that it handles the case where index and
   * length of new element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set
   * @param value array of bytes to write
   * @param start start index in array of bytes
   * @param length length of data in array of bytes
   */
  @Override
  public void setSafe(int index, byte[] value, int start, int length) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // append at the end
    super.setSafe(head, value, start, length);

    ++head;
  }

  /**
   * Set the variable length element at the specified index to the content in supplied ByteBuffer.
   *
   * @param index position of the element to set
   * @param value ByteBuffer with data
   * @param start start index in ByteBuffer
   * @param length length of data in ByteBuffer
   */
  @Override
  public void set(int index, ByteBuffer value, int start, int length) {
    assert index >= 0;

    updateGarbageAndCompact(index, length);

    // update the index
    fwdIndex.set(index, head);

    // append at the end
    super.set(head, value, start, length);

    ++head;
  }

  /**
   * Same as {@link #set(int, ByteBuffer, int, int)} except that it handles the case where index and
   * length of new element are beyond the existing capacity of the vector.
   *
   * @param index position of the element to set
   * @param value ByteBuffer with data
   * @param start start index in ByteBuffer
   * @param length length of data in ByteBuffer
   */
  @Override
  public void setSafe(int index, ByteBuffer value, int start, int length) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // append at the end
    super.setSafe(head, value, start, length);

    ++head;
  }

  /**
   * Store the given value at a particular position in the vector. isSet indicates whether the value
   * is NULL or not.
   *
   * @param index position of the new value
   * @param isSet 0 for NULL value, 1 otherwise
   * @param start start position of data in buffer
   * @param end end position of data in buffer
   * @param buffer data buffer containing the variable width element to be stored in the vector
   */
  @Override
  public void set(int index, int isSet, int start, int end, ArrowBuf buffer) {
    assert index >= 0;

    updateGarbageAndCompact(index, end - start);

    // update the index
    fwdIndex.set(index, head);

    // update the index
    super.set(head, isSet, start, end, buffer);

    ++head;
  }

  /**
   * Same as {@link #set(int, int, int, int, ArrowBuf)} except that it handles the case when index
   * is greater than or equal to current value capacity of the vector.
   *
   * @param index position of the new value
   * @param isSet 0 for NULL value, 1 otherwise
   * @param start start position of data in buffer
   * @param end end position of data in buffer
   * @param buffer data buffer containing the variable width element to be stored in the vector
   */
  @Override
  public void setSafe(int index, int isSet, int start, int end, ArrowBuf buffer) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // update the index
    super.setSafe(head, isSet, start, end, buffer);

    ++head;
  }

  /**
   * Store the given value at a particular position in the vector. isSet indicates whether the value
   * is NULL or not.
   *
   * @param index position of the new value
   * @param start start position of data in buffer
   * @param length length of data in buffer
   * @param buffer data buffer containing the variable width element to be stored in the vector
   */
  @Override
  public void set(int index, int start, int length, ArrowBuf buffer) {
    assert index >= 0;

    updateGarbageAndCompact(index, length);

    // update the index
    fwdIndex.set(index, head);

    // update the index
    super.set(head, start, length, buffer);

    ++head;
  }

  /**
   * Same as {@link #set(int, int, int, int, ArrowBuf)} except that it handles the case when index
   * is greater than or equal to current value capacity of the vector.
   *
   * @param index position of the new value
   * @param start start position of data in buffer
   * @param length length of data in buffer
   * @param buffer data buffer containing the variable width element to be stored in the vector
   */
  @Override
  public void setSafe(int index, int start, int length, ArrowBuf buffer) {
    assert index >= 0;

    /* No need to force compact as setSafe can grow the buffers */
    updateGarbageAndCompact(index, 0);

    // update the index
    fwdIndex.setSafe(index, head);

    // update the index
    super.setSafe(head, start, length, buffer);

    ++head;
  }

  /**
   * Copies the entries pointed by fwdIndex into the varchar vector. It assumes that the vector has
   * been sized to hold the entries.
   *
   * @param outVector target BaseVariableWidthVector where data is copied
   * @param recordsInBatch number of entries to copy
   * @param targetStartIndex index at which topy in the target vector
   */
  public void copyToVarWidthVec(
      BaseVariableWidthVector outVector, final int recordsInBatch, final int targetStartIndex) {
    Preconditions.checkArgument(recordsInBatch <= fwdIndex.getValueCapacity());
    for (int i = 0; i < recordsInBatch; ++i) {
      if (!fwdIndex.isNull(i)) {
        final int actualIndex = fwdIndex.get(i);
        Preconditions.checkArgument(actualIndex >= 0);
        Preconditions.checkArgument(!super.isNull(actualIndex));
        final int startOffset = getStartOffset(actualIndex);
        final int dataLength = getStartOffset(actualIndex + 1) - startOffset;
        outVector.set(targetStartIndex + i, startOffset, dataLength, getDataBuffer());
      }
    }
    outVector.setValueCount(targetStartIndex + recordsInBatch);
  }

  public boolean isIndexSafe(int index) {
    return fwdIndex.isSafe(index) && !fwdIndex.isNull(index);
  }

  @Override
  public List<ArrowBuf> getFieldBuffers() {
    throw new UnsupportedOperationException("not supported");
  }

  public void loadBuffers(
      int valueCount,
      final int varLenAccumulatorCapacity,
      final ArrowBuf dataBuffer,
      final ArrowBuf validityBuffer) {
    // load validity buffers
    final int indexValSize = UInt2Vector.getValidityBufferSizeFromCount(valueCount);
    fwdIndex.validityBuffer = validityBuffer.slice(0, indexValSize);
    fwdIndex.validityBuffer.writerIndex(indexValSize);
    fwdIndex.validityBuffer.getReferenceManager().retain(1);

    final int dataValSize = VarCharVector.getValidityBufferSizeFromCount(valueCount);
    super.validityBuffer = validityBuffer.slice(indexValSize, dataValSize);
    super.validityBuffer.writerIndex(dataValSize);
    super.validityBuffer.getReferenceManager().retain(1);

    // load offset buffer
    final int offsetSize = ((valueCount + 1) * 4);
    super.offsetBuffer = dataBuffer.slice(0, offsetSize);
    super.offsetBuffer.writerIndex(offsetSize);
    super.offsetBuffer.getReferenceManager().retain(1);

    // load index data buffer
    final int indexBufSize = this.getIndexDataBufferSizeFromCount(valueCount);
    fwdIndex.valueBuffer = dataBuffer.slice(offsetSize, indexBufSize);
    fwdIndex.valueBuffer.writerIndex(indexBufSize);
    fwdIndex.valueBuffer.getReferenceManager().retain(1);

    // load value data buffer
    super.valueBuffer = dataBuffer.slice((offsetSize + indexBufSize), varLenAccumulatorCapacity);
    super.valueBuffer.writerIndex(varLenAccumulatorCapacity);
    super.valueBuffer.getReferenceManager().retain(1);

    fwdIndex.refreshValueCapacity();

    reset();

    this.valueCapacity = super.getValueCapacity();
  }

  /**
   * This api copies the records to the target vector. It starts copying from 'startIndex', of
   * numRecords, usually till the end. It frees up the space after copying the records to
   * destination.
   *
   * @param startIndex index from which the records to be moved
   * @param dstStartIndex start index to which the records are moved to
   * @param numRecords number of records to copy
   * @param toVector destination
   */
  public void moveValuesAndFreeSpace(
      final int startIndex, int dstStartIndex, final int numRecords, FieldVector toVector) {
    Boolean dataMoved = false;
    Preconditions.checkArgument(startIndex + numRecords <= fwdIndex.getValueCapacity());
    for (int count = 0; count < numRecords; ++dstStartIndex, ++count) {
      if (!fwdIndex.isNull(startIndex + count)) {
        final int actualIndex = fwdIndex.get(startIndex + count);
        Preconditions.checkArgument(actualIndex >= 0);
        final int startOffset = getStartOffset(actualIndex);
        final int dataLength = getStartOffset(actualIndex + 1) - startOffset;
        ((MutableVarcharVector) toVector)
            .set(dstStartIndex, startOffset, dataLength, getDataBuffer());
        dataMoved = true;

        // mark it as free now
        super.setNull(actualIndex);
        fwdIndex.setNull(startIndex + count);
      }
    }

    // force compact to clean up the 'moved' data
    if (dataMoved) {
      this.forceCompact();
    }
  }

  /*----------------------------------------------------------------*
  |                                                                |
  |                      vector transfer                           |
  |                                                                |
  *----------------------------------------------------------------*/

  /**
   * Construct a TransferPair comprising of this and and a target vector of the same type.
   *
   * @param ref name of the target vector
   * @param allocator allocator for the target vector
   * @return {@link TransferPair}
   */
  @Override
  public TransferPair getTransferPair(String ref, BufferAllocator allocator) {
    // return new VarCharVector.TransferImpl(ref, allocator);
    throw new UnsupportedOperationException("not supported");
  }

  @Override
  public TransferPair getTransferPair(Field field, BufferAllocator bufferAllocator) {
    throw new UnsupportedOperationException("not supported");
  }

  /**
   * Construct a TransferPair with a desired target vector of the same type.
   *
   * @param to target vector
   * @return {@link TransferPair}
   */
  @Override
  public TransferPair makeTransferPair(ValueVector to) {
    // return new VarCharVector.TransferImpl((VarCharVector) to);
    throw new UnsupportedOperationException("not supported");
  }

  /* Use this function to verify the consistency of the mutable vector */
  public void checkMV() {
    final Map<Integer, Integer> validOffsetMap = new HashMap<>();
    final int idxCapacity = fwdIndex.getValueCapacity();
    for (int i = 0; i < idxCapacity; ++i) {
      if (!fwdIndex.isNull(i)) {
        final int actualIndex = fwdIndex.get(i);
        Preconditions.checkArgument(!super.isNull(actualIndex));
        validOffsetMap.put(actualIndex, i);
      }
    }

    int current = 0;
    while (current < head) {
      if (super.isNull(current)) {
        Preconditions.checkArgument(validOffsetMap.get(current) == null);
      } else {
        Preconditions.checkArgument(validOffsetMap.get(current) != null);
        validOffsetMap.remove(current);
      }
      current++;
    }
    Preconditions.checkArgument(validOffsetMap.isEmpty());
  }

  @Override
  public void read(int index, ReusableBuffer<?> buffer) {
    // This method was added for StringView support.
  }
}
