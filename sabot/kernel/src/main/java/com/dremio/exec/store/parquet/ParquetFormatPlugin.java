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
package com.dremio.exec.store.parquet;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.physical.base.AbstractWriter;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.dfs.BaseFormatPlugin;
import com.dremio.exec.store.dfs.BasicFormatMatcher;
import com.dremio.exec.store.dfs.FileDatasetHandle;
import com.dremio.exec.store.dfs.FileSelection;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.FormatMatcher;
import com.dremio.exec.store.dfs.MagicString;
import com.dremio.exec.store.dfs.PreviousDatasetInfo;
import com.dremio.exec.store.file.proto.FileProtobuf.FileUpdateKey;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.parquet2.ParquetRowiseReader;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.parquet.reader.ParquetDirectByteBufferAllocator;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import com.dremio.sabot.op.writer.WriterOperator;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.CodecFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;

public class ParquetFormatPlugin extends BaseFormatPlugin {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ParquetFormatPlugin.class);

  public static final ParquetMetadataConverter parquetMetadataConverter =
      new ParquetMetadataConverter();

  private static final String DEFAULT_NAME = "parquet";

  private static final List<Pattern> PATTERNS =
      Lists.newArrayList(
          Pattern.compile(".*\\.parquet$"),
          Pattern.compile(".*/" + ParquetFileWriter.PARQUET_METADATA_FILE));
  private static final List<MagicString> MAGIC_STRINGS =
      Lists.newArrayList(new MagicString(0, ParquetFileWriter.MAGIC));

  private final PluginSabotContext context;
  private final ParquetFormatMatcher formatMatcher;
  private final ParquetFormatConfig config;
  private final String name;

  // Note: This constructor is used by FormatCreator through classpath scanning.
  public ParquetFormatPlugin(
      String name, PluginSabotContext context, SupportsFsCreation supportsFsCreation) {
    this(name, context, new ParquetFormatConfig(), supportsFsCreation);
  }

  // Note: This constructor is used by FormatCreator through classpath scanning.
  public ParquetFormatPlugin(
      String name,
      PluginSabotContext context,
      ParquetFormatConfig formatConfig,
      SupportsFsCreation supportsFsCreation) {
    super();
    this.context = context;
    this.config = formatConfig;
    this.formatMatcher = new ParquetFormatMatcher(this, config);
    this.name = name == null ? DEFAULT_NAME : name;
  }

  @Override
  public ParquetFormatConfig getConfig() {
    return config;
  }

  @Override
  public AbstractWriter getWriter(
      PhysicalOperator child,
      String location,
      FileSystemPlugin<?> plugin,
      WriterOptions options,
      OpProps props)
      throws IOException {
    return new ParquetWriter(props, child, location, options, plugin, null);
  }

  public RecordWriter getRecordWriter(OperatorContext context, ParquetWriter writer)
      throws IOException, OutOfMemoryException {
    return new ParquetRecordWriter(context, writer, config);
  }

  public WriterOperator getWriterBatch(OperatorContext context, ParquetWriter writer)
      throws ExecutionSetupException {
    try {
      return new WriterOperator(context, writer.getOptions(), getRecordWriter(context, writer));
    } catch (IOException e) {
      throw new ExecutionSetupException(
          String.format("Failed to create the WriterRecordBatch. %s", e.getMessage()), e);
    }
  }

  public ParquetGroupScanUtils getGroupScan(
      String userName,
      FileSystemPlugin<?> plugin,
      FileSelection selection,
      List<String> tableSchemaPath,
      List<SchemaPath> columns,
      BatchSchema schema)
      throws IOException {
    return new ParquetGroupScanUtils(
        userName,
        selection,
        plugin,
        this,
        selection.getSelectionRoot(),
        columns,
        schema,
        null,
        context.getOptionManager());
  }

  @Override
  public RecordReader getRecordReader(
      OperatorContext context, FileSystem fs, FileAttributes attributes)
      throws ExecutionSetupException {
    try {
      return new PreviewReader(context, fs, attributes);
    } catch (IOException e) {
      throw new ExecutionSetupException(e);
    }
  }

  /**
   * A parquet reader that combines individual row groups into a single stream for preview purposes.
   */
  private class PreviewReader implements RecordReader {

    private final OperatorContext context;
    private final CompressionCodecFactory codec;
    private final FileSystem fs;
    private final FileAttributes attributes;
    private final MutableParquetMetadata footer;
    private final ParquetReaderUtility.DateCorruptionStatus dateStatus;
    private final SchemaDerivationHelper schemaHelper;
    private final InputStreamProvider streamProvider;

    private int currentIndex = -1;
    private OutputMutator output;
    private RecordReader current;

    public PreviewReader(OperatorContext context, FileSystem fs, FileAttributes attributes)
        throws IOException {
      super();
      this.context = context;
      this.fs = fs;
      this.attributes = attributes;
      final long maxFooterLen =
          context.getOptions().getOption(ExecConstants.PARQUET_MAX_FOOTER_LEN_VALIDATOR);
      this.streamProvider =
          new SingleStreamProvider(
              fs,
              attributes.getPath(),
              attributes.size(),
              maxFooterLen,
              false,
              null,
              null,
              false,
              ParquetFilters.NONE,
              ParquetFilterCreator.DEFAULT);
      this.footer = this.streamProvider.getFooter();
      boolean autoCorrectCorruptDates =
          context.getOptions().getOption(ExecConstants.PARQUET_AUTO_CORRECT_DATES_VALIDATOR)
              && getConfig().autoCorrectCorruptDates;
      this.dateStatus =
          ParquetReaderUtility.detectCorruptDates(
              footer, GroupScan.ALL_COLUMNS, autoCorrectCorruptDates);
      this.schemaHelper =
          SchemaDerivationHelper.builder()
              .readInt96AsTimeStamp(
                  context
                      .getOptions()
                      .getOption(ExecConstants.PARQUET_READER_INT96_AS_TIMESTAMP_VALIDATOR))
              .dateCorruptionStatus(dateStatus)
              .allowMixedDecimals(true)
              .mapDataTypeEnabled(
                  context.getOptions().getOption(ExecConstants.ENABLE_MAP_DATA_TYPE))
              .build();
      this.codec =
          CodecFactory.createDirectCodecFactory(
              new Configuration(), new ParquetDirectByteBufferAllocator(context.getAllocator()), 0);
    }

    @Override
    public void close() throws Exception {
      AutoCloseables.close(current, streamProvider, codec::release);
    }

    @Override
    public void setup(OutputMutator output) throws ExecutionSetupException {
      this.output = output;
      nextReader();
    }

    private void nextReader() {
      AutoCloseables.closeNoChecked(current);
      current = null;

      currentIndex++;

      /* Files with N rowgroups have a ParquetRowiseReader created for every rowgroup.
        Empty files (with block size is 0) must have a single ParquetRowiseReader created
        to get the columns in the files to generate schema, otherwise we cannot get schema
        from empty files in preview.
      */
      if (currentIndex >= footer.getBlocks().size() && currentIndex > 0) {
        return;
      }

      current =
          new ParquetRowiseReader(
              context,
              footer,
              currentIndex,
              attributes.getPath().toString(),
              ParquetScanProjectedColumns.fromSchemaPaths(GroupScan.ALL_COLUMNS),
              fs,
              schemaHelper,
              streamProvider,
              codec,
              true);
      try {
        current.setup(output);
      } catch (ExecutionSetupException e) {
        throw Throwables.propagate(e);
      }
    }

    @Override
    public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
      current.allocate(vectorMap);
    }

    @Override
    public int next() {
      if (current == null) {
        return 0;
      }

      int records = current.next();
      while (records == 0) {
        nextReader();
        if (current == null) {
          return 0;
        }
        records = current.next();
      }

      return records;
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public PluginSabotContext getContext() {
    return context;
  }

  @Override
  public FormatMatcher getMatcher() {
    return formatMatcher;
  }

  private static class ParquetFormatMatcher extends BasicFormatMatcher {
    public ParquetFormatMatcher(ParquetFormatPlugin plugin, ParquetFormatConfig formatConfig) {
      super(plugin, PATTERNS, MAGIC_STRINGS);
    }
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
      TimeTravelOption.TimeTravelRequest timeTravelRequest) {
    return new ParquetFormatDatasetAccessor(
        type,
        fs,
        fileSelection,
        fsPlugin,
        tableSchemaPath,
        updateKey,
        this,
        previousInfo,
        maxLeafColumns,
        context);
  }
}
