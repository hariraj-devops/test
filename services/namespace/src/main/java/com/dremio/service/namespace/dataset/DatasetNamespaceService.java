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
package com.dremio.service.namespace.dataset;

import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.DatasetConfigAndEntitiesOnPath;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceType;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.proto.EntityId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Namespace operations for Datasets. */
public interface DatasetNamespaceService {
  //// CREATE or UPDATE
  void addOrUpdateDataset(
      NamespaceKey datasetPath, DatasetConfig dataset, NamespaceAttribute... attributes)
      throws NamespaceException;

  // Create physical dataset if it doesn't exist.
  // TODO: DX-4493 No one is checking the return value. Not sure of the purpose of the return value.
  boolean tryCreatePhysicalDataset(
      NamespaceKey datasetPath, DatasetConfig config, NamespaceAttribute... attributes)
      throws NamespaceException;

  DatasetConfig renameDataset(NamespaceKey oldDatasetPath, NamespaceKey newDatasetPath)
      throws NamespaceException;

  /**
   * Create a dataset metadata saver for the given dataset.
   *
   * @param datasetPath dataset path
   * @param datasetId dataset id
   * @param splitCompression compression to be used on the (multi-)splits in the K/V store
   * @param maxSinglePartitionChunks maximum number of single split partition chunks allowed to be
   *     saved together
   * @return dataset metadata saver
   */
  DatasetMetadataSaver newDatasetMetadataSaver(
      NamespaceKey datasetPath,
      EntityId datasetId,
      NamespaceService.SplitCompression splitCompression,
      long maxSinglePartitionChunks,
      boolean datasetMetadataConsistencyValidate);

  //// READ
  /**
   * Returns {@link DatasetConfig configuration} corresponding to given path.
   *
   * @param datasetPath path whose config will be returned
   * @throws NamespaceNotFoundException if a namespace or a dataset cannot be found for the given
   *     key
   */
  DatasetConfig getDataset(NamespaceKey datasetPath) throws NamespaceNotFoundException;

  /**
   * Returns {@link DatasetMetadata} corresponding to given path.
   *
   * @param datasetPath path whose metadata will be returned
   * @throws NamespaceException if a namespace or a dataset cannot be found for the given key
   */
  DatasetMetadata getDatasetMetadata(NamespaceKey datasetPath) throws NamespaceException;

  /**
   * Returns {@link DatasetConfigAndEntitiesOnPath} corresponding to given path.
   *
   * @param datasetPath path whose config will be returned
   * @throws NamespaceException if a namespace or a dataset cannot be found for the given key
   */
  DatasetConfigAndEntitiesOnPath getDatasetAndEntitiesOnPath(NamespaceKey datasetPath)
      throws NamespaceException;

  //// LIST or COUNT datasets under folder/space/home/source
  //// Note: use sparingly!
  Iterable<NamespaceKey> getAllDatasets(final NamespaceKey parent);

  int getAllDatasetsCount(NamespaceKey path) throws NamespaceException;

  /**
   * Get the list of datasets under the given path with bounds to stop searching.
   *
   * @param root path to container of search start
   * @param searchTimeLimitMillis Time (wall clock) limit for searching. Count stops when this limit
   *     is reached and returns the count so far
   * @param countLimitToStopSearch Limit to stop searching. If we reach this number of datasets in
   *     count, stop searching and return.
   * @return
   */
  BoundedDatasetCount getDatasetCount(
      NamespaceKey root, long searchTimeLimitMillis, int countLimitToStopSearch)
      throws NamespaceException;

  /**
   * finds a dataset using an EntityId
   *
   * @param entityId
   * @return an optional dataset
   */
  Optional<DatasetConfig> getDatasetById(EntityId entityId);

  /** Returns a mapping of valid input namespace keys to the NamespaceType of their parent. */
  Map<NamespaceKey, NamespaceType> getDatasetNamespaceTypes(NamespaceKey... datasetPaths);

  //// DELETE
  void deleteDataset(NamespaceKey datasetPath, String version, NamespaceAttribute... attributes)
      throws NamespaceException;

  /**
   * Get count of datasets depending on given dataset
   *
   * @param path path of dataset
   * @return count of all descendants.
   */
  int getDownstreamsCount(NamespaceKey path);

  /**
   * Get list of datasets depending on given dataset
   *
   * @param path path of saved dataset
   * @return downstream datasets.
   * @throws NamespaceException
   */
  List<DatasetConfig> getAllDownstreams(NamespaceKey path) throws NamespaceException;

  /**
   * Get list of upstream PDSs for the given dataset
   *
   * @param path path of dataset
   * @return the keys of the PDSs that this dataset transitively depends on
   */
  List<NamespaceKey> getUpstreamPhysicalDatasets(NamespaceKey path);

  /**
   * Get list of upstream sources for the given dataset
   *
   * @param path path of dataset
   * @return the names of the sources containing PDSs that this dataset transitively depends on
   */
  List<String> getUpstreamSources(NamespaceKey path);
}
