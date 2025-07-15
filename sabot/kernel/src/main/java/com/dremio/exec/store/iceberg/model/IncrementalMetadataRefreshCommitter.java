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

import static com.dremio.common.exceptions.UserException.REFRESH_METADATA_FAILED_CONCURRENT_UPDATE_MSG;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.exception.NoSupportedUpPromotionOrCoercionException;
import com.dremio.exec.planner.acceleration.IncrementalUpdateUtils;
import com.dremio.exec.planner.common.ImmutableDremioFileAttrs;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.IcebergExpirySnapshotsCollector;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SnapshotEntry;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogGrpcClient;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogRequestBuilder;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.writer.WriterCommitterOperator;
import com.dremio.service.catalog.GetDatasetRequest;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DremioManifestListReaderUtils;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatsFileLocations;
import org.apache.iceberg.PartitionStatsMetadataUtil;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.Pair;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IcebergMetadataRefreshCommitter this committer has two update operation DELETE Followed by INSERT
 */
public class IncrementalMetadataRefreshCommitter
    implements IcebergOpCommitter, SupportsTypeCoercionsAndUpPromotions {

  private static final Logger logger =
      LoggerFactory.getLogger(IncrementalMetadataRefreshCommitter.class);
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(IncrementalMetadataRefreshCommitter.class);
  private static final int KVSTORE_UPDATE_RETRIES = 3;

  @VisibleForTesting
  public static final String INJECTOR_AFTER_ICEBERG_COMMIT_ERROR =
      "error-between-iceberg-commit-and-catalog-update";

  @VisibleForTesting public static final int MAX_NUM_SNAPSHOTS_TO_EXPIRE = 15;

  private static final int MIN_SNAPSHOTS_TO_KEEP = 5;
  private static final int DEFAULT_THREAD_POOL_SIZE = 4;
  private static final ExecutorService EXECUTOR_SERVICE =
      ThreadPools.newWorkerPool("metadata-refresh-delete", DEFAULT_THREAD_POOL_SIZE);

  private final long periodToKeepSnapshotsMs;
  private final String tableName;
  private final String tableUuid;
  private final IcebergCommand icebergCommand;
  private final DatasetCatalogGrpcClient client;
  private final Configuration conf;
  private List<ManifestFile> manifestFileList = new ArrayList<>();
  private List<DataFile> deleteDataFilesList = new ArrayList<>();
  private List<Types.NestedField> updatedColumnTypes = new ArrayList();
  private List<Types.NestedField> newColumnTypes = new ArrayList();
  private List<Types.NestedField> dropColumns = new ArrayList();
  private final DatasetCatalogRequestBuilder datasetCatalogRequestBuilder;
  private BatchSchema batchSchema;
  private boolean isFileSystem;
  private final OperatorStats operatorStats;
  private final String tableLocation;
  private final List<String> datasetPath;
  private final String prevMetadataRootPointer;
  private final ExecutionControls executionControls;
  private final DatasetConfig datasetConfig;
  private Table table;
  private final boolean isMapDataTypeEnabled;
  private final FileSystem fs;
  private final Long metadataExpireAfterMs;
  private final boolean isMetadataCleanEnabled;
  private final IcebergCommandType icebergOpType;
  private final boolean errorOnConcurrentRefresh;
  private boolean enableUseDefaultPeriod = true; // For unit test purpose only
  private int minSnapshotsToKeep = MIN_SNAPSHOTS_TO_KEEP;
  private final FileType fileType;
  private final Long startingSnapshotId;
  private final List<String> partitionColumnNames;
  private boolean kvstoreRetryEnabled = true; // For unit test purpose only

  public IncrementalMetadataRefreshCommitter(
      OperatorContext operatorContext,
      String tableName,
      List<String> datasetPath,
      String tableLocation,
      String tableUuid,
      BatchSchema batchSchema,
      Configuration configuration,
      List<String> partitionColumnNames,
      IcebergCommand icebergCommand,
      boolean isFileSystem,
      DatasetCatalogGrpcClient datasetCatalogGrpcClient,
      DatasetConfig datasetConfig,
      FileSystem fs,
      Long metadataExpireAfterMs,
      IcebergCommandType icebergOpType,
      FileType fileType,
      Long startingSnapshotId,
      boolean errorOnConcurrentRefresh) {
    Preconditions.checkState(icebergCommand != null, "Unexpected state");
    Preconditions.checkNotNull(
        datasetCatalogGrpcClient, "Unexpected state: DatasetCatalogService client not provided");
    Preconditions.checkNotNull(
        datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation());
    Preconditions.checkNotNull(startingSnapshotId, "Unexpected state: SnapshotId not provided");
    this.icebergCommand = icebergCommand;
    this.tableName = tableName;
    this.conf = configuration;
    this.tableUuid = tableUuid;
    this.client = datasetCatalogGrpcClient;
    this.datasetConfig = datasetConfig;
    this.fileType = fileType;
    this.partitionColumnNames = partitionColumnNames;
    this.datasetCatalogRequestBuilder =
        DatasetCatalogRequestBuilder.forIncrementalMetadataRefresh(
            datasetPath,
            tableLocation,
            batchSchema,
            partitionColumnNames,
            datasetCatalogGrpcClient,
            datasetConfig);
    this.prevMetadataRootPointer =
        datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
    this.batchSchema = batchSchema;
    this.isFileSystem = isFileSystem;
    this.operatorStats = operatorContext.getStats();
    this.tableLocation = tableLocation;
    this.datasetPath = datasetPath;
    this.executionControls = operatorContext.getExecutionControls();
    this.isMapDataTypeEnabled =
        operatorContext.getOptions().getOption(ExecConstants.ENABLE_MAP_DATA_TYPE);
    this.fs = fs;
    this.metadataExpireAfterMs = metadataExpireAfterMs;
    this.isMetadataCleanEnabled =
        operatorContext
            .getOptions()
            .getOption(ExecConstants.ENABLE_UNLIMITED_SPLITS_METADATA_CLEAN);
    this.icebergOpType = icebergOpType;
    this.periodToKeepSnapshotsMs =
        operatorContext.getOptions().getOption(ExecConstants.DEFAULT_PERIOD_TO_KEEP_SNAPSHOTS_MS);
    this.startingSnapshotId = startingSnapshotId;
    this.errorOnConcurrentRefresh = errorOnConcurrentRefresh;
  }

  private boolean hasAnythingChanged() {
    if ((newColumnTypes.size()
            + updatedColumnTypes.size()
            + deleteDataFilesList.size()
            + manifestFileList.size())
        > 0) {
      return true;
    }

    if (isFileSystem) {
      return false;
    }

    return (dropColumns.size() > 0);
  }

  @VisibleForTesting
  public void beginMetadataRefreshTransaction() {
    this.icebergCommand.beginTransaction();
  }

  @VisibleForTesting
  public void performUpdates() {
    if (newColumnTypes.size() > 0) {
      icebergCommand.consumeAddedColumns(newColumnTypes);
    }

    if (updatedColumnTypes.size() > 0) {
      icebergCommand.consumeUpdatedColumns(updatedColumnTypes);
    }

    if (!isFileSystem && dropColumns.size() > 0) {
      icebergCommand.consumeDroppedColumns(dropColumns);
    }

    if (deleteDataFilesList.size() > 0) {
      icebergCommand.beginDelete();
      icebergCommand.consumeDeleteDataFiles(deleteDataFilesList);
      icebergCommand.finishDelete();
    }
    if (manifestFileList.size() > 0) {
      icebergCommand.beginOverwrite(startingSnapshotId);
      if (logger.isDebugEnabled()) {
        logger.debug("Committing {} manifest files.", manifestFileList.size());
        manifestFileList.stream()
            .forEach(
                l ->
                    logger.debug(
                        "Committing manifest file: {}, with {} added files.",
                        l.path(),
                        l.addedFilesCount()));
      }
      icebergCommand.consumeManifestFilesWithOverwrite(manifestFileList);
      icebergCommand.finishOverwrite();
    }
  }

  @VisibleForTesting
  public Snapshot endMetadataRefreshTransaction() {
    table = icebergCommand.endTransaction();
    return table.currentSnapshot();
  }

  @VisibleForTesting
  public Snapshot postCommitTransaction() {
    // Skip post commit, if no table instance is assigned due to CME.
    if (table == null) {
      return null;
    }

    // For the Metadata Iceberg tables, we clean the old snapshots gradually.
    if (isMetadataCleanEnabled) {
      cleanSnapshotsAndMetadataFiles(table);
    }

    injector.injectChecked(
        executionControls,
        INJECTOR_AFTER_ICEBERG_COMMIT_ERROR,
        UnsupportedOperationException.class);

    setDatasetCatalogRequestBuilder(datasetCatalogRequestBuilder);
    logger.debug(
        "Committed incremental metadata change of table {}. Updating Dataset Catalog store",
        tableName);
    try {
      client.getCatalogServiceApi().addOrUpdateDataset(datasetCatalogRequestBuilder.build());
    } catch (StatusRuntimeException sre) {
      if (sre.getStatus().getCode() == Status.Code.ABORTED) {
        logger.error(
            "Metadata refresh failed. Dataset: "
                + Arrays.toString(datasetPath.toArray())
                + " TableLocation: "
                + tableLocation,
            sre);

        // DX-84083: Iceberg commit succeeds. However, it fails to update the KV store. Because,
        // Another concurrent metadata refresh query could already succeed and update the KV
        // store and the dataset has a new tag.
        // In this case, we will try get the latest tag info and re-try to the commit.
        // If re-tries succeed, we can update the KV Store with the Iceberg snapshot
        // and metadata location info.
        AtomicInteger nRetried = new AtomicInteger(0);
        AtomicBoolean kvstoreRetryFailed = new AtomicBoolean(false);

        if (kvstoreRetryEnabled) {
          Tasks.foreach(tableLocation)
              .retry(KVSTORE_UPDATE_RETRIES)
              .suppressFailureWhenFinished()
              .onFailure(
                  (tableLocation, exc) -> {
                    logger.info(
                        "Metadata refresh update KV store retry "
                            + nRetried.get()
                            + " time(s) failed. Dataset: "
                            + Arrays.toString(datasetPath.toArray())
                            + " TableLocation: "
                            + tableLocation);
                    if (nRetried.get() == KVSTORE_UPDATE_RETRIES) {
                      kvstoreRetryFailed.set(true);
                    }
                  })
              .run(
                  tableLocation -> {
                    nRetried.set(nRetried.get() + 1);
                    // For every retry, it gets the latest tag info and do the retry.
                    DatasetCatalogRequestBuilder datasetCatalogRequestBuilderRetry =
                        DatasetCatalogRequestBuilder.forIncrementalMetadataRefresh(
                            datasetPath,
                            tableLocation,
                            batchSchema,
                            partitionColumnNames,
                            client,
                            datasetConfig);

                    setDatasetCatalogRequestBuilder(datasetCatalogRequestBuilderRetry);
                    client
                        .getCatalogServiceApi()
                        .addOrUpdateDataset(datasetCatalogRequestBuilderRetry.build());
                  });
        }
        // If the retries can not successfully update the KV Store, we check original exception
        // to determine whether we need to finally throw CME.
        if (!kvstoreRetryEnabled || kvstoreRetryFailed.get()) {
          checkToThrowException(prevMetadataRootPointer, getRootPointer(), sre);
        }
      } else {
        throw sre;
      }
    }
    return table.currentSnapshot();
  }

  private void setDatasetCatalogRequestBuilder(DatasetCatalogRequestBuilder datasetBuilder) {
    Map<String, String> summary =
        Optional.ofNullable(table.currentSnapshot())
            .map(Snapshot::summary)
            .orElseGet(ImmutableMap::of);
    long numRecords = Long.parseLong(summary.getOrDefault("total-records", "0"));
    datasetBuilder.setNumOfRecords(numRecords);
    long numDataFiles = Long.parseLong(summary.getOrDefault("total-data-files", "0"));
    datasetBuilder.setNumOfDataFiles(numDataFiles);
    ImmutableDremioFileAttrs partitionStatsFileAttrs =
        IcebergUtils.getPartitionStatsFileAttrs(
            getRootPointer(), table.currentSnapshot().snapshotId(), icebergCommand.getFileIO());
    datasetBuilder.setIcebergMetadata(
        getRootPointer(),
        tableUuid,
        table.currentSnapshot().snapshotId(),
        getCurrentSpecMap(),
        getCurrentSchema(),
        partitionStatsFileAttrs.fileName(),
        partitionStatsFileAttrs.fileLength(),
        fileType);
    BatchSchema newSchemaFromIceberg =
        SchemaConverter.getBuilder()
            .setMapTypeEnabled(isMapDataTypeEnabled)
            .build()
            .fromIceberg(table.schema());
    newSchemaFromIceberg =
        BatchSchema.newBuilder()
            .addFields(newSchemaFromIceberg.getFields())
            .addField(
                Field.nullable(IncrementalUpdateUtils.UPDATE_COLUMN, new ArrowType.Int(64, true)))
            .build();
    datasetBuilder.overrideSchema(newSchemaFromIceberg);
  }

  private void cleanSnapshotsAndMetadataFiles(Table targetTable) {
    long periodToKeepSnapshots;
    if (enableUseDefaultPeriod) {
      // If the source has user-specified metadata expiration time, we intend to keep all snapshots
      // are still valid.
      periodToKeepSnapshots =
          metadataExpireAfterMs != null
              ? Math.max(periodToKeepSnapshotsMs, metadataExpireAfterMs)
              : periodToKeepSnapshotsMs;
    } else {
      Preconditions.checkNotNull(metadataExpireAfterMs, "Metadata expiry time not set.");
      periodToKeepSnapshots = metadataExpireAfterMs;
    }
    final long timestampExpiry = System.currentTimeMillis() - periodToKeepSnapshots;
    cleanSnapshotsAndMetadataFiles(targetTable, timestampExpiry);
  }

  /**
   * Clean the snapshots and delete orphan manifest list file paths and manifest file paths. Don't
   * delete data files in the manifests, since data files belong to original tables.
   */
  @VisibleForTesting
  public Pair<Set<String>, Long> cleanSnapshotsAndMetadataFiles(
      Table targetTable, long timestampExpiry) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    // Clean the metadata files through setting table properties.
    setTablePropertyToCleanOldMetadataFiles(targetTable);

    TableMetadata tableMetadata = icebergCommand.getTableOps().refresh();
    IcebergExpirySnapshotsCollector snapshotsCollector =
        new IcebergExpirySnapshotsCollector(tableMetadata);

    // Collect the candidate snapshots to expire.
    List<SnapshotEntry> candidateSnapshots =
        snapshotsCollector.collect(timestampExpiry, minSnapshotsToKeep).first();

    // The existing metadata tables might already have lots of snapshots. We don't try to expire
    // them one time,
    // because it could dramatically increase metadata refresh query time.
    // Instead, we only expire a small number of snapshots during one metadata refresh query.
    int numSnapshotsToExpire =
        candidateSnapshots.size() < MAX_NUM_SNAPSHOTS_TO_EXPIRE
            ? candidateSnapshots.size()
            : MAX_NUM_SNAPSHOTS_TO_EXPIRE;

    int numTotalSnapshots = Iterables.size(targetTable.snapshots());
    final int numSnapshotsRetain = numTotalSnapshots - numSnapshotsToExpire;

    // Call the api again to get the exact snapshots to expire.
    List<SnapshotEntry> expiredSnapshots =
        snapshotsCollector.collect(timestampExpiry, numSnapshotsRetain).first();

    // Perform the expiry operation.
    List<SnapshotEntry> liveSnapshots;
    try {
      liveSnapshots = icebergCommand.expireSnapshots(timestampExpiry, numSnapshotsRetain, true);
    } catch (ValidationException
        | CommitFailedException
        | CommitStateUnknownException
        | IllegalStateException e) {
      // Fail to expire old snapshots and exit old snapshots clean process.
      logger.warn("Fail to expire and clean old snapshots", e);
      checkToThrowException(getRootPointer(), getRootPointer(), e);
      return Pair.of(Collections.emptySet(), 0L);
    }

    // Collect the orphan files.
    final FileIO io = targetTable.io();
    Set<String> orphanFiles = new HashSet<>();
    for (SnapshotEntry entry : expiredSnapshots) {
      orphanFiles.addAll(collectFilesForSnapshot(io, entry, true));
    }

    // Remove the files that are still used by the valid snapshots. We only need to keep the
    // snapshots that are within certain amount of time.
    // Because, other snapshots are expired and not valid for in-flight queries.
    Long numValidSnapshots = 0L;
    for (SnapshotEntry entry : liveSnapshots) {
      if ((entry.getTimestampMillis() < timestampExpiry)
          && (entry.getSnapshotId() != tableMetadata.currentSnapshot().snapshotId())) {
        continue;
      }
      numValidSnapshots += 1L;
      // Partition stats files are organized by snapshot. Don't need to check partitions stats files
      // for the snapshots
      // that we try to keep.
      orphanFiles.removeAll(collectFilesForSnapshot(io, entry, false));
    }

    Preconditions.checkState(
        numValidSnapshots >= 1L, "Should keep files at least for current snapshot");

    // Make Iceberg commit, and clean old snapshots.
    clearMetric();
    operatorStats.setLongStat(
        WriterCommitterOperator.Metric.NUM_TOTAL_SNAPSHOTS, numTotalSnapshots);
    operatorStats.setLongStat(
        WriterCommitterOperator.Metric.NUM_EXPIRED_SNAPSHOTS, expiredSnapshots.size());

    // Remove orphan files
    IcebergUtils.removeOrphanFiles(fs, logger, EXECUTOR_SERVICE, orphanFiles);
    long clearTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    operatorStats.setLongStat(
        WriterCommitterOperator.Metric.NUM_ORPHAN_FILES_DELETED, orphanFiles.size());
    operatorStats.setLongStat(
        WriterCommitterOperator.Metric.CLEAR_EXPIRE_SNAPSHOTS_TIME, clearTime);
    operatorStats.setLongStat(
        WriterCommitterOperator.Metric.NUM_VALID_SNAPSHOTS, numValidSnapshots);
    return Pair.of(orphanFiles, numValidSnapshots);
  }

  private void clearMetric() {
    // DX-96559: When MD query makes Iceberg commit, it needs to refresh metadata file
    // (IcebergNessieTableOperations.refreshFromMetadataLocation). It involves to read old
    // metadata file and write a new metadata file (json file). This triggers to add
    // ScanOperator.metric values. Those values are brought back
    // (AsyncSeekableInputStreamFactory.close) and finally are written into
    // WriterCommitterOperator's OperatorStats.doubleMetrics,which causes the wrong numbers
    // shown in WriterCommitterOperator.Metric.
    operatorStats.clearDoubleStat(WriterCommitterOperator.Metric.NUM_TOTAL_SNAPSHOTS);
    operatorStats.clearDoubleStat(WriterCommitterOperator.Metric.NUM_EXPIRED_SNAPSHOTS);
    operatorStats.clearDoubleStat(WriterCommitterOperator.Metric.NUM_ORPHAN_FILES_DELETED);
    operatorStats.clearDoubleStat(WriterCommitterOperator.Metric.CLEAR_EXPIRE_SNAPSHOTS_TIME);
    operatorStats.clearDoubleStat(WriterCommitterOperator.Metric.NUM_VALID_SNAPSHOTS);
  }

  private Set<String> collectFilesForSnapshot(
      FileIO io, SnapshotEntry entry, boolean includePartitionStats) {
    Set<String> files = new HashSet<>();
    // Manifest list file
    files.add(entry.getManifestListPath());
    // Manifest files
    try {
      DremioManifestListReaderUtils.read(io, entry.getManifestListPath()).stream()
          .forEach(m -> files.add(m.path()));
    } catch (Exception e) {
      // Skip to read this manifest files from the manifest list file.
      logger.warn(
          "Can't successfully read the manifest list file: {}", entry.getManifestListPath(), e);
    }

    if (includePartitionStats) {
      String partitionStatsMetadataFileName =
          PartitionStatsMetadataUtil.toFilename(entry.getSnapshotId());
      String partitionStatsMetadataLocation =
          IcebergUtils.resolvePath(entry.getMetadataJsonPath(), partitionStatsMetadataFileName);
      PartitionStatsFileLocations partitionStatsLocations =
          PartitionStatsMetadataUtil.readMetadata(io, partitionStatsMetadataLocation);
      if (partitionStatsLocations != null) {
        // Partition stats have metadata file and partition files.
        files.add(partitionStatsMetadataLocation);
        files.addAll(
            partitionStatsLocations.all().entrySet().stream()
                .map(e -> e.getValue())
                .collect(Collectors.toList()));
      }
    }

    return files;
  }

  /**
   * For existing metadata tables, when they were created, those tables were not configured with the
   * table property to delete metadata files. We enabled the table property when refreshing metadata
   * and trigger to delete metadata files. However, the later configuration of this table property
   * will not help to clean orphan metadata files.
   */
  private void setTablePropertyToCleanOldMetadataFiles(Table targetTable) {
    Map<String, String> tblProperties = targetTable.properties();
    if (!tblProperties.containsKey(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED)
        || tblProperties
            .get(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED)
            .equalsIgnoreCase("false")) {
      icebergCommand.updateProperties(FullMetadataRefreshCommitter.internalIcebergTableProperties);
    }
  }

  @Override
  public Snapshot commit() {
    return commitImpl(false);
  }

  public Snapshot commitImpl(boolean skipBeginOperation /* test only */) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    logger.info("Incremental refresh type: {}", icebergOpType);
    if (isIcebergTableUpdated()) {
      // The metadata table was updated by other incremental refresh queries. Skip this update.
      // Iceberg commit is not successful and needs to clean the orphaned manifest files.
      cleanOrphans();
      checkToThrowException(prevMetadataRootPointer, getRootPointer(), null);
      // Don't need to return snapshot. Since, current table was updated.
      return null;
    }
    try {
      boolean shouldCommit = hasAnythingChanged();
      Table oldTable = null;
      if (!shouldCommit) {
        oldTable = this.icebergCommand.loadTable();
        shouldCommit =
            oldTable.currentSnapshot().snapshotId()
                != client
                    .getCatalogServiceApi()
                    .getDataset(
                        GetDatasetRequest.newBuilder().addAllDatasetPath(datasetPath).build())
                    .getIcebergMetadata()
                    .getSnapshotId();
      }
      if (shouldCommit) {
        try {
          if (!skipBeginOperation) {
            beginMetadataRefreshTransaction();
          }
          performUpdates();
          endMetadataRefreshTransaction();
          return postCommitTransaction();
        } catch (ValidationException
            | CommitFailedException
            | CommitStateUnknownException
            | IllegalStateException e) {
          // The metadata table was updated by other incremental refresh queries. Skip this update.
          // Iceberg commit is not successful and needs to clean the orphaned manifest files.
          logger.error("Fail to commit Iceberg metadata table", e);
          cleanOrphans();
          checkToThrowException(getRootPointer(), getRootPointer(), e);
          return null;
        }
      } else {
        logger.debug("Nothing is changed for  table " + this.tableName + ", Skipping commit");
        // Clean the metadata table's snapshots, if needed. Even, we don't increase new commits to
        // the table.
        if (isMetadataCleanEnabled) {
          cleanSnapshotsAndMetadataFiles(oldTable);
        }
        return oldTable.currentSnapshot();
      }
    } finally {
      long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      operatorStats.addLongStat(
          WriterCommitterOperator.Metric.ICEBERG_COMMIT_TIME, totalCommitTime);
    }
  }

  private void checkToThrowException(String rootPointer, String foundRootPointer, Throwable cause) {
    // The metadata table was updated by other incremental refresh queries. Skip this update.
    String metadataFiles =
        String.format(
            "Expected metadataRootPointer: %s, Found metadataRootPointer: %s.",
            rootPointer, foundRootPointer);
    logger.info("Concurrent operation has updated the table." + " " + metadataFiles);
    // If the refresh query works on partitions, or errors on concurrent refresh are enabled, we
    // should notify users the failure and re-run the query.
    if (icebergOpType == IcebergCommandType.PARTIAL_METADATA_REFRESH || errorOnConcurrentRefresh) {
      throw UserException.concurrentModificationError(cause)
          .message(REFRESH_METADATA_FAILED_CONCURRENT_UPDATE_MSG)
          .build(logger);
    }
  }

  private void cleanOrphans() {
    newColumnTypes.clear();
    updatedColumnTypes.clear();
    deleteDataFilesList.clear();

    // Only need to delete manifest files. The data files that plan to be deleted should be cleaned
    // by other
    // concurrent metadata refresh queries, which made successful commits.
    logger.info("Orphan manifest files to delete: {}", manifestFileList);
    IcebergUtils.removeOrphanFiles(
        fs,
        logger,
        EXECUTOR_SERVICE,
        manifestFileList.stream().map(file -> file.path()).collect(Collectors.toSet()));
    manifestFileList.clear();
  }

  @Override
  public void consumeManifestFile(ManifestFile manifestFile) {
    manifestFileList.add(manifestFile);
  }

  @Override
  public void consumeDeleteDataFile(DataFile icebergDeleteDatafile) {
    deleteDataFilesList.add(icebergDeleteDatafile);
  }

  @Override
  public void consumeDeleteDataFilePath(String icebergDeleteDatafilePath)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Deleting data file by path is not supported in metadata refresh Transaction");
  }

  @Override
  public void updateSchema(BatchSchema newSchema) {
    // handle update of columns from batch schema dropped columns and updated columns
    if (datasetConfig.getPhysicalDataset().getInternalSchemaSettings() != null) {

      if (!datasetConfig
          .getPhysicalDataset()
          .getInternalSchemaSettings()
          .getSchemaLearningEnabled()) {
        return;
      }

      List<Field> droppedColumns = new ArrayList<>();
      if (datasetConfig.getPhysicalDataset().getInternalSchemaSettings().getDroppedColumns()
          != null) {
        droppedColumns =
            BatchSchema.deserialize(
                    datasetConfig
                        .getPhysicalDataset()
                        .getInternalSchemaSettings()
                        .getDroppedColumns())
                .getFields();
      }

      List<Field> updatedColumns = new ArrayList<>();
      if (datasetConfig.getPhysicalDataset().getInternalSchemaSettings().getModifiedColumns()
          != null) {
        updatedColumns =
            BatchSchema.deserialize(
                    datasetConfig
                        .getPhysicalDataset()
                        .getInternalSchemaSettings()
                        .getModifiedColumns())
                .getFields();
      }

      for (Field field : droppedColumns) {
        newSchema = newSchema.dropField(field);
      }

      Map<String, Field> originalFieldsMap =
          batchSchema.getFields().stream()
              .collect(Collectors.toMap(x -> x.getName().toLowerCase(), Function.identity()));

      for (Field field : updatedColumns) {
        if (field.getChildren().isEmpty()) {
          newSchema = newSchema.changeTypeTopLevel(field);
        } else {
          // If complex we don't want schema learning on all fields. So
          // we drop the new struct field and replace it with old struct from the original batch
          // schema.
          Field oldField = originalFieldsMap.get(field.getName().toLowerCase());
          newSchema = newSchema.dropField(field.getName());
          try {
            newSchema = newSchema.mergeWithUpPromotion(BatchSchema.of(oldField), this);
          } catch (NoSupportedUpPromotionOrCoercionException e) {
            e.addDatasetPath(datasetPath);
            throw UserException.unsupportedError(e).message(e.getMessage()).build(logger);
          }
        }
      }
    }

    SchemaConverter schemaConverter =
        SchemaConverter.getBuilder()
            .setTableName(tableName)
            .setMapTypeEnabled(isMapDataTypeEnabled)
            .build();

    // DX-97502: Remove IncrementalUpdateUtils.UPDATE_COLUMN from those two schemas.
    // As this additional column could cause SchemaConverter.toIcebergSchema to generate
    // mismatched column id for Complex type's children, and result in detecting complex
    // field as an updated column, and being updated during Iceberg updates.
    BatchSchema batchSchemaWithoutUpdateColumn =
        batchSchema.dropField(IncrementalUpdateUtils.UPDATE_COLUMN);
    BatchSchema newSchemaWithoutUpdateColumn =
        newSchema.dropField(IncrementalUpdateUtils.UPDATE_COLUMN);
    Schema oldIcebergSchema = schemaConverter.toIcebergSchema(batchSchemaWithoutUpdateColumn);
    Schema newIcebergSchema = schemaConverter.toIcebergSchema(newSchemaWithoutUpdateColumn);

    List<Types.NestedField> oldFields = oldIcebergSchema.columns();
    List<Types.NestedField> newFields = newIcebergSchema.columns();

    Map<String, Types.NestedField> nameToTypeOld =
        oldFields.stream().collect(Collectors.toMap(x -> x.name(), x -> x));
    Map<String, Types.NestedField> nameToTypeNew =
        newFields.stream().collect(Collectors.toMap(x -> x.name(), x -> x));

    /*
      Collecting updated and drop columns here. Columns must not be dropped for filesystem.
    */
    for (Map.Entry<String, Types.NestedField> entry : nameToTypeOld.entrySet()) {
      Types.NestedField newType = nameToTypeNew.get(entry.getKey());
      if (newType != null && isColumnUpdated(newType, entry.getValue())) {
        updatedColumnTypes.add(newType);
      }
    }

    for (Map.Entry<String, Types.NestedField> entry : nameToTypeNew.entrySet()) {
      if (!nameToTypeOld.containsKey(entry.getKey())) {
        newColumnTypes.add(entry.getValue());
      }
    }

    for (Map.Entry<String, Types.NestedField> entry : nameToTypeOld.entrySet()) {
      if (!nameToTypeNew.containsKey(entry.getKey())) {
        if (!entry.getValue().name().equals(IncrementalUpdateUtils.UPDATE_COLUMN)) {
          dropColumns.add(entry.getValue());
        }
      }
    }

    Comparator<Types.NestedField> fieldComparator =
        Comparator.comparing(Types.NestedField::fieldId);
    Collections.sort(newColumnTypes, fieldComparator);
    Collections.sort(updatedColumnTypes, fieldComparator);
    Collections.sort(dropColumns, fieldComparator);
    this.batchSchema = newSchema;
    this.datasetCatalogRequestBuilder.overrideSchema(newSchema);
  }

  private boolean isColumnUpdated(Types.NestedField newField, Types.NestedField oldField) {
    if (newField.isOptional() != oldField.isOptional()) {
      return true;
    } else if (!newField.name().equals(oldField.name())) {
      return true;
    } else if (!Objects.equals(newField.doc(), oldField.doc())) {
      return true;
    }
    return !newField.type().equals(oldField.type());
  }

  @Override
  public String getRootPointer() {
    return icebergCommand.getRootPointer();
  }

  @Override
  public Map<Integer, PartitionSpec> getCurrentSpecMap() {
    return icebergCommand.getPartitionSpecMap();
  }

  @Override
  public Schema getCurrentSchema() {
    return icebergCommand.getIcebergSchema();
  }

  @Override
  public boolean isIcebergTableUpdated() {
    return !icebergCommand.getRootPointer().equals(prevMetadataRootPointer);
  }

  @Override
  public void updateReadSignature(ByteString newReadSignature) {
    logger.debug("Updating read signature");
    datasetCatalogRequestBuilder.setReadSignature(newReadSignature);
  }

  @VisibleForTesting
  public void disableUseDefaultPeriod() {
    enableUseDefaultPeriod = false;
  }

  @VisibleForTesting
  public void disableKvstoreRetry() {
    kvstoreRetryEnabled = false;
  }

  @VisibleForTesting
  public void setMinSnapshotsToKeep(int minSnapshotsToKeep) {
    this.minSnapshotsToKeep = minSnapshotsToKeep;
  }

  @VisibleForTesting
  public void setTable(Table table) {
    this.table = table;
  }

  @VisibleForTesting
  public DatasetCatalogRequestBuilder getDatasetCatalogRequestBuilder() {
    return datasetCatalogRequestBuilder;
  }

  @VisibleForTesting
  public List<Types.NestedField> getUpdatedColumnTypes() {
    return updatedColumnTypes;
  }
}
