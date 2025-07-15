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

package com.dremio.exec.store.iceberg;

import com.dremio.exec.catalog.TableMetadataImpl;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.FormatPlugin;
import com.dremio.exec.store.dfs.PhysicalDatasetUtils;
import com.dremio.service.namespace.file.proto.FileConfig;

public class HiveIcebergScanTableMetadata extends TableMetadataImpl {

  private final FormatPlugin formatPlugin;

  private final TableMetadata tableMetadata;

  public HiveIcebergScanTableMetadata(
      TableMetadata tableMetadata, SupportsIcebergRootPointer icebergTableStoragePlugin) {
    super(
        tableMetadata.getStoragePluginId(),
        tableMetadata.getDatasetConfig(),
        tableMetadata.getUser(),
        (SplitsPointer) tableMetadata.getSplitsKey(),
        tableMetadata.getPrimaryKey());
    formatPlugin = icebergTableStoragePlugin.getFormatPlugin(new IcebergFormatConfig());
    this.tableMetadata = tableMetadata;
  }

  @Override
  public FileConfig getFormatSettings() {
    return PhysicalDatasetUtils.toFileFormat(formatPlugin)
        .asFileConfig()
        .setLocation(getMetadataLocation());
  }

  @Override
  public BatchSchema getSchema() {
    return tableMetadata.getSchema();
  }

  private String getMetadataLocation() {
    return IcebergUtils.getMetadataLocation(
        tableMetadata.getDatasetConfig(), tableMetadata.getSplits());
  }
}
