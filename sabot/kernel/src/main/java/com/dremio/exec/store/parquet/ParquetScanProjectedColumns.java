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

import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_FALLBACK_NAME_BASED_READ;
import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_SCHEMA_NAME_MAPPING_DEFAULT;
import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_USE_BATCH_SCHEMA_FOR_RESOLVING_COLUMN;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.record.BatchSchema;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.DefaultNameMapping;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.IcebergSchemaField;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.iceberg.parquet.ParquetMessageTypeIDExtractor;
import org.apache.parquet.schema.MessageType;

/**
 * This class stores projected schema paths for the parquet scan operator It delegates the call to
 * appropriate column name resolver
 */
public final class ParquetScanProjectedColumns {
  private final List<SchemaPath> projectedColumns;
  private final List<IcebergProtobuf.IcebergSchemaField> icebergColumnIDs;
  private final List<DefaultNameMapping> icebergDefaultNameMapping;
  private final boolean isConvertedIcebergDataset;
  private final boolean fallBackOnNameBasedRead;
  private final BatchSchema batchSchema;
  private final boolean shouldUseBatchSchemaForResolvingProjectedColumn;

  private ParquetScanProjectedColumns(
      List<SchemaPath> projectedColumns,
      List<IcebergSchemaField> icebergColumnIDs,
      List<DefaultNameMapping> icebergDefaultNameMapping,
      boolean isConvertedIcebergDataset,
      boolean fallBackOnNameBasedRead,
      boolean shouldUseBatchSchemaForResolvingProjectedColumn,
      BatchSchema batchSchema) {
    this.projectedColumns = projectedColumns;
    this.icebergColumnIDs = icebergColumnIDs;
    this.icebergDefaultNameMapping = icebergDefaultNameMapping;
    this.isConvertedIcebergDataset = isConvertedIcebergDataset;
    this.fallBackOnNameBasedRead = fallBackOnNameBasedRead;
    this.batchSchema = batchSchema;
    this.shouldUseBatchSchemaForResolvingProjectedColumn =
        shouldUseBatchSchemaForResolvingProjectedColumn;
  }

  public int size() {
    return projectedColumns.size();
  }

  public ParquetColumnResolver getColumnResolver(MessageType parquetSchema) {
    // internal iceberg tables always use name based reader
    if (isConvertedIcebergDataset) {
      return new ParquetColumnDefaultResolver(projectedColumns);
    }

    if (ParquetColumnDeltaLakeResolver.hasColumnMapping(batchSchema)) {
      return new ParquetColumnDeltaLakeResolver(projectedColumns, batchSchema, parquetSchema);
    }

    if (icebergColumnIDs != null && !icebergColumnIDs.isEmpty()) {
      ParquetMessageTypeIDExtractor idExtractor = new ParquetMessageTypeIDExtractor();
      boolean parquetHasIds = idExtractor.hasIds(parquetSchema);
      // if native iceberg table, and parquet file has IDs, always use id based reader
      // if native iceberg table, and parquet file has no IDs,
      //   but user doesn't want to use name based reader, then use id based reader
      if (parquetHasIds || !fallBackOnNameBasedRead) {
        return new ParquetColumnIcebergResolver(
            parquetSchema,
            projectedColumns,
            icebergColumnIDs,
            icebergDefaultNameMapping,
            idExtractor.getAliases(),
            shouldUseBatchSchemaForResolvingProjectedColumn,
            batchSchema);
      }
    }

    // fallback to default resolver if no mappings provided
    return new ParquetColumnDefaultResolver(projectedColumns);
  }

  private ParquetScanProjectedColumns(List<SchemaPath> projectedColumns) {
    this(projectedColumns, null, null, true, true, false, null);
  }

  public List<SchemaPath> getBatchSchemaProjectedColumns() {
    return this.projectedColumns;
  }

  public static ParquetScanProjectedColumns fromSchemaPaths(List<SchemaPath> projectedColumns) {
    return new ParquetScanProjectedColumns(projectedColumns);
  }

  public static ParquetScanProjectedColumns fromSchemaPathAndIcebergSchema(
      List<SchemaPath> projectedColumns,
      List<IcebergSchemaField> icebergColumnIDs,
      List<DefaultNameMapping> icebergDefaultNameMapping,
      boolean isConvertedIcebergDataset,
      OperatorContext context,
      BatchSchema batchSchema) {
    Preconditions.checkArgument(context != null, "Unexpected state");
    boolean fallBackOnNameBasedRead =
        context.getOptions().getOption(ENABLE_ICEBERG_FALLBACK_NAME_BASED_READ);
    boolean shouldUseIcebergDefaultNameMapping =
        context.getOptions().getOption(ENABLE_ICEBERG_SCHEMA_NAME_MAPPING_DEFAULT);
    boolean shouldUseBatchSchemaForResolvingProjectedColumn =
        context.getOptions().getOption(ENABLE_ICEBERG_USE_BATCH_SCHEMA_FOR_RESOLVING_COLUMN);
    return new ParquetScanProjectedColumns(
        projectedColumns,
        icebergColumnIDs,
        shouldUseIcebergDefaultNameMapping ? icebergDefaultNameMapping : ImmutableList.of(),
        isConvertedIcebergDataset,
        fallBackOnNameBasedRead,
        shouldUseBatchSchemaForResolvingProjectedColumn,
        batchSchema);
  }

  ParquetScanProjectedColumns cloneForSchemaPaths(
      List<SchemaPath> projectedColumns, boolean isConvertedIcebergDataset) {
    return new ParquetScanProjectedColumns(
        projectedColumns,
        this.icebergColumnIDs,
        this.icebergDefaultNameMapping,
        isConvertedIcebergDataset,
        this.fallBackOnNameBasedRead,
        false,
        this.batchSchema);
  }
}
