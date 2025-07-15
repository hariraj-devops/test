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

import static org.apache.iceberg.DremioIndexByName.SEPARATOR;

import com.dremio.common.expression.PathSegment;
import com.dremio.common.expression.PathSegment.PathSegmentType;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.map.CaseInsensitiveImmutableBiMap;
import com.dremio.exec.record.BatchSchema;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.DefaultNameMapping;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.parquet.ParquetMessageTypeNameExtractor;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.schema.MessageType;

/**
 * This class maps column names from batch schema to parquet schema and vice versa for Iceberg
 * datasets. It maintains a) column name <=> ID mapping from Iceberg schema b) column name <=> ID
 * mapping from parquet file schema
 */
public class ParquetColumnIcebergResolver implements ParquetColumnResolver {
  private final List<SchemaPath> projectedColumns;

  private final List<SchemaPath> projectedParquetColumns;
  private CaseInsensitiveImmutableBiMap<Integer> icebergColumnIDMap;
  private Set<String> parquetColumnNamesUsedInParquetSchema =
      new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
  private Map<String, Integer> parquetColumnIDs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private Map<String, FieldInfo> fieldInfoMap = null;
  private final boolean shouldUseBatchSchemaForResolvingProjectedColumn;

  public ParquetColumnIcebergResolver(
      MessageType parquetSchema,
      List<SchemaPath> projectedColumns,
      List<IcebergProtobuf.IcebergSchemaField> icebergColumnIDs,
      List<DefaultNameMapping> icebergDefaultNameMapping,
      Map<String, Integer> parquetColumnIDs,
      boolean shouldUseBatchSchemaForResolvingProjectedColumn,
      BatchSchema batchSchema) {
    this.projectedColumns = projectedColumns;
    this.parquetColumnIDs.putAll(Preconditions.checkNotNull(parquetColumnIDs));
    Set parquetColumnNames =
        parquetColumnIDs.isEmpty() && parquetSchema != null
            ? ParquetMessageTypeNameExtractor.getFieldNames(parquetSchema)
            : parquetColumnIDs.keySet();
    this.parquetColumnNamesUsedInParquetSchema.addAll(parquetColumnNames);
    mergeIcebergDefaultNameMappingWithParquetColumnIDs(icebergDefaultNameMapping);
    initializeProjectedColumnIDs(icebergColumnIDs);
    this.shouldUseBatchSchemaForResolvingProjectedColumn =
        shouldUseBatchSchemaForResolvingProjectedColumn;
    if (shouldUseBatchSchemaForResolvingProjectedColumn) {
      this.fieldInfoMap = getFieldsMapForBatchSchema(batchSchema);
    }

    this.projectedParquetColumns = getProjectedParquetColumnsImpl();
  }

  public ParquetColumnIcebergResolver(
      List<SchemaPath> projectedColumns,
      List<IcebergProtobuf.IcebergSchemaField> icebergColumnIDs,
      List<DefaultNameMapping> icebergDefaultNameMapping,
      Map<String, Integer> parquetColumnIDs) {
    this(
        null,
        projectedColumns,
        icebergColumnIDs,
        icebergDefaultNameMapping,
        parquetColumnIDs,
        false,
        null);
  }

  private void initializeProjectedColumnIDs(
      List<IcebergProtobuf.IcebergSchemaField> icebergColumnIDs) {
    Map<String, Integer> icebergColumns = new HashMap<>();
    icebergColumnIDs.forEach(field -> icebergColumns.put(field.getSchemaPath(), field.getId()));
    this.icebergColumnIDMap = CaseInsensitiveImmutableBiMap.newImmutableMap(icebergColumns);
  }

  /***
   * IcebergDefaultNameMapping contains the default name/id mapping
   * from parquet field name to iceberg field id.
   * In case parquet files dont have field id and the Iceberg table we could use the
   * name/id mapping defined in table property "schema.name-mapping.default"
   */
  private void mergeIcebergDefaultNameMappingWithParquetColumnIDs(
      List<DefaultNameMapping> icebergDefaultNameMapping) {
    if (icebergDefaultNameMapping == null || icebergDefaultNameMapping.isEmpty()) {
      return;
    }

    Map<String, Integer> newParquetColumnIDs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    newParquetColumnIDs.putAll(parquetColumnIDs);
    icebergDefaultNameMapping.stream()
        .forEach(
            mapping -> {
              if (!newParquetColumnIDs.containsKey(mapping.getName())) {
                newParquetColumnIDs.put(mapping.getName(), mapping.getId());
              }
            });
    parquetColumnIDs = newParquetColumnIDs;
  }

  @Override
  public List<SchemaPath> getBatchSchemaProjectedColumns() {
    return projectedColumns;
  }

  @Override
  public List<SchemaPath> getProjectedParquetColumns() {
    return projectedParquetColumns;
  }

  private List<SchemaPath> getProjectedParquetColumnsImpl() {
    // a map for projected columns between segment paths to SchemaPath
    CaseInsensitiveImmutableBiMap<SchemaPath> projectedColumnNames =
        CaseInsensitiveImmutableBiMap.newImmutableMap(
            projectedColumns.stream()
                .collect(
                    Collectors.toMap(
                        this::getProjectedColumnSegmentPath, col -> col, (a, b) -> a)));
    Map<SchemaPath, SchemaPath> projectSchemaColumnToProjectedParquetColumnMap = new HashMap<>();
    for (String parquetColumnName : parquetColumnNamesUsedInParquetSchema) {
      Integer colId = parquetColumnIDs.get(parquetColumnName);
      if (colId == null) {
        continue;
      }
      String schemaColumnName = icebergColumnIDMap.inverse().get(colId);
      // parquet column does not have a matching iceberg column
      if (schemaColumnName == null) {
        continue;
      }
      // only keep the projected columns
      if (!projectedColumnNames.containsKey(schemaColumnName)) {
        continue;
      }
      SchemaPath schemaColumnPath = projectedColumnNames.get(schemaColumnName);
      boolean hasComplexSegments =
          schemaColumnPath.getNameSegments().size() > 1
              || schemaColumnPath.getComplexNameSegments().size() > 1;
      projectSchemaColumnToProjectedParquetColumnMap.put(
          schemaColumnPath,
          hasComplexSegments
              ? SchemaPath.getCompoundPath(parquetColumnName.split("\\."))
              : SchemaPath.getCompoundPath(parquetColumnName));
    }

    // return columns in the order of projected schema columns
    return projectedColumns.stream()
        .map(projectColumn -> projectSchemaColumnToProjectedParquetColumnMap.get(projectColumn))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  @Override
  public String getBatchSchemaColumnName(String columnInParquetFile) {
    Integer id = this.parquetColumnIDs.get(columnInParquetFile);
    if (id == null) {
      return null;
    }

    if (!this.icebergColumnIDMap.containsValue(id)) {
      return null;
    }

    return this.icebergColumnIDMap.inverse().get(id);
  }

  @Override
  public List<String> getBatchSchemaColumnName(List<String> columnInParquetFile) {
    String columnName = String.join(".", columnInParquetFile);

    Integer id = this.parquetColumnIDs.get(columnName);
    if (id == null) {
      return null;
    }

    if (!this.icebergColumnIDMap.containsValue(id)) {
      return null;
    }

    String columnInSchema = this.icebergColumnIDMap.inverse().get(id);

    if (columnInParquetFile.size() == 1) {
      return Lists.newArrayList(columnInSchema);
    }

    return Lists.newArrayList(columnInSchema.split("\\."));
  }

  private String getParquetColumnNameById(int id, Map<String, Integer> parquetColumnIDs) {
    List<Map.Entry<String, Integer>> parquetColumnNameIDs =
        parquetColumnIDs.entrySet().stream()
            .filter(
                e ->
                    e.getValue() == id
                        && parquetColumnNamesUsedInParquetSchema.contains(e.getKey()))
            .collect(Collectors.toList());
    // A single element is expected
    if (parquetColumnNameIDs.size() > 1) {
      throw new RuntimeException(
          String.format(
              "There are multiple parquet column names for Iceberg column id %s, %s",
              id, parquetColumnNameIDs));
    }
    return parquetColumnNameIDs.get(0).getKey();
  }

  @Override
  public String getParquetColumnName(String name) {
    Integer id = this.icebergColumnIDMap.get(name);
    if (id == null) {
      return null;
    }

    if (!this.parquetColumnIDs.containsValue(id)) {
      return null;
    }

    return getParquetColumnNameById(id, parquetColumnIDs);
  }

  @Override
  public List<SchemaPath> getBatchSchemaColumns(List<SchemaPath> parquestSchemaPaths) {
    return parquestSchemaPaths.stream()
        .map(this::getBatchSchemaColumnPath)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  @Override
  public SchemaPath getBatchSchemaColumnPath(SchemaPath pathInParquetFile) {
    List<String> pathSegmentsInParquet = pathInParquetFile.getComplexNameSegments();
    List<String> pathSegmentsInBatchSchema = getBatchSchemaColumnName(pathSegmentsInParquet);

    return (pathSegmentsInBatchSchema == null)
        ? null
        : SchemaPath.getCompoundPath(pathSegmentsInBatchSchema.toArray(new String[0]));
  }

  @Override
  public List<String> getNameSegments(SchemaPath schemaPath) {
    return shouldUseBatchSchemaForResolvingProjectedColumn && this.fieldInfoMap != null
        ? getComplexNameSegments(schemaPath)
        : schemaPath.getComplexNameSegments();
  }

  private String getProjectedColumnSegmentPath(SchemaPath pathInBatchSchema) {
    List<String> pathSegmentsInBatchSchema = getNameSegments(pathInBatchSchema);

    if (pathSegmentsInBatchSchema.size() == 1) {
      return pathInBatchSchema.getRootSegment().getPath();
    }
    return Joiner.on(SEPARATOR).join(pathSegmentsInBatchSchema);
  }

  /**
   * This Function takes schemaPath and by using batch schema, convert it to iceberg column name
   * List of string( i.e, by concatenating the list of string using '.', we can get complete column
   * name)
   *
   * <p>For example: If table schema is like A Row(B Array(Row(id int, name varchar), c int) if
   * projected Column is A.B[1], we get schemaPath with following hierarchy NameSegment(A)
   * NameSegment(B) ArraySegment[1] Using above schema path we want to convert to =>
   * A.B.list.element
   *
   * <p>Similarly, (projected column => expected column name) A.B.id => A.B.list.element.id
   * A.B[0].id => A.B.list.element.id A.B => A.B
   */
  public List<String> getComplexNameSegments(SchemaPath schemaPath) {
    List<String> segments = new ArrayList<>();
    PathSegment seg = schemaPath.getRootSegment();
    boolean isListChild = false;
    Map<String, FieldInfo> currentChildMap = fieldInfoMap;

    while (seg != null) {
      Preconditions.checkNotNull(currentChildMap, "currentChildMap");
      if (seg.getType().equals(PathSegmentType.ARRAY_INDEX) || isListChild) {
        segments.add("list");
        segments.add("element");
        FieldInfo fieldInfo = currentChildMap.getOrDefault("$data$", null);
        currentChildMap = fieldInfo != null ? fieldInfo.getChildFieldInfo() : null;
        isListChild =
            fieldInfo != null
                && fieldInfo.getType() != null
                && ArrowType.ArrowTypeID.List.equals(fieldInfo.getType());
        if (!seg.getType().equals(PathSegmentType.ARRAY_INDEX)) {
          continue;
        }
      } else {
        String segmentName = seg.getNameSegment().getPath();
        segments.add(segmentName);
        // planner doesn't always send index with list path segment
        FieldInfo fieldInfo = currentChildMap.getOrDefault(segmentName.toLowerCase(), null);
        isListChild =
            fieldInfo != null
                && fieldInfo.getType() != null
                && "list".equalsIgnoreCase(fieldInfo.getType().toString());
        currentChildMap = fieldInfo != null ? fieldInfo.getChildFieldInfo() : null;
      }
      seg = seg.getChild();
    }
    return segments;
  }

  @Override
  public List<String> convertColumnDescriptor(
      MessageType schema, ColumnDescriptor columnDescriptor) {
    return Lists.newArrayList(columnDescriptor.getPath());
  }

  @Override
  public String toDotString(SchemaPath schemaPath, ValueVector vector) {
    return schemaPath.toDotString().toLowerCase();
  }

  private Map<String, FieldInfo> getFieldsMapForBatchSchema(BatchSchema batchSchema) {
    if (batchSchema == null || batchSchema.getFields() == null) {
      return null;
    }
    Map<String, FieldInfo> fieldsInfoMap = new HashMap<>();

    for (Field field : batchSchema.getFields()) {
      FieldInfo fieldInfo = new FieldInfo(field);
      if (fieldInfo.getName() != null) {
        fieldsInfoMap.put(fieldInfo.getName().toString(), fieldInfo);
      }
    }
    return fieldsInfoMap;
  }

  /** This class is for holding the children information of the children Fields in map format */
  class FieldInfo {
    private String name;
    private ArrowType.ArrowTypeID type;
    private Map<String, FieldInfo> childFieldInfo;

    public String getName() {
      return name;
    }

    public ArrowType.ArrowTypeID getType() {
      return type;
    }

    public Map<String, FieldInfo> getChildFieldInfo() {
      return childFieldInfo;
    }

    private FieldInfo(Field field) {
      this.childFieldInfo = new HashMap<>();
      this.name = null;
      this.type = null;
      if (field == null) {
        return;
      }

      if (field.getName() != null) {
        this.name = field.getName().toLowerCase();
      }

      if (field.getFieldType() != null && field.getFieldType().getType() != null) {
        this.type = field.getFieldType().getType().getTypeID();
      }

      if (field.getChildren() != null) {
        for (Field child : field.getChildren()) {
          FieldInfo fieldInfo = new FieldInfo(child);
          if (fieldInfo.getName() != null) {
            childFieldInfo.put(fieldInfo.getName().toLowerCase(), fieldInfo);
          }
        }
      }
    }
  }
}
