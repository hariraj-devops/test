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

import static com.dremio.exec.planner.VacuumCatalogUtil.isGCEnabledForIcebergTable;
import static com.dremio.exec.planner.sql.handlers.SqlHandlerUtil.getTimestampFromMillis;
import static com.dremio.exec.store.iceberg.IcebergUtils.CLUSTERING_INFO;
import static com.dremio.exec.store.iceberg.IcebergUtils.PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY;
import static com.dremio.exec.store.iceberg.model.IcebergOpCommitter.CONCURRENT_OPERATION_ERROR;
import static org.apache.iceberg.DremioTableProperties.NESSIE_GC_ENABLED;
import static org.apache.iceberg.TableProperties.GC_ENABLED;
import static org.apache.iceberg.Transactions.createTableTransaction;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.catalog.PartitionSpecAlterOption;
import com.dremio.exec.catalog.RollbackOption;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.planner.sql.PartitionTransform;
import com.dremio.exec.planner.sql.parser.SqlAlterTablePartitionColumns;
import com.dremio.exec.proto.ExecProtos.ClusteringStatus;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.FieldIdBroker;
import com.dremio.exec.store.iceberg.IcebergExpiryAction;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SnapshotEntry;
import com.dremio.io.file.FileSystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.ManageSnapshots;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PendingUpdate;
import org.apache.iceberg.ReplaceSortOrder;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Term;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.CharSequenceSet;
import org.apache.iceberg.util.PropertyUtil;

/** Base Iceberg catalog */
public class IcebergBaseCommand implements IcebergCommand {
  public static final String DREMIO_JOB_ID_ICEBERG_PROPERTY = "dremio-job-id";
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(IcebergBaseCommand.class);
  private static final String MANIFEST_FILE_DEFAULT_SIZE = "153600";
  private Transaction transaction;
  private final TableOperations tableOperations;
  private AppendFiles appendFiles;
  private DeleteFiles deleteFiles;

  private List<DeleteFile> positionalDeleteFileList;
  private OverwriteFiles overwriteFiles;

  private RowDelta rowDelta;
  private final Configuration configuration;
  protected final Path fsPath;
  private Snapshot currentSnapshot;
  private final UserBitShared.QueryId queryId;
  private ClusteringStatus clusteringStatus;

  public IcebergBaseCommand(
      Configuration configuration,
      String tableFolder,
      TableOperations tableOperations,
      UserBitShared.QueryId queryId) {
    this.configuration = configuration;
    transaction = null;
    currentSnapshot = null;
    fsPath = new Path(tableFolder);
    this.tableOperations = tableOperations;
    this.queryId = queryId;
  }

  @Override
  public void beginCreateTableTransaction(
      String tableName,
      BatchSchema writerSchema,
      List<String> partitionColumns,
      Map<String, String> tableProperties,
      PartitionSpec partitionSpec,
      SortOrder sortOrder) {
    Preconditions.checkState(transaction == null, "Unexpected state - transaction should be null");
    Preconditions.checkNotNull(tableOperations);
    Schema schema;
    try {
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(tableName).build();
      schema = schemaConverter.toIcebergSchema(writerSchema);
    } catch (Exception ex) {
      throw UserException.validationError(ex).buildSilently();
    }
    if (partitionSpec == null) {
      partitionSpec = IcebergUtils.getIcebergPartitionSpec(writerSchema, partitionColumns, null);
    }
    if (sortOrder == null) {
      SortOrder.Builder sortOrderBuilder = SortOrder.builderFor(partitionSpec.schema());
      sortOrder = sortOrderBuilder.build();
    }

    Map<String, String> tableProp =
        tableProperties == null ? Collections.emptyMap() : new HashMap<>(tableProperties);
    tableProp.put(TableProperties.MANIFEST_TARGET_SIZE_BYTES, MANIFEST_FILE_DEFAULT_SIZE);
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            schema, partitionSpec, sortOrder, getTableLocation(), tableProp);

    if (tableOperations.current() != null) {
      throw UserException.validationError()
          .message("A table with the given name already exists")
          .buildSilently();
    }
    transaction = createTableTransaction(tableName, tableOperations, metadata);
    transaction.table();
  }

  @Override
  public void registerTable(TableMetadata tableMetadata) {
    tableOperations.commit(null, tableMetadata);
  }

  @Override
  public void beginTransaction() {
    Preconditions.checkState(transaction == null, "Unexpected state");
    Table table = loadTable();
    transaction = table.newTransaction();
  }

  @Override
  public Table endTransaction() {
    transaction.commitTransaction();
    Table table = transaction.table();
    transaction = null;
    return table;
  }

  @Override
  public void beginOverwrite(long snapshotId) {
    Preconditions.checkState(transaction != null, "Unexpected state");
    // Mark the transaction as a read-modify-write transaction. When performing DML (DELETE, UPDATE,
    // MERGE) operations to update an iceberg table, the version of the table while updating should
    // be the same as the version that was read.

    // Metadata refresh also use this API
    overwriteFiles =
        transaction.newOverwrite().validateFromSnapshot(snapshotId).validateNoConflictingData();
  }

  @Override
  public void beginRowDelta(Long snapshotId) {
    Preconditions.checkState(transaction != null, "Unexpected state");
    // RowDelta is used to track positional deleteFiles (merge-on-read DML mode)
    // Mark the transaction as a read-modify-write transaction. When performing DML (DELETE, UPDATE,
    // MERGE) operations
    // to update an iceberg table, the version of the table while updating should be the same as the
    // version that was read.
    rowDelta =
        transaction
            .newRowDelta()
            .validateFromSnapshot(snapshotId)
            .validateNoConflictingDataFiles()
            .validateNoConflictingDeleteFiles();
  }

  @Override
  public void beginSerializableIsolationOverwrite(
      long snapshotId, Expression conflictDetectionFilter) {
    Preconditions.checkState(transaction != null, "Unexpected state");
    overwriteFiles =
        transaction
            .newOverwrite()
            .validateFromSnapshot(snapshotId)
            .conflictDetectionFilter(conflictDetectionFilter)
            .validateNoConflictingData()
            .validateNoConflictingDeletes();
  }

  @Override
  public void beginSerializableIsolationRowDelta(
      CharSequenceSet referencedDataFiles, Long snapshotId, Expression conflictDetectionFilter) {
    Preconditions.checkState(transaction != null, "Unexpected state");
    rowDelta =
        transaction
            .newRowDelta()
            .validateFromSnapshot(snapshotId)
            .conflictDetectionFilter(conflictDetectionFilter)
            .validateNoConflictingDataFiles()
            .validateNoConflictingDeleteFiles()
            .validateDataFilesExist(referencedDataFiles) // unique to row delta
            .validateDeletedFiles(); // unique to row delta
  }

  @Override
  public void finishOverwrite() {
    stampSnapshotUpdateWithDremioJobId(overwriteFiles);
    overwriteFiles.commit();
    transaction.table().currentSnapshot();
  }

  @Override
  public void finishRowDelta() {
    stampSnapshotUpdateWithDremioJobId(rowDelta);
    rowDelta.commit();
    transaction.table().currentSnapshot();
  }

  @Override
  public Snapshot rewriteFiles(
      Set<DataFile> removedDataFiles,
      Set<DeleteFile> removedDeleteFiles,
      Set<DataFile> addedDataFiles,
      Set<DeleteFile> addedDeleteFiles,
      Long snapshotId) {
    if (transaction == null) {
      beginTransaction();
    }
    try {
      RewriteFiles rewriteFiles = transaction.newRewrite();
      if (clusteringStatus != null) {
        // 5 digits after dot
        DecimalFormat df = new DecimalFormat("#." + "#".repeat(5));
        rewriteFiles.set(
            CLUSTERING_INFO,
            String.format(
                "startClusteringDepth = %s estimatedEndClusteringDepth = %s",
                df.format(clusteringStatus.getStartClusteringDepth()),
                df.format(clusteringStatus.getEstimatedEndsClusteringDepth())));
      }
      rewriteFiles
          .validateFromSnapshot(snapshotId)
          .rewriteFiles(removedDataFiles, removedDeleteFiles, addedDataFiles, addedDeleteFiles)
          .commit();
      return transaction.table().currentSnapshot();
    } finally {
      endTransaction();
    }
  }

  @Override
  public void consumeDeleteDataFilesWithOverwriteByPaths(List<String> filePathsList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(overwriteFiles != null, "OverwriteFiles was not started");
    filePathsList.forEach(x -> overwriteFiles.deleteFile(x));
  }

  @Override
  public void consumeManifestFilesWithOverwrite(List<ManifestFile> filesList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(overwriteFiles != null, "OverwriteFiles was not started");
    filesList.forEach(x -> overwriteFiles.appendManifest(x));
  }

  @Override
  public void beginDelete() {
    Preconditions.checkState(transaction != null, "Unexpected state");
    deleteFiles = transaction.newDelete();
  }

  @Override
  public Snapshot finishDelete() {
    stampSnapshotUpdateWithDremioJobId(deleteFiles);
    deleteFiles.commit();
    return transaction.table().currentSnapshot();
  }

  @Override
  public void consumeUpdatedColumns(List<Types.NestedField> columns) {
    consumeDroppedColumns(columns);
    consumeAddedColumns(columns);
  }

  @Override
  public void consumeDroppedColumns(List<Types.NestedField> columns) {
    UpdateSchema updateSchema = transaction.updateSchema();
    for (Types.NestedField col : columns) {
      updateSchema.deleteColumn(col.name());
    }
    updateSchema.commit();
  }

  @Override
  public void consumeAddedColumns(List<Types.NestedField> columns) {
    UpdateSchema updateSchema = transaction.updateSchema();
    // DX-66623: Call the api that omits to check dot or '.' in the column name.
    columns.forEach(c -> updateSchema.addColumn(null, c.name(), c.type()));
    updateSchema.commit();
  }

  @Override
  public void beginInsert() {
    Preconditions.checkState(transaction != null, "Unexpected state");
    appendFiles = transaction.newAppend();
  }

  @Override
  public Snapshot finishInsert() {
    stampSnapshotUpdateWithDremioJobId(appendFiles);
    appendFiles.commit();
    return transaction.table().currentSnapshot();
  }

  private void stampSnapshotUpdateWithDremioJobId(SnapshotUpdate snapshotUpdate) {
    if (queryId == null) {
      logger.warn("Not adding jobId as Iceberg snapshot update property for {}", getTableName());
      return;
    }
    snapshotUpdate.set(DREMIO_JOB_ID_ICEBERG_PROPERTY, QueryIdHelper.getQueryId(queryId));
  }

  @Override
  public List<SnapshotEntry> expireSnapshots(
      long olderThanInMillis, int retainLast, boolean throwIcebergException) {
    return performExpire(olderThanInMillis, retainLast, throwIcebergException, false);
  }

  @Override
  public List<SnapshotEntry> expireSnapshotsForVersionedTable(
      long olderThanInMillis, int retainLast, boolean throwIcebergException) {
    return performExpire(olderThanInMillis, retainLast, throwIcebergException, true);
  }

  private List<SnapshotEntry> performExpire(
      long olderThanInMillis,
      int retainLast,
      boolean throwIcebergException,
      boolean isVersionedTable) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    // perform expiration
    String olderThanTimestamp = getTimestampFromMillis(olderThanInMillis);
    try {
      Table table = loadTable();
      // These checks are combined as we expect all versioned tables to have gc.enabled set to false
      // by default
      if ((isVersionedTable && isGCEnabledForIcebergTable(table.properties()))
          || PropertyUtil.propertyAsBoolean(table.properties(), GC_ENABLED, true)) {
        logger.info(
            "Trying to expire {}'s snapshots, which are older than {}, min {} snapshots will be retained.",
            table.name(),
            olderThanTimestamp,
            retainLast);
        ExpireSnapshots expireSnapshots =
            IcebergExpiryAction.getIcebergExpireSnapshots(table, olderThanInMillis, retainLast);
        if (throwIcebergException) {
          // Directly throw Iceberg native exceptions, and the callers will handle it.
          expireSnapshots.commit();
        } else {
          // Wrap Iceberg exceptions as CONCURRENT_MODIFICATION_EXCEPTION (CME)
          performNonTransactionCommit(expireSnapshots);
        }
      } else {
        logger.warn(
            "Skipping expiry on {} because {} is set to 'false'",
            table.name(),
            isVersionedTable ? NESSIE_GC_ENABLED : GC_ENABLED);
      }
      table.refresh();
      return findSnapshots(tableOperations.refresh());
    } finally {
      long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      logger.info("Iceberg ExpireSnapshots call took {} ms.", totalCommitTime);
    }
  }

  private List<SnapshotEntry> findSnapshots(TableMetadata metadata) {
    if (metadata.snapshots() == null) {
      return Collections.emptyList();
    }
    return metadata.snapshots().stream()
        .map(s -> new SnapshotEntry(metadata.metadataFileLocation(), s))
        .collect(Collectors.toList());
  }

  @Override
  public void rollback(RollbackOption rollbackOption) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Table table = loadTable();
    ManageSnapshots manageSnapshots = table.manageSnapshots();
    Preconditions.checkState(manageSnapshots != null, "ManageSnapshots was not started");
    long rollbackValue = rollbackOption.getValue();
    final boolean isSnapshot = RollbackOption.Type.SNAPSHOT == rollbackOption.getType();
    Snapshot currentSnapshot = table.currentSnapshot();

    // Cannot rollback table to the current snapshot
    if ((isSnapshot && rollbackValue == currentSnapshot.snapshotId())
        || (rollbackValue == currentSnapshot.timestampMillis())) {
      throw UserException.unsupportedError()
          .message("Cannot rollback table to current snapshot")
          .buildSilently();
    }

    try {
      if (isSnapshot) {
        if (table.snapshot(rollbackValue) == null) {
          // If rolling back to unknown snapshot id, we can make the error message clear and
          // concise.
          final String errorMsg =
              String.format(
                  "Cannot rollback table to unknown snapshot ID %s",
                  rollbackOption.getLiteralValue());
          logger.error(errorMsg);
          throw UserException.unsupportedError().message(errorMsg).buildSilently();
        }
        logger.info("Trying to rollback iceberg table to snapshot ID {}", rollbackValue);
        manageSnapshots.rollbackTo(rollbackValue);
      } else {
        final Snapshot firstSnapshot = Iterables.getFirst(table.snapshots(), null);
        if (firstSnapshot != null && rollbackValue < firstSnapshot.timestampMillis()) {
          // If rolling back to the timestamp that is older than the table's first snapshot,
          // we can make the error message clear and concise.
          final String errorMsg =
              String.format(
                  "Cannot rollback table, no valid snapshot older than: %s",
                  rollbackOption.getLiteralValue());
          logger.error(errorMsg);
          throw UserException.unsupportedError().message(errorMsg).buildSilently();
        }
        logger.info(
            "Trying to rollback iceberg table to snapshot before timestamp {}", rollbackValue);
        // Increase 1 millisecond to the given value. When users put the timestamp that matches a
        // snapshot, this can
        // help to roll table back to that particular snapshot.
        manageSnapshots.rollbackToTime(rollbackValue + 1);
      }
      performNonTransactionCommit(manageSnapshots);
      table.refresh();
    } catch (Exception e) {
      String errorMsg =
          String.format(
              "Cannot rollback table to snapshot ID " + (isSnapshot ? "%s" : "before timestamp %s"),
              rollbackOption.getLiteralValue());

      // Append the error message that is specifically reported by Iceberg.
      errorMsg = errorMsg + ": " + e.getMessage();
      logger.error(errorMsg, e);
      throw UserException.unsupportedError(e).message(errorMsg).buildSilently();
    } finally {
      long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      logger.info("Iceberg Rollback call takes {} milliseconds.", totalCommitTime);
    }
  }

  @Override
  public void consumeManifestFiles(List<ManifestFile> filesList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(appendFiles != null, "AppendFiles was not started");
    filesList.forEach(x -> appendFiles.appendManifest(x));
  }

  @Override
  public void consumeDeleteDataFiles(List<DataFile> filesList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(deleteFiles != null, "DeleteFiles was not started");
    filesList.forEach(x -> deleteFiles.deleteFile(x.path()));
  }

  @Override
  public void consumePositionalDeleteFiles(List<DeleteFile> positionalDeleteFileList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(rowDelta != null, "rowDelta was not started");
    positionalDeleteFileList.forEach(x -> rowDelta.addDeletes(x));
  }

  @Override
  public void consumeMergeOnReadDataFiles(List<DataFile> mergeOnReadDataFilesList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(rowDelta != null, "rowDelta was not started");
    mergeOnReadDataFilesList.forEach(x -> rowDelta.addRows(x));
  }

  @Override
  public void consumeDeleteDataFilesByPaths(List<String> filePathsList) {
    Preconditions.checkState(transaction != null, "Transaction was not started");
    Preconditions.checkState(deleteFiles != null, "DeleteFiles was not started");
    filePathsList.forEach(p -> deleteFiles.deleteFile(p));
  }

  @Override
  public void truncateTable() {
    try {
      Preconditions.checkState(transaction == null, "Unexpected state");
      Table table = loadTable();
      transaction = table.newTransaction();
      transaction.newDelete().deleteFromRowFilter(Expressions.alwaysTrue()).commit();
      transaction.commitTransaction();
      transaction = null;
    } catch (ValidationException | CommitFailedException | CommitStateUnknownException e) {
      logger.error(CONCURRENT_OPERATION_ERROR, e);
      throw UserException.concurrentModificationError(e)
          .message(CONCURRENT_OPERATION_ERROR)
          .buildSilently();
    }
  }

  @Override
  public void updatePropertiesInTransaction(Map<String, String> tblProperties) {
    if (tblProperties == null || tblProperties.isEmpty()) {
      logger.warn("Skipping updateProperties because properties to be set is empty");
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    UpdateProperties properties = transaction.table().updateProperties();
    tblProperties.forEach(properties::set);
    properties.commit();
    long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    logger.info("Iceberg UpdateProperties call takes {} milliseconds.", totalCommitTime);
  }

  @Override
  public void updateProperties(Map<String, String> tblProperties) {
    if (tblProperties == null || tblProperties.isEmpty()) {
      logger.warn("Skipping updateProperties because properties to be set is empty");
      return;
    }

    Stopwatch stopwatch = Stopwatch.createStarted();
    UpdateProperties properties = loadTable().updateProperties();
    tblProperties.forEach(properties::set);
    performNonTransactionCommit(properties);

    long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    logger.info("Iceberg UpdateProperties call takes {} milliseconds.", totalCommitTime);
  }

  @Override
  public void removeProperties(List<String> tblProperties) {
    if (tblProperties == null || tblProperties.isEmpty()) {
      logger.warn("Skipping removeProperties because properties to be removed is empty");
    } else {
      Stopwatch stopwatch = Stopwatch.createStarted();
      UpdateProperties properties;
      properties = loadTable().updateProperties();
      tblProperties.forEach(properties::remove);
      performNonTransactionCommit(properties);
      long totalCommitTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      logger.info("Iceberg removeProperties call takes {} milliseconds.", totalCommitTime);
    }
  }

  @Override
  public void addColumns(List<Types.NestedField> columnsToAdd) {
    Table table = loadTable();
    UpdateSchema updateSchema = table.updateSchema();
    columnsToAdd.forEach(x -> updateSchema.addColumn(null, x.name(), x.type(), x.doc()));
    performNonTransactionCommit(updateSchema);
  }

  @Override
  public void deleteTable() {
    try {
      com.dremio.io.file.Path p = com.dremio.io.file.Path.of(fsPath.toString());
      getFs().delete(p, true);
    } catch (IOException e) {
      String message =
          String.format(
              "The dataset is now forgotten by dremio, but there was an error while cleaning up respective data and metadata files residing at %s.",
              fsPath);
      logger.error(message);
      throw new UncheckedIOException(message, e);
    }
  }

  @Override
  public void updatePartitionSpec(PartitionSpecAlterOption partitionSpecAlterOption) {
    Preconditions.checkState(transaction == null, "Unexpected state");
    Table table = loadTable();
    try {
      SqlAlterTablePartitionColumns.Mode mode = partitionSpecAlterOption.getMode();
      PartitionTransform partitionTransform = partitionSpecAlterOption.getPartitionTransform();
      Term term = IcebergUtils.getIcebergTerm(partitionTransform);
      switch (mode) {
        case ADD:
          table.updateSpec().caseSensitive(false).addField(term).commit();
          if (table.properties().containsKey(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY)) {
            table.updateProperties().remove(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY).commit();
          }
          break;
        case DROP:
          table.updateSpec().caseSensitive(false).removeField(term).commit();
          table.refresh();
          if (!table.spec().isPartitioned()) {
            table
                .updateProperties()
                .set(
                    PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY,
                    String.valueOf(table.currentSnapshot().sequenceNumber()))
                .commit();
          }
          break;
      }
    } catch (ValidationException | CommitFailedException | CommitStateUnknownException e) {
      logger.error(CONCURRENT_OPERATION_ERROR, e);
      throw UserException.concurrentModificationError(e)
          .message(CONCURRENT_OPERATION_ERROR)
          .buildSilently();
    } catch (IllegalArgumentException e) {
      // It could drop the partition that does not exist.
      throw UserException.unsupportedError(e).message(e.getMessage().toString()).buildSilently();
    }
  }

  @Override
  public void deleteTableRootPointer() {}

  @Override
  public void beginAlterTableTransaction() {
    Preconditions.checkState(transaction == null, "Unexpected state");
    Table table = loadTable();
    transaction = table.newTransaction();
  }

  @Override
  public Table endAlterTableTransaction() {
    transaction.commitTransaction();
    return transaction.table();
  }

  @Override
  public void addColumnsInternalTable(List<Field> columnsToAdd) {
    UpdateSchema updateSchema = transaction.updateSchema();
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    List<Types.NestedField> icebergFields = schemaConverter.toIcebergFields(columnsToAdd);
    icebergFields.forEach(c -> updateSchema.addColumn(null, c.name(), c.type(), c.doc()));
    updateSchema.commit();
  }

  @Override
  public void dropColumnInternalTable(String columnToDrop) {
    dropColumn(columnToDrop, transaction.table(), transaction.updateSchema(), true);
  }

  @Override
  public void changeColumnForInternalTable(String columnToChange, Field batchField) {
    UpdateSchema schema = transaction.updateSchema();
    dropColumn(columnToChange, transaction.table(), schema, false);
    SchemaConverter converter = SchemaConverter.getBuilder().build();
    List<Types.NestedField> nestedFields = converter.toIcebergFields(ImmutableList.of(batchField));
    schema.addColumn(
        null, nestedFields.get(0).name(), nestedFields.get(0).type(), nestedFields.get(0).doc());
    schema.commit();
  }

  @Override
  public void dropColumn(String columnToDrop) {
    Table table = loadTable();
    dropColumn(columnToDrop, table, table.updateSchema(), true);
  }

  public void dropColumn(
      String columnToDrop, Table table, UpdateSchema updateSchema, boolean isCommit) {
    Types.NestedField columnInIceberg = table.schema().caseInsensitiveFindField(columnToDrop);
    if (!table
        .spec()
        .getFieldsBySourceId(columnInIceberg.fieldId())
        .isEmpty()) { // column is part of partitionspec
      throw UserException.unsupportedError()
          .message(
              "[%s] is a partition column. Partition spec change is not supported.",
              columnInIceberg.name())
          .buildSilently();
    } else if (table.sortOrder().schema().findField(columnInIceberg.fieldId())
        != null) { // column is part of SortOrder/ClusterKey
      throw UserException.unsupportedError()
          .message(
              "[%s] is a clustering key or sort order column. This change is not supported.",
              columnInIceberg.name())
          .buildSilently();
    }
    updateSchema =
        updateSchema.deleteColumn(table.schema().findColumnName(columnInIceberg.fieldId()));
    if (isCommit) {
      performNonTransactionCommit(updateSchema);
    }
  }

  @Override
  public void changeColumn(String columnToChange, Field batchField) {
    Table table = loadTable();
    UpdateSchema updateSchema = table.updateSchema();
    changeColumn(
        columnToChange,
        batchField,
        table,
        SchemaConverter.getBuilder().setTableName(table.name()).build(),
        updateSchema,
        false);
    performNonTransactionCommit(updateSchema);
  }

  /**
   * TODO: currently this function is called from unit tests only. need to revisit it when we
   * implement alter table rename column command renames an existing column name.
   *
   * @param name existing name in the table
   * @param newName new name for the column
   */
  @Override
  public void renameColumn(String name, String newName) {
    Table table = loadTable();
    UpdateSchema updateSchema = table.updateSchema().renameColumn(name, newName);
    performNonTransactionCommit(updateSchema);
  }

  @Override
  public void replaceSortOrder(List<String> sortOrder) {
    Table table = loadTable();
    ReplaceSortOrder newSortOrder = table.replaceSortOrder();
    if (sortOrder != null && !sortOrder.isEmpty()) {
      for (String sortColumn : sortOrder) {
        newSortOrder = newSortOrder.asc(sortColumn);
      }
    }

    performNonTransactionCommit(newSortOrder);
  }

  @Override
  public void updatePrimaryKey(List<Field> columns) {
    updateProperties(PrimaryKeyUpdateCommitter.getPropertiesMap(columns));
  }

  private String sqlTypeNameWithPrecisionAndScale(org.apache.iceberg.types.Type type) {
    SchemaConverter schemaConverter =
        SchemaConverter.getBuilder().setTableName(getTableName()).build();
    CompleteType completeType = schemaConverter.fromIcebergType(type);
    SqlTypeName calciteTypeFromMinorType =
        CalciteArrowHelper.getCalciteTypeFromMinorType(completeType.toMinorType());
    if (calciteTypeFromMinorType == SqlTypeName.DECIMAL) {
      return calciteTypeFromMinorType
          + "("
          + completeType.getPrecision()
          + ", "
          + completeType.getScale()
          + ")";
    }
    return calciteTypeFromMinorType.toString();
  }

  protected void performNonTransactionCommit(PendingUpdate pendingUpdate) {
    try {
      pendingUpdate.commit();
    } catch (ValidationException | CommitFailedException | CommitStateUnknownException e) {
      // Iceberg OverwriteFiles.commit or Transaction.commitTransaction could both throw
      // ValidationException.
      logger.error(CONCURRENT_OPERATION_ERROR, e);
      throw UserException.concurrentModificationError(e)
          .message(CONCURRENT_OPERATION_ERROR)
          .buildSilently();
    }
  }

  public String getTableName() {
    return fsPath.getName();
  }

  public String getTableLocation() {
    return IcebergUtils.getValidIcebergPath(fsPath, configuration, getFs().getScheme());
  }

  @Override
  public Table loadTable() {
    Table table = new DremioBaseTable(getTableOps(), getTableName());
    table.refresh();
    if (getTableOps().current() == null) {
      throw UserException.ioExceptionError(
              new IOException(
                  "Failed to load the Iceberg table. Please make sure to use correct Iceberg catalog and retry: "
                      + fsPath))
          .buildSilently();
    }
    this.currentSnapshot = table.currentSnapshot();
    return table;
  }

  private void changeColumn(
      String columnToChange,
      Field batchField,
      Table table,
      SchemaConverter schemaConverter,
      UpdateSchema updateSchema,
      boolean isInternalField) {
    Types.NestedField columnToChangeInIceberg =
        table.schema().caseInsensitiveFindField(columnToChange);
    if (!table
        .spec()
        .getFieldsBySourceId(columnToChangeInIceberg.fieldId())
        .isEmpty()) { // column is part of partitionspec
      throw UserException.unsupportedError()
          .message(
              "[%s] is a partition column. Partition spec change is not supported.",
              columnToChangeInIceberg.name())
          .buildSilently();
    }
    boolean isColumnToChangePrimitive = columnToChangeInIceberg.type().isPrimitiveType();
    boolean isNewDefComplex = batchField.getType().isComplex();
    if (isColumnToChangePrimitive && !isNewDefComplex) {
      changePrimitiveColumn(
          columnToChange,
          batchField,
          updateSchema,
          columnToChangeInIceberg,
          schemaConverter,
          table,
          isInternalField);
    }
    if (isColumnToChangePrimitive && isNewDefComplex) {
      throw UserException.unsupportedError()
          .message("Cannot convert a primitive field [%s] to a complex type", columnToChange)
          .buildSilently();
    }
    if (!isColumnToChangePrimitive && !isNewDefComplex) {
      throw UserException.unsupportedError()
          .message("Cannot convert a complex field [%s] to a primitive type", columnToChange)
          .buildSilently();
    }
    if (!isColumnToChangePrimitive && isNewDefComplex) {
      if ((columnToChangeInIceberg.type().isListType()
              && batchField.getType().getTypeID() != ArrowType.ArrowTypeID.List)
          || (columnToChangeInIceberg.type().isStructType()
              && batchField.getType().getTypeID() != ArrowType.ArrowTypeID.Struct)) {
        throw UserException.unsupportedError()
            .message(
                "Cannot convert complex field [%s] from [%s] to [%s]",
                columnToChange,
                columnToChangeInIceberg.type().toString(),
                batchField.getType().getTypeID().name())
            .buildSilently();
      } else {
        changeComplexColumn(
            columnToChangeInIceberg,
            batchField,
            schemaConverter,
            columnToChange,
            table,
            updateSchema,
            isInternalField);
      }
    }

    // Provide a more informative error message when trying to change
    // column nullability from optional to required.
    if (columnToChangeInIceberg.isOptional() && !batchField.isNullable()) {
      try {
        updateSchema.requireColumn(columnToChange);
      } catch (IllegalArgumentException e) {
        throw UserException.validationError()
            .message(
                e.getMessage()
                    + ". There maybe existing records with null values in the nullable column. "
                    + "Schema updates do not allow incompatible changes.")
            .buildSilently();
      }
    }
  }

  private void changeComplexColumn(
      Types.NestedField currentColumn,
      Field newFieldDef,
      SchemaConverter schemaConverter,
      String dottedParentColumnName,
      Table table,
      UpdateSchema updateSchema,
      boolean isInternalField) {
    List<Types.NestedField> currentChildren;
    if (currentColumn.type().isStructType()) {
      currentChildren = new ArrayList<>(currentColumn.type().asStructType().fields());
    } else if (currentColumn.type().isListType()) {
      currentChildren = new ArrayList<>(currentColumn.type().asListType().fields());
    } else {
      throw UserException.unsupportedError()
          .message(
              "Cannot convert a complex field [%s] of type [%s]",
              dottedParentColumnName, currentColumn.type().toString())
          .buildSilently();
    }
    List<Field> newChildren = newFieldDef.getChildren();
    for (Field newChild : newChildren) {
      if (currentChildren.size() == 1
          && currentChildren.get(0).name().equals("element")
          && newChild.getName().equalsIgnoreCase("$data$")) {
        changeColumn(
            dottedParentColumnName.concat(".").concat("element"),
            newChild,
            table,
            schemaConverter,
            updateSchema,
            true);
        currentChildren.clear();
      } else if (currentChildren.stream()
          .anyMatch(c -> c.name().equalsIgnoreCase(newChild.getName()))) {
        changeColumn(
            dottedParentColumnName.concat(".").concat(newChild.getName()),
            newChild,
            table,
            schemaConverter,
            updateSchema,
            true);
        currentChildren.removeAll(
            currentChildren.stream()
                .filter(c -> c.name().equalsIgnoreCase(newChild.getName()))
                .collect(Collectors.toList()));
      } else {
        updateSchema.addColumn(
            dottedParentColumnName,
            newChild.getName(),
            schemaConverter.toIcebergType(
                CompleteType.fromField(newChild),
                dottedParentColumnName.concat(".").concat(newChild.getName()),
                new FieldIdBroker.UnboundedFieldIdBroker()));
      }
    }
    for (Types.NestedField dropChild : currentChildren) {
      dropColumn(
          dottedParentColumnName.concat(".").concat(dropChild.name()), table, updateSchema, false);
    }
    // Only helpful to rename the actual column in table. For fields inside a complex root column,
    // old field is dropped and new field is added.
    if (!isInternalField && !currentColumn.name().equalsIgnoreCase(newFieldDef.getName())) {
      updateSchema.renameColumn(currentColumn.name(), newFieldDef.getName());
    }

    if (newFieldDef.isNullable()) {
      updateSchema.makeColumnOptional(table.schema().findColumnName(currentColumn.fieldId()));
    }
  }

  private void changePrimitiveColumn(
      String columnToChange,
      Field batchField,
      UpdateSchema updateSchema,
      Types.NestedField columnToChangeInIceberg,
      SchemaConverter schemaConverter,
      Table table,
      boolean isInternalField) {
    Types.NestedField newDef =
        schemaConverter.changeIcebergColumn(batchField, columnToChangeInIceberg);

    if (!TypeUtil.isPromotionAllowed(
        columnToChangeInIceberg.type(), newDef.type().asPrimitiveType())) {
      throw UserException.validationError()
          .message(
              "Cannot change data type of column [%s] from %s to %s",
              columnToChange,
              sqlTypeNameWithPrecisionAndScale(columnToChangeInIceberg.type()),
              sqlTypeNameWithPrecisionAndScale(newDef.type()))
          .buildSilently();
    }

    final String columnOrInnerFieldToChangeName =
        isInternalField
            ? table.schema().findColumnName(columnToChangeInIceberg.fieldId())
            : columnToChangeInIceberg.name();

    if (isInternalField) {
      // We are processing a field inside a complex column.Only update is possible here. Rename
      // happens via drop and add.
      updateSchema.updateColumn(columnOrInnerFieldToChangeName, newDef.type().asPrimitiveType());
    } else {
      updateSchema
          .renameColumn(columnOrInnerFieldToChangeName, newDef.name())
          .updateColumn(columnOrInnerFieldToChangeName, newDef.type().asPrimitiveType());
    }

    if (batchField.isNullable()) {
      updateSchema.makeColumnOptional(columnOrInnerFieldToChangeName);
    }
  }

  @Override
  public String getRootPointer() {
    TableMetadata metadata = getTableOps().current();
    if (metadata == null) {
      throw UserException.dataReadError()
          .message("Failed to get iceberg metadata: " + fsPath)
          .buildSilently();
    }
    return metadata.metadataFileLocation();
  }

  @Override
  public Snapshot getCurrentSnapshot() {
    Preconditions.checkArgument(
        transaction != null,
        "Fetching current snapshot supported only after starting a transaction");
    return this.currentSnapshot;
  }

  @Override
  public Map<Integer, PartitionSpec> getPartitionSpecMap() {
    return getTableOps().current().specsById();
  }

  @Override
  public Schema getIcebergSchema() {
    return getTableOps().current().schema();
  }

  @Override
  public TableOperations getTableOps() {
    return tableOperations;
  }

  @Override
  public long propertyAsLong(String propertyName, long defaultValue) {
    return tableOperations.current().propertyAsLong(propertyName, defaultValue);
  }

  @Override
  public void consumeClusteringStatus(ClusteringStatus clusteringStatus) {
    this.clusteringStatus = clusteringStatus;
  }

  @Override
  public FileIO getFileIO() {
    return getTableOps().io();
  }

  private FileSystem getFs() {
    return ((DremioFileIO) tableOperations.io()).getFs();
  }
}
