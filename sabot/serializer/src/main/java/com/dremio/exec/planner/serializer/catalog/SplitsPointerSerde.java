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
package com.dremio.exec.planner.serializer.catalog;

import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.connector.metadata.DatasetSplitImpl;
import com.dremio.connector.metadata.PartitionChunk;
import com.dremio.connector.metadata.PartitionChunkImpl;
import com.dremio.exec.catalog.MaterializedSplitsPointer;
import com.dremio.exec.catalog.VersionedDatasetAdapter;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.plan.serialization.PCatalog;
import com.dremio.plan.serialization.PCatalog.PMaterializedSplitsPointer;
import com.dremio.plan.serialization.PCatalog.PPartitionChunk;
import com.dremio.plan.serialization.PCatalog.PSplitsPointer;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

public class SplitsPointerSerde {

  public static PSplitsPointer toProto(SplitsPointer pojo, CatalogToProto catalogToProto) {
    if (pojo instanceof VersionedDatasetAdapter.VersionedDataSetSplitPointer) {
      return PSplitsPointer.newBuilder()
          .setVersionedDataset(
              toProto((VersionedDatasetAdapter.VersionedDataSetSplitPointer) pojo, catalogToProto))
          .build();
    } else if (pojo instanceof MaterializedSplitsPointer) {
      return PSplitsPointer.newBuilder()
          .setMaterialized(toProto((MaterializedSplitsPointer) pojo, catalogToProto))
          .build();
    } else {
      throw new IllegalArgumentException("Unknown splits pointer type: " + pojo.getClass());
    }
  }

  public static SplitsPointer fromProto(PSplitsPointer proto, CatalogFromProto catalogFromProto) {
    switch (proto.getTypeCase()) {
      case VERSIONED_DATASET:
        return fromProto(proto.getVersionedDataset(), catalogFromProto);
      case MATERIALIZED:
        return fromProto(proto.getMaterialized(), catalogFromProto);
      default:
        throw new IllegalArgumentException("Unknown splits pointer type: " + proto.getTypeCase());
    }
  }

  public static PMaterializedSplitsPointer toProto(
      MaterializedSplitsPointer pojo, CatalogToProto catalogToProto) {
    return PMaterializedSplitsPointer.newBuilder()
        .setSplitVersion(pojo.getSplitVersion())
        .setTotalSplitCount(pojo.getTotalSplitsCount())
        .addAllPartitionChunks(ImmutableList.of()) // TODO
        //            pojo.getPartitionChunks().stream()
        //                .map(partitionChunk -> toProto(partitionChunk, catalogToProto))
        //                .collect(java.util.stream.Collectors.toList()))
        .build();
  }

  public static MaterializedSplitsPointer fromProto(
      PMaterializedSplitsPointer materializedSplitsPointer, CatalogFromProto catalogFromProto) {
    return new MaterializedSplitsPointer(
        materializedSplitsPointer.getSplitVersion(),
        ImmutableList.of(), // TODO
        //            materializedSplitsPointer.getPartitionChunksList().stream()
        //                .map(partitionChunk -> fromProto(partitionChunk, catalogFromProto))
        //                .collect(java.util.stream.Collectors.toList())
        materializedSplitsPointer.getTotalSplitCount());
  }

  public static PCatalog.PVersionedDataSetSplitPointer toProto(
      VersionedDatasetAdapter.VersionedDataSetSplitPointer pojo, CatalogToProto catalogToProto) {
    return PCatalog.PVersionedDataSetSplitPointer.newBuilder()
        .setDatasetConfigBytes(catalogToProto.toProto(pojo.getDatasetConfig()))
        .addAllPartitionChunks(
            pojo.getChunkList().stream()
                .map(partitionChunk -> toProto(partitionChunk, catalogToProto))
                .collect(java.util.stream.Collectors.toList()))
        .build();
  }

  private static SplitsPointer fromProto(
      PCatalog.PVersionedDataSetSplitPointer versionedDataset, CatalogFromProto catalogFromProto) {
    return new VersionedDatasetAdapter.VersionedDataSetSplitPointer(
        versionedDataset.getPartitionChunksList().stream()
            .map(partitionChunk -> fromProto(partitionChunk, catalogFromProto))
            .collect(java.util.stream.Collectors.toList()),
        catalogFromProto.datasetConfigFromProto(versionedDataset.getDatasetConfigBytes()));
  }

  public static PPartitionChunk toProto(
      PartitionChunk partitionChunk, CatalogToProto catalogToProto) {
    return PPartitionChunk.newBuilder()
        .setSplitCount(partitionChunk.getSplitCount())
        .addAllDatasetSplits(
            partitionChunk.getSplits().stream()
                .map(SplitsPointerSerde::toProto)
                .collect(java.util.stream.Collectors.toList()))
        .build();
  }

  private static PartitionChunk fromProto(
      PPartitionChunk partitionChunk, CatalogFromProto catalogFromProto) {
    return new PartitionChunkImpl(
        ImmutableList.of(),
        partitionChunk.getSplitCount(),
        partitionChunk.getDatasetSplitsList().stream()
            .map(SplitsPointerSerde::fromProto)
            .collect(java.util.stream.Collectors.toList()),
        fromProto(partitionChunk.getExtraInfo()));
  }

  public static PCatalog.PDataSplit toProto(DatasetSplit dataSplit) {
    return PCatalog.PDataSplit.newBuilder()
        .setSizeInBytes(dataSplit.getSizeInBytes())
        .setRecordCount(dataSplit.getRecordCount())
        .setExtraInfo(ByteString.copyFrom(dataSplit.getExtraInfo().toByteArray()))
        .build();
  }

  private static DatasetSplit fromProto(PCatalog.PDataSplit proto) {
    return new DatasetSplitImpl(
        ImmutableList.of(), // TODO
        proto.getSizeInBytes(),
        proto.getRecordCount(),
        fromProto(proto.getExtraInfo()));
  }

  private static BytesOutput fromProto(ByteString extraInfo) {
    return os -> os.write(extraInfo.toByteArray());
  }
}
