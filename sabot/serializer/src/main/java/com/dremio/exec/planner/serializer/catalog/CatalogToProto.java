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
package com.dremio.exec.planner.serializer.catalog;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.exec.store.TableMetadata;
import com.dremio.plan.serialization.PCatalog;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.protobuf.ByteString;
import org.apache.iceberg.expressions.Expression;

public interface CatalogToProto {
  PCatalog.PTableMetadata toProto(TableMetadata tableMetadata);

  ByteString storagePluginIdToBson(StoragePluginId storagePluginId);

  ByteString toProto(DatasetConfig datasetConfig);

  String toBson(TableFunctionConfig config);

  ByteString toProto(BatchSchema batchSchema);

  String toBson(SchemaPath schemaPath);

  String toBson(Expression expression);

  ObjectWriter getObjectWriter();

  PCatalog.PSplitsPointer toProto(SplitsPointer splitsKey);
}
