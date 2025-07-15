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
package com.dremio.exec.store.iceberg.model;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.planner.common.ImmutableDremioFileAttrs;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogGrpcClient;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogRequestBuilder;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.service.catalog.UpdatableDatasetConfigFields;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableProperties;

/**
 * Similar to {@link IcebergTableCreationCommitter}, additionally updates Dataset Catalog with new
 * Iceberg table metadata
 */
public class FullMetadataRefreshCommitter extends IcebergTableCreationCommitter {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(FullMetadataRefreshCommitter.class);
  private final DatasetCatalogGrpcClient client;
  private final DatasetCatalogRequestBuilder datasetCatalogRequestBuilder;
  private final String tableUuid;
  private final String tableLocation;
  private final List<String> datasetPath;
  private final FileType fileType;

  public static final Map<String, String> internalIcebergTableProperties =
      Stream.of(
              new String[][] {
                {TableProperties.COMMIT_NUM_RETRIES, "0"},
                {TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED, "true"},
                {
                  TableProperties.METADATA_PREVIOUS_VERSIONS_MAX,
                  String.valueOf(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX_DEFAULT)
                }
              })
          .collect(Collectors.toMap(d -> d[0], d -> d[1]));

  public FullMetadataRefreshCommitter(
      String tableName,
      List<String> datasetPath,
      String tableLocation,
      String tableUuid,
      BatchSchema batchSchema,
      List<String> partitionColumnNames,
      IcebergCommand icebergCommand,
      DatasetCatalogGrpcClient client,
      DatasetConfig datasetConfig,
      OperatorStats operatorStats,
      PartitionSpec partitionSpec,
      FileType fileType) {
    super(
        tableName,
        batchSchema,
        partitionColumnNames,
        icebergCommand,
        internalIcebergTableProperties,
        operatorStats,
        partitionSpec,
        null); // Full MetadataRefresh is the only way to create internal iceberg table

    Preconditions.checkNotNull(client, "Metadata requires DatasetCatalog service client");
    this.client = client;
    this.tableUuid = tableUuid;
    this.tableLocation = tableLocation;
    this.datasetPath = datasetPath;
    this.fileType = fileType;
    datasetCatalogRequestBuilder =
        DatasetCatalogRequestBuilder.forFullMetadataRefresh(
            datasetPath, tableLocation, batchSchema, partitionColumnNames, datasetConfig);
  }

  @Override
  public Snapshot commit() {
    Snapshot snapshot = super.commit();
    Map<String, String> summary =
        Optional.ofNullable(snapshot).map(Snapshot::summary).orElseGet(ImmutableMap::of);
    long numRecords = Long.parseLong(summary.getOrDefault("total-records", "0"));
    datasetCatalogRequestBuilder.setNumOfRecords(numRecords);
    long numDataFiles = Long.parseLong(summary.getOrDefault("total-data-files", "0"));
    datasetCatalogRequestBuilder.setNumOfDataFiles(numDataFiles);
    ImmutableDremioFileAttrs partitionStatsFileAttrs =
        IcebergUtils.getPartitionStatsFileAttrs(
            getRootPointer(), snapshot.snapshotId(), icebergCommand.getFileIO());
    datasetCatalogRequestBuilder.setIcebergMetadata(
        getRootPointer(),
        tableUuid,
        snapshot.snapshotId(),
        getCurrentSpecMap(),
        getCurrentSchema(),
        partitionStatsFileAttrs.fileName(),
        partitionStatsFileAttrs.fileLength(),
        fileType);

    try {
      addOrUpdateDataSet();
    } catch (StatusRuntimeException sre) {
      logger.warn(
          "Unexpected behavior with status {} has been observed with Metadata refresh for Dataset: {} and TableLocation: {} ",
          sre.getStatus().getCode(),
          Arrays.toString(datasetPath.toArray()),
          tableLocation);
      try {
        logger.debug(
            "With failed Metadata refresh for Dataset: {} and TableLocation: {} , Cleanup task is going to happen with unwanted files.",
            Arrays.toString(datasetPath.toArray()),
            tableLocation);
        icebergCommand.deleteTable();
      } catch (Exception i) {
        logger.warn("Failure during cleaning up the unwanted files", i);
      }
      if (sre.getStatus().getCode() == Status.Code.ABORTED) {
        logger.debug(
            "Metadata Refresh has been ABORTED for Dataset: {} and TableLocation: {}. It's going to do validation for concurrent metadata refresh.",
            Arrays.toString(datasetPath.toArray()),
            tableLocation);
        // Validate if metadata is available or not for current dataset before throwing CME.
        if (isMetadataAlreadyCreated()) {
          logger.info(
              "Concurrent refresh have been seen here, Metadata is already present for dataset {}. so not failing the query.",
              datasetPath);
        } else {
          logger.error(
              "Irrespective of Concurrent Metadata refresh. It failed for Dataset: "
                  + Arrays.toString(datasetPath.toArray())
                  + " and TableLocation: "
                  + tableLocation,
              sre);
          throw UserException.concurrentModificationError(sre)
              .message(UserException.REFRESH_METADATA_FAILED_CONCURRENT_UPDATE_MSG)
              .buildSilently();
        }
      } else {
        // Throws non-handled RTE.
        throw sre;
      }
    }
    return snapshot;
  }

  @VisibleForTesting
  public void addOrUpdateDataSet() {
    client.getCatalogServiceApi().addOrUpdateDataset(datasetCatalogRequestBuilder.build());
  }

  /**
   * @return in case of concurrent refreshes of datasets. if metadata presents in the catalog return
   *     true else false.
   */
  @VisibleForTesting
  boolean isMetadataAlreadyCreated() {
    try {
      UpdatableDatasetConfigFields previousMetadata =
          datasetCatalogRequestBuilder.getPreviousMetadata(client, datasetPath);
      return previousMetadata != null;
    } catch (UserException uex) {
      logger.warn("Failure during retrieving metadata for CME dataset. {}", uex.getMessage());
    }
    return false;
  }

  @Override
  public void consumeManifestFile(ManifestFile icebergManifestFile) {
    super.consumeManifestFile(icebergManifestFile);
  }

  @Override
  public void updateReadSignature(ByteString newReadSignature) {
    logger.debug("Updating read signature.");
    datasetCatalogRequestBuilder.setReadSignature(newReadSignature);
  }
}
