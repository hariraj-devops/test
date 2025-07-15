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
import com.dremio.common.arrow.DremioArrowSchema;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.RuntimeFilter;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.parquet.schema.Type;

/**
 * Parquet reader for Iceberg and DeltaLake datasets. This will be an inner reader of a coercion
 * reader to support up promotion of column data types.
 */
public abstract class TransactionalTableParquetReader implements RecordReader {

  protected static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(TransactionalTableParquetReader.class);
  protected final OperatorContext context;
  protected final ParquetReaderFactory readerFactory;
  protected final BatchSchema tableSchema;
  protected final ParquetScanProjectedColumns projectedColumns;
  protected final ParquetFilters filters;
  protected final ParquetProtobuf.ParquetDatasetSplitScanXAttr readEntry;
  protected final FileSystem fs;
  protected final MutableParquetMetadata footer;
  protected final SchemaDerivationHelper schemaHelper;
  protected final boolean vectorize;
  protected final boolean enableDetailedTracing;
  protected final boolean supportsColocatedReads;
  protected final boolean isConvertedIcebergDataset;
  protected final InputStreamProvider inputStreamProvider;
  protected UnifiedParquetReader currentReader;
  protected final List<RuntimeFilter> runtimeFilters = new ArrayList<>();

  public TransactionalTableParquetReader(
      OperatorContext context,
      ParquetReaderFactory readerFactory,
      BatchSchema tableSchema,
      ParquetScanProjectedColumns projectedColumns,
      ParquetFilters filters,
      ParquetProtobuf.ParquetDatasetSplitScanXAttr readEntry,
      FileSystem fs,
      MutableParquetMetadata footer,
      SchemaDerivationHelper schemaHelper,
      boolean vectorize,
      boolean enableDetailedTracing,
      boolean supportsColocatedReads,
      InputStreamProvider inputStreamProvider,
      boolean isConvertedIcebergDataset) {
    this.context = context;
    this.readerFactory = readerFactory;
    this.tableSchema = tableSchema;
    this.projectedColumns = projectedColumns;
    this.filters = filters;
    this.readEntry = readEntry;
    this.fs = fs;
    this.footer = footer;
    this.schemaHelper = schemaHelper;
    this.vectorize = vectorize;
    this.enableDetailedTracing = enableDetailedTracing;
    this.supportsColocatedReads = supportsColocatedReads;
    this.inputStreamProvider = inputStreamProvider;
    this.isConvertedIcebergDataset = isConvertedIcebergDataset;
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    ParquetColumnResolver columnResolver =
        projectedColumns.getColumnResolver(footer.getFileMetaData().getSchema());

    Schema arrowSchema;
    try {
      arrowSchema = DremioArrowSchema.fromMetaData(footer.getFileMetaData().getKeyValueMetaData());
    } catch (Exception e) {
      arrowSchema = null;
      logger.warn("Invalid Arrow Schema", e);
    }

    // create output vector based on schema in parquet file
    List<SchemaPath> projectedParquetColumns = columnResolver.getProjectedParquetColumns();
    for (Type parquetField : footer.getFileMetaData().getSchema().getFields()) {
      SchemaPath columnSchemaPath = SchemaPath.getCompoundPath(parquetField.getName());
      for (SchemaPath projectedPath : projectedParquetColumns) {
        String name = projectedPath.getRootSegment().getNameSegment().getPath();
        if (parquetField.getName().equalsIgnoreCase(name)) {
          if (parquetField.isPrimitive()) {
            Field field =
                ParquetTypeHelper.createField(
                    columnResolver.getBatchSchemaColumnPath(columnSchemaPath),
                    parquetField.asPrimitiveType(),
                    parquetField.getLogicalTypeAnnotation(),
                    schemaHelper);
            final Class<? extends ValueVector> clazz = TypeHelper.getValueVectorClass(field);
            output.addField(field, clazz);
          } else {
            if (arrowSchema != null) {
              Field groupField = arrowSchema.findField(parquetField.getName());
              List<Field> arrowField = new ArrayList<>();
              arrowField.add(groupField);
              List<Field> dremioField = CompleteType.convertToDremioFields(arrowField);
              Field dremioGroupField = dremioField.get(0);
              Field field =
                  new Field(
                      columnResolver.getBatchSchemaColumnName(parquetField.getName()),
                      new FieldType(true, dremioGroupField.getType(), null),
                      dremioGroupField.getChildren());
              final Class<? extends ValueVector> clazz = TypeHelper.getValueVectorClass(field);
              output.addField(field, clazz);
            } else {
              ParquetTypeHelper.toField(parquetField, schemaHelper, columnResolver)
                  .ifPresent(f -> output.addField(f, TypeHelper.getValueVectorClass(f)));
            }
          }
          break;
        }
      }
    }
    output.getContainer().buildSchema();
    output.getAndResetSchemaChanged();
    setupCurrentReader(output);
  }

  protected void setupCurrentReader(OutputMutator output) throws ExecutionSetupException {
    currentReader =
        new UnifiedParquetReader(
            context,
            readerFactory,
            this.tableSchema,
            projectedColumns,
            filters,
            readerFactory.newFilterCreator(
                context,
                ParquetReaderFactory.ManagedSchemaType.ICEBERG,
                null,
                context.getAllocator()),
            ParquetDictionaryConvertor.DEFAULT,
            this.readEntry,
            fs,
            footer,
            schemaHelper,
            vectorize,
            enableDetailedTracing,
            supportsColocatedReads,
            inputStreamProvider,
            new ArrayList<>(),
            isConvertedIcebergDataset);
    currentReader.setIgnoreSchemaLearning(true);
    this.runtimeFilters.forEach(currentReader::addRuntimeFilter);
    currentReader.setup(output);
  }

  @Override
  public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
    if (currentReader == null) {
      return;
    }
    currentReader.allocate(vectorMap);
  }

  @Override
  public int next() {
    if (currentReader == null) {
      return 0;
    }

    return currentReader.next();
  }

  @Override
  public List<SchemaPath> getColumnsToBoost() {
    if (currentReader != null) {
      return currentReader.getColumnsToBoost();
    }

    return ImmutableList.of();
  }

  @Override
  public void addRuntimeFilter(RuntimeFilter runtimeFilter) {
    if (this.runtimeFilters.contains(runtimeFilter)) {
      logger.debug("Skipping runtime filter {} because it is already present", runtimeFilter);
      return;
    }

    this.runtimeFilters.add(runtimeFilter);
    if (this.currentReader != null) {
      this.currentReader.addRuntimeFilter(runtimeFilter);
    }
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(currentReader);
  }

  @Override
  public String getFilePath() {
    return readEntry.getPath();
  }
}
