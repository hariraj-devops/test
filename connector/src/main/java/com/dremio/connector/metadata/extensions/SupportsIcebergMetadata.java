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
package com.dremio.connector.metadata.extensions;

import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetStats;
import java.util.Map;

/**
 * Implemented as an extension to {@link com.dremio.connector.metadata.DatasetMetadata} to provide
 * additional Iceberg related metadata to the catalog.
 */
public interface SupportsIcebergMetadata {

  /**
   * Get the metadata file location.
   *
   * @return metadata file location, not null
   */
  String getMetadataFileLocation();

  /**
   * Get the snapshot id.
   *
   * @return snapshot id, -1 if not available
   */
  long getSnapshotId();

  /**
   * Provides a serialized version of partition specs.
   *
   * @return serialized partition specs
   */
  BytesOutput getPartitionSpecs();

  /**
   * Provides a serialized version of sort orders.
   *
   * @return serialized sort orders
   */
  String getSortOrder();

  /**
   * Provides a String version of Iceberg Schema.
   *
   * @return serialized partition specs
   */
  String getIcebergSchema();

  /**
   * Provides statistics for number of position/equality delete records.
   *
   * @return a DatasetStats instance with the count of total delete records.
   */
  DatasetStats getDeleteStats();

  /**
   * Provides statistics for number of equality delete records.
   *
   * @return a DatasetStats instance with the count of equality delete records.
   */
  DatasetStats getEqualityDeleteStats();

  /**
   * Provides statistics for number of delete files.
   *
   * @return a DatasetStats instance with the count of delete files.
   */
  DatasetStats getDeleteManifestStats();

  /** modification time for the snapshot */
  long getMtime();

  /**
   * Provides table properties.
   *
   * @return a Map with table properties names/values.
   */
  Map<String, String> getTableProperties();

  /**
   * Get the Iceberg format version number, without refreshing metadata.
   *
   * @return the format version number if it was loaded and readable, or null otherwise
   */
  Integer getFormatVersion();

  /** get the latest partition spec id */
  int getDefaultPartitionSpecId();
}
