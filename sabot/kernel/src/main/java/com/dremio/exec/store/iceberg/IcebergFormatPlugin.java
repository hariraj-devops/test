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

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.exec.catalog.MetadataObjectsUtils;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.EmptyRecordReader;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.SplitAndPartitionInfo;
import com.dremio.exec.store.dfs.FileCountTooLargeException;
import com.dremio.exec.store.dfs.FileDatasetHandle;
import com.dremio.exec.store.dfs.FileSelection;
import com.dremio.exec.store.dfs.FileSelectionProcessor;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.FormatMatcher;
import com.dremio.exec.store.dfs.FormatPlugin;
import com.dremio.exec.store.dfs.LayeredPluginFileSelectionProcessor;
import com.dremio.exec.store.dfs.PreviousDatasetInfo;
import com.dremio.exec.store.dfs.easy.EasyFormatPlugin;
import com.dremio.exec.store.dfs.easy.EasySubScan;
import com.dremio.exec.store.dfs.easy.EasyWriter;
import com.dremio.exec.store.file.proto.FileProtobuf.FileUpdateKey;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergTableLoader;
import com.dremio.exec.store.parquet.ParquetFormatConfig;
import com.dremio.exec.store.parquet.ParquetFormatPlugin;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.store.easy.proto.EasyProtobuf;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.iceberg.Table;

public class IcebergFormatPlugin extends EasyFormatPlugin<IcebergFormatConfig> {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(IcebergFormatPlugin.class);

  private static final String DEFAULT_NAME = "iceberg";
  private static final boolean IS_COMPRESSIBLE = true;
  private static final String NOT_SUPPORT_METASTORE_TABLE_MSG =
      "This folder does not contain a filesystem-based Iceberg table. If the table in this folder is managed via a catalog "
          + "such as Hive, Glue, or Nessie, please use a data source configured for that catalog to connect to this table.";

  private final IcebergFormatMatcher formatMatcher;
  private final IcebergFormatConfig config;
  private final String name;
  private final @Nullable FormatPlugin dataFormatPlugin;

  // NOTE: This constructor is used by FormatCreator through classpath scanning.
  public IcebergFormatPlugin(
      String name,
      PluginSabotContext pluginSabotContext,
      IcebergFormatConfig formatConfig,
      SupportsFsCreation supportsFsCreation) {
    super(
        name,
        pluginSabotContext,
        formatConfig,
        false,
        IS_COMPRESSIBLE,
        formatConfig.getExtensions(),
        DEFAULT_NAME);
    this.config = formatConfig;
    this.name = name == null ? DEFAULT_NAME : name;
    this.formatMatcher = new IcebergFormatMatcher(this);
    this.dataFormatPlugin =
        initializeDataFormatPlugin(formatConfig, supportsFsCreation, pluginSabotContext, name);
  }

  private static @Nullable FormatPlugin initializeDataFormatPlugin(
      IcebergFormatConfig formatConfig,
      SupportsFsCreation supportsFsCreation,
      PluginSabotContext pluginSabotContext,
      String name) {
    if (supportsFsCreation != null
        && formatConfig.getDataFormatType() == FileType.PARQUET
        && supportsFsCreation instanceof FileSystemPlugin) {
      return new ParquetFormatPlugin(
          name,
          pluginSabotContext,
          (ParquetFormatConfig) formatConfig.getDataFormatConfig(),
          supportsFsCreation);
    }
    return null;
  }

  @Override
  public IcebergFormatConfig getConfig() {
    return config;
  }

  @Override
  public boolean isLayered() {
    return true;
  }

  // TODO ravindra: should get the parquet file path by traversing the json file.
  @Override
  public RecordReader getRecordReader(
      OperatorContext context, FileSystem fs, FileAttributes attributes)
      throws ExecutionSetupException {
    if (attributes.getPath().getName().endsWith("parquet") && dataFormatPlugin != null) {
      return dataFormatPlugin.getRecordReader(context, fs, attributes);
    } else {
      return new EmptyRecordReader();
    }
  }

  @Override
  public FileSelectionProcessor getFileSelectionProcessor(
      FileSystem fs, FileSelection fileSelection) {
    return new LayeredPluginFileSelectionProcessor(fs, fileSelection);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public RecordReader getRecordReader(
      OperatorContext context,
      FileSystem dfs,
      EasyProtobuf.EasyDatasetSplitXAttr splitAttributes,
      List<SchemaPath> columns)
      throws ExecutionSetupException {
    throw new UnsupportedOperationException("unimplemented method");
  }

  @Override
  public RecordReader getRecordReader(
      OperatorContext context,
      FileSystem dfs,
      SplitAndPartitionInfo split,
      EasyProtobuf.EasyDatasetSplitXAttr splitAttributes,
      List<SchemaPath> columns,
      FragmentExecutionContext fec,
      EasySubScan config)
      throws ExecutionSetupException {
    throw new UnsupportedOperationException("Deprecated path");
  }

  @Override
  public FormatMatcher getMatcher() {
    return formatMatcher;
  }

  @Override
  public int getReaderOperatorType() {
    return UserBitShared.CoreOperatorType.ICEBERG_SUB_SCAN_VALUE;
  }

  @Override
  public int getWriterOperatorType() {
    return UserBitShared.CoreOperatorType.MANIFEST_WRITER_VALUE;
  }

  @Override
  public FileDatasetHandle getDatasetAccessor(
      DatasetType type,
      PreviousDatasetInfo previousInfo,
      FileSystem fs,
      FileSelection fileSelection,
      FileSystemPlugin<?> fsPlugin,
      NamespaceKey tableSchemaPath,
      FileUpdateKey updateKey,
      int maxLeafColumns,
      TimeTravelOption.TimeTravelRequest travelRequest) {

    final Supplier<Table> tableSupplier =
        Suppliers.memoize(
            () -> {
              final IcebergModel icebergModel = fsPlugin.getIcebergModel();
              final IcebergTableLoader icebergTableLoader =
                  icebergModel.getIcebergTableLoader(
                      icebergModel.getTableIdentifier(fileSelection.getSelectionRoot()));
              return icebergTableLoader.getIcebergTable();
            });

    final TableSnapshotProvider tableSnapshotProvider =
        TimeTravelProcessors.getTableSnapshotProvider(
            tableSchemaPath.getPathComponents(), travelRequest);
    final TableSchemaProvider tableSchemaProvider =
        TimeTravelProcessors.getTableSchemaProvider(travelRequest);
    return new IcebergExecutionDatasetAccessor(
        MetadataObjectsUtils.toEntityPath(tableSchemaPath),
        tableSupplier,
        this,
        fs,
        tableSnapshotProvider,
        tableSchemaProvider);
  }

  @Override
  public RecordWriter getRecordWriter(OperatorContext context, EasyWriter writer)
      throws IOException {
    throw new UnsupportedOperationException("Deprecated path");
  }

  private static final class DelegateDirectoryStream implements DirectoryStream<FileAttributes> {
    private final Iterable<FileAttributes> delegate;

    private DelegateDirectoryStream(Iterable<FileAttributes> fileAttributesIterable) {
      delegate = fileAttributesIterable;
    }

    @Override
    public Iterator<FileAttributes> iterator() {
      return delegate.iterator();
    }

    @Override
    public void close() throws IOException {}
  }

  @Override
  public DirectoryStream<FileAttributes> getFilesForSamples(
      FileSystem fs, FileSystemPlugin<?> fsPlugin, Path path)
      throws IOException, FileCountTooLargeException {
    if (!formatMatcher.isFileSystemSupportedIcebergTable(fs, path.toString())) {
      throw UserException.unsupportedError()
          .message(NOT_SUPPORT_METASTORE_TABLE_MSG)
          .buildSilently();
    }

    final IcebergModel icebergModel = fsPlugin.getIcebergModel();
    final IcebergTableLoader icebergTableLoader =
        icebergModel.getIcebergTableLoader(icebergModel.getTableIdentifier(path.toString()));
    Table table = icebergTableLoader.getIcebergTable();

    // Transform to lazy iteration and retrieve file attributes on demand
    final int maxFilesLimit = getMaxFilesLimit();
    Iterable<FileAttributes> fileAttributesIterable =
        Iterables.filter(
            Iterables.transform(
                Iterables.limit(table.newScan().planFiles(), maxFilesLimit),
                scanTask -> {
                  String dataFilePath =
                      Path.getContainerSpecificRelativePath(
                          Path.of(String.valueOf(scanTask.file().path())));
                  try {
                    return fs.getFileAttributes(Path.of(dataFilePath));
                  } catch (Exception e) {
                    logger.info("Failed to read file attributes: {}", dataFilePath, e);
                  }
                  return null;
                }),
            Predicates.notNull());
    return new DelegateDirectoryStream(fileAttributesIterable);
  }
}
