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

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.CompleteType;
import com.dremio.exec.planner.physical.WriterPrel;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.FieldIdBroker.UnboundedFieldIdBroker;
import com.dremio.exec.store.sys.udf.UserDefinedFunction;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeVisitor;
import org.apache.arrow.vector.types.pojo.ArrowType.Binary;
import org.apache.arrow.vector.types.pojo.ArrowType.BinaryView;
import org.apache.arrow.vector.types.pojo.ArrowType.Bool;
import org.apache.arrow.vector.types.pojo.ArrowType.Date;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.ArrowType.Duration;
import org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeBinary;
import org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeList;
import org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint;
import org.apache.arrow.vector.types.pojo.ArrowType.Int;
import org.apache.arrow.vector.types.pojo.ArrowType.Interval;
import org.apache.arrow.vector.types.pojo.ArrowType.LargeBinary;
import org.apache.arrow.vector.types.pojo.ArrowType.LargeList;
import org.apache.arrow.vector.types.pojo.ArrowType.LargeListView;
import org.apache.arrow.vector.types.pojo.ArrowType.LargeUtf8;
import org.apache.arrow.vector.types.pojo.ArrowType.Map;
import org.apache.arrow.vector.types.pojo.ArrowType.Null;
import org.apache.arrow.vector.types.pojo.ArrowType.RunEndEncoded;
import org.apache.arrow.vector.types.pojo.ArrowType.Struct;
import org.apache.arrow.vector.types.pojo.ArrowType.Time;
import org.apache.arrow.vector.types.pojo.ArrowType.Timestamp;
import org.apache.arrow.vector.types.pojo.ArrowType.Union;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8View;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.transforms.Transforms;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Type.NestedType;
import org.apache.iceberg.types.Type.PrimitiveType;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.BinaryType;
import org.apache.iceberg.types.Types.BooleanType;
import org.apache.iceberg.types.Types.DateType;
import org.apache.iceberg.types.Types.DecimalType;
import org.apache.iceberg.types.Types.DoubleType;
import org.apache.iceberg.types.Types.FixedType;
import org.apache.iceberg.types.Types.FloatType;
import org.apache.iceberg.types.Types.IntegerType;
import org.apache.iceberg.types.Types.ListType;
import org.apache.iceberg.types.Types.LongType;
import org.apache.iceberg.types.Types.MapType;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StringType;
import org.apache.iceberg.types.Types.StructType;
import org.apache.iceberg.types.Types.TimeType;
import org.apache.iceberg.types.Types.TimestampType;

/** Converter for iceberg schema to BatchSchema, and vice-versa. */
public final class SchemaConverter {

  private final String tableName;
  private final boolean isMapTypeEnabled;

  private SchemaConverter(Builder b) {
    this.tableName = b.tableName;
    this.isMapTypeEnabled = b.isMapTypeEnabled;
  }

  public static Builder getBuilder() {
    return new Builder();
  }

  public BatchSchema fromIceberg(Schema icebergSchema) {

    return new BatchSchema(
        icebergSchema.columns().stream()
            .map(this::fromIcebergColumn)
            .filter(Objects::nonNull)
            .collect(Collectors.toList()));
  }

  public List<String> getPartitionColumns(Table table) {
    return table.spec().fields().stream()
        .filter(partitionField -> !partitionField.transform().equals(Transforms.alwaysNull()))
        .map(PartitionField::sourceId)
        .map(table.schema()::findColumnName) // column name from schema
        .distinct()
        .collect(Collectors.toList());
  }

  public Field fromIcebergColumn(NestedField field) {
    try {
      CompleteType fieldType = fromIcebergType(field.type());
      return fieldType == null ? null : fieldType.toField(field.name(), field.isOptional());
    } catch (UnsupportedOperationException | UserException e) {
      String msg = "Type conversion error for column " + field.name();
      if (tableName != null) {
        msg = msg + " in table " + tableName;
      }
      throw UserException.unsupportedError(e).message(msg).buildSilently();
    }
  }

  public CompleteType fromIcebergType(Type type) {
    if (type.isPrimitiveType()) {
      return fromIcebergPrimitiveType(type.asPrimitiveType());
    } else {
      NestedType nestedType = type.asNestedType();
      if (nestedType.isListType()) {
        ListType listType = (ListType) nestedType;
        NestedField elementField = listType.fields().get(0);
        CompleteType elementType = fromIcebergType(elementField.type());
        return (elementType == null) ? null : elementType.asList();
      } else if (nestedType.isStructType()) {
        StructType structType = (StructType) nestedType;
        List<Types.NestedField> structFields = structType.fields();
        List<Field> innerFields = Lists.newArrayList();
        for (Types.NestedField nestedField : structFields) {
          Field field = fromIcebergColumn(nestedField);
          if (field == null) {
            return null;
          }
          innerFields.add(field);
        }
        return CompleteType.struct(innerFields);
      } else if (isEligibleForMapVector(nestedType)) {
        MapType mapType = (MapType) nestedType;
        List<Field> keyValueFields = Lists.newArrayList();
        for (Types.NestedField nestedField : mapType.fields()) {
          Field field = fromIcebergColumn(nestedField);
          if (field == null) {
            return null;
          }
          keyValueFields.add(field);
        }
        // the inner struct of a map is always required
        return new CompleteType(
            CompleteType.MAP.getType(),
            CompleteType.struct(keyValueFields).toField(MapVector.DATA_VECTOR_NAME, false));
      } else {
        // drop all other unknown iceberg column types
        return null;
      }
    }
  }

  private boolean isEligibleForMapVector(NestedType nestedType) {
    return isMapTypeEnabled
        && nestedType.isMapType()
        && nestedType.asMapType().keyType().isPrimitiveType();
  }

  public static CompleteType fromIcebergPrimitiveType(PrimitiveType type) {
    switch (type.typeId()) {
      case BOOLEAN:
        return CompleteType.BIT;
      case INTEGER:
        return CompleteType.INT;
      case LONG:
        return CompleteType.BIGINT;
      case FLOAT:
        return CompleteType.FLOAT;
      case DOUBLE:
        return CompleteType.DOUBLE;
      case STRING:
        return CompleteType.VARCHAR;
      case BINARY:
        return CompleteType.VARBINARY;
      case UUID:
        return new CompleteType(new FixedSizeBinary(16));
      case DATE:
        return CompleteType.DATE;
      case TIME:
        // TODO: When we support Time and Timestamp MICROS, this needs to be changed  to use
        // the existing schema definition for older tables, and to use MICROS for newer tables
        return CompleteType.TIME;
      case TIMESTAMP:
        return CompleteType.TIMESTAMP;
      case FIXED:
        return new CompleteType(new FixedSizeBinary(((FixedType) type).length()));
      case DECIMAL:
        DecimalType decimalType = (DecimalType) type;
        return new CompleteType(new Decimal(decimalType.precision(), decimalType.scale(), 128));
      default:
        throw new UnsupportedOperationException("Unsupported iceberg type : " + type);
    }
  }

  public List<NestedField> toIcebergFields(List<Field> fields) {
    UnboundedFieldIdBroker fieldIdBroker = new UnboundedFieldIdBroker();
    return fields.stream()
        .map(field -> toIcebergColumn(field, fieldIdBroker))
        .collect(Collectors.toList());
  }

  public List<NestedField> toIcebergNestedFields(List<UserDefinedFunction.FunctionArg> args) {
    UnboundedFieldIdBroker fieldIdBroker = new UnboundedFieldIdBroker();
    int id = 1;
    List<NestedField> nestedFields = Lists.newArrayList();
    for (UserDefinedFunction.FunctionArg arg : args) {
      NestedField field =
          NestedField.required(
              id++, arg.getName(), toIcebergType(arg.getDataType(), null, fieldIdBroker));
      nestedFields.add(field);
    }
    return nestedFields;
  }

  public Schema toIcebergSchema(BatchSchema schema) {
    return TypeUtil.assignIncreasingFreshIds(toIcebergSchema(schema, new UnboundedFieldIdBroker()));
  }

  public Schema toIcebergSchema(BatchSchema batchSchema, FieldIdBroker fieldIdBroker) {
    return new Schema(
        batchSchema.getFields().stream()
            .filter(
                field -> !field.getName().equalsIgnoreCase(WriterPrel.PARTITION_COMPARATOR_FIELD))
            .map(field -> toIcebergColumn(field, fieldIdBroker))
            .collect(Collectors.toList()));
  }

  public NestedField changeIcebergColumn(Field field, NestedField icebergField) {
    try {
      Type type =
          icebergField.type().isPrimitiveType()
              ? toIcebergType(CompleteType.fromField(field), null, new UnboundedFieldIdBroker())
              : icebergField.type();
      return NestedField.of(icebergField.fieldId(), field.isNullable(), field.getName(), type);
    } catch (Exception e) {
      String msg = "Type conversion error for column " + field.getName();
      if (tableName != null) {
        msg = msg + " in table " + tableName;
      }
      throw UserException.unsupportedError(e).message(msg).buildSilently();
    }
  }

  NestedField toIcebergColumn(Field field, FieldIdBroker fieldIdBroker) {
    return toIcebergColumn(field, fieldIdBroker, null);
  }

  private NestedField toIcebergColumn(Field field, FieldIdBroker fieldIdBroker, String fullName) {
    try {
      if (fullName == null) {
        fullName = field.getName();
      }
      int columnId = fieldIdBroker.get(fullName);
      return NestedField.of(
          columnId,
          field.isNullable(),
          field.getName(),
          toIcebergType(CompleteType.fromField(field), fullName, fieldIdBroker));
    } catch (Exception e) {
      String msg = "Type conversion error for column " + field.getName();
      if (tableName != null) {
        msg = msg + " in table " + tableName;
      }
      throw UserException.unsupportedError(e).message(msg).buildSilently();
    }
  }

  public Type toIcebergType(
      CompleteType completeType, String fullName, FieldIdBroker fieldIdBroker) {
    ArrowType arrowType = completeType.getType();
    return arrowType.accept(
        new ArrowTypeVisitor<Type>() {
          @Override
          public Type visit(Null aNull) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Struct struct) {
            List<NestedField> children =
                completeType.getChildren().stream()
                    .map(
                        field ->
                            toIcebergColumn(field, fieldIdBroker, fullName + "." + field.getName()))
                    .collect(Collectors.toList());
            return StructType.of(children);
          }

          @Override
          public Type visit(ArrowType.List list) {
            NestedField inner =
                toIcebergColumn(
                    completeType.getOnlyChild(), fieldIdBroker, fullName + ".list.element");
            if (inner.isOptional()) {
              return ListType.ofOptional(inner.fieldId(), inner.type());
            } else {
              return ListType.ofRequired(inner.fieldId(), inner.type());
            }
          }

          @Override
          public Type visit(ArrowType.ListView list) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(FixedSizeList fixedSizeList) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Union union) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Map map) {
            // Map is assumed to be struct of {key,value}
            Field struct = completeType.getChildren().get(0);
            Field keyField = struct.getChildren().get(0);
            NestedField key =
                toIcebergColumn(keyField, fieldIdBroker, fullName + "." + keyField.getName());
            Field valueField = struct.getChildren().get(1);
            NestedField value =
                toIcebergColumn(valueField, fieldIdBroker, fullName + "." + valueField.getName());
            if (value.isOptional()) {
              return MapType.ofOptional(key.fieldId(), value.fieldId(), key.type(), value.type());
            } else {
              return MapType.ofRequired(key.fieldId(), value.fieldId(), key.type(), value.type());
            }
          }

          @Override
          public Type visit(Int anInt) {
            return anInt.getBitWidth() == 32 ? IntegerType.get() : LongType.get();
          }

          @Override
          public Type visit(FloatingPoint floatingPoint) {
            switch (floatingPoint.getPrecision()) {
              case SINGLE:
                return FloatType.get();
              case DOUBLE:
                return DoubleType.get();
              default:
                throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
            }
          }

          @Override
          public Type visit(Utf8 utf8) {
            return StringType.get();
          }

          @Override
          public Type visit(Utf8View utf8) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Binary binary) {
            return BinaryType.get();
          }

          @Override
          public Type visit(BinaryView binary) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(FixedSizeBinary fixedSizeBinary) {
            return FixedType.ofLength(fixedSizeBinary.getByteWidth());
          }

          @Override
          public Type visit(LargeBinary largeBinary) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(LargeList largeList) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(LargeListView largeListView) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(RunEndEncoded param) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(LargeUtf8 largeUtf8) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Bool bool) {
            return BooleanType.get();
          }

          @Override
          public Type visit(Decimal decimal) {
            return DecimalType.of(decimal.getPrecision(), decimal.getScale());
          }

          @Override
          public Type visit(Date date) {
            return DateType.get();
          }

          @Override
          public Type visit(Time time) {
            return TimeType.get();
          }

          @Override
          public Type visit(Timestamp timestamp) {
            return TimestampType.withZone();
          }

          @Override
          public Type visit(Interval interval) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }

          @Override
          public Type visit(Duration duration) {
            throw new UnsupportedOperationException("Unsupported arrow type : " + arrowType);
          }
        });
  }

  public static final class Builder {
    private String tableName;
    private boolean isMapTypeEnabled;

    private Builder() {}

    public Builder setTableName(String tableName) {
      this.tableName = tableName;
      return this;
    }

    public Builder setMapTypeEnabled(boolean isMapTypeEnabled) {
      this.isMapTypeEnabled = isMapTypeEnabled;
      return this;
    }

    public SchemaConverter build() {
      return new SchemaConverter(this);
    }
  }
}
