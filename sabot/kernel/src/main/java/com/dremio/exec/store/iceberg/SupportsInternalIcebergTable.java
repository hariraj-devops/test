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

import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.refresh.AbstractRefreshPlanBuilder;
import com.dremio.exec.planner.sql.handlers.refresh.UnlimitedSplitsMetadataProvider;
import com.dremio.exec.planner.sql.parser.SqlRefreshDataset;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.metadatarefresh.SupportsUnlimitedSplits;
import com.dremio.exec.store.metadatarefresh.committer.FullRefreshReadSignatureProvider;
import com.dremio.exec.store.metadatarefresh.committer.IncrementalRefreshReadSignatureProvider;
import com.dremio.exec.store.metadatarefresh.committer.PartialRefreshReadSignatureProvider;
import com.dremio.exec.store.metadatarefresh.committer.ReadSignatureProvider;
import com.dremio.exec.store.metadatarefresh.dirlisting.DirListingRecordReader;
import com.dremio.exec.store.metadatarefresh.footerread.FooterReadTableFunction;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.dirlist.proto.DirListInputSplitProto;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.function.Predicate;

/**
 * This is an optional interface. When extended by an implementation of {@link StoragePlugin}, this
 * is used to provide information from such storage plugins that supports reading/writing from
 * internally created Iceberg Tables by Dremio.
 */
public interface SupportsInternalIcebergTable extends SupportsUnlimitedSplits, SupportsFsCreation {

  String QUERY_TYPE_METADATA_REFRESH = "METADATA_REFRESH";

  /**
   * Indicates that the plugin supports getting dataset metadata (partition spec, partition values,
   * table schema) in the coordinator itself. Eg. for Hive plugin, we can get this info from Hive
   * metastore.
   */
  default boolean canGetDatasetMetadataInCoordinator() {
    return false;
  }

  /** Resolves the table name to a valid path. */
  List<String> resolveTableNameToValidPath(List<String> tableSchemaPath);

  /** Creates the plugin-specific footer reader Table Function. */
  FooterReadTableFunction getFooterReaderTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig);

  AbstractRefreshPlanBuilder createRefreshDatasetPlanBuilder(
      SqlHandlerConfig config,
      SqlRefreshDataset sqlRefreshDataset,
      UnlimitedSplitsMetadataProvider metadataProvider,
      boolean isFullRefresh);

  DirListingRecordReader createDirListRecordReader(
      OperatorContext context,
      FileSystem fs,
      DirListInputSplitProto.DirListInputSplit dirListInputSplit,
      boolean isRecursive,
      BatchSchema tableSchema,
      List<PartitionProtobuf.PartitionValue> partitionValues);

  default ReadSignatureProvider createReadSignatureProvider(
      ByteString existingReadSignature,
      final String dataTableRoot,
      final long queryStartTime,
      List<String> partitionPaths,
      Predicate<String> partitionExists,
      boolean isFullRefresh,
      boolean isPartialRefresh) {
    if (isFullRefresh) {
      return new FullRefreshReadSignatureProvider(dataTableRoot, queryStartTime);
    } else if (isPartialRefresh) {
      return new PartialRefreshReadSignatureProvider(
          existingReadSignature, dataTableRoot, queryStartTime, partitionExists);
    } else {
      return new IncrementalRefreshReadSignatureProvider(
          existingReadSignature, dataTableRoot, queryStartTime, partitionExists);
    }
  }

  default boolean supportReadSignature(DatasetMetadata metadata, boolean isFileDataset) {
    return !isFileDataset;
  }
}
