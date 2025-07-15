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

import static com.dremio.sabot.op.writer.WriterCommitterOperator.SnapshotCommitStatus.COMMITTED;
import static com.dremio.sabot.op.writer.WriterCommitterOperator.SnapshotCommitStatus.NONE;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.expr.fn.impl.ByteArrayWrapper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.manifestwriter.IcebergCommitOpHelper;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.writer.WriterCommitterOperator;
import com.dremio.sabot.op.writer.WriterCommitterOperator.SnapshotCommitStatus;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowLevelOperationMode;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.util.CharSequenceSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class used to commit DML (delete, update, merge) table operation, which has two update operation
 * DELETE Followed by INSERT
 */
public class IcebergDmlOperationCommitter implements IcebergOpCommitter {
  private static final Logger logger = LoggerFactory.getLogger(IcebergDmlOperationCommitter.class);

  private List<ManifestFile> manifestFileList = new ArrayList<>();
  private final List<DeleteFile> positionalDeleteFileList = new ArrayList<>();
  private final List<DataFile> mergeOnReadDataFileList = new ArrayList<>();
  private List<String> deletedDataFilePathList = new ArrayList<>();

  // data files referenced by positional delete rows
  private final CharSequenceSet referencedDataFiles = CharSequenceSet.empty();

  private final IcebergCommand icebergCommand;
  private final OperatorStats operatorStats;
  private final String prevMetadataRootPointer;
  private final Long startingSnapshotId;
  private final boolean isConcurrencyEnabled;

  private final RowLevelOperationMode dmlWriteMode;

  public IcebergDmlOperationCommitter(
      OperatorContext operatorContext,
      IcebergCommand icebergCommand,
      DatasetConfig datasetConfig,
      Long startingSnapshotId,
      RowLevelOperationMode dmlWriteMode) {
    Preconditions.checkState(icebergCommand != null, "Unexpected state");
    Preconditions.checkNotNull(
        datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation());
    this.operatorStats = operatorContext.getStats();
    this.icebergCommand = icebergCommand;
    this.prevMetadataRootPointer =
        datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
    this.startingSnapshotId = startingSnapshotId;
    this.isConcurrencyEnabled =
        operatorContext.getOptions().getOption(ExecConstants.ENABLE_ICEBERG_CONCURRENCY);
    this.dmlWriteMode = dmlWriteMode;
  }

  @Override
  public Snapshot commit() {
    return commitImpl(false);
  }

  /**
   * Commit the DML operation
   *
   * @param skipBeginOperation Skip the 'begin' operation
   * @return
   */
  public Snapshot commitImpl(boolean skipBeginOperation /* test only */) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    SnapshotCommitStatus commitStatus = NONE;
    Snapshot snapshot = null;
    try {
      if (!skipBeginOperation) {
        beginDmlOperationTransaction();
      }
      Snapshot currentSnapshot = icebergCommand.getCurrentSnapshot();
      switch (dmlWriteMode) {
        case COPY_ON_WRITE:
          performCopyOnWriteTransaction();
          break;
        case MERGE_ON_READ:
          performMergeOnReadTransaction();
          break;
        default:
          throw new UnsupportedOperationException("Unsupported Dml Write Mode: " + dmlWriteMode);
      }
      snapshot = endDmlOperationTransaction().currentSnapshot();
      commitStatus =
          (currentSnapshot != null && snapshot.snapshotId() == startingSnapshotId)
              ? NONE
              : COMMITTED;
      return snapshot;
    } catch (ValidationException
        | CommitFailedException
        | CommitStateUnknownException
        | IllegalStateException e) {
      logger.error(CONCURRENT_OPERATION_ERROR, e);
      throw UserException.concurrentModificationError(e)
          .message(CONCURRENT_OPERATION_ERROR)
          .buildSilently();
    } finally {
      long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      operatorStats.addLongStat(
          WriterCommitterOperator.Metric.ICEBERG_COMMIT_TIME, totalCommitTime);

      IcebergOpCommitter.writeSnapshotStats(operatorStats, commitStatus, snapshot);
    }
  }

  /** Perform the merge on read transaction */
  @VisibleForTesting
  public void performMergeOnReadTransaction() {
    if (mergeOnReadHasAnythingChanged()) {
      Preconditions.checkArgument(
          startingSnapshotId != null, "DML commit does not specify starting snapshot id");
      if (isConcurrencyEnabled) {
        Expression conflictDetectionFilter = Expressions.alwaysTrue();

        icebergCommand.beginSerializableIsolationRowDelta(
            referencedDataFiles, startingSnapshotId, conflictDetectionFilter);
      } else {
        icebergCommand.beginRowDelta(startingSnapshotId);
      }

      // Commit the delete files
      if (!positionalDeleteFileList.isEmpty()) {
        posDeleteFileLogging();
        icebergCommand.consumePositionalDeleteFiles(positionalDeleteFileList);
      }

      // Commit the data files
      if (!mergeOnReadDataFileList.isEmpty()) {
        dataFileLogging();
        icebergCommand.consumeMergeOnReadDataFiles(mergeOnReadDataFileList);
      }
      // Commit the RowDelta operation
      icebergCommand.finishRowDelta();
    }
  }

  private void posDeleteFileLogging() {
    if (logger.isDebugEnabled()) {
      logger.debug("Committing {} delete file(s).", positionalDeleteFileList.size());
      positionalDeleteFileList.forEach(
          l ->
              logger.debug(
                  "Committing delete file: {}, with {} positional delete records.",
                  l.path(),
                  l.recordCount()));
    }
  }

  private void dataFileLogging() {
    if (logger.isDebugEnabled()) {
      logger.debug("Committing {} data file(s).", mergeOnReadDataFileList.size());
      mergeOnReadDataFileList.forEach(
          l ->
              logger.debug(
                  "Committing data file: {}, with {} records.", l.path(), l.recordCount()));
    }
  }

  private boolean copyOnWriteHasAnythingChanged() {
    return deletedDataFilePathList.size() + manifestFileList.size() > 0;
  }

  private boolean mergeOnReadHasAnythingChanged() {
    return mergeOnReadDataFileList.size() + positionalDeleteFileList.size() > 0;
  }

  @VisibleForTesting
  public void beginDmlOperationTransaction() {
    if (!isConcurrencyEnabled && isIcebergTableUpdated()) {
      String metadataFiles =
          String.format(
              "Expected metadataRootPointer: %s, Found metadataRootPointer: %s",
              prevMetadataRootPointer, getRootPointer());
      logger.error(CONCURRENT_OPERATION_ERROR + metadataFiles);
      throw UserException.concurrentModificationError()
          .message(CONCURRENT_OPERATION_ERROR)
          .buildSilently();
    }
    icebergCommand.beginTransaction();
  }

  @VisibleForTesting
  public Table endDmlOperationTransaction() {
    return icebergCommand.endTransaction();
  }

  @VisibleForTesting
  public List<String> getDeletedDataFilePaths() {
    return deletedDataFilePathList;
  }

  @VisibleForTesting
  public void performCopyOnWriteTransaction() {
    if (copyOnWriteHasAnythingChanged()) {
      Preconditions.checkArgument(
          startingSnapshotId != null, "DML commit does not specify starting snapshot id");
      if (isConcurrencyEnabled) {
        // The conflictDetectionFilter is set to be TRUE and the overwriteFiles will be rejected if
        // there are any
        // conflicting DataFiles or DeleteFiles committed by concurrent commits.
        // In the future, we can improve further, 1) bringing simple filters down here to further
        // improve the concurrent
        // commits for DML cases, 2) supporting Isolation at SNAPSHOT level.
        Expression conflictDetectionFilter = Expressions.alwaysTrue();
        icebergCommand.beginSerializableIsolationOverwrite(
            startingSnapshotId, conflictDetectionFilter);
      } else {
        icebergCommand.beginOverwrite(startingSnapshotId);
      }
      if (!deletedDataFilePathList.isEmpty()) {
        logger.debug(
            "Committing delete data files, file count: {} ", deletedDataFilePathList.size());
        icebergCommand.consumeDeleteDataFilesWithOverwriteByPaths(deletedDataFilePathList);
      }

      if (!manifestFileList.isEmpty()) {
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
      }
      icebergCommand.finishOverwrite();
    }
  }

  @Override
  public void cleanup(FileIO fileIO) {
    IcebergCommitOpHelper.deleteManifestFiles(fileIO, manifestFileList, true);
  }

  @Override
  public void consumeManifestFile(ManifestFile icebergManifestFile) {
    manifestFileList.add(icebergManifestFile);
  }

  @Override
  public void consumePositionalDeleteFile(
      DeleteFile positionalDeleteFile, Set<ByteArrayWrapper> referencedDataFiles)
      throws UnsupportedOperationException {
    positionalDeleteFileList.add(positionalDeleteFile);
    for (ByteArrayWrapper dataFile : referencedDataFiles) {
      this.referencedDataFiles.add(new String(dataFile.getBytes(), StandardCharsets.UTF_8));
    }
  }

  @Override
  public void consumeMergeOnReadAddDataFile(DataFile mergeOnReadDataFile)
      throws UnsupportedOperationException {
    mergeOnReadDataFileList.add(mergeOnReadDataFile);
  }

  @Override
  public void consumeDeleteDataFile(DataFile icebergDeleteDatafile)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Deleting data file by DataFile object is not supported in DML table Transaction");
  }

  @Override
  public void consumeDeleteDataFilePath(String icebergDeleteDatafilePath)
      throws UnsupportedOperationException {
    deletedDataFilePathList.add(icebergDeleteDatafilePath);
  }

  @Override
  public void updateSchema(BatchSchema newSchema) {
    throw new UnsupportedOperationException(
        "Updating schema is not supported for DML table Transaction");
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
    return !Path.getContainerSpecificRelativePath(Path.of(getRootPointer()))
        .equals(Path.getContainerSpecificRelativePath(Path.of(prevMetadataRootPointer)));
  }
}
