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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.OperationType;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.IcebergSerDe;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.writer.WriterCommitterOutputHandler;
import com.dremio.sabot.op.writer.WriterCommitterRecord;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import io.protostuff.ByteString;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.assertj.core.groups.Tuple;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Tests for {@link IcebergOptimizeOperationCommitter} */
public class TestIcebergOptimizeOperationCommitter {
  private static final String META_LOCATION = "/table/metadata/v1.metadata.json";

  @Test
  public void testNoChange() {
    IcebergCommand command = mock(IcebergCommand.class);
    Snapshot currentSnapshot = mock(Snapshot.class);
    Snapshot rewriteSnapshot = mock(Snapshot.class);
    Table table = mock(Table.class);
    when(table.currentSnapshot()).thenReturn(currentSnapshot);
    when(command.loadTable()).thenReturn(table);
    when(command.rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong()))
        .thenReturn(rewriteSnapshot);
    WriterCommitterOutputHandler outputHandler = mock(WriterCommitterOutputHandler.class);
    doNothing().when(outputHandler).write(any(WriterCommitterRecord.class));
    ArgumentCaptor<WriterCommitterRecord> writerRecordCaptor =
        ArgumentCaptor.forClass(WriterCommitterRecord.class);

    // No added, no deleted
    IcebergOptimizeOperationCommitter opCommitter =
        new IcebergOptimizeOperationCommitter(
            command,
            getOperatorStats(),
            getDatasetConfig(),
            null,
            null,
            getTableProps(),
            mock(FileSystem.class),
            null);
    Snapshot commitSnapshot = opCommitter.commit(outputHandler);

    assertThat(commitSnapshot).isEqualTo(currentSnapshot).isNotEqualTo(rewriteSnapshot);
    verify(command, never()).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());

    // file deleted but no replace write or vice-versa
    opCommitter.consumeAddDataFile(getDatafile("/table/data/added1.parquet"));
    commitSnapshot = opCommitter.commit(outputHandler);

    assertThat(commitSnapshot).isEqualTo(currentSnapshot).isNotEqualTo(rewriteSnapshot);
    verify(command, never()).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());

    opCommitter.consumeDeleteDataFile(getDatafile("/table/data/deleted1.parquet"));
    commitSnapshot = opCommitter.commit(outputHandler);

    assertThat(commitSnapshot).isEqualTo(currentSnapshot).isNotEqualTo(rewriteSnapshot);
    verify(command, never()).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());

    verify(outputHandler, times(9)).write(writerRecordCaptor.capture());
    assertThat(writerRecordCaptor.getAllValues())
        .extracting(WriterCommitterRecord::operationType, WriterCommitterRecord::records)
        .hasSize(9)
        .containsExactly(
            Tuple.tuple(OperationType.DELETE_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DELETEFILE.value, 0L),
            Tuple.tuple(OperationType.ADD_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DELETEFILE.value, 0L),
            Tuple.tuple(OperationType.ADD_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DELETEFILE.value, 0L),
            Tuple.tuple(OperationType.ADD_DATAFILE.value, 0L));
  }

  @Test
  public void testRewrite() {
    IcebergCommand command = mock(IcebergCommand.class);
    Snapshot currentSnapshot = mock(Snapshot.class);
    Snapshot rewriteSnapshot = mock(Snapshot.class);
    Table table = mock(Table.class);
    when(table.currentSnapshot()).thenReturn(currentSnapshot);
    when(command.loadTable()).thenReturn(table);
    when(command.rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong()))
        .thenReturn(rewriteSnapshot);
    WriterCommitterOutputHandler outputHandler = mock(WriterCommitterOutputHandler.class);
    doNothing().when(outputHandler).write(any(WriterCommitterRecord.class));
    ArgumentCaptor<WriterCommitterRecord> writerRecordCaptor =
        ArgumentCaptor.forClass(WriterCommitterRecord.class);

    IcebergOptimizeOperationCommitter opCommitter =
        new IcebergOptimizeOperationCommitter(
            command,
            getOperatorStats(),
            getDatasetConfig(),
            2L,
            currentSnapshot.snapshotId(),
            getTableProps(),
            mock(FileSystem.class),
            null);
    opCommitter.consumeAddDataFile(getDatafile("/a1.parquet"));
    opCommitter.consumeAddDataFile(getDatafile("/a2.parquet"));
    opCommitter.consumeDeleteDataFile(getDatafile("/d1.parquet"));
    opCommitter.consumeDeleteDataFile(getDatafile("/d2.parquet"));
    opCommitter.consumeDeleteDeleteFile(getDeletefile("/pd1.parquet"));
    opCommitter.consumeDeleteDeleteFile(getDeletefile("/pd2.parquet"));

    assertThat(opCommitter.getAddedDataFiles())
        .extracting(ContentFile::path)
        .contains("/a1.parquet", "/a2.parquet");
    assertThat(opCommitter.getRemovedDataFiles())
        .extracting(ContentFile::path)
        .contains("/d1.parquet", "/d2.parquet");
    assertThat(opCommitter.getRemovedDeleteFiles())
        .extracting(ContentFile::path)
        .contains("/pd1.parquet", "/pd2.parquet");

    Snapshot commitSnapshot = opCommitter.commit(outputHandler);
    assertThat(commitSnapshot).isEqualTo(rewriteSnapshot).isNotEqualTo(currentSnapshot);

    verify(command, times(1)).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());
    verify(outputHandler, times(3)).write(writerRecordCaptor.capture());
    assertThat(writerRecordCaptor.getAllValues())
        .extracting(WriterCommitterRecord::operationType, WriterCommitterRecord::records)
        .hasSize(3)
        .containsExactly(
            Tuple.tuple(OperationType.DELETE_DATAFILE.value, 2L),
            Tuple.tuple(OperationType.DELETE_DELETEFILE.value, 2L),
            Tuple.tuple(OperationType.ADD_DATAFILE.value, 2L));
  }

  @Test
  public void testRewriteMinInputNotPassed() throws IOException {
    IcebergCommand command = mock(IcebergCommand.class);
    Snapshot currentSnapshot = mock(Snapshot.class);
    Snapshot rewriteSnapshot = mock(Snapshot.class);
    Table table = mock(Table.class);
    when(table.currentSnapshot()).thenReturn(currentSnapshot);
    when(command.loadTable()).thenReturn(table);
    when(command.rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong()))
        .thenReturn(rewriteSnapshot);
    FileSystem fs = mock(FileSystem.class);
    WriterCommitterOutputHandler outputHandler = mock(WriterCommitterOutputHandler.class);
    doNothing().when(outputHandler).write(any(WriterCommitterRecord.class));
    ArgumentCaptor<WriterCommitterRecord> writerRecordCaptor =
        ArgumentCaptor.forClass(WriterCommitterRecord.class);

    // minInputFiles is set to 5 as the evaluation criteria is based on total of removed data files
    // and removed delete files.
    IcebergOptimizeOperationCommitter opCommitter =
        new IcebergOptimizeOperationCommitter(
            command,
            getOperatorStats(),
            getDatasetConfig(),
            5L,
            currentSnapshot.snapshotId(),
            getTableProps(),
            fs,
            null);
    opCommitter.consumeAddDataFile(getDatafile("/a1.parquet"));
    opCommitter.consumeAddDataFile(getDatafile("/a2.parquet"));
    opCommitter.consumeDeleteDataFile(getDatafile("/d1.parquet"));
    opCommitter.consumeDeleteDataFile(getDatafile("/d2.parquet"));
    opCommitter.consumeDeleteDeleteFile(getDeletefile("/pd1.parquet"));
    opCommitter.consumeDeleteDeleteFile(getDeletefile("/pd2.parquet"));

    assertThat(opCommitter.getAddedDataFiles())
        .extracting(ContentFile::path)
        .contains("/a1.parquet", "/a2.parquet");
    assertThat(opCommitter.getRemovedDataFiles())
        .extracting(ContentFile::path)
        .contains("/d1.parquet", "/d2.parquet");
    assertThat(opCommitter.getRemovedDeleteFiles())
        .extracting(ContentFile::path)
        .contains("/pd1.parquet", "/pd2.parquet");

    Snapshot commitSnapshot = opCommitter.commit(outputHandler);
    assertThat(commitSnapshot).isEqualTo(currentSnapshot).isNotEqualTo(rewriteSnapshot);

    verify(command, never()).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());
    // Two data files and the empty directory due to NOOP
    verify(fs, times(3)).delete(any(Path.class), anyBoolean());
    verify(command, never()).rewriteFiles(anySet(), anySet(), anySet(), anySet(), anyLong());
    verify(outputHandler, times(3)).write(writerRecordCaptor.capture());
    assertThat(writerRecordCaptor.getAllValues())
        .extracting(WriterCommitterRecord::operationType, WriterCommitterRecord::records)
        .hasSize(3)
        .containsExactly(
            Tuple.tuple(OperationType.DELETE_DATAFILE.value, 0L),
            Tuple.tuple(OperationType.DELETE_DELETEFILE.value, 0L),
            Tuple.tuple(OperationType.ADD_DATAFILE.value, 0L));
  }

  @Test
  public void testGetRootPointer() {
    IcebergCommand command = mock(IcebergCommand.class);
    String rootPointerLocation = "/table/metadata/v2.metadata.json";
    when(command.getRootPointer()).thenReturn(rootPointerLocation);

    IcebergOptimizeOperationCommitter opCommitter =
        new IcebergOptimizeOperationCommitter(
            command,
            getOperatorStats(),
            getDatasetConfig(),
            null,
            null,
            getTableProps(),
            mock(FileSystem.class),
            null);

    assertThat(opCommitter.getRootPointer()).isEqualTo(rootPointerLocation);
    assertThat(opCommitter.isIcebergTableUpdated()).isTrue();
  }

  @Test
  public void testUnsupportedOperations() {
    IcebergCommand command = mock(IcebergCommand.class);
    IcebergOptimizeOperationCommitter opCommitter =
        new IcebergOptimizeOperationCommitter(
            command,
            getOperatorStats(),
            getDatasetConfig(),
            null,
            null,
            getTableProps(),
            mock(FileSystem.class),
            null);

    assertThatThrownBy(() -> opCommitter.consumeManifestFile(mock(ManifestFile.class)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("OPTIMIZE TABLE can't consume pre-prepared manifest files");

    assertThatThrownBy(() -> opCommitter.consumeDeleteDataFilePath("/table/data/f1.parquet"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("OPTIMIZE TABLE can't consume string paths");

    assertThatThrownBy(() -> opCommitter.updateSchema(BatchSchema.EMPTY))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("Updating schema is not supported for OPTIMIZE TABLE transaction");
  }

  private DatasetConfig getDatasetConfig() {
    IcebergMetadata icebergMetadata = mock(IcebergMetadata.class);
    PhysicalDataset physicalDataset = mock(PhysicalDataset.class);
    DatasetConfig datasetConfig = mock(DatasetConfig.class);

    when(icebergMetadata.getMetadataFileLocation()).thenReturn(META_LOCATION);
    when(physicalDataset.getIcebergMetadata()).thenReturn(icebergMetadata);
    when(datasetConfig.getPhysicalDataset()).thenReturn(physicalDataset);

    return datasetConfig;
  }

  private IcebergTableProps getTableProps() {
    return new IcebergTableProps(
        "s3://testdata/table_location/",
        UUID.randomUUID().toString(),
        BatchSchema.EMPTY,
        Collections.emptyList(),
        IcebergCommandType.OPTIMIZE,
        "db",
        "table_location",
        "",
        null,
        ByteString.copyFrom(IcebergSerDe.serializePartitionSpec(PartitionSpec.unpartitioned())),
        null,
        null,
        null,
        Collections.emptyMap(),
        null);
  }

  private OperatorStats getOperatorStats() {
    OperatorStats stats = mock(OperatorStats.class);
    doNothing().when(stats).addLongStat(any(MetricDef.class), anyLong());
    return stats;
  }

  private DataFile getDatafile(String path) {
    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath(path)
            .withFileSizeInBytes(40)
            .withRecordCount(9)
            .build();
    return dataFile;
  }

  private DeleteFile getDeletefile(String path) {
    DeleteFile deleteFile =
        FileMetadata.deleteFileBuilder(PartitionSpec.unpartitioned())
            .ofPositionDeletes()
            .withPath(path)
            .withFileSizeInBytes(40)
            .withRecordCount(9)
            .build();
    return deleteFile;
  }
}
