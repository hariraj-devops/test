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

import static com.dremio.common.expression.CompleteType.BIGINT;
import static com.dremio.common.expression.CompleteType.BIT;
import static com.dremio.common.expression.CompleteType.DATE;
import static com.dremio.common.expression.CompleteType.DECIMAL;
import static com.dremio.common.expression.CompleteType.DOUBLE;
import static com.dremio.common.expression.CompleteType.FLOAT;
import static com.dremio.common.expression.CompleteType.INT;
import static com.dremio.common.expression.CompleteType.STRUCT;
import static com.dremio.common.expression.CompleteType.TIME;
import static com.dremio.common.expression.CompleteType.TIMESTAMP;
import static com.dremio.common.expression.CompleteType.VARCHAR;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.CONCURRENT_MODIFICATION;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.UNSUPPORTED_OPERATION;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static com.dremio.exec.store.iceberg.IcebergUtils.PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY;
import static com.dremio.exec.store.iceberg.model.IcebergCommandType.INCREMENTAL_METADATA_REFRESH;
import static com.dremio.exec.store.iceberg.model.IcebergCommandType.PARTIAL_METADATA_REFRESH;
import static com.dremio.exec.store.iceberg.model.IncrementalMetadataRefreshCommitter.MAX_NUM_SNAPSHOTS_TO_EXPIRE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.dremio.common.expression.CompleteType;
import com.dremio.exec.catalog.PartitionSpecAlterOption;
import com.dremio.exec.planner.acceleration.IncrementalUpdateUtils;
import com.dremio.exec.planner.sql.PartitionTransform;
import com.dremio.exec.planner.sql.parser.SqlAlterTablePartitionColumns;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.manifestwriter.IcebergCommitOpHelper;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergDmlOperationCommitter;
import com.dremio.exec.store.iceberg.model.IcebergOpCommitter;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.iceberg.model.IncrementalMetadataRefreshCommitter;
import com.dremio.io.file.Path;
import com.dremio.sabot.op.writer.WriterCommitterOperator;
import com.dremio.service.catalog.GetDatasetRequest;
import com.dremio.service.catalog.UpdatableDatasetConfigFields;
import com.dremio.service.namespace.dataset.proto.DatasetCommonProtobuf;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.file.proto.FileProtobuf;
import com.dremio.test.UserExceptionAssert;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.commons.io.FileUtils;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.RowLevelOperationMode;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.util.Pair;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestIcebergOpCommitter extends TestIcebergCommitterBase {

  @Test
  public void testAddOnlyMetadataRefreshCommitter() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableName,
              tableFolder.toPath().toString(),
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      DataFile dataFile6 = getDatafile("books/add1.parquet");
      DataFile dataFile7 = getDatafile("books/add2.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile6, dataFile7);
      insertTableCommitter.consumeManifestFile(m1);
      insertTableCommitter.commit();
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      Assert.assertEquals(2, manifestFileList.size());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFile1")) {
          Assert.assertEquals(5, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(2, (int) manifestFile.addedFilesCount());
          Assert.assertEquals(0, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(0, (int) manifestFile.existingFilesCount());
        }
      }
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testDeleteThenAddMetadataRefreshCommitter() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter metaDataRefreshCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile3);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile4);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile5);
      metaDataRefreshCommitter.consumeManifestFile(m1);
      metaDataRefreshCommitter.commit();

      // After this operation manifestList was expected to have two manifest file
      // One is manifestFile2 and other one is newly created due to delete data file. as This newly
      // created Manifest is due to rewriting
      // of manifestFile1 file. it is expected to 2 existing file account and 3 deleted file count.
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFile2")) {
          Assert.assertEquals(2, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(3, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(2, (int) manifestFile.existingFilesCount());
        }
      }

      UpdatableDatasetConfigFields dataset =
          client
              .getCatalogServiceApi()
              .getDataset(GetDatasetRequest.newBuilder().addAllDatasetPath(datasetPath).build());

      Assert.assertEquals(
          DatasetCommonProtobuf.DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER,
          dataset.getDatasetType());

      BatchSchema newschema =
          BatchSchema.newBuilder()
              .addFields(schema.getFields())
              .addField(
                  Field.nullable(IncrementalUpdateUtils.UPDATE_COLUMN, new ArrowType.Int(64, true)))
              .build();
      Assert.assertEquals(
          newschema, BatchSchema.deserialize(dataset.getBatchSchema().toByteArray()));

      Assert.assertEquals(tableFolder.toPath().toString(), dataset.getFileFormat().getLocation());
      Assert.assertEquals(FileProtobuf.FileType.PARQUET, dataset.getFileFormat().getType());

      Assert.assertEquals(4, dataset.getReadDefinition().getManifestScanStats().getRecordCount());
      Assert.assertEquals(36, dataset.getReadDefinition().getScanStats().getRecordCount());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testAcrossBatchMetadataRefreshCommitter() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter metaDataRefreshCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile3);
      metaDataRefreshCommitter.consumeManifestFile(m1);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile4);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile5);
      metaDataRefreshCommitter.commit();
      // Sequence of consuming input file is different from
      // testDeleteThenAddMetadataRefreshCommitter
      // This simulates that commit will work across batch

      // After this operation manifestList was expected to have two manifest file
      // One is manifestFile2 and other one is newly created due to delete data file. as This newly
      // created Manifest is due to rewriting
      // of manifestFile1 file. it is expected to 2 existing file account and 3 deleted file count.
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFile2")) {
          Assert.assertEquals(2, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(3, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(2, (int) manifestFile.existingFilesCount());
        }
      }
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testDmlOperation() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      Table tableBefore = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter deleteCommitter =
          icebergModel.getDmlCommitter(
              operatorContext,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              datasetConfig,
              IcebergCommandType.UPDATE,
              tableBefore.currentSnapshot().snapshotId(),
              RowLevelOperationMode.COPY_ON_WRITE);

      // Add a new manifest list, and delete several previous datafiles
      String deleteDataFile1 = "books/add1.parquet";
      String deleteDataFile2 = "books/add2.parquet";
      String deleteDataFile3 = "books/add3.parquet";
      String deleteDataFile4 = "books/add4.parquet";

      DataFile dataFile1 = getDatafile("books/add7.parquet");
      DataFile dataFile2 = getDatafile("books/add8.parquet");
      DataFile dataFile3 = getDatafile("books/add9.parquet");

      ManifestFile m1 =
          writeManifest(tableFolder, "manifestFileDmlDelete", dataFile1, dataFile2, dataFile3);
      deleteCommitter.consumeManifestFile(m1);
      deleteCommitter.consumeDeleteDataFilePath(deleteDataFile1);
      deleteCommitter.consumeDeleteDataFilePath(deleteDataFile2);
      deleteCommitter.consumeDeleteDataFilePath(deleteDataFile3);
      deleteCommitter.consumeDeleteDataFilePath(deleteDataFile4);
      deleteCommitter.commit();

      // After this operation, the manifestList was expected to have two manifest file.
      // One is 'manifestFileDelete' and the other is the newly created due to delete data file.
      // This newly created manifest
      // is due to rewriting of 'manifestFile1' file. It is expected to 1 existing file account and
      // 4 deleted file count.
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      Assert.assertEquals(2, manifestFileList.size());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFileDmlDelete")) {
          Assert.assertEquals(3, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(4, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(1, (int) manifestFile.existingFilesCount());
        }
      }
      Assert.assertEquals(3, Iterables.size(table.snapshots()));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testNumberOfSnapshot() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table oldTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(2, Iterables.size(oldTable.snapshots()));
      IcebergOpCommitter metaDataRefreshCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              oldTable.currentSnapshot().snapshotId(),
              false);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile3);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile4);
      metaDataRefreshCommitter.consumeDeleteDataFile(dataFile5);
      metaDataRefreshCommitter.consumeManifestFile(m1);
      metaDataRefreshCommitter.commit();
      Table table = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(4, Iterables.size(table.snapshots()));
      table.refresh();
      TableOperations tableOperations = ((BaseTable) table).operations();
      metadataFileLocation = tableOperations.current().metadataFileLocation();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      metaDataRefreshCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              table.currentSnapshot().snapshotId(),
              false);
      DataFile dataFile2 = getDatafile("books/add2.parquet");
      ManifestFile m2 = writeManifest(tableFolder, "manifestFile3", dataFile2);
      metaDataRefreshCommitter.consumeManifestFile(m2);
      metaDataRefreshCommitter.commit();
      table = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(5, Iterables.size(table.snapshots()));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testIncrementalRefreshExpireSnapshots() throws Exception {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table oldTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(2, Iterables.size(oldTable.snapshots()));

      // Increase more snapshots
      long startTimestampExpiry = System.currentTimeMillis();
      Long startingSnapshotId = oldTable.currentSnapshot().snapshotId();
      for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 5; j++) {
          IcebergOpCommitter metaDataRefreshCommitter =
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  startingSnapshotId,
                  false);

          DataFile dataFile1 = getDatafile("books/add4.parquet");
          DataFile dataFile3 = getDatafile("books/add3.parquet");
          DataFile dataFile4 = getDatafile("books/add4.parquet");
          DataFile dataFile5 = getDatafile("books/add5.parquet");
          ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1);
          metaDataRefreshCommitter.consumeDeleteDataFile(dataFile3);
          metaDataRefreshCommitter.consumeDeleteDataFile(dataFile4);
          metaDataRefreshCommitter.consumeDeleteDataFile(dataFile5);
          metaDataRefreshCommitter.consumeManifestFile(m1);
          metaDataRefreshCommitter.commit();
          Table table = getIcebergTable(icebergModel, tableFolder);
          table.refresh();
          startingSnapshotId = table.currentSnapshot().snapshotId();
          TableOperations tableOperations = ((BaseTable) table).operations();
          metadataFileLocation = tableOperations.current().metadataFileLocation();
          icebergMetadata.setMetadataFileLocation(metadataFileLocation);
          datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
        }

        if (i == 1) {
          startTimestampExpiry = System.currentTimeMillis();
        }
      }
      Table table = getIcebergTable(icebergModel, tableFolder);
      table.refresh();
      final int numTotalSnapshots = Iterables.size(table.snapshots());
      Assert.assertEquals(42, numTotalSnapshots);

      Thread.sleep(100);

      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  table.currentSnapshot().snapshotId(),
                  false);

      Pair<Set<String>, Long> entry =
          refreshCommitter.cleanSnapshotsAndMetadataFiles(table, startTimestampExpiry);
      Set<String> orphanFiles = entry.first();
      Long numValidSnapshots = entry.second();
      // For one commit, we don't expire all old snapshots. Instead, we expire a limit number of
      // them.
      table = getIcebergTable(icebergModel, tableFolder);
      table.refresh();
      final int numSnapshotsAfterExpiry = Iterables.size(table.snapshots());
      Assert.assertEquals(27, numSnapshotsAfterExpiry);
      final int numExpiredSnapshots = numTotalSnapshots - numSnapshotsAfterExpiry;
      Assert.assertEquals(MAX_NUM_SNAPSHOTS_TO_EXPIRE, numExpiredSnapshots);
      Assert.assertEquals(22, orphanFiles.size());

      assertEquals(
          42L, operatorStats.getLongStat(WriterCommitterOperator.Metric.NUM_TOTAL_SNAPSHOTS));
      assertEquals(
          15L, operatorStats.getLongStat(WriterCommitterOperator.Metric.NUM_EXPIRED_SNAPSHOTS));
      assertEquals(
          22L, operatorStats.getLongStat(WriterCommitterOperator.Metric.NUM_ORPHAN_FILES_DELETED));

      assertEquals(
          0L,
          (long) operatorStats.getDoubleStat(WriterCommitterOperator.Metric.NUM_EXPIRED_SNAPSHOTS));

      // Only need to loop through valid snapshots (not all remaining snapshots) to determine final
      // orphan files.
      Assert.assertEquals(20, numValidSnapshots.intValue());
      for (String filePath : orphanFiles) {
        // Should not collect any data/parquet file as orphan files.
        String fileExtension = filePath.substring(filePath.lastIndexOf(".") + 1);
        Assert.assertFalse("parquet".equalsIgnoreCase(fileExtension));

        // Orphan files should be deleted.
        Assert.assertFalse(localFs.exists(Path.of(filePath)));
      }

      TableOperations tableOperations = ((BaseTable) table).operations();
      metadataFileLocation = tableOperations.current().metadataFileLocation();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      icebergMetadata.setSnapshotId(tableOperations.current().currentSnapshot().snapshotId());
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      IncrementalMetadataRefreshCommitter refreshCommitter2 =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  100L,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  table.currentSnapshot().snapshotId(),
                  false);
      // Do empty commit. Still clean table's snapshots
      refreshCommitter2.disableUseDefaultPeriod();
      refreshCommitter2.commit();
      table = getIcebergTable(icebergModel, tableFolder);
      table.refresh();
      final int numSnapshotsAfterSecondExpiry = Iterables.size(table.snapshots());
      Assert.assertEquals(12, numSnapshotsAfterSecondExpiry);

      // Table properties should be updated to delete metadata entry file.
      Map<String, String> tblProperties = table.properties();
      Assert.assertTrue(
          tblProperties.containsKey(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED));
      Assert.assertTrue(
          tblProperties
              .get(TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED)
              .equalsIgnoreCase("true"));
      Assert.assertTrue(tblProperties.containsKey(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX));
      Assert.assertTrue(
          tblProperties
              .get(TableProperties.METADATA_PREVIOUS_VERSIONS_MAX)
              .equalsIgnoreCase("100"));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testIncrementalRefreshKeepCurrentSnapshotFiles() throws Exception {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table oldTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(2, Iterables.size(oldTable.snapshots()));

      // Increase more snapshots
      Long startingSnapshotId = oldTable.currentSnapshot().snapshotId();
      for (int i = 0; i < 5; i++) {
        IcebergOpCommitter metaDataRefreshCommitter =
            icebergModel.getIncrementalMetadataRefreshCommitter(
                operatorContext,
                tableName,
                datasetPath,
                tableFolder.toPath().toString(),
                tableName,
                icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                schema,
                Collections.emptyList(),
                true,
                datasetConfig,
                localFs,
                null,
                INCREMENTAL_METADATA_REFRESH,
                null,
                startingSnapshotId,
                false);

        DataFile dataFile1 = getDatafile("books/add4.parquet");
        DataFile dataFile3 = getDatafile("books/add3.parquet");
        DataFile dataFile4 = getDatafile("books/add4.parquet");
        DataFile dataFile5 = getDatafile("books/add5.parquet");
        ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1);
        metaDataRefreshCommitter.consumeDeleteDataFile(dataFile3);
        metaDataRefreshCommitter.consumeDeleteDataFile(dataFile4);
        metaDataRefreshCommitter.consumeDeleteDataFile(dataFile5);
        metaDataRefreshCommitter.consumeManifestFile(m1);
        metaDataRefreshCommitter.commit();
        Table table = getIcebergTable(icebergModel, tableFolder);
        table.refresh();
        TableOperations tableOperations = ((BaseTable) table).operations();
        startingSnapshotId = table.currentSnapshot().snapshotId();
        metadataFileLocation = tableOperations.current().metadataFileLocation();
        icebergMetadata.setMetadataFileLocation(metadataFileLocation);
        datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      }
      Thread.sleep(1000); // 1 Second
      Table table = getIcebergTable(icebergModel, tableFolder);
      table.refresh();

      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  table.currentSnapshot().snapshotId(),
                  false);

      // Keep the snapshots within 100 ms.
      long startTimestampExpiry = System.currentTimeMillis() - 100;
      Pair<Set<String>, Long> entry =
          refreshCommitter.cleanSnapshotsAndMetadataFiles(table, startTimestampExpiry);
      Set<String> orphanFiles = entry.first();
      Long numValidSnapshots = entry.second();
      Assert.assertEquals("Should keep current snapshot", 1, numValidSnapshots.longValue());

      table.refresh();
      Set<String> currentSnapshotFiles =
          collectAllFilesFromSnapshot(table.currentSnapshot(), table.io());
      Assert.assertTrue(
          "Current snapshot should have multiple metadata-related files",
          currentSnapshotFiles.size() > 1);
      for (String filePath : currentSnapshotFiles) {
        Assert.assertFalse(
            "Current snapshot's files are not orphan files", orphanFiles.contains(filePath));
      }
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshSchemaUpdateAndUpPromotion() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      BatchSchema schema1 =
          new BatchSchema(
              Arrays.asList(
                  INT.toField("field1"),
                  INT.toField("field2"),
                  BIGINT.toField("field3"),
                  INT.toField("field4"),
                  BIGINT.toField("field5"),
                  FLOAT.toField("field6"),
                  DECIMAL.toField("field7"),
                  BIT.toField("field8"),
                  INT.toField("field9"),
                  BIGINT.toField("field10"),
                  FLOAT.toField("field11"),
                  DOUBLE.toField("field12"),
                  DECIMAL.toField("field13"),
                  DATE.toField("field14"),
                  TIME.toField("field15"),
                  TIMESTAMP.toField("field16"),
                  INT.toField("field17"),
                  BIGINT.toField("field18"),
                  FLOAT.toField("field19")));
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema1, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema1,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      BatchSchema schema2 =
          new BatchSchema(
              Arrays.asList(
                  BIGINT.toField("field1"),
                  FLOAT.toField("field2"),
                  FLOAT.toField("field3"),
                  DOUBLE.toField("field4"),
                  DOUBLE.toField("field5"),
                  DOUBLE.toField("field6"),
                  VARCHAR.toField("field6"),
                  DOUBLE.toField("field7"),
                  VARCHAR.toField("field8"),
                  VARCHAR.toField("field9"),
                  VARCHAR.toField("field10"),
                  VARCHAR.toField("field11"),
                  VARCHAR.toField("field12"),
                  VARCHAR.toField("field13"),
                  VARCHAR.toField("field14"),
                  VARCHAR.toField("field15"),
                  VARCHAR.toField("field16"),
                  DECIMAL.toField("field17"),
                  DECIMAL.toField("field18"),
                  DECIMAL.toField("field19")));

      BatchSchema consolidatedSchema = schema1.mergeWithUpPromotion(schema2, this);
      insertTableCommitter.updateSchema(consolidatedSchema);
      insertTableCommitter.commit();

      Table table = getIcebergTable(icebergModel, tableFolder);
      Schema sc = table.schema();
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(table.name()).build();
      Assert.assertTrue(
          consolidatedSchema.equalsTypesWithoutPositions(schemaConverter.fromIceberg(sc)));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshSchemaComplexTypeNotUpdate() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      List<Field> childrenField1 = ImmutableList.of(VARCHAR.toField("doubleCol"));

      Field structField1 =
          new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField1);
      Field dremioUpdateField = INT.toField(IncrementalUpdateUtils.UPDATE_COLUMN);
      BatchSchema schema1 =
          new BatchSchema(Arrays.asList(INT.toField("field1"), dremioUpdateField, structField1));
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema1, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema1,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      BatchSchema schema2 = new BatchSchema(Arrays.asList(INT.toField("field1"), structField1));
      insertTableCommitter.updateSchema(schema2);
      Assert.assertEquals(
          "Complex field type is not updated",
          0,
          ((IncrementalMetadataRefreshCommitter) insertTableCommitter)
              .getUpdatedColumnTypes()
              .size());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshSchemaUpdate() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      BatchSchema newSchema =
          BatchSchema.of(
              Field.nullablePrimitive(
                  "id", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              Field.nullablePrimitive("data", new ArrowType.Utf8()),
              Field.nullablePrimitive("boolean", new ArrowType.Bool()),
              Field.nullablePrimitive("stringCol", new ArrowType.Utf8()));

      BatchSchema consolidatedSchema = schema.mergeWithUpPromotion(newSchema, this);
      insertTableCommitter.updateSchema(consolidatedSchema);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");

      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      insertTableCommitter.consumeDeleteDataFile(dataFile3);
      insertTableCommitter.consumeManifestFile(m1);
      insertTableCommitter.consumeDeleteDataFile(dataFile4);
      insertTableCommitter.consumeDeleteDataFile(dataFile5);
      insertTableCommitter.commit();

      Table newTable = getIcebergTable(icebergModel, tableFolder);
      Schema sc = newTable.schema();
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(newTable.name()).build();
      Assert.assertTrue(
          consolidatedSchema.equalsTypesWithoutPositions(schemaConverter.fromIceberg(sc)));
      Assert.assertEquals(4, Iterables.size(newTable.snapshots()));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshColumnNameIncludeDot() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      BatchSchema newSchema =
          BatchSchema.of(
              Field.nullablePrimitive(
                  "id.A", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              Field.nullablePrimitive("data.A", new ArrowType.Utf8()),
              Field.nullablePrimitive("boolean.A", new ArrowType.Bool()),
              Field.nullablePrimitive("stringCol.A", new ArrowType.Utf8()));

      BatchSchema consolidatedSchema = schema.mergeWithUpPromotion(newSchema, this);
      insertTableCommitter.updateSchema(consolidatedSchema);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");

      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      insertTableCommitter.consumeDeleteDataFile(dataFile3);
      insertTableCommitter.consumeManifestFile(m1);
      insertTableCommitter.consumeDeleteDataFile(dataFile4);
      insertTableCommitter.consumeDeleteDataFile(dataFile5);
      insertTableCommitter.commit();

      Table newTable = getIcebergTable(icebergModel, tableFolder);
      Schema sc = newTable.schema();
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(newTable.name()).build();
      Assert.assertTrue(
          consolidatedSchema.equalsTypesWithoutPositions(schemaConverter.fromIceberg(sc)));
      Assert.assertEquals(4, Iterables.size(newTable.snapshots()));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshSchemaDropColumns() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              false,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      BatchSchema newSchema =
          BatchSchema.of(
              Field.nullablePrimitive(
                  "id", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              Field.nullablePrimitive("boolean", new ArrowType.Bool()),
              Field.nullablePrimitive("stringCol", new ArrowType.Utf8()));

      insertTableCommitter.updateSchema(newSchema);
      insertTableCommitter.commit();

      Table newTable = getIcebergTable(icebergModel, tableFolder);
      Schema sc = newTable.schema();
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(newTable.name()).build();
      Assert.assertTrue(newSchema.equalsTypesWithoutPositions(schemaConverter.fromIceberg(sc)));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testMetadataRefreshDelete() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      DataFile dataFile1 = getDatafileWithPartitionSpec("books/add4.parquet");
      DataFile dataFile2 = getDatafileWithPartitionSpec("books/add5.parquet");

      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      insertTableCommitter.consumeManifestFile(m1);

      DataFile dataFile1Delete = getDatafile("books/add4.parquet");
      DataFile dataFile2Delete = getDatafile("books/add4.parquet");

      insertTableCommitter.consumeDeleteDataFile(dataFile1);
      insertTableCommitter.consumeManifestFile(m1);
      insertTableCommitter.consumeDeleteDataFile(dataFile1Delete);
      insertTableCommitter.consumeDeleteDataFile(dataFile2Delete);
      insertTableCommitter.commit();

      Table newTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(4, Iterables.size(newTable.snapshots()));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentIncrementalMetadataRefresh() throws Exception {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);

      // Two concurrent iceberg committeres
      IcebergOpCommitter firstCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableName,
              tableFolder.toPath().toString(),
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      IcebergOpCommitter secondCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableName,
              tableFolder.toPath().toString(),
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      DataFile dataFile6 = getDatafile("books/add1.parquet");
      DataFile dataFile7 = getDatafile("books/add2.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile6, dataFile7);

      firstCommitter.consumeManifestFile(m1);
      secondCommitter.consumeManifestFile(m1);

      // The first commit succeeds.
      firstCommitter.commit();
      Table firstCommitTable = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList =
          firstCommitTable.currentSnapshot().allManifests(firstCommitTable.io());
      Assert.assertEquals(2, manifestFileList.size());

      // Due to concurrent operation, the second commit should not make any update to table. The
      // commit should be omitted.
      Snapshot snapshot = secondCommitter.commit();
      Assert.assertNull(snapshot);
      Assert.assertTrue(secondCommitter.isIcebergTableUpdated());
      Table secondCommitTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertEquals(
          firstCommitTable.currentSnapshot().snapshotId(),
          secondCommitTable.currentSnapshot().snapshotId());
      Assert.assertEquals(
          manifestFileList.size(),
          secondCommitTable.currentSnapshot().allManifests(secondCommitTable.io()).size());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testIncrementalRefreshDroppedAndAddedColumns() throws Exception {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      List<Field> childrenField1 = ImmutableList.of(VARCHAR.toField("doubleCol"));

      Field structField1 =
          new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField1);

      BatchSchema schema =
          BatchSchema.of(
              Field.nullablePrimitive("id", new ArrowType.Int(64, true)),
              Field.nullablePrimitive("data", new ArrowType.Utf8()),
              Field.nullablePrimitive("stringField", new ArrowType.Utf8()),
              Field.nullablePrimitive("intField", new ArrowType.Utf8()),
              structField1);

      List<Field> childrenField2 =
          ImmutableList.of(
              CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

      Field structField2 =
          new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField2);

      BatchSchema newSchema =
          BatchSchema.of(
              Field.nullablePrimitive(
                  "id", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              Field.nullablePrimitive("data", new ArrowType.Utf8()),
              Field.nullablePrimitive("stringField", new ArrowType.Utf8()),
              Field.nullablePrimitive("intField", new ArrowType.Int(32, false)),
              Field.nullablePrimitive(
                  "floatField", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              structField2);

      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      datasetConfig.setRecordSchema(schema.toByteString());

      BatchSchema droppedColumns =
          BatchSchema.of(
              Field.nullablePrimitive("stringField", new ArrowType.Utf8()),
              new Field(
                  "structField",
                  FieldType.nullable(STRUCT.getType()),
                  ImmutableList.of(CompleteType.INT.toField("integerCol"))));

      BatchSchema updatedColumns =
          BatchSchema.of(
              Field.nullablePrimitive("intField", new ArrowType.Utf8()),
              new Field(
                  "structField",
                  FieldType.nullable(STRUCT.getType()),
                  ImmutableList.of(VARCHAR.toField("doubleCol"))));

      datasetConfig
          .getPhysicalDataset()
          .getInternalSchemaSettings()
          .setDroppedColumns(droppedColumns.toByteString());
      datasetConfig
          .getPhysicalDataset()
          .getInternalSchemaSettings()
          .setModifiedColumns(updatedColumns.toByteString());

      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter insertTableCommitter =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              false,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);

      Field newStructField =
          new Field(
              "structField",
              FieldType.nullable(STRUCT.getType()),
              ImmutableList.of(VARCHAR.toField("doubleCol")));

      BatchSchema expectedSchema =
          BatchSchema.of(
              Field.nullablePrimitive(
                  "id", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              Field.nullablePrimitive("data", new ArrowType.Utf8()),
              Field.nullablePrimitive("intField", new ArrowType.Utf8()),
              Field.nullablePrimitive(
                  "floatField", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
              newStructField);

      insertTableCommitter.updateSchema(newSchema);
      insertTableCommitter.commit();

      Table newTable = getIcebergTable(icebergModel, tableFolder);
      Schema sc = newTable.schema();
      SchemaConverter schemaConverter =
          SchemaConverter.getBuilder().setTableName(newTable.name()).build();
      Assert.assertTrue(
          expectedSchema.equalsTypesWithoutPositions(schemaConverter.fromIceberg(sc)));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentIncrementalPartialRefresh() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter committer =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter) committer;

      IcebergOpCommitter committer2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              PARTIAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer2 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter partialRefresher =
          (IncrementalMetadataRefreshCommitter) committer2;

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      refreshCommitter.consumeDeleteDataFile(dataFile3);
      refreshCommitter.consumeDeleteDataFile(dataFile4);
      refreshCommitter.consumeDeleteDataFile(dataFile5);
      refreshCommitter.consumeManifestFile(m1);

      partialRefresher.consumeDeleteDataFile(dataFile3);
      partialRefresher.consumeDeleteDataFile(dataFile4);
      partialRefresher.consumeDeleteDataFile(dataFile5);
      partialRefresher.consumeManifestFile(m1);

      // start both commits
      refreshCommitter.beginMetadataRefreshTransaction();
      partialRefresher.beginMetadataRefreshTransaction();

      // end commit 1
      refreshCommitter.commitImpl(true);

      // end commit 2 should fail with CONCURRENT_MODIFICATION error
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                partialRefresher.commitImpl(true);
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");

      // After this operation manifestList was expected to have two manifest file
      // One is manifestFile2 and other one is newly created due to delete data file. as This newly
      // created Manifest is due to rewriting
      // of manifestFile1 file. it is expected to 2 existing file account and 3 deleted file count.
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFile2")) {
          Assert.assertEquals(2, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(3, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(2, (int) manifestFile.existingFilesCount());
        }
      }

      UpdatableDatasetConfigFields dataset =
          client
              .getCatalogServiceApi()
              .getDataset(GetDatasetRequest.newBuilder().addAllDatasetPath(datasetPath).build());

      Assert.assertEquals(
          DatasetCommonProtobuf.DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER,
          dataset.getDatasetType());

      BatchSchema newschema =
          BatchSchema.newBuilder()
              .addFields(schema.getFields())
              .addField(
                  Field.nullable(IncrementalUpdateUtils.UPDATE_COLUMN, new ArrowType.Int(64, true)))
              .build();
      Assert.assertEquals(
          newschema, BatchSchema.deserialize(dataset.getBatchSchema().toByteArray()));

      Assert.assertEquals(tableFolder.toPath().toString(), dataset.getFileFormat().getLocation());
      Assert.assertEquals(FileProtobuf.FileType.PARQUET, dataset.getFileFormat().getType());

      Assert.assertEquals(4, dataset.getReadDefinition().getManifestScanStats().getRecordCount());
      Assert.assertEquals(36, dataset.getReadDefinition().getScanStats().getRecordCount());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentTwoIncrementalRefresh() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter committer =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter) committer;

      IcebergOpCommitter committer2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer2 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter2 =
          (IncrementalMetadataRefreshCommitter) committer2;

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      refreshCommitter.consumeDeleteDataFile(dataFile3);
      refreshCommitter.consumeDeleteDataFile(dataFile4);
      refreshCommitter.consumeDeleteDataFile(dataFile5);
      refreshCommitter.consumeManifestFile(m1);

      refreshCommitter2.consumeDeleteDataFile(dataFile3);
      refreshCommitter2.consumeDeleteDataFile(dataFile4);
      refreshCommitter2.consumeDeleteDataFile(dataFile5);
      refreshCommitter2.consumeManifestFile(m1);

      // Two INCREMENTAL_METADATA_REFRESH commits, skip the second without throwing exception.

      // start both commits
      refreshCommitter.beginMetadataRefreshTransaction();
      refreshCommitter2.beginMetadataRefreshTransaction();

      // end commit 1
      Snapshot snapshot1 = refreshCommitter.commitImpl(true);
      Assert.assertNotNull(snapshot1);

      // end commit 2 should not fail. Basically, skip second commit.
      Snapshot snapshot2 = refreshCommitter2.commitImpl(true);
      Assert.assertNull(snapshot2);

      // After this operation manifestList was expected to have two manifest file
      // One is manifestFile2 and other one is newly created due to delete data file. as This newly
      // created Manifest is due to rewriting
      // of manifestFile1 file. it is expected to 2 existing file account and 3 deleted file count.
      Table table = getIcebergTable(icebergModel, tableFolder);
      List<ManifestFile> manifestFileList = table.currentSnapshot().allManifests(table.io());
      for (ManifestFile manifestFile : manifestFileList) {
        if (manifestFile.path().contains("manifestFile2")) {
          Assert.assertEquals(2, (int) manifestFile.addedFilesCount());
        } else {
          Assert.assertEquals(3, (int) manifestFile.deletedFilesCount());
          Assert.assertEquals(2, (int) manifestFile.existingFilesCount());
        }
      }

      UpdatableDatasetConfigFields dataset =
          client
              .getCatalogServiceApi()
              .getDataset(GetDatasetRequest.newBuilder().addAllDatasetPath(datasetPath).build());

      Assert.assertEquals(
          DatasetCommonProtobuf.DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER,
          dataset.getDatasetType());

      BatchSchema newschema =
          BatchSchema.newBuilder()
              .addFields(schema.getFields())
              .addField(
                  Field.nullable(IncrementalUpdateUtils.UPDATE_COLUMN, new ArrowType.Int(64, true)))
              .build();
      Assert.assertEquals(
          newschema, BatchSchema.deserialize(dataset.getBatchSchema().toByteArray()));

      Assert.assertEquals(tableFolder.toPath().toString(), dataset.getFileFormat().getLocation());
      Assert.assertEquals(FileProtobuf.FileType.PARQUET, dataset.getFileFormat().getType());

      Assert.assertEquals(4, dataset.getReadDefinition().getManifestScanStats().getRecordCount());
      Assert.assertEquals(36, dataset.getReadDefinition().getScanStats().getRecordCount());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentIncrementalPartialRefreshPostCommit() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter committer =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter) committer;

      IcebergOpCommitter committer2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              PARTIAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer2 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter partialCommitter =
          (IncrementalMetadataRefreshCommitter) committer2;
      // Disable kv store retry to test if the kv store update fails finally, it throws CME.
      partialCommitter.disableKvstoreRetry();

      Table table = getIcebergTable(icebergModel, tableFolder);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      refreshCommitter.consumeDeleteDataFile(dataFile3);
      refreshCommitter.consumeDeleteDataFile(dataFile4);
      refreshCommitter.consumeDeleteDataFile(dataFile5);
      refreshCommitter.consumeManifestFile(m1);

      partialCommitter.consumeDeleteDataFile(dataFile3);
      partialCommitter.consumeDeleteDataFile(dataFile4);
      partialCommitter.consumeDeleteDataFile(dataFile5);
      partialCommitter.consumeManifestFile(m1);

      // One INCREMENTAL_METADATA_REFRESH commit and one PARTIAL_METADATA_REFRESH, skip the second
      // without throwing exception.

      // start both commits
      refreshCommitter.beginMetadataRefreshTransaction();
      partialCommitter.beginMetadataRefreshTransaction();

      // end commit 1
      Snapshot snapshot1 = refreshCommitter.commitImpl(true);
      Assert.assertNotNull(snapshot1);

      // end commit 2 should fail with CONCURRENT_MODIFICATION error
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                partialCommitter.commitImpl(true);
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");

      // Set the Iceberg table instance to test postCommitTransaction.
      // For PARTIAL_METADATA_REFRESH command, we throw CME as the post commit fails due to
      // StatusRuntimeException (ABORTED)
      partialCommitter.setTable(table);
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                partialCommitter.postCommitTransaction();
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentTwoIncrementalRefreshPostCommit() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter committer =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter) committer;

      IcebergOpCommitter committer2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer2 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter2 =
          (IncrementalMetadataRefreshCommitter) committer2;

      Table table = getIcebergTable(icebergModel, tableFolder);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      refreshCommitter.consumeDeleteDataFile(dataFile3);
      refreshCommitter.consumeDeleteDataFile(dataFile4);
      refreshCommitter.consumeDeleteDataFile(dataFile5);
      refreshCommitter.consumeManifestFile(m1);

      refreshCommitter2.consumeDeleteDataFile(dataFile3);
      refreshCommitter2.consumeDeleteDataFile(dataFile4);
      refreshCommitter2.consumeDeleteDataFile(dataFile5);
      refreshCommitter2.consumeManifestFile(m1);

      // Two INCREMENTAL_METADATA_REFRESH commits, skip the second without throwing exception.

      // start both commits
      refreshCommitter.beginMetadataRefreshTransaction();
      refreshCommitter2.beginMetadataRefreshTransaction();

      // end commit 1
      Snapshot snapshot1 = refreshCommitter.commitImpl(true);
      Assert.assertNotNull(snapshot1);

      // end commit 2 should not fail. Basically, skip second commit.
      Snapshot snapshot3 = refreshCommitter2.commitImpl(true);
      Assert.assertNull(snapshot3);

      // Set the Iceberg table instance to test postCommitTransaction.
      // For INCREMENTAL_METADATA_REFRESH command, we skip to throw CME, even the post commit fails
      // with StatusRuntimeException.
      refreshCommitter2.setTable(table);
      Snapshot snapshot4 = refreshCommitter2.postCommitTransaction();
      Assert.assertEquals(table.currentSnapshot().snapshotId(), snapshot4.snapshotId());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentIncrementalRefreshCommitSameFile() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter committer =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter =
          (IncrementalMetadataRefreshCommitter) committer;

      IcebergOpCommitter committer2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer2 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter refreshCommitter2 =
          (IncrementalMetadataRefreshCommitter) committer2;

      IcebergOpCommitter committer3 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              PARTIAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              false);
      Assert.assertTrue(committer3 instanceof IncrementalMetadataRefreshCommitter);
      IncrementalMetadataRefreshCommitter partialCommitter =
          (IncrementalMetadataRefreshCommitter) committer3;

      DataFile sameDataFile1 = getDatafile("books/add4.parquet");
      DataFile sameDataFile2 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", sameDataFile1, sameDataFile2);
      ManifestFile m2 = writeManifest(tableFolder, "manifestFile2", sameDataFile1, sameDataFile2);
      ManifestFile m3 = writeManifest(tableFolder, "manifestFile2", sameDataFile1, sameDataFile2);

      refreshCommitter.consumeManifestFile(m1);
      refreshCommitter2.consumeManifestFile(m2);
      partialCommitter.consumeManifestFile(m3);

      // start both commits
      refreshCommitter.beginMetadataRefreshTransaction();
      refreshCommitter2.beginMetadataRefreshTransaction();
      partialCommitter.beginMetadataRefreshTransaction();

      // end commit 1
      Snapshot snapshot1 = refreshCommitter.commitImpl(true);
      Assert.assertNotNull(snapshot1);

      // Test commit 2 should to catch the native Iceberg exception that complains the conflicting
      // file.
      assertThatThrownBy(
              () -> {
                refreshCommitter2.performUpdates();
                refreshCommitter2.endMetadataRefreshTransaction();
              })
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Found conflicting files that can contain records matching true");

      // The committer for Partition metadata commit should fail with CME exception

      UserExceptionAssert.assertThatThrownBy(
              () -> {
                partialCommitter.commitImpl(true);
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentTwoIncrementalRefreshPostCommitWithErrorEnabled() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IcebergOpCommitter commiter1 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              true);
      Assert.assertTrue(commiter1 instanceof IncrementalMetadataRefreshCommitter);

      IcebergOpCommitter commiter2 =
          icebergModel.getIncrementalMetadataRefreshCommitter(
              operatorContext,
              tableName,
              datasetPath,
              tableFolder.toPath().toString(),
              tableName,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              schema,
              Collections.emptyList(),
              true,
              datasetConfig,
              localFs,
              null,
              INCREMENTAL_METADATA_REFRESH,
              null,
              initialTable.currentSnapshot().snapshotId(),
              true);
      Assert.assertTrue(commiter2 instanceof IncrementalMetadataRefreshCommitter);

      // Disable kv store retry to test if the kv store update fails finally, it throws CME.
      ((IncrementalMetadataRefreshCommitter) commiter2).disableKvstoreRetry();

      Table table = getIcebergTable(icebergModel, tableFolder);

      DataFile dataFile1 = getDatafile("books/add4.parquet");
      DataFile dataFile2 = getDatafile("books/add5.parquet");
      DataFile dataFile3 = getDatafile("books/add3.parquet");
      DataFile dataFile4 = getDatafile("books/add4.parquet");
      DataFile dataFile5 = getDatafile("books/add5.parquet");
      ManifestFile m1 = writeManifest(tableFolder, "manifestFile2", dataFile1, dataFile2);
      commiter1.consumeDeleteDataFile(dataFile3);
      commiter1.consumeDeleteDataFile(dataFile4);
      commiter1.consumeDeleteDataFile(dataFile5);
      commiter1.consumeManifestFile(m1);

      commiter2.consumeDeleteDataFile(dataFile3);
      commiter2.consumeDeleteDataFile(dataFile4);
      commiter2.consumeDeleteDataFile(dataFile5);
      commiter2.consumeManifestFile(m1);

      // Two INCREMENTAL_METADATA_REFRESH commits, second should fail with an error

      // start both commits
      ((IncrementalMetadataRefreshCommitter) commiter1).beginMetadataRefreshTransaction();
      ((IncrementalMetadataRefreshCommitter) commiter2).beginMetadataRefreshTransaction();

      // end commit 1
      ((IncrementalMetadataRefreshCommitter) commiter1).commitImpl(true);

      // end commit 2 should fail with CONCURRENT_MODIFICATION error
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                ((IncrementalMetadataRefreshCommitter) commiter2).commitImpl(true);
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");

      // Set the Iceberg table instance to test postCommitTransaction.
      // With errorOnConcurrentRefresh enabled we throw CME as the post commit fails
      ((IncrementalMetadataRefreshCommitter) commiter2).setTable(table);
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                ((IncrementalMetadataRefreshCommitter) commiter2).postCommitTransaction();
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentCommitsFailToUpdateKVStore() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IncrementalMetadataRefreshCommitter incrementalCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  initialTable.currentSnapshot().snapshotId(),
                  false);

      long startingSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();

      DataFile dataFile6 = getDatafile("books/add6.parquet");
      DataFile dataFile7 = getDatafile("books/add7.parquet");
      ManifestFile manifestFile2 = writeManifest(tableFolder, "manifestFile2", dataFile6);
      ManifestFile manifestFile3 = writeManifest(tableFolder, "manifestFile3", dataFile7);

      incrementalCommitter.consumeManifestFile(manifestFile2);

      // Start incremental commit into Iceberg and update table's snapshot and metadata file
      incrementalCommitter.beginMetadataRefreshTransaction();
      incrementalCommitter.performUpdates();
      incrementalCommitter.endMetadataRefreshTransaction();
      long secondSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();
      Assert.assertNotEquals(
          "Iceberg table should be updated", startingSnapshotId, secondSnapshotId);

      // Before incremental commit updates KV store, start the partial commit process. In this case,
      // we can build a new snapshot upon incremental commit.
      IncrementalMetadataRefreshCommitter partialCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  PARTIAL_METADATA_REFRESH,
                  null,
                  secondSnapshotId,
                  false);

      // Disable kv store retry to test if the kv store update fails finally, it throws CME.
      partialCommitter.disableKvstoreRetry();

      String incrementalCommitTag =
          incrementalCommitter
              .getDatasetCatalogRequestBuilder()
              .build()
              .getDatasetConfig()
              .getTag();
      String partialCommitTag =
          partialCommitter.getDatasetCatalogRequestBuilder().build().getDatasetConfig().getTag();
      Assert.assertEquals("Tags should not change", incrementalCommitTag, partialCommitTag);

      partialCommitter.consumeManifestFile(manifestFile3);
      partialCommitter.beginMetadataRefreshTransaction();

      // Update Incremental commit into KV store and update dataset's tag info.
      incrementalCommitter.postCommitTransaction();
      String updatedTag = getTag(datasetPath);
      Assert.assertNotEquals("Tag should be updated", updatedTag, partialCommitTag);

      // Make another Iceberg commit and update KV store. The KV store update should fail due to tag
      // mismatch.
      partialCommitter.performUpdates();
      partialCommitter.endMetadataRefreshTransaction();
      long thirdSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();
      Assert.assertNotEquals("Iceberg table should be updated", secondSnapshotId, thirdSnapshotId);

      // For PARTIAL_METADATA_REFRESH command, it throws CME as the post commit fails due to
      // StatusRuntimeException (ABORTED)
      // It does not update the KV store successfully. But, it needs the manifest files not deleted.
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                partialCommitter.postCommitTransaction();
                Assert.fail();
              })
          .hasErrorType(CONCURRENT_MODIFICATION)
          .hasMessageContaining(
              "Unable to refresh metadata for the dataset (due to concurrent updates). Please retry");

      // Manifest files are not deleted.
      Assert.assertTrue(localFs.exists(Path.of(manifestFile2.path())));
      Assert.assertTrue(localFs.exists(Path.of(manifestFile3.path())));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testConcurrentCommitsKVStoreUpdateRetries() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IncrementalMetadataRefreshCommitter incrementalCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  initialTable.currentSnapshot().snapshotId(),
                  false);

      long startingSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();

      DataFile dataFile6 = getDatafile("books/add6.parquet");
      DataFile dataFile7 = getDatafile("books/add7.parquet");
      ManifestFile manifestFile2 = writeManifest(tableFolder, "manifestFile2", dataFile6);
      ManifestFile manifestFile3 = writeManifest(tableFolder, "manifestFile3", dataFile7);

      incrementalCommitter.consumeManifestFile(manifestFile2);

      // Start incremental commit into Iceberg and update table's snapshot and metadata file
      incrementalCommitter.beginMetadataRefreshTransaction();
      incrementalCommitter.performUpdates();
      incrementalCommitter.endMetadataRefreshTransaction();
      long secondSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();
      Assert.assertNotEquals(
          "Iceberg table should be updated", startingSnapshotId, secondSnapshotId);

      // Before incremental commit updates KV store, start the partial commit process. In this case,
      // we can build a new snapshot upon incremental commit.
      IncrementalMetadataRefreshCommitter partialCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  PARTIAL_METADATA_REFRESH,
                  null,
                  secondSnapshotId,
                  false);

      String incrementalCommitTag =
          incrementalCommitter
              .getDatasetCatalogRequestBuilder()
              .build()
              .getDatasetConfig()
              .getTag();
      String partialCommitTag =
          partialCommitter.getDatasetCatalogRequestBuilder().build().getDatasetConfig().getTag();
      Assert.assertEquals("Tags should not change", incrementalCommitTag, partialCommitTag);

      partialCommitter.consumeManifestFile(manifestFile3);
      partialCommitter.beginMetadataRefreshTransaction();

      // Update Incremental commit into KV store and update dataset's tag info.
      incrementalCommitter.postCommitTransaction();
      String tagAfterIncrementalCommit = getTag(datasetPath);
      Assert.assertNotEquals("Tag should be updated", tagAfterIncrementalCommit, partialCommitTag);

      // Make another Iceberg commit and update KV store. It enables KV store retry for
      // 'partialCommitter'.
      partialCommitter.performUpdates();
      partialCommitter.endMetadataRefreshTransaction();
      long thirdSnapshotId =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().snapshotId();
      Assert.assertNotEquals("Iceberg table should be updated", secondSnapshotId, thirdSnapshotId);

      // So, partialCommitter's postCommitTransaction will succeed to update KV store and update tag
      // info.
      partialCommitter.postCommitTransaction();
      String tagAfterPartialCommit = getTag(datasetPath);
      Assert.assertNotEquals(
          "Tag should be updated", tagAfterIncrementalCommit, tagAfterPartialCommit);

      // Manifest files are not deleted.
      Assert.assertTrue(localFs.exists(Path.of(manifestFile2.path())));
      Assert.assertTrue(localFs.exists(Path.of(manifestFile3.path())));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testIncrementalRefreshMissingManifestListFile() throws Exception {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      Table initialTable = getIcebergTable(icebergModel, tableFolder);
      IncrementalMetadataRefreshCommitter incrementalCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  null,
                  INCREMENTAL_METADATA_REFRESH,
                  null,
                  initialTable.currentSnapshot().snapshotId(),
                  false);

      // Check the manifest list file we plan to delete does exist now.
      String manifestListToDelete =
          getIcebergTable(icebergModel, tableFolder).currentSnapshot().manifestListLocation();
      Assert.assertTrue(
          "Manifest list file should exist", localFs.exists(Path.of(manifestListToDelete)));

      DataFile dataFile6 = getDatafile("books/add6.parquet");
      DataFile dataFile7 = getDatafile("books/add7.parquet");
      ManifestFile manifestFile2 = writeManifest(tableFolder, "manifestFile2", dataFile6);
      ManifestFile manifestFile3 = writeManifest(tableFolder, "manifestFile3", dataFile7);

      // Make a refresh and increase a snapshot.
      incrementalCommitter.consumeManifestFile(manifestFile2);
      Snapshot updatedSnapshot = incrementalCommitter.commit();

      // Delete the intermediate snapshot's manifest list file.
      localFs.delete(Path.of(manifestListToDelete), false);
      Assert.assertFalse(
          "Manifest list file should be deleted", localFs.exists(Path.of(manifestListToDelete)));

      // Make another commit at the situation that a manifest list file missed. The commit should
      // succeed.

      // Set new Iceberg metadata file location.
      icebergMetadata.setMetadataFileLocation(incrementalCommitter.getRootPointer());
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);
      IncrementalMetadataRefreshCommitter partialCommitter =
          (IncrementalMetadataRefreshCommitter)
              icebergModel.getIncrementalMetadataRefreshCommitter(
                  operatorContext,
                  tableName,
                  datasetPath,
                  tableFolder.toPath().toString(),
                  tableName,
                  icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
                  schema,
                  Collections.emptyList(),
                  true,
                  datasetConfig,
                  localFs,
                  1000L,
                  PARTIAL_METADATA_REFRESH,
                  null,
                  updatedSnapshot.snapshotId(),
                  false);

      partialCommitter.consumeManifestFile(manifestFile3);
      partialCommitter.disableUseDefaultPeriod();
      partialCommitter.setMinSnapshotsToKeep(1);
      Assert.assertNotNull(partialCommitter.commit());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testDmlCommittedSnapshotNumber() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      Table tableBefore = getIcebergTable(icebergModel, tableFolder);
      final int countBeforeDmlCommit = Iterables.size(tableBefore.snapshots());

      IcebergOpCommitter committer =
          icebergModel.getDmlCommitter(
              operatorContext,
              icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
              datasetConfig,
              IcebergCommandType.UPDATE,
              tableBefore.currentSnapshot().snapshotId(),
              RowLevelOperationMode.COPY_ON_WRITE);
      Assert.assertTrue(committer instanceof IcebergDmlOperationCommitter);
      IcebergDmlOperationCommitter dmlCommitter = (IcebergDmlOperationCommitter) committer;

      // Add a new manifest list, and delete several previous datafiles
      String deleteDataFile1 = "books/add1.parquet";
      String deleteDataFile2 = "books/add2.parquet";
      DataFile dataFile1 = getDatafile("books/add7.parquet");
      DataFile dataFile2 = getDatafile("books/add8.parquet");

      ManifestFile m1 = writeManifest(tableFolder, "manifestFileDmlDelete", dataFile1, dataFile2);
      dmlCommitter.consumeManifestFile(m1);
      dmlCommitter.consumeDeleteDataFilePath(deleteDataFile1);
      dmlCommitter.consumeDeleteDataFilePath(deleteDataFile2);
      dmlCommitter.beginDmlOperationTransaction();
      dmlCommitter.performCopyOnWriteTransaction();
      Table tableAfter = dmlCommitter.endDmlOperationTransaction();
      int countAfterDmlCommit = Iterables.size(tableAfter.snapshots());
      Assert.assertEquals(
          "Expect to increase 1 snapshot", 1, countAfterDmlCommit - countBeforeDmlCommit);
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testDeleteManifestFiles() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);

    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      String dataFile1Name = "books/add1.parquet";
      String dataFile2Name = "books/add2.parquet";

      // Add a new manifest list, and delete several previous datafiles
      DataFile dataFile1 = getDatafile(dataFile1Name);
      DataFile dataFile2 = getDatafile(dataFile2Name);

      ManifestFile m = writeManifest(tableFolder, "manifestFileDml", dataFile1, dataFile2);
      Table table = getIcebergTable(icebergModel, tableFolder);
      InputFile inputFile = table.io().newInputFile(m);
      DremioFileIO dremioFileIO = Mockito.mock(DremioFileIO.class);
      Set<String> actualDeletedFiles = new HashSet<>();

      when(dremioFileIO.newInputFile(m)).thenReturn(inputFile);
      doAnswer(
              new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) {
                  Object[] args = invocation.getArguments();
                  Assert.assertEquals("one file path arg is expected", args.length, 1);
                  actualDeletedFiles.add((String) args[0]);
                  return null;
                }
              })
          .when(dremioFileIO)
          .deleteFile(anyString());

      // scenario 1: delete both manifest file and data files
      IcebergCommitOpHelper.deleteManifestFiles(dremioFileIO, ImmutableList.of(m), true);
      Set<String> expectedDeletedFilesIncludeDataFiles =
          ImmutableSet.of(dataFile1Name, dataFile2Name, m.path(), getManifestCrcFileName(m.path()));
      Assert.assertEquals(expectedDeletedFilesIncludeDataFiles, actualDeletedFiles);

      // scenario 2: delete manifest file only
      actualDeletedFiles.clear();
      IcebergCommitOpHelper.deleteManifestFiles(dremioFileIO, ImmutableList.of(m), false);
      expectedDeletedFilesIncludeDataFiles =
          ImmutableSet.of(m.path(), getManifestCrcFileName(m.path()));
      Assert.assertEquals(expectedDeletedFilesIncludeDataFiles, actualDeletedFiles);
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testDropNonExistPartitionSpec() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      Table icebergTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertFalse("Non-partitioned table", icebergTable.spec().isPartitioned());

      IcebergTableIdentifier tableIdentifier =
          icebergModel.getTableIdentifier(tableFolder.toPath().toString());
      PartitionTransform idTransform = new PartitionTransform(ID_COLUMN);
      PartitionSpecAlterOption addOption =
          new PartitionSpecAlterOption(idTransform, SqlAlterTablePartitionColumns.Mode.ADD);
      // Add Partition Fields
      icebergModel.alterTable(tableIdentifier, addOption);
      icebergTable.refresh();
      Assert.assertTrue("Partitioned table", icebergTable.spec().isPartitioned());

      PartitionTransform dataTransform = new PartitionTransform(DATA_COLUMN);
      PartitionSpecAlterOption dropOption =
          new PartitionSpecAlterOption(dataTransform, SqlAlterTablePartitionColumns.Mode.DROP);
      // Drop a non-partitioned field
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                icebergModel.alterTable(tableIdentifier, dropOption);
                Assert.fail();
              })
          .hasErrorType(UNSUPPORTED_OPERATION)
          .hasMessageContaining("Cannot find partition field to remove: ref(name=\"data\")");
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testSequenceNumberTableProperty() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      String metadataFileLocation = initialiseTableWithLargeSchema(schema, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      Table icebergTable = getIcebergTable(icebergModel, tableFolder);
      Assert.assertFalse("Non-partitioned table", icebergTable.spec().isPartitioned());

      IcebergTableIdentifier tableIdentifier =
          icebergModel.getTableIdentifier(tableFolder.toPath().toString());
      PartitionTransform idTransform = new PartitionTransform(ID_COLUMN);
      PartitionSpecAlterOption addOption =
          new PartitionSpecAlterOption(idTransform, SqlAlterTablePartitionColumns.Mode.ADD);
      // Add Partition Field
      icebergModel.alterTable(tableIdentifier, addOption);
      icebergTable.refresh();
      Assert.assertTrue("Partitioned table", icebergTable.spec().isPartitioned());
      Assert.assertFalse(
          "The table property should not be set",
          icebergTable.properties().containsKey(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY));

      PartitionSpecAlterOption dropOption =
          new PartitionSpecAlterOption(idTransform, SqlAlterTablePartitionColumns.Mode.DROP);
      // Drop the partitioned field
      icebergModel.alterTable(tableIdentifier, dropOption);
      icebergTable.refresh();

      // Test to add sequence number table property
      Assert.assertTrue(
          "The table property should be set",
          icebergTable.properties().containsKey(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY));
      Assert.assertEquals(
          "2", icebergTable.properties().get(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY));

      // Add Partition Field again, and remove sequence number table property
      icebergModel.alterTable(tableIdentifier, addOption);
      icebergTable.refresh();
      Assert.assertFalse(
          "The table property should be removed",
          icebergTable.properties().containsKey(PARTITION_DROPPED_SEQUENCE_NUMBER_PROPERTY));
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }

  @Test
  public void testAddChangeAndDropColumn() throws IOException {
    final String tableName = UUID.randomUUID().toString();
    final File tableFolder = new File(folder, tableName);
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    try {
      DatasetConfig datasetConfig = getDatasetConfig(datasetPath);
      BatchSchema schemaWithNonNullableColumns =
          BatchSchema.of(
              Field.notNullable("required_int", new ArrowType.Int(32, true)),
              Field.notNullable(
                  "required_float", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
              Field.nullable(
                  "optional_double", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
      String metadataFileLocation =
          initialiseTableWithLargeSchema(schemaWithNonNullableColumns, tableName);
      IcebergMetadata icebergMetadata = new IcebergMetadata();
      icebergMetadata.setMetadataFileLocation(metadataFileLocation);
      datasetConfig.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

      Table icebergTable = getIcebergTable(icebergModel, tableFolder);
      IcebergTableIdentifier tableIdentifier =
          icebergModel.getTableIdentifier(tableFolder.toPath().toString());

      // Add an optional column.
      SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
      icebergModel.addColumns(
          tableIdentifier,
          schemaConverter.toIcebergFields(
              List.of(Field.nullable("optional_bool", new ArrowType.Bool()))));
      icebergTable.refresh();
      Assert.assertTrue(icebergTable.schema().findField("optional_bool") != null);

      // Change a required column to optional.
      icebergModel.changeColumn(
          tableIdentifier,
          "required_int",
          Field.nullable("optional_int", new ArrowType.Int(32, true)));
      icebergTable.refresh();
      Assert.assertTrue(icebergTable.schema().findField("optional_int") != null);
      Assert.assertTrue(icebergTable.schema().findField("required_int") == null);

      // Try to promote a column from INTEGER to FLOAT.
      UserExceptionAssert.assertThatThrownBy(
              () -> {
                icebergModel.changeColumn(
                    tableIdentifier,
                    "optional_int",
                    Field.nullable(
                        "optional_int",
                        new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
              })
          .hasErrorType(VALIDATION)
          .hasMessageContaining(
              "Cannot change data type of column [optional_int] from INTEGER to FLOAT");

      UserExceptionAssert.assertThatThrownBy(
              () -> {
                icebergModel.changeColumn(
                    tableIdentifier,
                    "optional_int",
                    Field.nullable(
                        "optional_float",
                        new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
              })
          .hasErrorType(VALIDATION)
          .hasMessageContaining(
              "Cannot change data type of column [optional_int] from INTEGER to FLOAT");

      UserExceptionAssert.assertThatThrownBy(
              () -> {
                icebergModel.changeColumn(
                    tableIdentifier,
                    "optional_double",
                    Field.notNullable(
                        "required_double",
                        new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
              })
          .hasErrorType(VALIDATION)
          .hasMessageContaining(
              "Cannot change column nullability: optional_double: optional -> required");

      // Drop a column.
      icebergModel.dropColumn(tableIdentifier, "optional_int");
      icebergTable.refresh();
      Assert.assertTrue(icebergTable.schema().findField("optional_int") == null);
      Assert.assertEquals(3, icebergTable.schema().columns().size());
    } finally {
      FileUtils.deleteDirectory(tableFolder);
    }
  }
}
