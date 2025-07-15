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
package com.dremio.exec.store;

import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;

/** Generates block based splits using SplitCreator */
public class BlockBasedSplitGenerator {

  private final SplitCreator splitCreator;
  private long currentOffset;
  private final boolean isOneSplitPerFile;

  public BlockBasedSplitGenerator(
      OperatorContext context,
      SupportsIcebergRootPointer plugin,
      byte[] extendedBytes,
      boolean isInternalIcebergTable) {
    this(context, plugin, extendedBytes, isInternalIcebergTable, false);
  }

  public BlockBasedSplitGenerator(
      OperatorContext context,
      SupportsIcebergRootPointer plugin,
      byte[] extendedBytes,
      boolean isInternalIcebergTable,
      boolean isOneSplitPerFile) {
    this.splitCreator = plugin.createSplitCreator(context, extendedBytes, isInternalIcebergTable);
    this.isOneSplitPerFile = isOneSplitPerFile;
  }

  public long getCurrentOffset() {
    return currentOffset;
  }

  public List<SplitAndPartitionInfo> getSplitAndPartitionInfo(
      int maxOutputCount,
      PartitionProtobuf.NormalizedPartitionInfo filePartitionInfo,
      String filePath,
      long offset,
      long fileSize,
      long currentModTime,
      String fileFormat,
      List<SplitIdentity> splitsIdentity)
      throws InvalidProtocolBufferException {
    return getSplitAndPartitionInfo(
        maxOutputCount,
        filePartitionInfo,
        filePath,
        offset,
        fileSize,
        currentModTime,
        fileFormat,
        null,
        splitsIdentity);
  }

  public List<SplitAndPartitionInfo> getSplitAndPartitionInfo(
      int maxOutputCount,
      PartitionProtobuf.NormalizedPartitionInfo filePartitionInfo,
      String filePath,
      long offset,
      long fileSize,
      long currentModTime,
      String fileFormat,
      Long fileGroupIndex,
      List<SplitIdentity> splitsIdentity)
      throws InvalidProtocolBufferException {
    int splitCount = 0;
    currentOffset = offset;
    List<SplitAndPartitionInfo> splits = new ArrayList<>();
    long targetSplitSize =
        isOneSplitPerFile ? fileSize : splitCreator.getTargetSplitSize(fileFormat);

    while (splitCount < maxOutputCount && currentOffset < fileSize) {
      long curBlockSize = Math.min(targetSplitSize, fileSize - currentOffset);
      SplitIdentity splitIdentity =
          new SplitIdentity(filePath, currentOffset, curBlockSize, fileSize, fileGroupIndex);
      splits.add(
          splitCreator.createSplit(
              filePartitionInfo, splitIdentity, fileFormat, fileSize, currentModTime));
      if (splitsIdentity != null) {
        splitsIdentity.add(splitIdentity);
      }
      currentOffset += curBlockSize;
      splitCount++;
    }
    return splits;
  }

  public interface SplitCreator {
    SplitAndPartitionInfo createSplit(
        PartitionProtobuf.NormalizedPartitionInfo filePartitionInfo,
        SplitIdentity splitIdentity,
        String fileFormat,
        long fileSize,
        long currentModTime)
        throws InvalidProtocolBufferException;

    long getTargetSplitSize(String fileFormat);
  }
}
