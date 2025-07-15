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

import static com.dremio.exec.store.iceberg.IcebergUtils.getPartitionStatsFiles;
import static com.dremio.exec.store.iceberg.model.IcebergCommandType.INCREMENTAL_METADATA_REFRESH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.BaseTestQuery;
import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.expr.fn.impl.ByteArrayWrapper;
import com.dremio.exec.planner.cost.ScanCostFactor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergOpCommitter;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogGrpcClient;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OpProfileDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.service.catalog.GetDatasetRequest;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import com.dremio.service.namespace.dataset.proto.ScanStatsType;
import com.dremio.service.namespace.dataset.proto.UserDefinedSchemaSettings;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.test.AllocatorRule;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatsFileLocations;
import org.apache.iceberg.PartitionStatsMetadata;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.junit.Before;
import org.junit.Rule;

public class TestIcebergCommitterBase extends BaseTestQuery
    implements SupportsTypeCoercionsAndUpPromotions {

  @Rule public final AllocatorRule allocatorRule = AllocatorRule.defaultAllocator();

  protected final String folder = Files.createTempDir().getAbsolutePath();
  protected final DatasetCatalogGrpcClient client =
      new DatasetCatalogGrpcClient(getSabotContext().getDatasetCatalogBlockingStub().get());
  protected BufferAllocator testAllocator;
  protected IcebergModel icebergModel;
  protected OperatorStats operatorStats;
  protected OperatorContext operatorContext;
  protected static final String ID_COLUMN = "id";
  protected static final String DATA_COLUMN = "data";

  protected final BatchSchema schema =
      BatchSchema.of(
          Field.nullablePrimitive(ID_COLUMN, new ArrowType.Int(64, true)),
          Field.nullablePrimitive(DATA_COLUMN, new ArrowType.Utf8()));

  @Before
  public void beforeTest() {
    this.testAllocator = allocatorRule.newAllocator("test-iceberg_committer", 0, Long.MAX_VALUE);
    this.operatorStats = newStats();
    this.operatorContext = mock(OperatorContext.class);
    when(operatorContext.getStats()).thenReturn(operatorStats);
    ExecutionControls executionControls = mock(ExecutionControls.class);
    when(executionControls.lookupExceptionInjection(any(), any())).thenReturn(null);
    when(operatorContext.getExecutionControls()).thenReturn(executionControls);
    OptionManager optionManager = mock(OptionManager.class);
    when(optionManager.getOption(ExecConstants.ENABLE_MAP_DATA_TYPE)).thenReturn(true);
    when(optionManager.getOption(ExecConstants.ENABLE_UNLIMITED_SPLITS_METADATA_CLEAN))
        .thenReturn(true);
    when(optionManager.getOption(ExecConstants.ENABLE_ICEBERG_CONCURRENCY)).thenReturn(true);
    when(optionManager.getOption(ExecConstants.DEFAULT_PERIOD_TO_KEEP_SNAPSHOTS_MS))
        .thenReturn(8 * 24 * 3600 * 1000L);
    when(operatorContext.getOptions()).thenReturn(optionManager);
    icebergModel = getIcebergModel(TEMP_SCHEMA);
  }

  protected String initialiseTableWithLargeSchema(BatchSchema schema, String tableName)
      throws IOException {
    final List<String> datasetPath = Lists.newArrayList("dfs", tableName);
    final File tableFolder = new File(folder, tableName);
    tableFolder.mkdirs();

    DatasetConfig config = getDatasetConfig(datasetPath);

    IcebergOpCommitter fullRefreshCommitter =
        icebergModel.getFullMetadataRefreshCommitter(
            tableName,
            datasetPath,
            tableFolder.toPath().toString(),
            tableName,
            icebergModel.getTableIdentifier(tableFolder.toPath().toString()),
            schema,
            Collections.emptyList(),
            config,
            operatorStats,
            null,
            null);
    fullRefreshCommitter.commit();

    DataFile dataFile1 = getDatafile("books/add1.parquet");
    DataFile dataFile2 = getDatafile("books/add2.parquet");
    DataFile dataFile3 = getDatafile("books/add3.parquet");
    DataFile dataFile4 = getDatafile("books/add4.parquet");
    DataFile dataFile5 = getDatafile("books/add5.parquet");

    String tag = getTag(datasetPath);
    config.setTag(tag);
    Table table = getIcebergTable(icebergModel, new File(folder, tableName));
    TableOperations tableOperations = ((BaseTable) table).operations();
    String metadataFileLocation = tableOperations.current().metadataFileLocation();
    IcebergMetadata icebergMetadata = new IcebergMetadata();
    icebergMetadata.setMetadataFileLocation(metadataFileLocation);
    config.getPhysicalDataset().setIcebergMetadata(icebergMetadata);

    IcebergOpCommitter incrementalRefreshCommitter =
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
            config,
            localFs,
            null,
            INCREMENTAL_METADATA_REFRESH,
            null,
            table.currentSnapshot().snapshotId(),
            false);

    ManifestFile m1 =
        writeManifest(
            tableFolder, "manifestFile1", dataFile1, dataFile2, dataFile3, dataFile4, dataFile5);
    incrementalRefreshCommitter.consumeManifestFile(m1);
    incrementalRefreshCommitter.commit();
    return incrementalRefreshCommitter.getRootPointer();
  }

  protected String getTag(List<String> datasetPath) {
    return client
        .getCatalogServiceApi()
        .getDataset(GetDatasetRequest.newBuilder().addAllDatasetPath(datasetPath).build())
        .getTag();
  }

  protected ManifestFile writeManifest(File tableFolder, String fileName, DataFile... files)
      throws IOException {
    return writeManifest(tableFolder, fileName, null, files);
  }

  protected ManifestFile writeManifest(
      File tableFolder, String fileName, Long snapshotId, DataFile... files) throws IOException {
    File manifestFile = new File(folder, fileName + ".avro");
    Table table = getIcebergTable(icebergModel, tableFolder);
    OutputFile outputFile = table.io().newOutputFile(manifestFile.getCanonicalPath());

    ManifestWriter<DataFile> writer = ManifestFiles.write(1, table.spec(), outputFile, snapshotId);
    try {
      for (DataFile file : files) {
        writer.add(file);
      }
    } finally {
      writer.close();
    }
    return writer.toManifestFile();
  }

  protected DataFile getDatafile(String path) {
    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(path)
            .withFileSizeInBytes(40)
            .withRecordCount(9)
            .build();
    return dataFile;
  }

  protected DeleteFile getPositionalDeleteFile(String path) {
    DeleteFile deleteFile =
        FileMetadata.deleteFileBuilder(PartitionSpec.unpartitioned())
            .ofPositionDeletes()
            .withPath(path)
            .withFileSizeInBytes(40)
            .withRecordCount(9)
            .build();
    return deleteFile;
  }

  // build Set of referenced data files within the positional deletes "file_path" column.
  protected Set<ByteArrayWrapper> getReferencedDataFiles(String referenceFile) {
    Set<ByteArrayWrapper> referencedFilesAsBytes = new HashSet<>();
    referencedFilesAsBytes.add(new ByteArrayWrapper(referenceFile.getBytes()));
    return referencedFilesAsBytes;
  }

  protected DataFile getDatafileWithPartitionSpec(String path) {
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    PartitionSpec spec_id_and_data_column =
        PartitionSpec.builderFor(schemaConverter.toIcebergSchema(schema)).identity("id").build();

    DataFile dataFile =
        DataFiles.builder(spec_id_and_data_column)
            .withPath(path)
            .withFileSizeInBytes(40)
            .withRecordCount(9)
            .build();
    return dataFile;
  }

  protected DatasetConfig getDatasetConfig(List<String> datasetPath) {
    NamespaceKey tableNSKey = new NamespaceKey(datasetPath);
    final FileConfig format = new FileConfig();
    format.setType(FileType.PARQUET);
    format.setLocation(tableNSKey.toString());
    final PhysicalDataset physicalDataset = new PhysicalDataset();
    physicalDataset.setFormatSettings(format);
    final ReadDefinition initialReadDef = ReadDefinition.getDefaultInstance();
    final ScanStats stats = new ScanStats();
    stats.setType(ScanStatsType.NO_EXACT_ROW_COUNT);
    stats.setScanFactor(ScanCostFactor.PARQUET.getFactor());
    stats.setRecordCount(500L);
    initialReadDef.setScanStats(stats);

    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setFullPathList(tableNSKey.getPathComponents());
    datasetConfig.setPhysicalDataset(physicalDataset);
    datasetConfig.setId(new EntityId(UUID.randomUUID().toString()));
    datasetConfig.setReadDefinition(initialReadDef);
    datasetConfig.setType(DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER);

    datasetConfig.getPhysicalDataset().setInternalSchemaSettings(new UserDefinedSchemaSettings());
    return datasetConfig;
  }

  protected static String getManifestCrcFileName(String manifestFilePath) {
    com.dremio.io.file.Path p = com.dremio.io.file.Path.of(manifestFilePath);
    String fileName = p.getName();
    com.dremio.io.file.Path parentPath = p.getParent();
    return parentPath + com.dremio.io.file.Path.SEPARATOR + "." + fileName + ".crc";
  }

  protected static Set<String> collectAllFilesFromSnapshot(Snapshot snapshot, FileIO io) {
    Set<String> files = Sets.newHashSet();
    files.addAll(pathSet(snapshot.addedDataFiles(io)));
    files.add(snapshot.manifestListLocation());
    files.addAll(manifestPaths(snapshot.allManifests(io)));
    files.addAll(partitionStatsPaths(snapshot.partitionStatsMetadata(), io));
    return files;
  }

  protected static Set<String> pathSet(Iterable<DataFile> files) {
    return Sets.newHashSet(Iterables.transform(files, file -> file.path().toString()));
  }

  protected static Set<String> manifestPaths(Iterable<ManifestFile> files) {
    return Sets.newHashSet(Iterables.transform(files, file -> file.path().toString()));
  }

  protected static Set<String> partitionStatsPaths(
      PartitionStatsMetadata partitionStatsMetadata, FileIO io) {
    Set<String> partitionStatsFiles = Sets.newHashSet();
    if (partitionStatsMetadata != null) {
      String partitionStatsMetadataLocation = partitionStatsMetadata.metadataFileLocation();
      PartitionStatsFileLocations partitionStatsLocations =
          getPartitionStatsFiles(io, partitionStatsMetadataLocation);
      if (partitionStatsLocations != null) {
        // Partition stats have metadata file and partition files.
        partitionStatsFiles.add(partitionStatsMetadataLocation);
        partitionStatsFiles.addAll(
            partitionStatsLocations.all().values().stream().collect(Collectors.toList()));
      }
    }

    return partitionStatsFiles;
  }

  protected OperatorStats newStats() {
    OpProfileDef prof = new OpProfileDef(1, 1, 1);
    final OperatorStats operatorStats = new OperatorStats(prof, testAllocator);
    return operatorStats;
  }
}
