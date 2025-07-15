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

import static com.dremio.sabot.Fixtures.struct;
import static com.dremio.sabot.Fixtures.t;
import static com.dremio.sabot.Fixtures.th;
import static com.dremio.sabot.Fixtures.tr;
import static com.dremio.sabot.Fixtures.tuple;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.config.SplitProducerTableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionPOP;
import com.dremio.exec.store.SplitAndPartitionInfo;
import com.dremio.exec.store.SplitIdentity;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.parquet.ParquetSplitCreator;
import com.dremio.sabot.BaseTestTableFunction;
import com.dremio.sabot.Fixtures;
import com.dremio.sabot.Fixtures.Table;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.NormalizedPartitionInfo;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.collect.ImmutableList;
import java.util.stream.Collectors;
import org.junit.Test;
import org.mockito.Mock;

public class TestIcebergSplitGenTableFunction extends BaseTestTableFunction {

  private static final NormalizedPartitionInfo PARTITION_1 = createPartitionInfo("part1");
  private static final NormalizedPartitionInfo PARTITION_2 = createPartitionInfo("part2");
  private static final NormalizedPartitionInfo PARTITION_3 = createPartitionInfo("part3");

  private static final byte[] COL_IDS_1 = new byte[] {1};
  private static final byte[] COL_IDS_2 = new byte[] {2};
  private static final byte[] COL_IDS_3 = new byte[] {3};

  @Mock private StoragePluginId pluginId;

  @Mock(extraInterfaces = {SupportsIcebergRootPointer.class})
  private StoragePlugin plugin;

  @Test
  public void testSplitGenWithExtraCols() throws Exception {
    try (AutoCloseable option =
        with(ExecConstants.PARQUET_SPLIT_SIZE_VALIDATOR, 300L * 1024 * 1024)) {
      when(fec.getStoragePlugin(pluginId)).thenReturn(plugin);
      when(((SupportsIcebergRootPointer) plugin).createSplitCreator(any(), any(), anyBoolean()))
          .thenAnswer(i -> new ParquetSplitCreator(i.getArgument(0), true));

      Table input =
          t(
              th(
                  SystemSchemas.DATAFILE_PATH,
                  SystemSchemas.FILE_SIZE,
                  SystemSchemas.PARTITION_INFO,
                  SystemSchemas.COL_IDS),
              inputRow("path1", 1000L, PARTITION_1, COL_IDS_1),
              inputRow("path2", 100L, PARTITION_1, COL_IDS_1),
              inputRow("path3", 500L, PARTITION_3, COL_IDS_3),
              inputRow("path4", 200L, PARTITION_2, COL_IDS_2),
              inputRow("path5", 350L, PARTITION_3, COL_IDS_3));

      Table output =
          t(
              th(
                  struct(
                      SystemSchemas.SPLIT_IDENTITY,
                      ImmutableList.of(
                          SplitIdentity.PATH,
                          SplitIdentity.OFFSET,
                          SplitIdentity.LENGTH,
                          SplitIdentity.FILE_LENGTH)),
                  SystemSchemas.SPLIT_INFORMATION,
                  SystemSchemas.COL_IDS),
              outputRow("path1", 0L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 300L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 600L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 900L, 100L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path2", 0L, 100L, 100L, PARTITION_1, COL_IDS_1),
              outputRow("path3", 0L, 300L, 500L, PARTITION_3, COL_IDS_3),
              outputRow("path3", 300L, 200L, 500L, PARTITION_3, COL_IDS_3),
              outputRow("path4", 0L, 200L, 200L, PARTITION_2, COL_IDS_2),
              outputRow("path5", 0L, 300L, 350L, PARTITION_3, COL_IDS_3),
              outputRow("path5", 300L, 50L, 350L, PARTITION_3, COL_IDS_3));

      validateSingle(getPop(), TableFunctionOperator.class, input, output, 3);
    }
  }

  @Test
  public void testSplitGenWithExtraColsOnlyInInput() throws Exception {
    try (AutoCloseable option =
        with(ExecConstants.PARQUET_SPLIT_SIZE_VALIDATOR, 300L * 1024 * 1024)) {
      when(fec.getStoragePlugin(pluginId)).thenReturn(plugin);
      when(((SupportsIcebergRootPointer) plugin).createSplitCreator(any(), any(), anyBoolean()))
          .thenAnswer(i -> new ParquetSplitCreator(i.getArgument(0), true));

      Table input =
          t(
              th(
                  SystemSchemas.DATAFILE_PATH,
                  SystemSchemas.FILE_SIZE,
                  SystemSchemas.PARTITION_INFO,
                  SystemSchemas.COL_IDS,
                  "input_only_col1",
                  "input_only_col2"),
              tr(
                  "path1",
                  1000L * 1024 * 1024,
                  IcebergSerDe.serializeToByteArray(PARTITION_1),
                  COL_IDS_1,
                  "ignore",
                  "ignore"),
              tr(
                  "path2",
                  100L * 1024 * 1024,
                  IcebergSerDe.serializeToByteArray(PARTITION_1),
                  COL_IDS_1,
                  "ignore",
                  "ignore"),
              tr(
                  "path3",
                  500L * 1024 * 1024,
                  IcebergSerDe.serializeToByteArray(PARTITION_3),
                  COL_IDS_3,
                  "ignore",
                  "ignore"),
              tr(
                  "path4",
                  200L * 1024 * 1024,
                  IcebergSerDe.serializeToByteArray(PARTITION_2),
                  COL_IDS_2,
                  "ignore",
                  "ignore"),
              tr(
                  "path5",
                  350L * 1024 * 1024,
                  IcebergSerDe.serializeToByteArray(PARTITION_3),
                  COL_IDS_3,
                  "ignore",
                  "ignore"));

      Table output =
          t(
              th(
                  struct(
                      SystemSchemas.SPLIT_IDENTITY,
                      ImmutableList.of(
                          SplitIdentity.PATH,
                          SplitIdentity.OFFSET,
                          SplitIdentity.LENGTH,
                          SplitIdentity.FILE_LENGTH)),
                  SystemSchemas.SPLIT_INFORMATION,
                  SystemSchemas.COL_IDS),
              outputRow("path1", 0L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 300L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 600L, 300L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path1", 900L, 100L, 1000L, PARTITION_1, COL_IDS_1),
              outputRow("path2", 0L, 100L, 100L, PARTITION_1, COL_IDS_1),
              outputRow("path3", 0L, 300L, 500L, PARTITION_3, COL_IDS_3),
              outputRow("path3", 300L, 200L, 500L, PARTITION_3, COL_IDS_3),
              outputRow("path4", 0L, 200L, 200L, PARTITION_2, COL_IDS_2),
              outputRow("path5", 0L, 300L, 350L, PARTITION_3, COL_IDS_3),
              outputRow("path5", 300L, 50L, 350L, PARTITION_3, COL_IDS_3));

      validateSingle(getPop(), TableFunctionOperator.class, input, output, 3);
    }
  }

  @Test
  public void testPathWithVersion() throws Exception {
    try (AutoCloseable option =
        with(ExecConstants.PARQUET_SPLIT_SIZE_VALIDATOR, 300L * 1024 * 1024)) {
      when(fec.getStoragePlugin(pluginId)).thenReturn(plugin);
      when(((SupportsIcebergRootPointer) plugin).createSplitCreator(any(), any(), anyBoolean()))
          .thenAnswer(i -> new ParquetSplitCreator(i.getArgument(0), true));

      Table input =
          t(
              th(
                  SystemSchemas.DATAFILE_PATH,
                  SystemSchemas.FILE_SIZE,
                  SystemSchemas.PARTITION_INFO,
                  SystemSchemas.COL_IDS),
              inputRow("path1?version=100", 500L, PARTITION_1, COL_IDS_1));

      Table output =
          t(
              th(
                  struct(
                      SystemSchemas.SPLIT_IDENTITY,
                      ImmutableList.of(
                          SplitIdentity.PATH,
                          SplitIdentity.OFFSET,
                          SplitIdentity.LENGTH,
                          SplitIdentity.FILE_LENGTH)),
                  SystemSchemas.SPLIT_INFORMATION,
                  SystemSchemas.COL_IDS),
              outputRowWithVersion("path1", 100L, 0L, 300L, 500L, PARTITION_1, COL_IDS_1),
              outputRowWithVersion("path1", 100L, 300L, 200L, 500L, PARTITION_1, COL_IDS_1));

      validateSingle(getPop(), TableFunctionOperator.class, input, output, 3);
    }
  }

  @Test
  public void testOutputBufferNotReused() throws Exception {
    when(fec.getStoragePlugin(pluginId)).thenReturn(plugin);
    when(((SupportsIcebergRootPointer) plugin).createSplitCreator(any(), any(), anyBoolean()))
        .thenAnswer(i -> new ParquetSplitCreator(i.getArgument(0), true));

    Table input =
        t(
            th(
                SystemSchemas.DATAFILE_PATH,
                SystemSchemas.FILE_SIZE,
                SystemSchemas.PARTITION_INFO,
                SystemSchemas.COL_IDS),
            inputRow("path1", 1000L, PARTITION_1, COL_IDS_1),
            inputRow("path2", 100L, PARTITION_1, COL_IDS_1),
            inputRow("path3", 500L, PARTITION_3, COL_IDS_3),
            inputRow("path4", 200L, PARTITION_2, COL_IDS_2),
            inputRow("path5", 350L, PARTITION_3, COL_IDS_3));

    validateOutputBufferNotReused(getPop(), input, 3);
  }

  private Fixtures.DataRow inputRow(
      String path, long fileSizeInMb, NormalizedPartitionInfo partitionInfo, byte[] colIds)
      throws Exception {
    return tr(
        path, fileSizeInMb * 1024 * 1024, IcebergSerDe.serializeToByteArray(partitionInfo), colIds);
  }

  private Fixtures.DataRow outputRow(
      String path,
      long offsetInMb,
      long sizeInMb,
      long fileSizeInMb,
      NormalizedPartitionInfo partitionInfo,
      byte[] colIds)
      throws Exception {
    return tr(
        tuple(path, offsetInMb * 1024 * 1024, sizeInMb * 1024 * 1024, fileSizeInMb * 1024 * 1024),
        createSplitInformation(
            path,
            offsetInMb * 1024 * 1024,
            sizeInMb * 1024 * 1024,
            fileSizeInMb * 1024 * 1024,
            0,
            partitionInfo),
        colIds);
  }

  private Fixtures.DataRow outputRowWithVersion(
      String path,
      long version,
      long offsetInMb,
      long sizeInMb,
      long fileSizeInMb,
      NormalizedPartitionInfo partitionInfo,
      byte[] colIds)
      throws Exception {
    return tr(
        tuple(path, offsetInMb * 1024 * 1024, sizeInMb * 1024 * 1024, fileSizeInMb * 1024 * 1024),
        createSplitInformation(
            path,
            offsetInMb * 1024 * 1024,
            sizeInMb * 1024 * 1024,
            fileSizeInMb * 1024 * 1024,
            version,
            partitionInfo),
        colIds);
  }

  private TableFunctionPOP getPop() {
    return new TableFunctionPOP(
        PROPS,
        null,
        new TableFunctionConfig(
            TableFunctionConfig.FunctionType.ICEBERG_SPLIT_GEN,
            true,
            new SplitProducerTableFunctionContext(
                null,
                SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA,
                null,
                null,
                null,
                null,
                pluginId,
                null,
                SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA.getFields().stream()
                    .map(f -> SchemaPath.getSimplePath(f.getName()))
                    .collect(Collectors.toList()),
                null,
                null,
                false,
                false,
                true,
                null,
                false)));
  }

  private static NormalizedPartitionInfo createPartitionInfo(String id) {
    return NormalizedPartitionInfo.newBuilder().setId(id).build();
  }

  private static byte[] createSplitInformation(
      String path,
      long offset,
      long length,
      long fileSize,
      long mtime,
      NormalizedPartitionInfo partitionInfo)
      throws Exception {
    ParquetProtobuf.ParquetBlockBasedSplitXAttr splitExtended =
        ParquetProtobuf.ParquetBlockBasedSplitXAttr.newBuilder()
            .setPath(path)
            .setStart(offset)
            .setLength(length)
            .setFileLength(fileSize)
            .setLastModificationTime(mtime)
            .build();

    PartitionProtobuf.NormalizedDatasetSplitInfo.Builder splitInfo =
        PartitionProtobuf.NormalizedDatasetSplitInfo.newBuilder()
            .setPartitionId(partitionInfo.getId())
            .setExtendedProperty(splitExtended.toByteString());

    return IcebergSerDe.serializeToByteArray(
        new SplitAndPartitionInfo(partitionInfo, splitInfo.build()));
  }

  @Test
  public void testGetFileType() {
    TableFunctionContext tableFunctionContext = mock(TableFunctionContext.class);
    // return parquet if no fileConfig
    assertEquals(FileType.PARQUET, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));

    FileConfig fileConfig = mock(FileConfig.class);
    doReturn(fileConfig).when(tableFunctionContext).getFormatSettings();
    // return parquet if there is fileConfig, but it does not have a file format
    assertEquals(FileType.PARQUET, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));

    // return PARQUET for ICEBERG, because we only support ICEBERG with parquet files
    doReturn(FileType.ICEBERG).when(fileConfig).getType();
    assertEquals(FileType.PARQUET, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));

    doReturn(FileType.PARQUET).when(fileConfig).getType();
    assertEquals(FileType.PARQUET, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));

    doReturn(FileType.ORC).when(fileConfig).getType();
    assertEquals(FileType.ORC, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));

    doReturn(FileType.AVRO).when(fileConfig).getType();
    assertEquals(FileType.AVRO, IcebergSplitGenTableFunction.getFileType(tableFunctionContext));
  }
}
