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
package com.dremio.dac.api;

import com.dremio.common.expression.AbstractArrowTypeVisitor;
import com.dremio.common.expression.SqlTypeNameVisitor;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.Decimal;
import org.apache.arrow.vector.types.pojo.Field;

/** Describes an Arrow field into a JSON generator for the REST API */
public class APIFieldDescriber {
  /** Describes an Arrow field into a JSON generator */
  public static final class FieldDescriber extends AbstractArrowTypeVisitor<Void> {
    private final JsonGenerator generator;
    private final TypeDescriber typeDescriber;
    private final Field field;
    private final Boolean isPartitionedField;
    private final Boolean isSortedField;
    private boolean skipName;

    public FieldDescriber(JsonGenerator jsonGenerator, Field field, boolean skipName) {
      this(jsonGenerator, field, null, null, skipName);
    }

    public FieldDescriber(
        JsonGenerator jsonGenerator,
        Field field,
        Boolean isPartitionedField,
        Boolean isSortedField,
        boolean skipName) {
      generator = jsonGenerator;
      typeDescriber = new TypeDescriber(jsonGenerator);
      this.field = field;
      this.isPartitionedField = isPartitionedField;
      this.isSortedField = isSortedField;
      this.skipName = skipName;
    }

    @Override
    protected Void visitGeneric(ArrowType type) {
      try {
        generator.writeStartObject();

        if (!skipName) {
          generator.writeFieldName("name");
          generator.writeString(field.getName());
        }

        generator.writeFieldName("type");

        generator.writeStartObject();
        generator.writeFieldName("name");
        type.accept(typeDescriber);
        generator.writeEndObject();

        if (isPartitionedField != null) {
          generator.writeFieldName("isPartitioned");
          generator.writeBoolean(isPartitionedField);
        }

        if (isSortedField != null) {
          generator.writeFieldName("isSorted");
          generator.writeBoolean(isSortedField);
        }

        generator.writeEndObject();
      } catch (IOException e) {
        // no op
      }
      return null;
    }

    @Override
    public Void visit(ArrowType.Struct type) {
      return getDescriberWithChildren(type, false);
    }

    @Override
    public Void visit(ArrowType.List type) {
      return getDescriberWithChildren(type, true);
    }

    @Override
    public Void visit(ArrowType.Map type) {
      return getDescriberWithChildren(type, true);
    }

    @Override
    public Void visit(ArrowType.Union type) {
      return getDescriberWithChildren(type, true);
    }

    private Void getDescriberWithChildren(ArrowType type, boolean skipChildName) {
      try {
        generator.writeStartObject();

        if (!skipName) {
          generator.writeFieldName("name");
          generator.writeString(field.getName());
        }

        generator.writeFieldName("type");

        generator.writeStartObject();
        generator.writeFieldName("name");
        type.accept(typeDescriber);

        List<Field> children = field.getChildren();
        if (children != null) {
          generator.writeFieldName("subSchema");
          generator.writeStartArray();

          for (Field field : children) {
            FieldDescriber describer = new FieldDescriber(generator, field, skipChildName);
            field.getType().accept(describer);
          }

          generator.writeEndArray();
        }
        generator.writeEndObject();

        generator.writeEndObject();
      } catch (IOException e) {
        // no op
      }
      return null;
    }
  }

  /** Describes a Arrow type into a JSON generator */
  private static class TypeDescriber implements ArrowType.ArrowTypeVisitor<Void> {
    private final JsonGenerator generator;
    private final SqlTypeNameVisitor sqlTypeNameVisitor;

    private Void writeString(String name) {
      try {
        generator.writeString(name);
      } catch (IOException e) {
        // no op
      }
      return null;
    }

    public TypeDescriber(JsonGenerator jsonGenerator) {
      this.generator = jsonGenerator;
      this.sqlTypeNameVisitor = new SqlTypeNameVisitor();
    }

    @Override
    public Void visit(ArrowType.Null aNull) {
      return writeString("OTHER");
    }

    @Override
    public Void visit(ArrowType.Struct struct) {
      return writeString("STRUCT");
    }

    @Override
    public Void visit(ArrowType.List list) {
      return writeString("LIST");
    }

    @Override
    public Void visit(ArrowType.ListView list) {
      return writeString("LISTVIEW");
    }

    @Override
    public Void visit(ArrowType.FixedSizeList fixedSizeList) {
      return writeString("LIST");
    }

    @Override
    public Void visit(ArrowType.FixedSizeBinary fixedSizeBinary) {
      return writeString("BINARY");
    }

    @Override
    public Void visit(ArrowType.Union union) {
      return writeString(sqlTypeNameVisitor.visit(union));
    }

    @Override
    public Void visit(ArrowType.Int anInt) {
      return writeString(sqlTypeNameVisitor.visit(anInt));
    }

    @Override
    public Void visit(ArrowType.FloatingPoint floatingPoint) {
      return writeString(sqlTypeNameVisitor.visit(floatingPoint));
    }

    @Override
    public Void visit(ArrowType.Utf8 utf8) {
      return writeString("VARCHAR");
    }

    @Override
    public Void visit(ArrowType.Utf8View utf8) {
      return writeString("VARCHARVIEW");
    }

    @Override
    public Void visit(ArrowType.Binary binary) {
      return writeString("VARBINARY");
    }

    @Override
    public Void visit(ArrowType.BinaryView binary) {
      return writeString("VARBINARYVIEW");
    }

    @Override
    public Void visit(ArrowType.Bool bool) {
      return writeString(sqlTypeNameVisitor.visit(bool));
    }

    @Override
    public Void visit(Decimal decimal) {
      writeString(sqlTypeNameVisitor.visit(decimal));

      try {
        generator.writeFieldName("precision");
        generator.writeNumber(decimal.getPrecision());

        generator.writeFieldName("scale");
        generator.writeNumber(decimal.getScale());
      } catch (IOException e) {
        // no op
      }

      return null;
    }

    @Override
    public Void visit(ArrowType.Date date) {
      return writeString(sqlTypeNameVisitor.visit(date));
    }

    @Override
    public Void visit(ArrowType.Time time) {
      return writeString(sqlTypeNameVisitor.visit(time));
    }

    @Override
    public Void visit(ArrowType.Timestamp timestamp) {
      return writeString(sqlTypeNameVisitor.visit(timestamp));
    }

    @Override
    public Void visit(ArrowType.Interval interval) {
      return writeString(sqlTypeNameVisitor.visit(interval));
    }

    @Override
    public Void visit(ArrowType.LargeBinary largeBinary) {
      throw new UnsupportedOperationException("LargeBinary is not supported");
    }

    @Override
    public Void visit(ArrowType.LargeList largeList) {
      throw new UnsupportedOperationException("LargeList is not supported");
    }

    @Override
    public Void visit(ArrowType.LargeListView param) {
      throw new UnsupportedOperationException("LargeListView is not supported");
    }

    @Override
    public Void visit(ArrowType.RunEndEncoded param) {
      throw new UnsupportedOperationException("RunEndEncoded is not supported");
    }

    @Override
    public Void visit(ArrowType.LargeUtf8 largeUtf8) {
      throw new UnsupportedOperationException("LargeUtf8 is not supported");
    }

    @Override
    public Void visit(ArrowType.Duration interval) {
      throw new UnsupportedOperationException("Duration is not supported");
    }

    @Override
    public Void visit(ArrowType.Map map) {
      return writeString(sqlTypeNameVisitor.visit(map));
    }
  }
}
