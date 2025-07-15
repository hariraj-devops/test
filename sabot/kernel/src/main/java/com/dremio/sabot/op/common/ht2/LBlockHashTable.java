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
package com.dremio.sabot.op.common.ht2;

import static java.util.Arrays.asList;
import static java.util.Arrays.copyOfRange;

import com.dremio.common.AutoCloseables;
import com.dremio.common.AutoCloseables.RollbackCloseable;
import com.dremio.common.util.CloseableIterator;
import com.dremio.common.util.Numbers;
import com.dremio.sabot.op.aggregate.vectorized.Accumulator;
import com.dremio.sabot.op.join.vhash.spill.SV2UnsignedUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Streams;
import com.google.common.primitives.Longs;
import com.koloboke.collect.hash.HashConfig;
import com.koloboke.collect.impl.hash.HashConfigWrapper;
import com.koloboke.collect.impl.hash.LHashCapacities;
import io.netty.util.internal.PlatformDependent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.util.LargeMemoryUtil;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.FieldVector;

/**
 * A hash table of blocks. Table is broken into a fixed block and a variable block.
 *
 * <p>Built from the following koloboke independent implementations and customized for this purpose:
 * UpdatableQHashObjSetGO &lt UpdatableObjQHashSetSO &lt UpdatableSeparateKVObjQHashGO &lt
 * UpdatableSeparateKVObjQHashSO &lt UpdatableQHash
 */
public final class LBlockHashTable implements HashTable, AutoCloseable {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(LBlockHashTable.class);
  /* per data batch inserted into hash table, we have 2 buffers
   * that store hash table data.
   * one buffer that stores the data from all the fixed width group by
   * key columns and other buffer that stores data from variable
   * width key columns.
   */
  private static final int NUM_HASHTABLE_BUFFERS_PERBATCH = 2;
  private final HashConfigWrapper config;
  private ResizeListener resizeListener;
  private SpaceCheckListener spaceCheckListener;

  private final PivotDef pivot;
  private BufferAllocator allocator;
  private final NullComparator nullComparator;
  private final boolean fixedOnly;

  private int capacity;
  private int maxSize;

  private int currentOrdinal;

  /**
   * Number of gaps in ordinal space. We skip the ordinals left in current batch when the variable
   * width buffer reaches max limit
   */
  private int gaps;

  private final int variableBlockMaxLength;
  private final int ACTUAL_VALUES_PER_BATCH;
  private final int MAX_VALUES_PER_BATCH;
  private final int BITS_IN_CHUNK;
  private final int CHUNK_OFFSET_MASK;

  private ControlBlock[] controlBlocks;
  private FixedBlockVector[] fixedBlocks = new FixedBlockVector[0];
  private VariableBlockVector[] variableBlocks = new VariableBlockVector[0];
  private long[] tableControlAddresses = new long[0];
  private long[] tableFixedAddresses = new long[0];
  private long[] openVariableAddresses = new long[0]; // current pointer where we should add values.
  private long[] initVariableAddresses = new long[0];
  private long[] maxVariableAddresses = new long[0];

  private int rehashCount = 0;
  private int spliceCount = 0;
  private final Stopwatch rehashTimer = Stopwatch.createUnstarted();
  private final Stopwatch initTimer = Stopwatch.createUnstarted();
  private final Stopwatch spliceTimer = Stopwatch.createUnstarted();

  private ArrowBuf traceBuf;
  private long traceBufNext;

  private long allocatedForFixedBlocks;
  private long allocatedForVarBlocks;
  private long unusedForFixedBlocks;
  private long unusedForVarBlocks;

  private final boolean enforceVarWidthBufferLimit;
  private int maxOrdinalBeforeExpand;

  public LBlockHashTable(HashTableCreateArgs createArgs) {
    this(
        createArgs.getHashConfig(),
        createArgs.getPivot(),
        createArgs.getAllocator(),
        createArgs.getInitialSize(),
        createArgs.getDefaultVarLengthSize(),
        createArgs.isEnforceVarWidthBufferLimit(),
        createArgs.getMaxHashTableBatchSize(),
        createArgs.getNullComparator(),
        createArgs.isLightWeightInstance());
  }

  public LBlockHashTable(
      HashConfig config,
      PivotDef pivot,
      BufferAllocator parentAllocator,
      int initialSize,
      int defaultVariableLengthSize,
      final boolean enforceVarWidthBufferLimit,
      final int maxHashTableBatchSize) {
    this(
        config,
        pivot,
        parentAllocator,
        initialSize,
        defaultVariableLengthSize,
        enforceVarWidthBufferLimit,
        maxHashTableBatchSize,
        null,
        false);
  }

  public LBlockHashTable(
      HashConfig config,
      PivotDef pivot,
      BufferAllocator parentAllocator,
      int initialSize,
      int defaultVariableLengthSize,
      final boolean enforceVarWidthBufferLimit,
      final int maxHashTableBatchSize,
      NullComparator nullComparator,
      boolean lightWeightInstance) {
    this.pivot = pivot;
    this.nullComparator = nullComparator;
    this.config = new HashConfigWrapper(config);
    this.fixedOnly = pivot.getVariableCount() == 0;
    this.enforceVarWidthBufferLimit = enforceVarWidthBufferLimit;
    this.resizeListener = ResizeListener.NO_OP;
    this.spaceCheckListener = SpaceCheckListener.NO_OP;
    // this could be less than MAX_VALUES_PER_BATCH to optimally use direct memory with non power of
    // 2 batch size
    this.ACTUAL_VALUES_PER_BATCH = maxHashTableBatchSize;
    /* maximum records that can be stored in hashtable block/chunk */
    this.MAX_VALUES_PER_BATCH = Numbers.nextPowerOfTwo(maxHashTableBatchSize);
    this.BITS_IN_CHUNK = Long.numberOfTrailingZeros(MAX_VALUES_PER_BATCH);
    this.CHUNK_OFFSET_MASK = (1 << BITS_IN_CHUNK) - 1;
    int batchSizeForPivotVectors = Math.max(MIN_RESERVATION_BATCH_SIZE, ACTUAL_VALUES_PER_BATCH);
    this.variableBlockMaxLength =
        (pivot.getVariableCount() == 0)
            ? 0
            : (batchSizeForPivotVectors
                * (((defaultVariableLengthSize + VAR_LENGTH_SIZE) * pivot.getVariableCount())
                    + VAR_LENGTH_SIZE));
    this.allocatedForFixedBlocks = 0;
    this.allocatedForVarBlocks = 0;
    this.unusedForFixedBlocks = 0;
    this.unusedForVarBlocks = 0;
    this.maxOrdinalBeforeExpand = 0;
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Creating LBlockHashTable for blockWidth, {} with fixedOnly {} ",
          pivot.getBlockWidth(),
          fixedOnly);
    }
    if (lightWeightInstance) {
      return;
    }
    try (RollbackCloseable rc = new RollbackCloseable(true)) {
      this.allocator =
          rc.add(
              parentAllocator.newChildAllocator("lblock-hashtable", 0, parentAllocator.getLimit()));
      internalInit(LHashCapacities.capacity(this.config, initialSize, false));
      rc.commit();
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    logger.debug(
        "initialized hashtable, maxSize:{}, capacity:{}, maxVariableBlockLength:{}, maxValuesPerBatch:{}",
        maxSize,
        capacity,
        variableBlockMaxLength,
        MAX_VALUES_PER_BATCH);
  }

  @Override
  public int getMaxValuesPerBatch() {
    return MAX_VALUES_PER_BATCH;
  }

  @Override
  public int getActualValuesPerBatch() {
    return ACTUAL_VALUES_PER_BATCH;
  }

  @Override
  public int getMaxPerBlock() {
    return MAX_VALUES_PER_BATCH;
  }

  @Override
  public int getBitsInChunk() {
    return BITS_IN_CHUNK;
  }

  @Override
  public int getChunkOffsetMask() {
    return CHUNK_OFFSET_MASK;
  }

  @Override
  public int getVariableBlockMaxLength() {
    return variableBlockMaxLength;
  }

  @Override
  public int getNumHashTableBuffersPerBatch() {
    return NUM_HASHTABLE_BUFFERS_PERBATCH;
  }

  @Override
  public long[] getDataPageAddresses() {
    long[] dataAddresses = new long[tableFixedAddresses.length];
    for (int i = 0; i < tableFixedAddresses.length; i++) {
      dataAddresses[i] = tableFixedAddresses[i];
    }
    return dataAddresses;
  }

  // Compute the direct memory required for one pivoted variable block.
  public static int computeVariableBlockMaxLength(
      final int hashTableBatchSize, final int numVarColumns, final int defaultVariableLengthSize) {
    return (numVarColumns == 0)
        ? 0
        : (hashTableBatchSize
            * (((defaultVariableLengthSize + VAR_OFFSET_SIZE) * numVarColumns) + VAR_LENGTH_SIZE));
  }

  @Override
  public void computeHash(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, long seed, ArrowBuf hashOut8B) {
    keyFixed.checkBytes(0, numRecords * pivot.getBlockWidth());
    hashOut8B.checkBytes(0, numRecords * 8);
    final BlockChunk blockChunk =
        new BlockChunk(
            keyFixed.memoryAddress(),
            keyVar == null ? 0 : keyVar.memoryAddress(),
            keyVar == null ? 0 : keyVar.capacity(),
            fixedOnly,
            pivot.getBlockWidth(),
            numRecords,
            hashOut8B.memoryAddress(),
            seed);
    HashComputation.computeHash(blockChunk);
  }

  /**
   * Search for a key. If the key doesn't exist, insert into the hash table
   *
   * @param keyFixedVectorAddr starting address of fixed vector block
   * @param keyVarVectorAddr starting address of variable vector block
   * @param keyVarVectorSize size of variable vector block
   * @param keyIndex record #
   * @param keyHash hashvalue (hashing is external to the hash table)
   * @return ordinal (of newly inserted key or existing key)
   */
  @Override
  public int add(
      final long keyFixedVectorAddr,
      final long keyVarVectorAddr,
      final long keyVarVectorSize,
      final int keyIndex,
      final int keyHash) {
    return getOrInsert(
        keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash, true);
  }

  /**
   * Search for a vector of keys. If any key doesn't exist, insert into the hashtable
   *
   * @param numRecords number of records to search
   * @param keyFixed buffer holding fixed portion of keys
   * @param keyVar buffer holding variable portion of keys
   * @param hash8B buffer holding 8-byte hash values
   * @param outOrdinals hash-table ordinals (output)
   */
  @Override
  public int add(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, ArrowBuf hash8B, ArrowBuf outOrdinals) {
    int keyIndex = 0;

    keyFixed.checkBytes(0, numRecords * pivot.getBlockWidth());
    hash8B.checkBytes(0, numRecords * 8);
    outOrdinals.checkBytes(0, numRecords * ORDINAL_SIZE);

    long outputAddr = outOrdinals.memoryAddress();
    long hashVectorAddr8B = hash8B.memoryAddress();
    final long keyFixedVectorAddr = keyFixed.memoryAddress();
    final long keyVarVectorAddr = keyVar == null ? 0 : keyVar.memoryAddress();
    final long keyVarVectorSize = keyVar == null ? 0 : keyVar.capacity();
    try {
      for (keyIndex = 0;
          keyIndex < numRecords;
          keyIndex++, outputAddr += 4, hashVectorAddr8B += 8) {
        final int keyHash = (int) PlatformDependent.getLong(hashVectorAddr8B);

        PlatformDependent.putInt(
            outputAddr,
            add(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
      }
    } catch (OutOfMemoryException | HashTableMaxCapacityReachedException ex) {
      logger.debug(
          String.format(
              "%s when trying to insert %d records (%d records were successfully inserted)",
              ex.getClass().getSimpleName(), numRecords, keyIndex));
    } catch (Exception ex) {
      logger.error(
          String.format(
              "An unexpected error was thrown when trying to insert %d records (%d records were successfully inserted)",
              numRecords, keyIndex),
          ex);
    }

    return keyIndex;
  }

  /**
   * Search for a vector of keys. If any key doesn't exist, insert into the hashtable
   *
   * @param sv2 sv2 of valid ordinals
   * @param pivotShift shift to deduct in pivot block
   * @param numRecords number of records to search
   * @param keyFixed buffer holding fixed portion of keys
   * @param keyVar buffer holding variable portion of keys
   * @param hash4B buffer holding 4-byte hash values
   * @param outOrdinals hash-table ordinals (output)
   */
  @Override
  public int addSv2(
      ArrowBuf sv2,
      int pivotShift,
      int numRecords,
      ArrowBuf keyFixed,
      ArrowBuf keyVar,
      ArrowBuf hash4B,
      ArrowBuf outOrdinals) {
    int index = 0;

    keyFixed.checkBytes(0, numRecords * pivot.getBlockWidth());
    hash4B.checkBytes(0, numRecords * 4);
    outOrdinals.checkBytes(0, numRecords * ORDINAL_SIZE);

    long outputAddr = outOrdinals.memoryAddress();
    long tableHashAddr4B = hash4B.memoryAddress();
    final long keyFixedVectorAddr = keyFixed.memoryAddress();
    final long keyVarVectorAddr = keyVar == null ? 0 : keyVar.memoryAddress();
    final long keyVarVectorSize = keyVar == null ? 0 : keyVar.capacity();
    try {
      for (index = 0; index < numRecords; index++, outputAddr += 4) {
        final int keyIndex = SV2UnsignedUtil.readAtIndex(sv2, index) - pivotShift;
        assert keyIndex >= 0;

        final int keyHash = PlatformDependent.getInt(tableHashAddr4B + keyIndex * 4);
        PlatformDependent.putInt(
            outputAddr,
            add(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
      }
    } catch (OutOfMemoryException | HashTableMaxCapacityReachedException ex) {
      logger.debug(
          String.format(
              "%s when trying to insert %d records (%d records were successfully inserted)",
              ex.getClass().getSimpleName(), numRecords, index));
    } catch (Exception ex) {
      logger.error(
          String.format(
              "An unexpected error was thrown when trying to insert %d records (%d records were successfully inserted)",
              numRecords, index),
          ex);
    }

    return index;
  }

  /**
   * Find a key.
   *
   * @param keyFixedVectorAddr starting address of fixed vector block
   * @param keyVarVectorAddr starting address of variable vector block
   * @param keyVarVectorSize size of variable vector block
   * @param keyIndex record #
   * @param keyHash hashvalue (hashing is external to the hash table)
   * @return ordinal if the key exists, -1 otherwise This function is used by {@link
   *     com.dremio.sabot.op.join.vhash.VectorizedHashJoinOperator} since the operator has a probe
   *     only phase in which it only searches for matching keys
   */
  public int find(
      final long keyFixedVectorAddr,
      final long keyVarVectorAddr,
      long keyVarVectorSize,
      final int keyIndex,
      final int keyHash) {
    return getOrInsert(
        keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash, false);
  }

  /**
   * Search for a vector of keys.
   *
   * @param numRecords number of records to search
   * @param keyFixed buffer holding fixed portion of keys
   * @param keyVar buffer holding variable portion of keys
   * @param hash8B buffer holding 8-byte hash values
   * @param outOrdinals hash-table ordinals (output)
   */
  @Override
  public void find(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, ArrowBuf hash8B, ArrowBuf outOrdinals) {
    NullComparator.Mode mode =
        nullComparator == null ? NullComparator.Mode.NONE : nullComparator.getMode();

    keyFixed.checkBytes(0, numRecords * pivot.getBlockWidth());
    hash8B.checkBytes(0, numRecords * 8);
    outOrdinals.checkBytes(0, numRecords * ORDINAL_SIZE);

    final int blockWidth = pivot.getBlockWidth();
    long bitsAddr = keyFixed.memoryAddress();
    long outputAddr = outOrdinals.memoryAddress();
    long hashVectorAddr8B = hash8B.memoryAddress();
    final long keyFixedVectorAddr = keyFixed.memoryAddress();
    final long keyVarVectorAddr = keyVar == null ? 0 : keyVar.memoryAddress();
    final long keyVarVectorSize = keyVar == null ? 0 : keyVar.capacity();

    switch (mode) {
      default:
      case NONE:
        for (int keyIndex = 0;
            keyIndex < numRecords;
            keyIndex++, outputAddr += 4, hashVectorAddr8B += 8) {
          final int keyHash = (int) PlatformDependent.getLong(hashVectorAddr8B);
          PlatformDependent.putInt(
              outputAddr,
              find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
        }
        break;

      case FOUR:
        int four = nullComparator.getFour();
        for (int keyIndex = 0;
            keyIndex < numRecords;
            keyIndex++, outputAddr += 4, hashVectorAddr8B += 8, bitsAddr += blockWidth) {
          if ((PlatformDependent.getInt(bitsAddr) & four) == four) {
            final int keyHash = (int) PlatformDependent.getLong(hashVectorAddr8B);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;

      case EIGHT:
        long eight = nullComparator.getEight();
        for (int keyIndex = 0;
            keyIndex < numRecords;
            keyIndex++, outputAddr += 4, hashVectorAddr8B += 8, bitsAddr += blockWidth) {
          if ((PlatformDependent.getLong(bitsAddr) & eight) == eight) {
            final int keyHash = (int) PlatformDependent.getLong(hashVectorAddr8B);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;

      case BIG:
        for (int keyIndex = 0;
            keyIndex < numRecords;
            keyIndex++, outputAddr += 4, hashVectorAddr8B += 8, bitsAddr += blockWidth) {
          if (nullComparator.isComparableBigBits(bitsAddr)) {
            final int keyHash = (int) PlatformDependent.getLong(hashVectorAddr8B);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;
    }
  }

  /**
   * Search for a vector of keys.
   *
   * @param sv2 sv2 of valid ordinals
   * @param pivotShift shift to be deducted to get the corresponding entry in pivot block
   * @param numRecords number of records to search
   * @param keyFixed buffer holding fixed portion of keys
   * @param keyVar buffer holding variable portion of keys
   * @param hash4B buffer holding 4-byte hash values
   * @param outOrdinals hash-table ordinals (output)
   */
  @Override
  public void findSv2(
      ArrowBuf sv2,
      int pivotShift,
      int numRecords,
      ArrowBuf keyFixed,
      ArrowBuf keyVar,
      ArrowBuf hash4B,
      ArrowBuf outOrdinals) {
    NullComparator.Mode mode =
        nullComparator == null ? NullComparator.Mode.NONE : nullComparator.getMode();

    keyFixed.checkBytes(0, numRecords * pivot.getBlockWidth());
    hash4B.checkBytes(0, numRecords * 4);
    outOrdinals.checkBytes(0, numRecords * ORDINAL_SIZE);

    final int blockWidth = pivot.getBlockWidth();
    long outputAddr = outOrdinals.memoryAddress();
    long hashVectorAddr4B = hash4B.memoryAddress();
    final long keyFixedVectorAddr = keyFixed.memoryAddress();
    final long keyVarVectorAddr = keyVar == null ? 0 : keyVar.memoryAddress();
    final long keyVarVectorSize = keyVar == null ? 0 : keyVar.capacity();

    switch (mode) {
      default:
      case NONE:
        for (int i = 0; i < numRecords; i++, outputAddr += 4) {
          final int keyIndex = SV2UnsignedUtil.readAtIndex(sv2, i) - pivotShift;
          assert keyIndex >= 0;
          final int keyHash = PlatformDependent.getInt(hashVectorAddr4B + keyIndex * 4);
          PlatformDependent.putInt(
              outputAddr,
              find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
        }
        break;

      case FOUR:
        int four = nullComparator.getFour();
        for (int i = 0; i < numRecords; i++, outputAddr += 4) {
          final int keyIndex = SV2UnsignedUtil.readAtIndex(sv2, i) - pivotShift;
          assert keyIndex >= 0;
          final long bitsAddr = keyFixedVectorAddr + keyIndex * blockWidth;
          if ((PlatformDependent.getInt(bitsAddr) & four) == four) {
            final int keyHash = PlatformDependent.getInt(hashVectorAddr4B + keyIndex * 4);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;

      case EIGHT:
        long eight = nullComparator.getEight();
        for (int i = 0; i < numRecords; i++, outputAddr += 4) {
          final int keyIndex = SV2UnsignedUtil.readAtIndex(sv2, i) - pivotShift;
          assert keyIndex >= 0;
          final long bitsAddr = keyFixedVectorAddr + keyIndex * blockWidth;
          if ((PlatformDependent.getLong(bitsAddr) & eight) == eight) {
            final int keyHash = PlatformDependent.getInt(hashVectorAddr4B + keyIndex * 4);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;

      case BIG:
        for (int i = 0; i < numRecords; i++, outputAddr += 4) {
          final int keyIndex = SV2UnsignedUtil.readAtIndex(sv2, i) - pivotShift;
          assert keyIndex >= 0;
          final long bitsAddr = keyFixedVectorAddr + keyIndex * blockWidth;
          if (nullComparator.isComparableBigBits(bitsAddr)) {
            final int keyHash = PlatformDependent.getInt(hashVectorAddr4B + keyIndex * 4);
            PlatformDependent.putInt(
                outputAddr,
                find(keyFixedVectorAddr, keyVarVectorAddr, keyVarVectorSize, keyIndex, keyHash));
          } else {
            // the nulls are not comparable. as such, this doesn't match.
            PlatformDependent.putInt(outputAddr, SKIP);
          }
        }
        break;
    }
  }

  // TODO: we need to fix the hashjoin operator code to pass addresses directly pointing
  // to records in pivot buffers and then we can remove this method. right now it is
  // passing starting address of pivot buffers and the hash table has to repeat
  // the pointer movement in this method whereas it is something already done by the
  // operator while computing the hash. once hashjoin code is fixed like hashagg,
  // we can remove this along with add() and find() methods and directly call
  // getOrInsertWithRetry with pointers to records inside the pivot buffers, length of record etc
  private int getOrInsert(
      final long keyFixedVectorAddr,
      final long keyVarVectorAddr,
      long keyVarVectorSize,
      final int keyIndex,
      final int keyHash,
      boolean insertNew) {
    final int blockWidth = pivot.getBlockWidth();
    final long keyFixedAddr = keyFixedVectorAddr + (blockWidth * keyIndex);
    final long keyVarAddr;
    final int keyVarLen;
    final int dataWidth;

    if (fixedOnly) {
      dataWidth = blockWidth;
      keyVarAddr = -1;
      keyVarLen = 0;
    } else {
      dataWidth = blockWidth - VAR_OFFSET_SIZE;
      long keyVarOffset = PlatformDependent.getInt(keyFixedAddr + dataWidth);
      keyVarAddr = keyVarVectorAddr + keyVarOffset;
      keyVarLen = PlatformDependent.getInt(keyVarAddr);
      // check bound of the var section
      Preconditions.checkState(keyVarOffset + VAR_OFFSET_SIZE + keyVarLen <= keyVarVectorSize);
    }

    return getOrInsertWithRetry(keyFixedAddr, keyVarAddr, keyVarLen, keyHash, dataWidth, insertNew);
  }

  /**
   * {@link com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggOperator} directly calls this
   * method to pass addresses pointing directly to the record in pivot buffers. Since the operator
   * computes the hash and passes to hash table, we don't want hash table to repeat some computation
   * like advancing the pointer in fixed and variable vector buffers to point to the new record in
   * the pivot buffer etc. Since this is something that operator already did while computing the
   * hash, we skip repeating all that work by directly calling into this method.
   *
   * @param keyFixedAddr pointer to record in fixed key buffer
   * @param keyVarAddr pointer to record in variable key buffer
   * @param keyVarLen length of variable key
   * @param keyHash 32 bit hash
   * @param dataWidth width of data in fixed key buffer
   * @return for find operation (ordinal if the key exists, -1 otherwise) for add operation (ordinal
   *     if the key exists or new ordinal after insertion)
   */
  public int getOrInsertWithRetry(
      final long keyFixedAddr,
      final long keyVarAddr,
      final int keyVarLen,
      final int keyHash,
      final int dataWidth,
      final boolean insertNew) {
    int returnValue;
    int iters = 0;

    do {
      Preconditions.checkArgument(iters < 2);
      returnValue =
          probeOrInsert(keyFixedAddr, keyVarAddr, keyVarLen, keyHash, dataWidth, insertNew, 0);
      iters++;
    } while (returnValue == RETRY_RETURN_CODE);

    return returnValue;
  }

  @Override
  public int getOrInsertWithAccumSpaceCheck(
      final long keyFixedAddr,
      final long keyVarAddr,
      final int keyVarLen,
      final int keyHash,
      final int dataWidth,
      final long seed) {
    int returnValue;

    do {
      /*
       * In worst case, we may splice every batch. Additionally additional retry
       * due to rehash.  BTW, no guarantee how many times a single batch can splice.
       */
      returnValue =
          probeOrInsert(keyFixedAddr, keyVarAddr, keyVarLen, keyHash, dataWidth, true, seed);
    } while (returnValue == RETRY_RETURN_CODE);

    return returnValue;
  }

  /**
   * Helper method for inserting/searching the hash table. For a given key, it first searches
   * (linear probing) the hash table If the key is not found and the caller indicates that the new
   * key has to be added, this function inserts the given key
   *
   * @param keyFixedAddr pointer to record in fixed key buffer
   * @param keyVarAddr pointer to record in variable key buffer
   * @param keyVarLen length of variable key
   * @param keyHash 32 bit hash
   * @param dataWidth width of data in fixed key buffer
   * @return for find operation (ordinal if the key exists, -1 otherwise) for add operation (ordinal
   *     if the key exists or new ordinal after insertion or -2 if the caller should retry)
   */
  private int probeOrInsert(
      final long keyFixedAddr,
      final long keyVarAddr,
      final int keyVarLen,
      final int keyHash,
      final int dataWidth,
      final boolean insertNew,
      final long seed) {
    final boolean fixedOnly = this.fixedOnly;
    final int blockWidth = pivot.getBlockWidth();
    final long[] tableControlAddresses = this.tableControlAddresses;
    final long[] tableFixedAddresses = this.tableFixedAddresses;
    final long[] initVariableAddresses = this.initVariableAddresses;
    long tableControlAddr;

    // start with a hash index.
    int controlIndex = keyHash & (capacity - 1);

    while (true) {
      int controlChunkIndex = getBatchIndexForOrdinal(controlIndex);
      int offsetInChunk = controlIndex & CHUNK_OFFSET_MASK;
      tableControlAddr = tableControlAddresses[controlChunkIndex] + (offsetInChunk * CONTROL_WIDTH);
      long control = PlatformDependent.getLong(tableControlAddr);
      if (control == LFREE) {
        break;
      }

      int ordinal = (int) control;
      if (keyHash == (int) (control >>> 32)) {
        int dataChunkIndex = getBatchIndexForOrdinal(ordinal);
        offsetInChunk = ordinal & CHUNK_OFFSET_MASK;
        long tableDataAddr = tableFixedAddresses[dataChunkIndex] + (offsetInChunk * blockWidth);
        if (fixedKeyEquals(keyFixedAddr, tableDataAddr, dataWidth)
            && (fixedOnly
                || variableKeyEquals(
                    keyVarAddr,
                    initVariableAddresses[dataChunkIndex]
                        + PlatformDependent.getInt(tableDataAddr + dataWidth),
                    keyVarLen))) {
          if (!insertNew
              || spaceCheckListener.resizeListenerHasSpace(
                  resizeListener, dataChunkIndex, offsetInChunk, seed)) {
            return ordinal;
          }
          /* Space check have failed. Batches must have rehashed so retry again. */
          return RETRY_RETURN_CODE;
        }
      }

      controlIndex = (controlIndex - 1) & (capacity - 1);
    }

    // key not found
    if (!insertNew) {
      return -1;
    }
    // caller wants to add key so insert
    return insert(
        blockWidth,
        tableControlAddr,
        keyHash,
        dataWidth,
        keyFixedAddr,
        keyVarAddr,
        keyVarLen,
        seed);
  }

  // Get the length of the variable keys for the specified ordinals.
  @Override
  public int getCumulativeVarKeyLength(ArrowBuf ordinals, int numRecords) {
    if (fixedOnly) {
      return 0;
    } else {
      ordinals.checkBytes(0, numRecords * ORDINAL_SIZE);
      int size = 0;
      long maxAddr = ordinals.memoryAddress() + ORDINAL_SIZE * numRecords;
      for (long curAddr = ordinals.memoryAddress(); curAddr < maxAddr; curAddr += ORDINAL_SIZE) {
        final int ordinal = PlatformDependent.getInt(curAddr);
        size += getVarKeyLengthForOrdinal(ordinal);
      }
      return size;
    }
  }

  @Override
  public void getVarKeyLengths(ArrowBuf ordinals, int numRecords, ArrowBuf outLengths) {
    ordinals.checkBytes(0, numRecords * ORDINAL_SIZE);
    outLengths.checkBytes(0, numRecords * VAR_LENGTH_SIZE);

    final long ordinalsAddr = ordinals.memoryAddress();
    final long outAddr = outLengths.memoryAddress();
    if (fixedOnly) {
      for (int i = 0; i < numRecords; ++i) {
        PlatformDependent.putInt(outAddr + (i * VAR_LENGTH_SIZE), 0);
      }
    } else {
      for (int i = 0; i < numRecords; ++i) {
        final int ordinal = PlatformDependent.getInt(ordinalsAddr + i * ORDINAL_SIZE);
        final int size = getVarKeyLengthForOrdinal(ordinal);
        PlatformDependent.putInt(outAddr + i * VAR_LENGTH_SIZE, size);
      }
    }
  }

  private int getVarKeyLengthForOrdinal(int ordinal) {
    final int blockWidth = pivot.getBlockWidth();
    final int dataChunkIndex = ordinal >>> BITS_IN_CHUNK;
    final int offsetInChunk = ordinal & CHUNK_OFFSET_MASK;
    final long tableVarOffsetAddr =
        tableFixedAddresses[dataChunkIndex]
            + (offsetInChunk * blockWidth)
            + blockWidth
            - VAR_OFFSET_SIZE;
    final int tableVarOffset = PlatformDependent.getInt(tableVarOffsetAddr);
    // VAR_LENGTH_SIZE is not added to varLen when pivot it in pivotVariableLengths method, so we
    // need to add it here
    return PlatformDependent.getInt(initVariableAddresses[dataChunkIndex] + tableVarOffset)
        + VAR_LENGTH_SIZE;
  }

  /**
   * Copy the keys of the records specified to destination memory (keyFixed/keyVar).
   *
   * @param ordinals buffer holding ordinals
   * @param count count of ordinals
   * @param keyFixed dest for fixed portions of keys
   * @param keyVar dest for variable portion of keys
   */
  @Override
  public void copyKeysToBuffer(
      ArrowBuf ordinals, final int count, ArrowBuf keyFixed, ArrowBuf keyVar) {
    ordinals.checkBytes(0, count * ORDINAL_SIZE);
    keyFixed.checkBytes(0, count * pivot.getBlockWidth());

    final long maxAddr = ordinals.memoryAddress() + count * ORDINAL_SIZE;
    final int blockWidth = pivot.getBlockWidth();
    long keyFixedAddr = keyFixed.memoryAddress();
    if (fixedOnly) {
      for (long ordinalAddr = ordinals.memoryAddress();
          ordinalAddr < maxAddr;
          ordinalAddr += ORDINAL_SIZE, keyFixedAddr += blockWidth) {
        // Copy the fixed key that is pivoted in Pivots.pivot
        final int ordinal = PlatformDependent.getInt(ordinalAddr);
        final int dataChunkIndex = ordinal >>> BITS_IN_CHUNK;
        final int offsetInChunk = ordinal & CHUNK_OFFSET_MASK;
        final long tableFixedAddr =
            tableFixedAddresses[dataChunkIndex] + (offsetInChunk * blockWidth);
        Copier.copy(tableFixedAddr, keyFixedAddr, blockWidth);
      }
    } else {
      int varOffset = 0;
      final long keyVarAddr = keyVar.memoryAddress();
      final long keyVarBufSize = keyVar.capacity();
      for (long ordinalAddr = ordinals.memoryAddress();
          ordinalAddr < maxAddr;
          ordinalAddr += ORDINAL_SIZE, keyFixedAddr += blockWidth) {
        // Copy the fixed keys that is pivoted in Pivots.pivot
        final int ordinal = PlatformDependent.getInt(ordinalAddr);
        final int dataChunkIndex = ordinal >>> BITS_IN_CHUNK;
        final int offsetInChunk = ordinal & CHUNK_OFFSET_MASK;
        final long tableFixedAddr =
            tableFixedAddresses[dataChunkIndex] + (offsetInChunk * blockWidth);
        Copier.copy(tableFixedAddr, keyFixedAddr, blockWidth - VAR_OFFSET_SIZE);
        // Update the variable offset of the key
        PlatformDependent.putInt(keyFixedAddr + blockWidth - VAR_OFFSET_SIZE, varOffset);

        // Copy the variable keys that is pivoted in Pivots.pivot
        final long tableVarOffsetAddr = tableFixedAddr + blockWidth - VAR_OFFSET_SIZE;
        final int tableVarOffset = PlatformDependent.getInt(tableVarOffsetAddr);
        final int varLen =
            PlatformDependent.getInt(initVariableAddresses[dataChunkIndex] + tableVarOffset)
                + VAR_LENGTH_SIZE;
        Preconditions.checkState(varOffset + varLen <= keyVarBufSize);
        Copier.copy(
            initVariableAddresses[dataChunkIndex] + tableVarOffset, keyVarAddr + varOffset, varLen);

        varOffset += varLen;
      }
    }
  }

  @Override
  public long getAllocatedForFixedBlocks() {
    return allocatedForFixedBlocks;
  }

  @Override
  public long getUnusedForFixedBlocks() {
    return unusedForFixedBlocks;
  }

  @Override
  public long getAllocatedForVarBlocks() {
    return allocatedForVarBlocks;
  }

  @Override
  public long getUnusedForVarBlocks() {
    return unusedForVarBlocks;
  }

  @Override
  public int getMaxHashTableBatchSize() {
    return ACTUAL_VALUES_PER_BATCH;
  }

  /**
   * Helper method that moves to the next available valid ordinal.
   *
   * @param keyVarLen
   * @return True if the table is resized.
   */
  private boolean moveToNextValidOrdinal(int keyVarLen) {
    // check if we need to resize the hash table
    if (currentOrdinal > maxSize) {
      tryRehashForExpansion();
      return true;
    }

    // check the value of currentOrdinal and decide if we need to add new block
    boolean addNewBlocks = false;
    if (currentOrdinal == maxOrdinalBeforeExpand) {
      // this condition covers all cases -- preallocated 0th block or not, using power of 2
      // batchsize or not
      // new blocks need to be added if currentOrdinal is a multiple of max number of
      // (ACTUAL_VALUES_PER_BATCH) we can store within a single batch of hash table
      addNewBlocks = true;
    }

    if (addNewBlocks) {
      final int oldChunkIndex = getBatchIndexForOrdinal(currentOrdinal);

      // need to add new blocks
      if (ACTUAL_VALUES_PER_BATCH < MAX_VALUES_PER_BATCH && currentOrdinal > 0) {
        // skip ordinals for optimizing the use of direct memory if using a non power of two
        // batchsize
        // we fit only non power of 2 records within a hashtable block (and accumulator)
        // to work well with memory allocation strategies that are optimized for both heap and
        // direct
        // memory but require a non power of 2 value count in vectors
        final int currentChunkIndex = currentOrdinal >>> BITS_IN_CHUNK;
        final int newCurrentOrdinal = (currentChunkIndex + 1) * MAX_VALUES_PER_BATCH;

        // since we are moving to first ordinal in next batch, we need to first check for resize
        // before adding data blocks
        if (newCurrentOrdinal > maxSize) {
          tryRehashForExpansion();
          // don't move the current ordinal now, come back in retry, add data blocks, and move the
          // currentOrdinal
          return true;
        }

        // no need to resize, so add new blocks and proceed
        addDataBlocks();

        gaps += newCurrentOrdinal - currentOrdinal;

        // it is important that currentOrdinal is set only after successful return from
        // addDataBlocks()
        // as the latter can fail with OOM
        currentOrdinal = newCurrentOrdinal;
      } else {
        // if we are using power of 2 batch size then
        // no need to skip ordinals; there is no need to check for rehash
        // as currentOrdinal hasn't moved, just add the data blocks and proceed
        addDataBlocks();
      }

      /* bump these stats iff we are going to skip ordinals */
      final long curFixedBlockWritePos = fixedBlocks[oldChunkIndex].getBufferLength();
      unusedForFixedBlocks += fixedBlocks[oldChunkIndex].getCapacity() - curFixedBlockWritePos;
      final long curVarBlockWritePos = variableBlocks[oldChunkIndex].getBufferLength();
      unusedForVarBlocks += variableBlocks[oldChunkIndex].getCapacity() - curVarBlockWritePos;

      // if we are here, it means we have added a new block
      // track max ordinal for block addition in future
      maxOrdinalBeforeExpand = currentOrdinal + ACTUAL_VALUES_PER_BATCH;
    }

    if (fixedOnly) {
      // only using fixed width keys so we don't have to check
      // if ordinals should be skipped due to max limit on var block vector
      return false;
    }

    // Check if we can fit variable component in available space in current chunk
    // if remaining data in variable block vector is not enough for the key
    // we are trying to insert, we need to skip ordinals and move to the first
    // ordinal in next block
    final int currentChunkIndex = currentOrdinal >>> BITS_IN_CHUNK;
    long tableVarAddr = openVariableAddresses[currentChunkIndex];
    final long tableMaxVarAddr = maxVariableAddresses[currentChunkIndex];
    if (tableMaxVarAddr - tableVarAddr >= keyVarLen + VAR_LENGTH_SIZE) {
      // there is enough space to insert the next varchar key so don't skip
      return false;
    }

    Preconditions.checkState(!addNewBlocks, "Error: detected inconsistent state ");

    // skip ordinals to fix this varchar key in next block; move to first ordinal in next batch
    int newCurrentOrdinal = (currentChunkIndex + 1) * MAX_VALUES_PER_BATCH;

    // since we are moving to first ordinal in next batch, we need to first check for resize
    if (newCurrentOrdinal > maxSize) {
      tryRehashForExpansion();
      // don't move the current ordinal now, come back in retry, add data blocks, and move the
      // currentOrdinal
      return true;
    }

    // do sanity check
    addDataBlocks();

    /* bump these stats iff we are going to skip ordinals */
    final long curFixedBlockWritePos = fixedBlocks[currentChunkIndex].getBufferLength();
    final long curVarBlockWritePos = variableBlocks[currentChunkIndex].getBufferLength();
    unusedForFixedBlocks += fixedBlocks[currentChunkIndex].getCapacity() - curFixedBlockWritePos;
    unusedForVarBlocks += variableBlocks[currentChunkIndex].getCapacity() - curVarBlockWritePos;
    gaps += newCurrentOrdinal - currentOrdinal;

    currentOrdinal = newCurrentOrdinal;
    maxOrdinalBeforeExpand = currentOrdinal + ACTUAL_VALUES_PER_BATCH;
    return false;
  }

  private boolean checkForRehashAndNewBlocks() {
    if (currentOrdinal > maxSize) {
      tryRehashForExpansion();
      return true;
    }

    if ((currentOrdinal & CHUNK_OFFSET_MASK) == 0) {
      addDataBlocks();
    }

    return false;
  }

  private int insert(
      final int blockWidth,
      long tableControlAddr,
      final int keyHash,
      final int dataWidth,
      final long keyFixedAddr,
      final long keyVarAddr,
      final int keyVarLen,
      final long seed) {
    final boolean retry;
    if (enforceVarWidthBufferLimit) {
      retry = moveToNextValidOrdinal(keyVarLen);
    } else {
      retry = checkForRehashAndNewBlocks();
    }
    if (retry) {
      // If the table is resized, we need to start search from beginning as in the new table this
      // entry is mapped to
      // different control address which is determined by the caller of this method.
      return RETRY_RETURN_CODE;
    }

    final int insertedOrdinal = currentOrdinal;

    // first we need to make sure we are up to date on the
    final int dataChunkIndex = insertedOrdinal >>> BITS_IN_CHUNK;
    final int offsetInChunk = insertedOrdinal & CHUNK_OFFSET_MASK;

    if (!spaceCheckListener.resizeListenerHasSpace(
        resizeListener, dataChunkIndex, offsetInChunk, seed)) {
      /* Space check has failed. Batches must have rehashed so retry again. */
      return RETRY_RETURN_CODE;
    }

    final long tableDataAddr = tableFixedAddresses[dataChunkIndex] + (offsetInChunk * blockWidth);

    // set the ordinal value for the insertion.
    PlatformDependent.putInt(tableControlAddr, insertedOrdinal);
    PlatformDependent.putInt(tableControlAddr + 4, keyHash);

    // set the body part of the fixed data in the table.
    Copier.copy(keyFixedAddr, tableDataAddr, dataWidth);
    final ArrowBuf fixedBlockBuffer = fixedBlocks[dataChunkIndex].getUnderlying();
    fixedBlockBuffer.writerIndex(fixedBlockBuffer.writerIndex() + dataWidth);

    if (!fixedOnly) {
      long tableVarAddr = openVariableAddresses[dataChunkIndex];
      final VariableBlockVector block = variableBlocks[dataChunkIndex];
      final int tableVarOffset = (int) (tableVarAddr - initVariableAddresses[dataChunkIndex]);

      // set the var offset in the fixed position at the variable info offset.
      PlatformDependent.putInt(tableDataAddr + dataWidth, tableVarOffset);
      fixedBlockBuffer.writerIndex(fixedBlockBuffer.writerIndex() + VAR_OFFSET_SIZE);

      /* we should be considering to expand the buffer only if we have not enforced the constraint
       * on max size of variable block vector. if we are working (true by default) under the
       * max size constraint then moveToNextValidOrdinal() above would have taken
       * care of checking whether the max size is exhausted and if a new block needs
       * to be allocated. accordingly gaps would have been created in ordinals
       */
      if (!enforceVarWidthBufferLimit
          && maxVariableAddresses[dataChunkIndex] < tableVarAddr + keyVarLen + VAR_LENGTH_SIZE) {
        block.ensureAvailableDataSpace(tableVarOffset + keyVarLen + VAR_LENGTH_SIZE);
        tableVarAddr = block.getMemoryAddress() + tableVarOffset;
        this.initVariableAddresses[dataChunkIndex] = block.getMemoryAddress();
        this.openVariableAddresses[dataChunkIndex] = block.getMemoryAddress() + tableVarOffset;
        maxVariableAddresses[dataChunkIndex] = block.getMaxMemoryAddress();
      }

      // copy variable data.
      int size = keyVarLen + VAR_LENGTH_SIZE;
      Copier.copy(keyVarAddr, tableVarAddr, size);
      this.openVariableAddresses[dataChunkIndex] += size;
      final ArrowBuf variableBlockBuffer = variableBlocks[dataChunkIndex].getUnderlying();
      variableBlockBuffer.writerIndex(variableBlockBuffer.writerIndex() + size);
    }

    currentOrdinal++;

    return insertedOrdinal;
  }

  /**
   * Get number of records in hashtable batch. We just need to look at fixed block buffer to count
   * the number of records since both fixed block and variable block buffers run paralelly (not in
   * terms of length) but in terms of rows (pivoted records) they store.
   *
   * <p>A row in fixed block buffer is equal to block width (validity, all fixed columns, var
   * offset) and upon insertion of every new entry into hash table, we bump the writer index in both
   * blocks. So readableBytes in buffer along with block width gives the exact count of records
   * inserted in a particular hash table batch.
   *
   * @param batchIndex hash table batch/block/chunk index
   * @return number of records in batch
   */
  @Override
  public int getNumRecordsInBlock(final int batchIndex) {
    Preconditions.checkArgument(batchIndex < blocks(), "Error: invalid batch index");
    /* If blockWidth == 0, there will be only one entry */
    if (pivot.getBlockWidth() == 0) {
      Preconditions.checkState(fixedBlocks[batchIndex].getUnderlying().readableBytes() == 0);
      Preconditions.checkState(relativeSize() == 1 || relativeSize() == 0);
      return relativeSize();
    }
    final int records =
        LargeMemoryUtil.checkedCastToInt(
            (fixedBlocks[batchIndex].getUnderlying().readableBytes()) / pivot.getBlockWidth());
    Preconditions.checkArgument(
        records <= MAX_VALUES_PER_BATCH, "Error: detected invalid number of records in batch");
    return records;
  }

  @Override
  public int[] getRecordsInBlocks() {
    int numBlocks = blocks();
    if (numBlocks == 0) {
      return new int[0];
    }
    int[] records = new int[numBlocks];
    for (int batchIndex = 0; batchIndex < numBlocks; batchIndex++) {
      records[batchIndex] = getNumRecordsInBlock(batchIndex);
    }
    return records;
  }

  /**
   * Get underlying buffers storing hash table data for fixed width key column(s) Note that we don't
   * account for control block buffers here since the main purpose of this method is to get the data
   * block buffers for serializing primarily.
   *
   * @return list of ArrowBufs for hash table's fixed data blocks.
   */
  @Override
  public List<ArrowBuf> getFixedBlockBuffers() {
    final int blocks = blocks();
    final List<ArrowBuf> blockBuffers = new ArrayList<>(blocks);
    for (int i = 0; i < blocks; i++) {
      final ArrowBuf buffer = fixedBlocks[i].getUnderlying();
      blockBuffers.add(buffer);
    }
    return blockBuffers;
  }

  /**
   * Get underlying buffers storing hash table data for variable width key columns. Note that we
   * don't account for control block buffers here since the main purpose of this method is to get
   * the data block buffers for serializing primarily.
   *
   * @return list of ArrowBufs for hash table's variable data blocks.
   */
  @Override
  public List<ArrowBuf> getVariableBlockBuffers() {
    final int blocks = blocks();
    final List<ArrowBuf> blockBuffers = new ArrayList<>(blocks);
    for (int i = 0; i < blocks; i++) {
      final ArrowBuf buffer = variableBlocks[i].getUnderlying();
      blockBuffers.add(buffer);
    }
    return blockBuffers;
  }

  @Override
  public List<ArrowBuf> getKeyBuffers(int batchIdx) {
    int numBatches = blocks();
    Preconditions.checkArgument(
        batchIdx <= numBatches,
        "Request batch index %s is greater than " + "number of batches %s in the hash table",
        batchIdx,
        numBatches);
    List<ArrowBuf> spillBuffers = new ArrayList<>();
    ArrowBuf arrowBuf = fixedBlocks[batchIdx].getUnderlying();
    spillBuffers.add(arrowBuf);
    arrowBuf = variableBlocks[batchIdx].getUnderlying();
    spillBuffers.add(arrowBuf);
    return spillBuffers;
  }

  @Override
  public int getNumChunks() {
    return blocks();
  }

  /**
   * Get the size of hash table structure in bytes. This peeks at ArrowBuf for each internal
   * structure (fixed blocks, variable blocks, control blocks) and gets the total size of buffers in
   * bytes.
   *
   * @return hash table size (in bytes).
   */
  @Override
  public long getSizeInBytes() {
    if (size() == 0) {
      /* hash table is empty if currentOrdinal is 0. as long as there is at least
       * one record inserted into the hashtable, currentOrdinal is >= 1
       */
      return 0;
    }
    final long sizeOfKeyBlocks = getKeyBlockSizeInBytes();
    final long sizeOfControlBlock = CONTROL_WIDTH * MAX_VALUES_PER_BATCH * blocks();
    return sizeOfKeyBlocks + sizeOfControlBlock;
  }

  /**
   * Compute the size of fixed vector block and variable block size (in bytes) for all batches of
   * data inserted into the hash table.
   *
   * @return total size (in bytes) of fixed and variable blocks in the hash table.
   */
  private long getKeyBlockSizeInBytes() {
    final int blocks = blocks();
    long totalFixedBlockSize = 0;
    long totalVariableBlockSize = 0;
    for (int i = 0; i < blocks; i++) {
      totalFixedBlockSize += fixedBlocks[i].getUnderlying().readableBytes();
      totalVariableBlockSize += variableBlocks[i].getUnderlying().readableBytes();
    }
    logger.debug(
        "Hash table blocks: {}, total size of fixed blocks: {}, total size of var blocks: {}",
        blocks,
        totalFixedBlockSize,
        totalVariableBlockSize);
    return totalFixedBlockSize + totalVariableBlockSize;
  }

  /**
   * Add a new data block (batch) to the hashtable (and accumulator). Memory allocation is needed
   * for the following things:
   *
   * <p>(1) Add new {@link FixedBlockVector} to array of fixed blocks. (2) Add new {@link
   * VariableBlockVector} to array of variable blocks. (3) Add new {@link
   * org.apache.arrow.vector.FieldVector} as a new target vector for _each_ accumulator inside
   * resizeListener, i.e {@link com.dremio.sabot.op.aggregate.vectorized.AccumulatorSet}.
   *
   * <p>All of the above operations have to be done in a single transaction as one atomic unit of
   * work. This allows us to handle OutOfMemory situations without creating any inconsistent state
   * of data structures.
   */
  private void addDataBlocks() {
    Preconditions.checkArgument(
        fixedBlocks.length == variableBlocks.length,
        "Error: detected inconsistent state in hashtable before batch extention");
    resizeListener.verifyBatchCount(fixedBlocks.length);

    final long currentAllocatedMemory = allocator.getAllocatedMemory();
    int oldBlocksLength = fixedBlocks.length;

    try (RollbackCloseable rollbackable = new RollbackCloseable()) {
      FixedBlockVector newFixed;
      VariableBlockVector newVariable;

      /* add new target accumulator vector to each accumulator.
       * since this is an array based allocation, we can fail in the middle
       * later on we revert on each accumulator and that might be a NO-OP
       * for some accumulators
       */
      resizeListener.addBatch();

      /* if the above operation was successful and we fail anywhere in the following
       * operations then we need to revert memory allocation on accumulator (all accumulators
       * in NestedAccumulator)
       */
      newFixed = new FixedBlockVector(allocator, pivot.getBlockWidth());
      /* no need to rollback explicitly */
      rollbackable.add(newFixed);
      newFixed.ensureAvailableBlocks(MAX_VALUES_PER_BATCH);

      /* if we fail while allocating memory in above step, the state of fixed block array is still
       * consistent so we don't have to revert anything. */
      newVariable = new VariableBlockVector(allocator, pivot.getVariableCount());
      /* no need to rollback explicitly */
      rollbackable.add(newVariable);
      newVariable.ensureAvailableDataSpace(variableBlockMaxLength);
      /* if we fail while allocating memory in above step, the state of variable block array is still
       * consistent so we don't have to revert anything. */

      /* Now all memory allocations succeeded */

      /* Commit all accumulators */
      resizeListener.commitResize();

      fixedBlocks = ObjectArrays.concat(fixedBlocks, newFixed);
      tableFixedAddresses =
          Longs.concat(tableFixedAddresses, new long[] {newFixed.getMemoryAddress()});

      variableBlocks = ObjectArrays.concat(variableBlocks, newVariable);
      initVariableAddresses =
          Longs.concat(initVariableAddresses, new long[] {newVariable.getMemoryAddress()});
      openVariableAddresses =
          Longs.concat(openVariableAddresses, new long[] {newVariable.getMemoryAddress()});
      maxVariableAddresses =
          Longs.concat(maxVariableAddresses, new long[] {newVariable.getMaxMemoryAddress()});

      rollbackable.commit();

      /* bump these stats only after all new allocations have been successful as otherwise we would revert everything */
      allocatedForFixedBlocks += newFixed.getCapacity();
      allocatedForVarBlocks += newVariable.getCapacity();

      Preconditions.checkArgument(
          fixedBlocks.length == oldBlocksLength + 1,
          "Error: detected inconsistent fixedBlocks in hashtable after successful batch extention");
      Preconditions.checkArgument(
          fixedBlocks.length == variableBlocks.length,
          "Error: detected inconsistent variableBlocks in hashtable after successful batch extention");
      resizeListener.verifyBatchCount(fixedBlocks.length);
    } catch (Exception e) {
      logger.debug("ERROR: failed to add data blocks, exception: ", e);
      /* explicitly rollback resizing operations on NestedAccumulator */
      resizeListener.revertResize();
      /* do sanity checking on the state of all data structures after
       * memory allocation failed and we rollbacked. this helps in proactively detecting
       * potential IndexOutOfBoundsException and seg faults due to inconsistent state across
       * data structures.
       */
      Preconditions.checkArgument(
          fixedBlocks.length == oldBlocksLength,
          "Error: detected inconsistent fixedBlocks in hashtable after unsuccessful batch extention");
      Preconditions.checkArgument(
          fixedBlocks.length == variableBlocks.length,
          "Error: detected inconsistent variableBlocks in hashtable after unsuccessful batch extention");
      resizeListener.verifyBatchCount(fixedBlocks.length);

      /* at this point we are as good as no memory allocation was ever attempted */
      Preconditions.checkArgument(
          allocator.getAllocatedMemory() == currentAllocatedMemory,
          "Error: detected inconsistent state of allocated memory");
      /* VectorizedHashAggOperator inserts data into hashtable and will handle (if OOM) this exception */
      throw Throwables.propagate(e);
    }
  }

  private void rehash(int newCapacity) {
    // grab old references.
    final ControlBlock[] oldControlBlocks = this.controlBlocks;
    final long[] oldControlAddrs = this.tableControlAddresses;

    try {
      /* this is the only step that allocates memory during rehash, if the method fails the state is unchanged */
      internalInit(newCapacity);

      final long[] controlAddrs = this.tableControlAddresses;

      // loop through backwards.

      final int capacity = this.capacity;
      for (int batch = 0; batch < oldControlAddrs.length; batch++) {
        long addr = oldControlAddrs[batch];
        final long max = addr + MAX_VALUES_PER_BATCH * CONTROL_WIDTH;
        for (long oldControlAddr = addr; oldControlAddr < max; oldControlAddr += CONTROL_WIDTH) {
          long oldControl = PlatformDependent.getLong(oldControlAddr);

          if (oldControl != LFREE) {
            int index =
                ((int) (oldControl >>> 32))
                    & (capacity - 1); // get previously computed hash and slice it.
            int newChunkIndex = index >>> BITS_IN_CHUNK;
            int offetInChunk = index & CHUNK_OFFSET_MASK;
            long controlAddr = controlAddrs[newChunkIndex] + (offetInChunk * CONTROL_WIDTH);
            if (PlatformDependent.getInt(controlAddr) != FREE) {
              while (true) {
                index = (index - 1) & (capacity - 1);
                newChunkIndex = index >>> BITS_IN_CHUNK;
                offetInChunk = index & CHUNK_OFFSET_MASK;
                controlAddr = controlAddrs[newChunkIndex] + (offetInChunk * CONTROL_WIDTH);
                if (PlatformDependent.getInt(controlAddr) == FREE) {
                  break;
                }
              }
            }
            PlatformDependent.putLong(controlAddr, oldControl);
          }
        }
      }

      // Release existing control blocks only after rehashing is successful.
      AutoCloseables.close(asList(oldControlBlocks));
    } catch (Exception e) {
      logger.debug("ERROR: failed to rehash, exception: ", e);
      /* VectorizedHashAggOperator inserts data into hashtable  and will handle (if OOM) this exception */
      throw Throwables.propagate(e);
    }
  }

  private static boolean fixedKeyEquals(
      final long keyDataAddr, final long tableDataAddr, final int dataWidth) {
    return memEqual(keyDataAddr, tableDataAddr, dataWidth);
  }

  private static boolean variableKeyEquals(
      final long keyVarAddr, final long tableVarAddr, final int keyVarLength) {
    final int tableVarLength = PlatformDependent.getInt(tableVarAddr);
    return keyVarLength == tableVarLength
        && memEqual(keyVarAddr + VAR_LENGTH_SIZE, tableVarAddr + VAR_LENGTH_SIZE, keyVarLength);
  }

  private static boolean memEqual(final long laddr, final long raddr, int len) {
    int n = len;
    long lPos = laddr;
    long rPos = raddr;

    while (n > 7) {
      long leftLong = PlatformDependent.getLong(lPos);
      long rightLong = PlatformDependent.getLong(rPos);
      if (leftLong != rightLong) {
        return false;
      }
      lPos += 8;
      rPos += 8;
      n -= 8;
    }
    while (n > 3) {
      int leftInt = PlatformDependent.getInt(lPos);
      int rightInt = PlatformDependent.getInt(rPos);
      if (leftInt != rightInt) {
        return false;
      }
      lPos += 4;
      rPos += 4;
      n -= 4;
    }
    while (n-- != 0) {
      byte leftByte = PlatformDependent.getByte(lPos);
      byte rightByte = PlatformDependent.getByte(rPos);
      if (leftByte != rightByte) {
        return false;
      }
      lPos++;
      rPos++;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "BlockHashTable";
  }

  @Override
  public boolean equals(Object obj) {
    return this == obj;
  }

  @Override
  public int size() {
    /* Return the actual number of records are inserted */
    return relativeSize();
  }

  @Override
  public int getMaxOrdinal() {
    return currentOrdinal - 1;
  }

  public int relativeSize() {
    return currentOrdinal - gaps;
  }

  @Override
  public int blocks() {
    return (int) Math.ceil(currentOrdinal / (MAX_VALUES_PER_BATCH * 1.0d));
  }

  @Override
  public int capacity() {
    return capacity;
  }

  @VisibleForTesting
  public int gaps() {
    return gaps;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(
        Streams.concat(
                Arrays.stream(controlBlocks),
                Arrays.stream(fixedBlocks),
                Arrays.stream(variableBlocks))
            .collect(ImmutableList.toImmutableList()));
    AutoCloseables.close(allocator);
  }

  private void tryRehashForExpansion() {
    int newCapacity = LHashCapacities.capacity(config, capacity(), false);
    if (newCapacity > capacity()) {
      try {
        rehashTimer.start();
        rehash(newCapacity);
        rehashCount++;
      } finally {
        rehashTimer.stop();
      }
    } else {
      throw new HashTableMaxCapacityReachedException(capacity());
    }
  }

  @Override
  public long getRehashTime(TimeUnit unit) {
    return rehashTimer.elapsed(unit);
  }

  @Override
  public long getSpliceTime(TimeUnit unit) {
    return spliceTimer.elapsed(unit);
  }

  @Override
  public int getRehashCount() {
    return rehashCount;
  }

  @Override
  public int getSpliceCount() {
    return spliceCount;
  }

  @Override
  public int getAccumCompactionCount() {
    return resizeListener.getAccumCompactionCount();
  }

  @Override
  public long getAccumCompactionTime(TimeUnit unit) {
    return resizeListener.getAccumCompactionTime(unit);
  }

  private void internalInit(int capacity) {
    /* capacity is power of 2 */
    if (capacity == 0) {
      return;
    }
    assert (capacity & (capacity - 1)) == 0;
    initTimer.start();
    /* tentative new state */
    capacity = Math.max(Numbers.nextPowerOfTwo(MAX_VALUES_PER_BATCH), capacity);
    final int newMaxSize =
        !LHashCapacities.isMaxCapacity(capacity, false) ? config.maxSize(capacity) : capacity - 1;
    final int newCtrlBatches = (int) Math.ceil(capacity / (MAX_VALUES_PER_BATCH * 1.0d));
    /* new memory allocation */
    final ControlBlock[] newControlBlocks = new ControlBlock[newCtrlBatches];
    final long[] newTableControlAddresses = new long[newCtrlBatches];
    try (RollbackCloseable rollbackable = new RollbackCloseable()) {
      /* if we fail while allocating a ControlBlock,
       * RollbackCloseable will take care of releasing memory allocated so far.
       * secondly, control block array, address array and corresponding
       * state is anyway unchanged since their state is updated only
       * after allocation for all control blocks is successful.
       */
      for (int i = 0; i < newCtrlBatches; i++) {
        newControlBlocks[i] = new ControlBlock(allocator, MAX_VALUES_PER_BATCH);
        rollbackable.add(newControlBlocks[i]);
        newTableControlAddresses[i] = newControlBlocks[i].getMemoryAddress();
        initControlBlock(newTableControlAddresses[i]);
      }

      /* memory allocation successful so update ControlBlock arrays and state */
      this.controlBlocks = newControlBlocks;
      this.tableControlAddresses = newTableControlAddresses;
      this.capacity = capacity;
      this.maxSize = newMaxSize;
      rollbackable.commit();
    } catch (Exception e) {
      /* will be propagated back to the operator */
      throw Throwables.propagate(e);
    } finally {
      initTimer.stop();
    }
  }

  // Compute the direct memory required for the one control block.
  public static int computePreAllocationForControlBlock(
      final int initialCapacity, final int hashTableBatchSize) {
    final HashConfigWrapper config = new HashConfigWrapper(HashConfig.getDefault());
    int capacity = LHashCapacities.capacity(config, initialCapacity, false);
    Preconditions.checkArgument(
        (capacity & (capacity - 1)) == 0, "hashtable capacity should be a power of 2");

    int maxValuesPerBatch = Numbers.nextPowerOfTwo(hashTableBatchSize);
    capacity = Math.max(maxValuesPerBatch, capacity);
    final int blocks = (int) Math.ceil(capacity / (maxValuesPerBatch * 1.0d));
    int controlBlockSize = (LBlockHashTable.CONTROL_WIDTH * maxValuesPerBatch * blocks);
    if (controlBlockSize < PAGE_SIZE) {
      controlBlockSize = PAGE_SIZE;
    }
    return controlBlockSize;
  }

  @Override
  public void unpivot(int startBatchIndex, int[] recordsInBatches) {
    Unpivots.unpivotBatches(
        pivot,
        Arrays.copyOfRange(fixedBlocks, startBatchIndex, startBatchIndex + recordsInBatches.length),
        Arrays.copyOfRange(
            variableBlocks, startBatchIndex, startBatchIndex + recordsInBatches.length),
        recordsInBatches);
  }

  @Override
  public CloseableIterator<HashTableKeyAddress> keyIterator() {
    return new HashTableKeyIterator();
  }

  private class HashTableKeyIterator implements CloseableIterator<HashTableKeyAddress> {
    private int scanOrdinal = 0;

    HashTableKeyIterator() {
      /*
       * Travese over the ordinals is done only by hashjoin, in which case, we make sure no gaps
       * in ordinals (and no enforceVarWidthBufferLimit, i.e variable buffer will be extended in
       * case original size does not fit for the entire batch)
       *
       * XXX: This need to be fixed if we ever need to traverse the oridinal with gaps (i.e for
       * HashAgg)
       */
      Preconditions.checkState(MAX_VALUES_PER_BATCH == ACTUAL_VALUES_PER_BATCH);
    }

    @Override
    public boolean hasNext() {
      if (scanOrdinal >= currentOrdinal) {
        return false;
      }
      return true;
    }

    @Override
    public HashTableKeyAddress next() {
      Preconditions.checkState(scanOrdinal < currentOrdinal);

      int dataChunkIndex = scanOrdinal >> BITS_IN_CHUNK;
      int offsetInChunk = scanOrdinal & CHUNK_OFFSET_MASK;
      long fixedKeyAddress =
          tableFixedAddresses[dataChunkIndex] + offsetInChunk * pivot.getBlockWidth();
      long varKeyAddress = 0;

      if (!fixedOnly) {
        varKeyAddress =
            initVariableAddresses[dataChunkIndex]
                + PlatformDependent.getInt(
                    fixedKeyAddress + pivot.getBlockWidth() - VAR_OFFSET_SIZE);
      }
      scanOrdinal++;
      return new HashTableKeyAddress(fixedKeyAddress, varKeyAddress);
    }

    @Override
    public void close() {}
  }

  // Tracing support
  // When tracing is started, the hashtable allocates an ArrowBuf that will contain the following
  // information:
  // 1. hashtable state before the insertion (recorded at {@link #traceStartInsert()})
  //       | int capacity | int maxSize | int batches | int currentOrdinal | int rehashCount | int
  // numRecords |
  //              4B             4B           4B                4B                4B
  // 4B
  //
  // 2. insertion record:
  //    - an int containing the count of insertions
  //       | int numInsertions |
  //               4B
  //    - an entry for each record being inserted into the hash table
  //       | match? |  batch# | ordinal-within-batch |      4 bytes
  //          1 bit    15 bits               16 bits
  // Please note that the batch# and ordinal-within-batch together form the hash table's
  // currentOrdinal
  //
  // 3. hashtable state after the insertion
  //    (same structure as #1 without numRecords)

  /** Start tracing the insert() operation of this table */
  @Override
  public void traceStart(int numRecords) {
    int numEntries = 5 * 2 + 1 + numRecords;
    traceBuf = allocator.buffer(numEntries * 4);
    traceBufNext = traceBuf.memoryAddress();
  }

  /** Stop tracing the insert(), and release any buffers that were allocated */
  @Override
  public void traceEnd() {
    traceBuf.close();
    traceBuf = null;
    traceBufNext = 0;
  }

  @Override
  public void traceOrdinals(final long indexes, final int numRecords) {
    if (traceBuf == null) {
      return;
    }
    PlatformDependent.copyMemory(indexes, traceBufNext, 4 * numRecords);
    traceBufNext += 4 * numRecords;
  }

  /** Start of insertion. Record the state before insertion */
  @Override
  public void traceInsertStart(int numRecords) {
    if (traceBuf == null) {
      return;
    }
    PlatformDependent.putInt(traceBufNext + 0 * 4, capacity);
    PlatformDependent.putInt(traceBufNext + 1 * 4, maxSize);
    PlatformDependent.putInt(traceBufNext + 2 * 4, blocks());
    PlatformDependent.putInt(traceBufNext + 3 * 4, currentOrdinal);
    PlatformDependent.putInt(traceBufNext + 4 * 4, rehashCount);
    PlatformDependent.putInt(traceBufNext + 5 * 4, numRecords);
    traceBufNext += 6 * 4;
  }

  /** End of insertion. Record the state after insertion */
  @Override
  public void traceInsertEnd() {
    if (traceBuf == null) {
      return;
    }
    PlatformDependent.putInt(traceBufNext + 0 * 4, capacity);
    PlatformDependent.putInt(traceBufNext + 1 * 4, maxSize);
    PlatformDependent.putInt(traceBufNext + 2 * 4, blocks());
    PlatformDependent.putInt(traceBufNext + 3 * 4, currentOrdinal);
    PlatformDependent.putInt(traceBufNext + 4 * 4, rehashCount);
    traceBufNext += 5 * 4;
  }

  /**
   * Report results from the tracing. Typically invoked when there was an error, since it generates
   * a boatload of log messages
   */
  @Override
  public String traceReport() {
    if (traceBuf == null) {
      return "";
    }

    long traceBufAddr = traceBuf.memoryAddress();
    int numEntries = PlatformDependent.getInt(traceBufAddr + 5 * 4);

    int reportSize =
        17 * numEntries
            + 1024; // it's really 16 bytes tops per entry, with a newline every 16 entries
    StringBuilder sb = new StringBuilder(reportSize);
    Formatter formatter = new Formatter(sb);
    int origOrdinal = PlatformDependent.getInt(traceBufAddr + 3 * 4) - 1;
    formatter.format(
        "Pre-insert: capacity: %1$d, maxSize: %2$d, batches: %3$d (%3$#X), currentOrdinal: %4$d, rehashCount: %5$d %n",
        PlatformDependent.getInt(traceBufAddr + 0 * 4),
        PlatformDependent.getInt(traceBufAddr + 1 * 4),
        PlatformDependent.getInt(traceBufAddr + 2 * 4),
        PlatformDependent.getInt(traceBufAddr + 3 * 4),
        PlatformDependent.getInt(traceBufAddr + 4 * 4));

    long traceBufCurr = traceBufAddr + 6 * 4; // skip the numEntries as well
    long traceBufLast = traceBufCurr + numEntries * 4;
    formatter.format("Number of entries: %1$d%n", numEntries);
    for (int i = 0; traceBufCurr < traceBufLast; traceBufCurr += 4, i++) {
      int traceValue = PlatformDependent.getInt(traceBufCurr);
      boolean isInsert = false;
      if (traceValue > origOrdinal) {
        isInsert = true;
        origOrdinal = traceValue;
      }
      formatter.format(
          "%1$c(%2$d,%3$d)",
          isInsert ? 'i' : 'm', (traceValue & 0xffff0000) >>> 16, (traceValue & 0x0000ffff));
      if ((i % 16) == 15) {
        formatter.format("%n");
      } else if (traceBufCurr < traceBufLast - 4) {
        formatter.format(", ");
      }
    }
    if ((numEntries % 16) != 0) {
      formatter.format("%n");
    }

    formatter.format(
        "Post-insert: capacity: %1$d, maxSize: %2$d, batches: %3$d (%3$#X), currentOrdinal: %4$d, rehashCount: %5$d %n",
        PlatformDependent.getInt(traceBufLast + 0 * 4),
        PlatformDependent.getInt(traceBufLast + 1 * 4),
        PlatformDependent.getInt(traceBufLast + 2 * 4),
        PlatformDependent.getInt(traceBufLast + 3 * 4),
        PlatformDependent.getInt(traceBufLast + 4 * 4));
    return sb.toString();
  }

  /**
   * Resets the HashTable to minimum size which has capacity to contain {@link
   * #MAX_VALUES_PER_BATCH}.
   */
  @Override
  public void resetToMinimumSize() throws Exception {
    final List<AutoCloseable> toRelease = Lists.newArrayList();

    Preconditions.checkArgument(fixedBlocks.length >= 1);
    /*
     * Even if fixedBlocks.length == 1, controlBlocks.length might not be 1 so
     * remove all controlBlocks as well as we are going to reset the capacy to minimum.
     */

    // Release all except the first entry
    toRelease.addAll(asList(copyOfRange(controlBlocks, 1, controlBlocks.length)));
    toRelease.addAll(asList(copyOfRange(fixedBlocks, 1, fixedBlocks.length)));
    toRelease.addAll(asList(copyOfRange(variableBlocks, 1, variableBlocks.length)));
    AutoCloseables.close(toRelease);

    controlBlocks = copyOfRange(controlBlocks, 0, 1);
    fixedBlocks = copyOfRange(fixedBlocks, 0, 1);
    variableBlocks = copyOfRange(variableBlocks, 0, 1);

    tableControlAddresses = copyOfRange(tableControlAddresses, 0, 1);
    tableFixedAddresses = copyOfRange(tableFixedAddresses, 0, 1);
    initVariableAddresses = copyOfRange(initVariableAddresses, 0, 1);
    openVariableAddresses = copyOfRange(openVariableAddresses, 0, 1);
    maxVariableAddresses = copyOfRange(maxVariableAddresses, 0, 1);

    resetToMinimumSizeHelper();
  }

  private void resetToMinimumSizeHelper() throws Exception {
    controlBlocks[0].reset();
    initControlBlock(tableControlAddresses[0]);
    fixedBlocks[0].reset();
    variableBlocks[0].reset();
    currentOrdinal = 0;
    maxOrdinalBeforeExpand = ACTUAL_VALUES_PER_BATCH;
    gaps = 0;
    capacity = MAX_VALUES_PER_BATCH;
    maxSize =
        !LHashCapacities.isMaxCapacity(capacity, false) ? config.maxSize(capacity) : capacity - 1;
    openVariableAddresses[0] = initVariableAddresses[0];

    resizeListener.resetToMinimumSize();
  }

  /*
   * XXX: releaseBatch() should be removed. We are releasing fixedBlocks and variableBlocks without
   * touching the controlBlocks, which means controlBlocks can satisfy the looking even no corresponding
   * data in fixedBlocks and variableBlocks. Also everything, batches, blocks, all are inconsistent...
   * The only purpose of this function is to have some memory released immediately, before spilling
   * the next batch.
   * For now, it is okay as until resetToMinimumSize() we don't touch the data.
   */
  @Override
  public void releaseBatch(final int batchIdx) throws Exception {
    if (batchIdx == 0) {
      fixedBlocks[0].reset();
      variableBlocks[0].reset();
      return;
    }

    AutoCloseables.close(fixedBlocks[batchIdx], variableBlocks[batchIdx]);

    // release memory from accumulator
    resizeListener.releaseBatch(batchIdx);
  }

  private void initControlBlock(final long controlBlockAddr) {
    final long addr = controlBlockAddr;
    final long max = addr + MAX_VALUES_PER_BATCH * CONTROL_WIDTH;
    for (long l = addr; l < max; l += LBlockHashTable.CONTROL_WIDTH) {
      PlatformDependent.putLong(l, LBlockHashTable.LFREE);
    }
  }

  /** Preallocate memory for storing a single batch of data in hashtable (and accumulators). */
  @Override
  public void preallocateSingleBatch() {
    Preconditions.checkArgument(fixedBlocks.length == 0, "Error: expecting 0 batches in hashtable");
    Preconditions.checkArgument(
        variableBlocks.length == 0, "Error: expecting 0 batches in hashtable");
    Preconditions.checkArgument(size() == 0, "Error: Expecting empty hashtable");
    addDataBlocks();
    maxOrdinalBeforeExpand = ACTUAL_VALUES_PER_BATCH;
    Preconditions.checkArgument(
        fixedBlocks.length == 1, "Error: expecting space for single batch for fixed block");
    Preconditions.checkArgument(
        variableBlocks.length == 1, "Error: expecting space for single batch for variable block");
    Preconditions.checkArgument(size() == 0, "Error: Expecting empty hashtable");
  }

  @Override
  public int getCurrentNumberOfBlocks() {
    Preconditions.checkArgument(
        fixedBlocks.length == variableBlocks.length,
        "Error: detected inconsistent number of blocks");
    return fixedBlocks.length;
  }

  /**
   * Compute hash for fixed width key columns only
   *
   * @param keyDataAddr pointer to the row of fixed width keys in pivot buffer
   * @param dataWidth width of the entire row (fixed key data + validity)
   * @param seed seed for computing hash (depends on the iteration of aggregation)
   * @return 64bit hash
   */
  public static long fixedKeyHashCode(long keyDataAddr, int dataWidth, long seed) {
    return mix(XXH64.xxHash64(keyDataAddr, dataWidth, seed));
  }

  /**
   * Compute hash for both fixed width and variable width key columns
   *
   * @param keyDataAddr pointer to the row of fixed width keys in pivot buffer
   * @param dataWidth width of the entire row (fixed key data + validity)
   * @param keyVarAddr pointer to the row of variable width keys in pivot buffer
   * @param varDataLen length of variable width row (data + metadata)
   * @param seed seed for computing hash (depends on iteration of aggregation)
   * @return 64bit hash
   */
  public static long keyHashCode(
      long keyDataAddr, int dataWidth, final long keyVarAddr, int varDataLen, long seed) {
    final long fixedValue = XXH64.xxHash64(keyDataAddr, dataWidth, seed);
    return mix(XXH64.xxHash64(keyVarAddr + VAR_LENGTH_SIZE, varDataLen, fixedValue));
  }

  public static long mix(long hash) {
    return (hash & 0x7FFFFFFFFFFFFFFFL);
  }

  /**
   * A branch free copier of FIXED keys from source batch to target.
   *
   * @param batchIndex source batch
   * @param sourceStartOrdinal start ordinal of the source
   * @param sourceStartIndex index at which to start copying keys
   * @param sourceEndIndex index upto which to copy (exclusive)
   * @param newCurrentOrdinal start ordinal at which to copy the keys
   * @param seed hash seed
   * @return final ordinal after moving the keys
   */
  private int copyWithFixedKeyOnly(
      final int batchIndex,
      final int sourceStartOrdinal,
      final int sourceStartIndex,
      final int sourceEndIndex,
      final int newCurrentOrdinal,
      final long seed) {
    Preconditions.checkState(fixedOnly);

    final int dstChunkIndex = newCurrentOrdinal >>> BITS_IN_CHUNK;
    long dstFixedAddr = tableFixedAddresses[dstChunkIndex];
    final int blockWidth = pivot.getBlockWidth();
    int targetOrdinal = newCurrentOrdinal;
    int srcOrdinal = sourceStartOrdinal;

    for (int startIdx = sourceStartIndex;
        startIdx < sourceEndIndex;
        ++startIdx, dstFixedAddr += blockWidth, ++targetOrdinal, ++srcOrdinal) {
      final long srcFixedAddr = tableFixedAddresses[batchIndex] + (startIdx * blockWidth);
      // 1. copy key to target
      Copier.copy(srcFixedAddr, dstFixedAddr, blockWidth);

      final long keyHash = LBlockHashTable.fixedKeyHashCode(srcFixedAddr, blockWidth, seed);
      final int keyHashInt = (int) keyHash;
      int controlIndex = keyHashInt & (capacity - 1);
      int originalControlIndex = controlIndex;

      while (true) {
        final int controlChunkIndex = controlIndex >>> BITS_IN_CHUNK;
        final int offsetInChunk = controlIndex & CHUNK_OFFSET_MASK;
        final long tableControlAddr =
            tableControlAddresses[controlChunkIndex] + (offsetInChunk * CONTROL_WIDTH);
        final long control = PlatformDependent.getLong(tableControlAddr);

        // must not get a free slot before finding the target key, as we always probe up when
        // inserting
        Preconditions.checkArgument(control != LFREE);

        final int storedOrdinal = (int) control;
        // fix the ordinal, if it matches
        if (keyHashInt == (int) (control >>> 32) && (storedOrdinal == srcOrdinal)) {
          PlatformDependent.putInt(tableControlAddr, targetOrdinal);
          break;
        }
        controlIndex = (controlIndex - 1) & (capacity - 1);
        Preconditions.checkArgument(controlIndex != originalControlIndex);
      }
    }

    // new insert location
    return targetOrdinal;
  }

  /**
   * Same as above, except when the schema has varlen keys as well.
   *
   * @param batchIndex
   * @param sourceStartOrdinal
   * @param sourceStartIndex
   * @param sourceEndIndex
   * @param newCurrentOrdinal
   * @param seed
   * @return
   */
  private int copyWithVarKey(
      final int batchIndex,
      final int sourceStartOrdinal,
      final int sourceStartIndex,
      final int sourceEndIndex,
      final int newCurrentOrdinal,
      final long seed) {
    Preconditions.checkState(!fixedOnly);

    final int dstChunkIndex = newCurrentOrdinal >>> BITS_IN_CHUNK;
    Preconditions.checkArgument((newCurrentOrdinal & CHUNK_OFFSET_MASK) == 0);
    long dstFixedAddr = tableFixedAddresses[dstChunkIndex];
    final long dstVarKeyAddr = openVariableAddresses[dstChunkIndex];
    final int blockWidth = pivot.getBlockWidth();
    int targetOrdinal = newCurrentOrdinal;
    int srcOrdinal = sourceStartOrdinal;

    int varOffset = 0;
    for (int startIdx = sourceStartIndex;
        startIdx < sourceEndIndex;
        ++startIdx, dstFixedAddr += blockWidth, ++targetOrdinal, ++srcOrdinal) {
      final long srcFixedAddr =
          tableFixedAddresses[batchIndex] + (startIdx * pivot.getBlockWidth());
      // 1. copy the fixed key
      Copier.copy(srcFixedAddr, dstFixedAddr, blockWidth - VAR_OFFSET_SIZE);

      // 2. copy the offset
      PlatformDependent.putInt(dstFixedAddr + (blockWidth - VAR_OFFSET_SIZE), varOffset);

      // 3. copy the var key
      final long srcVarOffsetAddr = srcFixedAddr + (blockWidth - VAR_OFFSET_SIZE);
      final int offset = PlatformDependent.getInt(srcVarOffsetAddr);
      final int varLen =
          PlatformDependent.getInt(initVariableAddresses[batchIndex] + offset) + VAR_LENGTH_SIZE;
      Copier.copy(initVariableAddresses[batchIndex] + offset, dstVarKeyAddr + varOffset, varLen);
      varOffset += varLen;

      final long keyVarAddr = initVariableAddresses[batchIndex] + offset;
      final long keyHash =
          LBlockHashTable.keyHashCode(
              srcFixedAddr,
              (blockWidth - VAR_OFFSET_SIZE),
              keyVarAddr,
              (varLen - VAR_LENGTH_SIZE),
              seed);
      final int keyHashInt = (int) keyHash;
      int controlIndex = keyHashInt & (capacity - 1);
      int originalControlIndex = controlIndex;

      // 4 lookup the key & fix the ordinal
      while (true) {
        final int controlChunkIndex = controlIndex >>> BITS_IN_CHUNK;
        final int offsetInChunk = controlIndex & CHUNK_OFFSET_MASK;
        final long tableControlAddr =
            tableControlAddresses[controlChunkIndex] + (offsetInChunk * CONTROL_WIDTH);
        final long control = PlatformDependent.getLong(tableControlAddr);

        // must not get a free slot before finding the target key, as we always probe up when
        // inserting
        if (control == LFREE) {
          Preconditions.checkArgument(
              control != LFREE, "Error: Cannot lookup ordinal duing splice remap");
        }

        final int storedOrdinal = (int) control;
        // fix the ordinal, if it matches
        if (keyHashInt == (int) (control >>> 32) && (storedOrdinal == srcOrdinal)) {
          PlatformDependent.putInt(tableControlAddr, targetOrdinal);
          break;
        }
        controlIndex = (controlIndex - 1) & (capacity - 1);
        Preconditions.checkArgument(controlIndex != originalControlIndex);
      }
    }

    // fix the usage of dest varlen buffer
    variableBlocks[dstChunkIndex].getUnderlying().writerIndex(varOffset);
    openVariableAddresses[dstChunkIndex] = initVariableAddresses[dstChunkIndex] + varOffset;

    // new insert location
    return targetOrdinal;
  }

  /**
   * copies the keys from batchIndex to the new batch (identified by newCurrentOrdinal). also fixes
   * the ordinal of keys.
   *
   * @param batchIndex source batch from which to copy the keys
   * @param sourceStartIndex the index in fixedBlockVector from which to copy keys
   * @param sourceEndIndex index (exclusive) upto which the keys are copied (equivalent to
   *     'numRecords')
   * @param newCurrentOrdinal the starting ordinal of the target batch
   * @return returns the new cardinal value
   */
  @VisibleForTesting
  private int copyKeysToNewBatch(
      final int batchIndex,
      final int sourceStartOrdinal,
      final int sourceStartIndex,
      final int sourceEndIndex,
      final int newCurrentOrdinal,
      final long seed) {
    /* Code duplication to avoid any 'if' conditions inside the for loop. */
    if (fixedOnly) {
      return copyWithFixedKeyOnly(
          batchIndex,
          sourceStartOrdinal,
          sourceStartIndex,
          sourceEndIndex,
          newCurrentOrdinal,
          seed);
    } else {
      return copyWithVarKey(
          batchIndex,
          sourceStartOrdinal,
          sourceStartIndex,
          sourceEndIndex,
          newCurrentOrdinal,
          seed);
    }
  }

  /**
   * Copies the records from 1 batch to another, starting from a given index. And, frees up the
   * space in the source accumulator.
   *
   * @param srcBatchIndex batch num of source accumulator
   * @param dstBatchIndex batch num of destination
   * @param sourceStartIndex start index of source
   * @param dstStartIndex start index of source
   * @return
   */
  @VisibleForTesting
  private void moveAccumulatedRecords(
      final int srcBatchIndex,
      final int dstBatchIndex,
      final int sourceStartIndex,
      final int dstStartIndex,
      final int numRecords) {
    // 1. copy any variable length accumulators
    moveVarLenAccumulatedRecords(
        srcBatchIndex, dstBatchIndex, sourceStartIndex, dstStartIndex, numRecords);

    // 2. copy fixed width accumulators
    moveFixedLenAccumulatedRecords(
        srcBatchIndex, dstBatchIndex, sourceStartIndex, dstStartIndex, numRecords);
  }

  /**
   * Same as above, but for variable length accumulators - i.e. mutablevarchar vector
   *
   * @param srcBatchIndex batch num of source accumulator
   * @param dstBatchIndex batch num of destination
   * @param srcStartIndex start index of source
   * @param dstStartIndex start index of source
   * @param numRecords total records to move
   * @return
   */
  @VisibleForTesting
  private void moveVarLenAccumulatedRecords(
      final int srcBatchIndex,
      final int dstBatchIndex,
      final int srcStartIndex,
      final int dstStartIndex,
      final int numRecords) {
    List<Accumulator> varLenAccums = resizeListener.getVarlenAccumChildren();

    for (Accumulator a : varLenAccums) {
      a.moveValuesAndFreeSpace(
          srcBatchIndex, dstBatchIndex, srcStartIndex, dstStartIndex, numRecords);
    }
  }

  /**
   * Same as above, but for fixed length accumulators.
   *
   * @param srcBatchIndex batch num of source accumulator
   * @param dstBatchIndex batch num of destination
   * @param sourceStartIndex start index of source
   * @param dstStartIndex start index of destination
   * @param numRecords total records to move
   * @return
   */
  @VisibleForTesting
  private void moveFixedLenAccumulatedRecords(
      final int srcBatchIndex,
      final int dstBatchIndex,
      final int sourceStartIndex,
      final int dstStartIndex,
      final int numRecords) {
    final List<FieldVector> srcVectors = resizeListener.getFixedlenAccumulators(srcBatchIndex);
    final List<FieldVector> dstVectors = resizeListener.getFixedlenAccumulators(dstBatchIndex);

    Preconditions.checkArgument(srcVectors.size() == dstVectors.size());

    for (int i = 0; i < srcVectors.size(); ++i) {
      final BaseFixedWidthVector src = ((BaseFixedWidthVector) srcVectors.get(i));
      final BaseFixedWidthVector dst = ((BaseFixedWidthVector) dstVectors.get(i));
      final ArrowBuf srcValidityBuf = src.getValidityBuffer();
      final ArrowBuf dstValidityBuf = dst.getValidityBuffer();

      // copy each record from source to destination

      // 1. first copy the validity buffer.
      // XXX: Seems no helper function available yet to copy validity bits from a random index at
      // source.
      int dstIndex = dstStartIndex;
      for (int srcIndex = sourceStartIndex;
          srcIndex < sourceStartIndex + numRecords;
          ++srcIndex, ++dstIndex) {
        BitVectorHelper.setValidityBit(
            dstValidityBuf, dstIndex, BitVectorHelper.get(srcValidityBuf, srcIndex));
        BitVectorHelper.setValidityBit(srcValidityBuf, srcIndex, 0);
      }

      final ArrowBuf srcDataBuf = src.getDataBuffer();
      final ArrowBuf dstDataBuf = dst.getDataBuffer();
      final int typeWidth = src.getTypeWidth();

      // 2. copy the databuf. setBytes() take absolute byte address
      dstDataBuf.setBytes(
          dstStartIndex * typeWidth,
          srcDataBuf,
          sourceStartIndex * typeWidth,
          numRecords * typeWidth);
    }
  }

  /**
   * Splits a batch, by creating a new batch and copying some records from old to new. The copied
   * records are then marked as 'free' in the original batch.
   *
   * @param batchIndex the batch at which the entry trying to be inserted
   * @param seed seed to use when rehashing the keys
   * @return index of newly added batch
   */
  @Override
  public int splice(final int batchIndex, final long seed) {
    final int dstBatchIndex;
    try {
      ++spliceCount;
      spliceTimer.start();
      final int numRecords = this.getNumRecordsInBlock(batchIndex);
      Preconditions.checkArgument(numRecords > 1);

      resizeListener.verifyBatchCount(fixedBlocks.length);
      Preconditions.checkArgument(
          fixedBlocks.length == variableBlocks.length,
          "Error: detected inconsistent state in hashtable before starting splice");

      // 1. create a fresh batch, to which the records are going to be copied
      // (may throw an OOM exception, the caller must handle this)
      addDataBlocks();

      resizeListener.verifyBatchCount(fixedBlocks.length);
      Preconditions.checkArgument(
          fixedBlocks.length == variableBlocks.length,
          "Error: detected inconsistent state in hashtable during splice");

      // 2. set the start of target ordinal
      final int newCurrentOrdinal = blocks() * MAX_VALUES_PER_BATCH;
      Preconditions.checkArgument(getBatchIndexForOrdinal(newCurrentOrdinal) != batchIndex);
      Preconditions.checkArgument(currentOrdinal <= newCurrentOrdinal);

      // split this chunk into half by default
      final int recordsToCopy = numRecords / 2;
      final int sourceStartIndex = (numRecords - recordsToCopy);

      // 3. move the keys
      final int srcStartOrdinal = (batchIndex * MAX_VALUES_PER_BATCH) + sourceStartIndex;
      final int targetOrdinal =
          copyKeysToNewBatch(
              batchIndex, srcStartOrdinal, sourceStartIndex, numRecords, newCurrentOrdinal, seed);

      // 4 move accumulated records and free space
      dstBatchIndex = newCurrentOrdinal >>> BITS_IN_CHUNK;
      moveAccumulatedRecords(batchIndex, dstBatchIndex, sourceStartIndex, 0, recordsToCopy);

      // 5 fix the usage of fixed & varlen key buffers post copying
      if (!fixedOnly) {
        final long srcFixedAddr =
            tableFixedAddresses[batchIndex] + (sourceStartIndex * pivot.getBlockWidth());
        final long srcVarOffsetAddr = srcFixedAddr + (pivot.getBlockWidth() - VAR_OFFSET_SIZE);
        final int offset = PlatformDependent.getInt(srcVarOffsetAddr);
        // dst buffer gets fixed during copying
        Preconditions.checkArgument(
            offset <= variableBlocks[batchIndex].getUnderlying().writerIndex());
        unusedForVarBlocks += variableBlocks[batchIndex].getUnderlying().writerIndex() - offset;
        variableBlocks[batchIndex].getUnderlying().writerIndex(offset);
        openVariableAddresses[batchIndex] = (initVariableAddresses[batchIndex] + offset);
      }

      fixedBlocks[batchIndex].getUnderlying().writerIndex(sourceStartIndex * pivot.getBlockWidth());
      unusedForFixedBlocks += recordsToCopy * pivot.getBlockWidth();
      gaps += recordsToCopy;
      fixedBlocks[dstBatchIndex].getUnderlying().writerIndex(recordsToCopy * pivot.getBlockWidth());

      // 6. fix the ordinal to its new position
      // logger.debug("spliceop batch: {} old ordinal: {}, new ordinal: {}", batchIndex,
      // currentOrdinal, targetOrdinal);
      maxOrdinalBeforeExpand = newCurrentOrdinal + ACTUAL_VALUES_PER_BATCH;
      currentOrdinal = targetOrdinal;
    } finally {
      spliceTimer.stop();
    }
    return dstBatchIndex;
  }

  @Override
  public final int getBatchIndexForOrdinal(final int ordinal) {
    final int batchIndex = ordinal >>> BITS_IN_CHUNK;
    return batchIndex;
  }

  public void registerSpaceCheckListener(SpaceCheckListener spaceCheckListener) {
    this.spaceCheckListener = spaceCheckListener;
  }

  @Override
  public void registerResizeListener(ResizeListener resizeListener) {
    this.resizeListener = resizeListener;
  }

  @Override
  public int getMaxVarLenKeySize() {
    return resizeListener.getMaxVarLenKeySize();
  }
}
