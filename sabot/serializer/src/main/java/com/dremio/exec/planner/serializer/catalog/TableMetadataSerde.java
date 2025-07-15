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

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.TableMetadataImpl;
import com.dremio.exec.store.TableMetadata;
import com.dremio.plan.serialization.PCatalog.PTableMetadata;
import com.dremio.plan.serialization.PCatalog.PTableMetadataImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;

public class TableMetadataSerde {

  public static PTableMetadata toProto(TableMetadata tableMetadata, CatalogToProto toProto) {
    PTableMetadata.Builder builder = PTableMetadata.newBuilder();
    if (tableMetadata instanceof TableMetadataImpl) {
      builder.setImpl(toProto((TableMetadataImpl) tableMetadata, toProto));
    } else {
      throw new IllegalArgumentException("Unknown table type: " + tableMetadata.getClass());
    }
    return builder.build();
  }

  public static PTableMetadataImpl toProto(
      TableMetadataImpl tableMetadata, CatalogToProto toProto) {
    if (tableMetadata == null) {
      return null;
    }
    return PTableMetadataImpl.newBuilder()
        .setStoragePluginIdBson(toProto.storagePluginIdToBson(tableMetadata.getStoragePluginId()))
        .setDatasetConfigBytes(toProto.toProto(tableMetadata.getDatasetConfig()))
        .setUser(tableMetadata.getUser())
        .addAllPrimaryKeys(tableMetadata.getPrimaryKey())
        .setTableVersionContextJson(tableMetadata.getVersionContext().serialize())
        .setSplitsPointer(toProto.toProto(tableMetadata.getSplitsKey()))
        .build();
  }

  public static TableMetadata fromProto(PTableMetadata proto, CatalogFromProto fromProto) {
    switch (proto.getTableTypeCase()) {
      case IMPL:
        return fromProto(proto.getImpl(), fromProto);
      default:
        throw new IllegalArgumentException("Unknown table type: " + proto.getTableTypeCase());
    }
  }

  public static TableMetadataImpl fromProto(PTableMetadataImpl proto, CatalogFromProto fromProto) {
    if (proto == null) {
      return null;
    }
    StoragePluginId storagePluginId =
        fromProto.storagePluginIdFromBson(proto.getStoragePluginIdBson());
    DatasetConfig datasetConfig = fromProto.datasetConfigFromProto(proto.getDatasetConfigBytes());
    TableVersionContext tableVersionContext =
        TableVersionContext.deserialize(proto.getTableVersionContextJson());

    return new TableMetadataImpl(
        storagePluginId,
        datasetConfig,
        proto.getUser(),
        fromProto.fromProto(proto.getSplitsPointer()), // splits
        proto.getPrimaryKeysList(), // primaryKey
        tableVersionContext);
  }
}
