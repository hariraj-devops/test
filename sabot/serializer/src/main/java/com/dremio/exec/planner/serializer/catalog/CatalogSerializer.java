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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.StringReader;
import org.apache.iceberg.expressions.Expression;

public class CatalogSerializer implements CatalogFromProto, CatalogToProto {

  // PhysicalPlanReader.physicalPlanReader
  private final ObjectReader objectReader;
  private final ObjectWriter objectWriter;

  public CatalogSerializer(ObjectReader objectReader, ObjectWriter objectWriter) {
    this.objectReader = objectReader;
    this.objectWriter = objectWriter;
  }

  @Override
  public PCatalog.PTableMetadata toProto(TableMetadata tableMetadata) {
    return TableMetadataSerde.toProto(tableMetadata, this);
  }

  @Override
  public TableMetadata fromProto(PCatalog.PTableMetadata proto) {
    return TableMetadataSerde.fromProto(proto, this);
  }

  @Override
  public StoragePluginId storagePluginIdFromBson(ByteString bson) {
    return StoragePluginSerde.storagePluginIdFromBson(bson, this);
  }

  @Override
  public ByteString storagePluginIdToBson(StoragePluginId storagePluginId) {
    return StoragePluginSerde.storagePluginIdToBson(storagePluginId, this);
  }

  @Override
  public DatasetConfig datasetConfigFromProto(ByteString byteString) {
    return DatasetConfigSerde.fromProto(byteString);
  }

  @Override
  public ByteString toProto(DatasetConfig datasetConfig) {
    return DatasetConfigSerde.toProto(datasetConfig);
  }

  @Override
  public String toBson(TableFunctionConfig config) {
    return pojoToJson(config);
  }

  @Override
  public ByteString toProto(BatchSchema batchSchema) {
    try {
      String json = objectWriter.writeValueAsString(batchSchema);
      return ByteString.copyFrom(json, "UTF-8");
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize batch schema to json", e);
    }
  }

  @Override
  public BatchSchema batchSchemaFromJson(ByteString json) {
    try {
      return objectReader.forType(BatchSchema.class).readValue(json.toStringUtf8());
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize batch schema from json", e);
    }
  }

  @Override
  public TableFunctionConfig tableFunctionConfigFromJson(String json) {
    return fromJson(json, TableFunctionConfig.class);
  }

  @Override
  public String toBson(SchemaPath schemaPath) {
    try {
      return objectWriter.writeValueAsString(schemaPath);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize object to json", e);
    }
  }

  @Override
  public SchemaPath schemaPathFromBson(String json) {
    try {
      return objectReader.forType(SchemaPath.class).readValue(json);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize object from json", e);
    }
  }

  @Override
  public String toBson(Expression expression) {
    try {
      return objectWriter.writeValueAsString(expression);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize object to json", e);
    }
  }

  @Override
  public Expression expressionFromBson(String bson) {
    try {
      return objectReader.readValue(new StringReader(bson), Expression.class);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize object to json", e);
    }
  }

  @Override
  public PCatalog.PSplitsPointer toProto(SplitsPointer splitsPointer) {
    return SplitsPointerSerde.toProto(splitsPointer, this);
  }

  @Override
  public SplitsPointer fromProto(PCatalog.PSplitsPointer splitsPointer) {
    return SplitsPointerSerde.fromProto(splitsPointer, this);
  }

  @Override
  public ObjectReader getObjectReader() {
    return objectReader;
  }

  @Override
  public ObjectWriter getObjectWriter() {
    return objectWriter;
  }

  private String pojoToJson(Object obj) {
    try {
      return objectWriter.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize object to json", e);
    }
  }

  public <V> V fromJson(String json, Class<V> clazz) {
    try {
      return objectReader.forType(clazz).readValue(json);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize object from json", e);
    }
  }
}
