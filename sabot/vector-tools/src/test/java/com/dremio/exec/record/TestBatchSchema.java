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
package com.dremio.exec.record;

import static com.dremio.common.expression.CompleteType.LIST;
import static com.dremio.common.expression.CompleteType.MAP;
import static com.dremio.common.expression.CompleteType.STRUCT;
import static org.apache.arrow.vector.complex.BaseRepeatedValueVector.DATA_VECTOR_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.Test;

/** Tests for {@link BatchSchema} */
public class TestBatchSchema {

  private static final SupportsTypeCoercionsAndUpPromotions DEFAULT_RULES =
      new SupportsTypeCoercionsAndUpPromotions() {};

  @Test
  public void testBatchSchemaDropColumnSimple() throws Exception {
    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema =
        tableSchema.dropFields(ImmutableList.of(ImmutableList.of("integerCol")));

    assertEquals(newSchema.getFields().size(), 3);
  }

  @Test
  public void testBatchSchemaDropColumnComplex() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field listField = new Field("listField", FieldType.nullable(LIST.getType()), childrenField);

    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structField,
            listField);

    BatchSchema newSchema =
        tableSchema.dropFields(
            ImmutableList.of(ImmutableList.of("structField"), ImmutableList.of("listField")));

    assertEquals(newSchema.getFields().size(), 4);
  }

  @Test
  public void testBatchSchemaDropColumnStruct() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema = BatchSchema.of(structField);

    BatchSchema newSchema =
        tableSchema.dropFields(ImmutableList.of(ImmutableList.of("structField", "integerCol")));

    assertEquals(
        newSchema.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"}]}]}");
  }

  @Test
  public void testBatchSchemaDropColumnStructInStruct() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field structStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(structField));

    BatchSchema tableSchema = BatchSchema.of(structStructField);

    BatchSchema newSchema =
        tableSchema.dropFields(
            ImmutableList.of(ImmutableList.of("outerStructField", "structField", "integerCol")));

    assertEquals(
        newSchema.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"outerStructField\",\"type\":\"Struct\",\"children\":[{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"}]}]}]}");
  }

  @Test
  public void testBatchSchemaDropColumnStructInList() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);
    Field listStruct =
        new Field("listStruct", FieldType.nullable(LIST.getType()), ImmutableList.of(structField));

    BatchSchema tableSchema = BatchSchema.of(listStruct);

    // Drop one field from the struct inside the list
    BatchSchema newSchema1 =
        tableSchema.dropFields(
            ImmutableList.of(ImmutableList.of("listStruct", "structField", "integerCol")));

    assertEquals(
        newSchema1.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"listStruct\",\"type\":\"List\",\"children\":[{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"}]}]}]}");
  }

  @Test
  public void testBatchSchemaUpdateTypeOfColumns() {
    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));
    BatchSchema newSchema =
        tableSchema.changeTypeTopLevel(CompleteType.BIGINT.toField("integerCol"));
    assertEquals(newSchema.getFields().size(), 4);
    assertEquals(newSchema.getColumn(0), CompleteType.BIGINT.toField("integerCol"));
  }

  @Test
  public void testBatchSchemaUpdateTypeOfStructColumns() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structField);

    List<Field> newChildren =
        ImmutableList.of(
            CompleteType.BIGINT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field newStructField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), newChildren);

    BatchSchema newSchema = tableSchema.changeTypeTopLevel(newStructField);
    assertEquals(newSchema.getFields().size(), 5);
    assertEquals(
        newSchema.getColumn(4).getChildren().get(0), CompleteType.BIGINT.toField("integerCol"));
  }

  @Test
  public void testBatchSchemaDropColumnsTypes() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);
    Field listField = new Field("listField", FieldType.nullable(LIST.getType()), childrenField);

    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            structField,
            listField,
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema1 = tableSchema.dropField("integerCol");
    assertEquals(newSchema1.getFields().size(), 5);

    // First column should not be an integerCol
    assertNotEquals(newSchema1.getColumn(0).getName(), "integerCol");

    BatchSchema newSchema2 = tableSchema.dropField("structField");
    assertEquals(newSchema1.getFields().size(), 5);
    // second column should not be a struct column
    assertNotEquals(newSchema1.getColumn(1).getName(), "structField");

    BatchSchema newSchema3 = tableSchema.dropField("listField");
    assertEquals(newSchema3.getFields().size(), 5);
    // third column should not be a struct column
    assertNotEquals(newSchema1.getColumn(2).getName(), "listField");
  }

  @Test
  public void testBatchSchemaUpdateColumns() {
    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema1 =
        tableSchema.changeTypeTopLevel(CompleteType.BIGINT.toField("integercol"));
    assertEquals(newSchema1.getFields().size(), 4);
    // First column should not be an integerCol
    assertEquals(newSchema1.getColumn(0), CompleteType.BIGINT.toField("integercol"));
  }

  @Test
  public void testBatchSchemaDropColumnsTypesIgnoreCase() {
    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema1 = tableSchema.dropField("integercol");
    assertEquals(newSchema1.getFields().size(), 3);
    // First column should not be an integerCol
    assertNotEquals(newSchema1.getColumn(0).getName(), "integerCol");
  }

  @Test
  public void testBatchSchemaDropNonExistingFields() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structField);

    Field primitiveField = CompleteType.INT.toField("tempCol");
    BatchSchema newSchema1 = tableSchema.dropField(primitiveField);
    assertEquals(newSchema1.getFields().size(), 5);

    Field structFieldDrop =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(CompleteType.INT.toField("lolCol")));
    BatchSchema newSchema2 = tableSchema.dropField(structFieldDrop);

    assertEquals(newSchema2.getFields().size(), 5);
    assertEquals(newSchema2.getFields().get(4), structField);
  }

  @Test
  public void testBatchSchemaDropField() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structField);

    Field structFieldDrop =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(CompleteType.INT.toField("integerCol")));
    BatchSchema newSchema1 = tableSchema.dropField(structFieldDrop);

    assertEquals(newSchema1.getFields().size(), 5);
    assertEquals(
        newSchema1.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"integerCol\",\"type\":\"Int\"},{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"},{\"name\":\"bitField\",\"type\":\"Bool\"},{\"name\":\"varCharField\",\"type\":\"Utf8\"},{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"}]}]}");
  }

  @Test
  public void testBatchSchemaUpdateNonExistentColumn() {
    BatchSchema tableSchema =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema1 = tableSchema.changeTypeTopLevel(CompleteType.BIGINT.toField("lolCol"));
    assertEquals(newSchema1.getFields().size(), 4);
    // First column should not be an integerCol
  }

  @Test
  public void testBatchSchemaDiff() {
    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema tableSchema2 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema1 = tableSchema1.difference(tableSchema2);
    assertEquals(newSchema1.getFields().size(), 1);
    assertEquals(newSchema1.getFields().get(0), CompleteType.BIT.toField("bitField"));
  }

  @Test
  public void testBatchSchemaAddColumns() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structField);

    Field newChildToAdd =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(CompleteType.INT.toField("lolCol")));
    BatchSchema newSchema1 = tableSchema1.addColumn(newChildToAdd);

    List<Field> childrenFieldNew =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.INT.toField("lolCol"));

    Field newStructField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenFieldNew);
    assertEquals(newSchema1.getFields().size(), 5);
    assertEquals(newSchema1.getFields().get(4), newStructField);
  }

  @Test
  public void testBatchSchemaAddColumnsStructInsideStruct() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field structStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(structField));

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"),
            structStructField);

    Field newStructField =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(CompleteType.INT.toField("lolCol")));

    Field newChildToAdd =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(newStructField));

    Field assertNewStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(
                new Field(
                    "structField",
                    FieldType.nullable(STRUCT.getType()),
                    ImmutableList.of(
                        CompleteType.INT.toField("integerCol"),
                        CompleteType.DOUBLE.toField("doubleCol"),
                        CompleteType.INT.toField("lolCol")))));

    BatchSchema newSchema1 = tableSchema1.addColumn(newChildToAdd);
    assertEquals(newSchema1.getFields().size(), 5);
    assertEquals(newSchema1.getFields().get(4), assertNewStructField);
  }

  @Test
  public void testBatchSchemaAddEntireStruct() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            CompleteType.VARCHAR.toField("varCharField"));

    BatchSchema newSchema = tableSchema1.addColumn(structField);
    assertEquals(newSchema.getFields().size(), 5);
    assertEquals(newSchema.getFields().get(4), structField);
  }

  @Test
  public void testBatchSchemaAddMultipleColumns() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);
    Field varcharField = CompleteType.VARCHAR.toField("varCharField");

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"));

    BatchSchema newSchema = tableSchema1.addColumns(ImmutableList.of(varcharField, structField));
    assertEquals(newSchema.getFields().size(), 5);
    assertEquals(newSchema.getFields().get(3), varcharField);
    assertEquals(newSchema.getFields().get(4), structField);
  }

  @Test
  public void testBatchSchemaChangeTypeRecursiveStructInStruct() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field structStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(structField));

    Field varcharField = CompleteType.VARCHAR.toField("varCharField");

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            structStructField);

    Field newStructField =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(CompleteType.VARCHAR.toField("integerCol")));

    Field changeIntInStrcut =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(newStructField));

    BatchSchema newSchema = tableSchema1.changeTypeRecursive(changeIntInStrcut);
    assertEquals(newSchema.getFields().size(), 4);
    assertEquals(
        newSchema.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"integerCol\",\"type\":\"Int\"},{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"},{\"name\":\"bitField\",\"type\":\"Bool\"},{\"name\":\"outerStructField\",\"type\":\"Struct\",\"children\":[{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"integerCol\",\"type\":\"Utf8\"},{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"}]}]}]}");
  }

  @Test
  public void testBatchSchemaChangeTypeRecursiveStructInStructWithMultipleFields()
      throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field structStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(structField));

    Field varcharField = CompleteType.VARCHAR.toField("varCharField");

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            structStructField);

    Field newStructField =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(
                CompleteType.VARCHAR.toField("integerCol"),
                CompleteType.VARCHAR.toField("doubleCol")));

    Field changeIntInStrcut =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(newStructField));

    BatchSchema newSchema = tableSchema1.changeTypeRecursive(changeIntInStrcut);
    assertEquals(newSchema.getFields().size(), 4);
    assertEquals(
        newSchema.toJSONString(),
        "{\"name\":\"root\",\"children\":[{\"name\":\"integerCol\",\"type\":\"Int\"},{\"name\":\"doubleCol\",\"type\":\"FloatingPoint\"},{\"name\":\"bitField\",\"type\":\"Bool\"},{\"name\":\"outerStructField\",\"type\":\"Struct\",\"children\":[{\"name\":\"structField\",\"type\":\"Struct\",\"children\":[{\"name\":\"integerCol\",\"type\":\"Utf8\"},{\"name\":\"doubleCol\",\"type\":\"Utf8\"}]}]}]}");
  }

  @Test
  public void testBatchSchemaChangeTypeRecursiveStructInStructNonExistent() throws Exception {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.INT.toField("integerCol"), CompleteType.DOUBLE.toField("doubleCol"));

    Field structField =
        new Field("structField", FieldType.nullable(STRUCT.getType()), childrenField);

    Field structStructField =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(structField));

    Field varcharField = CompleteType.VARCHAR.toField("varCharField");

    BatchSchema tableSchema1 =
        BatchSchema.of(
            CompleteType.INT.toField("integerCol"),
            CompleteType.DOUBLE.toField("doubleCol"),
            CompleteType.BIT.toField("bitField"),
            structStructField);

    Field newStructField =
        new Field(
            "structField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(
                CompleteType.VARCHAR.toField("nonExistent1"),
                CompleteType.VARCHAR.toField("nonExistent2")));

    Field changeIntInStrcut =
        new Field(
            "outerStructField",
            FieldType.nullable(STRUCT.getType()),
            ImmutableList.of(newStructField));

    // Should not throw exception
    BatchSchema newSchema = tableSchema1.changeTypeRecursive(changeIntInStrcut);
    assertTrue(newSchema.equalsIgnoreCase(tableSchema1));
  }

  @Test
  public void testToStringVerbose() {
    List<Field> childrenField =
        ImmutableList.of(
            CompleteType.VARCHAR.toField("key"), CompleteType.VARCHAR.toField("value"));

    Field mapEntry = new Field("entries", FieldType.notNullable(STRUCT.getType()), childrenField);
    Field mapCol =
        new Field("mapCol", FieldType.nullable(MAP.getType()), ImmutableList.of(mapEntry));

    BatchSchema batchSchema = BatchSchema.of(CompleteType.VARCHAR.toField("varcharCol"), mapCol);

    String schemaString = batchSchema.toStringVerbose();
    String schemaString1 =
        "\nvarcharCol;true;varchar\n"
            + "mapCol;true;map\n"
            + " entries;false;struct\n"
            + "  key;true;varchar\n"
            + "  value;true;varchar";

    assertEquals(schemaString, schemaString1);
  }

  @Test
  public void testApplyUserSchemaDroppedFields() {
    BatchSchema baseSchema = BatchSchema.of(varchar("a"));

    BatchSchema newSchema = BatchSchema.of(varchar("a"), varchar("b"), varchar("C"), varchar("d"));

    List<Field> droppedColumns = ImmutableList.of(varchar("b"), varchar("c"));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"), varchar("d"));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema, droppedColumns, ImmutableList.of(), false, true, null, null, DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testApplyUserSchemaUpdatedFields() {
    // base schema should include any updates already
    BatchSchema baseSchema = BatchSchema.of(varchar("a"), bigint("b"));

    BatchSchema newSchema = BatchSchema.of(varchar("a"), varchar("b"));

    List<Field> updatedColumns = ImmutableList.of(bigint("b"));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"), bigint("b"));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema, ImmutableList.of(), updatedColumns, false, true, null, null, DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testApplyUserSchemaDroppedThenUpdatedField() {
    // base schema should include any updates already
    BatchSchema baseSchema = BatchSchema.of(varchar("a"), bigint("b"));

    BatchSchema newSchema = BatchSchema.of(varchar("a"), varchar("b"));

    List<Field> droppedColumns = ImmutableList.of(varchar("b"));
    List<Field> updatedColumns = ImmutableList.of(bigint("b"));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"), bigint("b"));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema, droppedColumns, updatedColumns, false, true, null, null, DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testApplyUserSchemaNullFieldsRemoved() {
    BatchSchema baseSchema = BatchSchema.of(varchar("a"));

    BatchSchema newSchema = BatchSchema.of(varchar("a"), list("b", fnull(DATA_VECTOR_NAME)));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema,
            ImmutableList.of(),
            ImmutableList.of(),
            false,
            true,
            null,
            null,
            DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testApplyUserSchemaTypePromotionOfNonUserFields() {
    BatchSchema baseSchema = BatchSchema.of(varchar("a"), list("b", fint(DATA_VECTOR_NAME)));

    BatchSchema newSchema = BatchSchema.of(fint("a"), list("b", float8(DATA_VECTOR_NAME)));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"), list("b", float8(DATA_VECTOR_NAME)));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema,
            ImmutableList.of(),
            ImmutableList.of(),
            false,
            true,
            null,
            null,
            DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testApplyUserSchemaInvalidCoercion() {
    // base schema should include any updates already
    BatchSchema baseSchema = BatchSchema.of(struct("a", varchar("b")));

    BatchSchema newSchema = BatchSchema.of(varchar("a"));

    List<Field> updatedColumns = ImmutableList.of(struct("a", varchar("b")));

    assertThatThrownBy(
            () ->
                baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
                    newSchema,
                    ImmutableList.of(),
                    updatedColumns,
                    false,
                    true,
                    null,
                    null,
                    DEFAULT_RULES))
        .hasMessageContaining(
            "Unable to coerce from the file's data type \"varchar\" to the column's data type \"struct<b::varchar>\", column \"a\"");
  }

  @Test
  public void testApplyUserSchemaInvalidCoercionOnNestedField() {
    // base schema should include any updates already
    BatchSchema baseSchema =
        BatchSchema.of(struct("a", struct("b", list("c", struct(DATA_VECTOR_NAME, varchar("d"))))));

    BatchSchema newSchema =
        BatchSchema.of(struct("a", struct("b", list("c", varchar(DATA_VECTOR_NAME)))));

    List<Field> updatedColumns =
        ImmutableList.of(
            struct("a", struct("b", list("c", struct(DATA_VECTOR_NAME, varchar("d"))))));

    assertThatThrownBy(
            () ->
                baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
                    newSchema,
                    ImmutableList.of(),
                    updatedColumns,
                    false,
                    true,
                    null,
                    null,
                    DEFAULT_RULES))
        .hasMessageContaining(
            "Unable to coerce from the file's data type \"varchar\" to the column's data type \"struct<d::varchar>\", column \"a.b.c\"");
  }

  @Test
  public void testApplyUserSchemaUnionRemoval() {
    BatchSchema baseSchema = BatchSchema.of(varchar("a"), list("b", fint(DATA_VECTOR_NAME)));

    BatchSchema newSchema =
        BatchSchema.of(
            fint("a"), list("b", union(DATA_VECTOR_NAME, fint("int"), float8("float8"))));

    BatchSchema expectedSchema = BatchSchema.of(varchar("a"), list("b", float8(DATA_VECTOR_NAME)));

    BatchSchema resultSchema =
        baseSchema.applyUserDefinedSchemaAfterSchemaLearning(
            newSchema,
            ImmutableList.of(),
            ImmutableList.of(),
            false,
            true,
            null,
            null,
            DEFAULT_RULES);

    assertThat(resultSchema).isEqualTo(expectedSchema);
  }

  @Test
  public void testIncompatibleTypeDetectionWithSchemaLearningDisabled() {
    BatchSchema originalSchema =
        BatchSchema.of(
            bigint("f_bigint"), struct("f_struct", list("f_list", bigint(DATA_VECTOR_NAME))));

    BatchSchema incompatibleSchemaStruct =
        BatchSchema.of(
            bigint("f_bigint"),
            union(
                "f_struct",
                bigint("bigint"),
                struct("struct", list("f_list", bigint(DATA_VECTOR_NAME)))));

    BatchSchema incompatibleSchemaList =
        BatchSchema.of(
            bigint("f_bigint"),
            struct(
                "f_struct",
                union("f_list", bigint("bigint"), list("list", bigint(DATA_VECTOR_NAME)))));

    BatchSchema compatibleSchema =
        BatchSchema.of(
            union("f_bigint", bigint("bigint"), float8("float8")),
            struct("f_struct", list("f_list", bigint(DATA_VECTOR_NAME))),
            bigint("f_new"));

    // new schema with Union type and original schema with Struct type should result in an error
    assertThatThrownBy(
            () ->
                originalSchema.applyUserDefinedSchemaAfterSchemaLearning(
                    incompatibleSchemaStruct, null, null, true, true, null, null, DEFAULT_RULES))
        .hasMessageContaining(
            "Unable to coerce from the file's data type \"struct<f_list::list<int64>>\" to the column's data type \"int64\"");

    // new schema with Union type and original schema with nested List type should result in an
    // error
    assertThatThrownBy(
            () ->
                originalSchema.applyUserDefinedSchemaAfterSchemaLearning(
                    incompatibleSchemaList, null, null, true, true, null, null, DEFAULT_RULES))
        .hasMessageContaining(
            "Unable to coerce from the file's data type \"list<int64>\" to the column's data type \"int64\"");

    // new schema with Union type and original schema with primitive type is allowed,
    // error will be handled when trying to cast
    assertThat(
            originalSchema.applyUserDefinedSchemaAfterSchemaLearning(
                compatibleSchema, null, null, true, true, null, null, DEFAULT_RULES))
        .isEqualTo(originalSchema);
  }

  @Test
  public void testDifferencePrimitiveAndStruct() {
    List<Field> left = ImmutableList.of(varchar("a1"));
    List<Field> right = ImmutableList.of(struct("a1", varchar("b1")));

    assertThat(BatchSchema.difference(left, right)).isEmpty();
    assertThat(BatchSchema.difference(right, left)).isEmpty();
  }

  @Test
  public void testDifferencePrimitiveAndList() {
    List<Field> left = ImmutableList.of(varchar("a1"));
    List<Field> right = ImmutableList.of(list("a1", varchar(DATA_VECTOR_NAME)));

    assertThat(BatchSchema.difference(left, right)).isEmpty();
    assertThat(BatchSchema.difference(right, left)).isEmpty();
  }

  @Test
  public void testDifferencePrimitiveAndMap() {
    List<Field> left = ImmutableList.of(varchar("a1"));
    List<Field> right = ImmutableList.of(map("a1", varchar("key"), varchar("value")));

    assertThat(BatchSchema.difference(left, right)).isEmpty();
    assertThat(BatchSchema.difference(right, left)).isEmpty();
  }

  @Test
  public void testDifferenceStructAndList() {
    List<Field> left = ImmutableList.of(struct("a1", varchar("b1"), varchar("c1")));
    List<Field> right = ImmutableList.of(list("a1", varchar(DATA_VECTOR_NAME)));

    assertThat(BatchSchema.difference(left, right)).isEmpty();
    assertThat(BatchSchema.difference(right, left)).isEmpty();
  }

  @Test
  public void testDifferenceStructAndStruct() {
    // a1: struct<
    //     b1: varchar,
    //     b2: struct<
    //         c1: struct<
    //             d1: varchar,
    //             d2: varchar>,
    //         c2: varchar,
    //         c3: varchar>>
    Field d1 = varchar("d1");
    Field d2 = varchar("d2");
    Field c1 = struct("c1", d1, d2);
    Field c2 = varchar("c2");
    Field c3 = varchar("c3");
    Field b1 = varchar("b1");
    Field b2 = struct("b2", c1, c2, c3);
    Field a1 = struct("a1", b1, b2);
    List<Field> left = ImmutableList.of(a1);

    // a1: struct<b1: varchar>
    // nested field a1.b2 is on left only
    List<Field> right1 = ImmutableList.of(struct("a1", b1));
    assertThat(BatchSchema.difference(left, right1))
        .containsExactlyElementsOf(ImmutableList.of(struct("a1", b2)));

    // a1: struct<b2: struct<c1: struct<d2: varchar>, c2: varchar>>
    // nested fields a1.b1, a1.b2.c1.d1, a1.b2.c2, a1.b2.c2, and a1.b2.c3 are on left only
    List<Field> right2 =
        ImmutableList.of(struct("a1", struct("b2", struct("c1", d2), varchar("c4"))));
    assertThat(BatchSchema.difference(left, right2))
        .containsExactlyElementsOf(
            ImmutableList.of(struct("a1", b1, struct("b2", struct("c1", d1), c2, c3))));
  }

  @Test
  public void testDifferenceListAndList() {
    List<Field> left =
        ImmutableList.of(list("l", struct(DATA_VECTOR_NAME, varchar("a"), varchar("b"))));
    List<Field> right = ImmutableList.of(list("l", struct(DATA_VECTOR_NAME, varchar("b"))));

    assertThat(BatchSchema.difference(left, right))
        .containsExactlyElementsOf(
            ImmutableList.of(list("l", struct(DATA_VECTOR_NAME, varchar("a")))));
  }

  @Test
  public void testDifferenceMapAndMap() {
    Field key1 = varchar("key");
    Field value1 = struct("value", varchar("a"), varchar("b"));
    Field map1 = map("m", key1, value1);
    List<Field> left = ImmutableList.of(map1);

    Field key2 = varchar("key");
    Field value2 = struct("value", varchar("b"));
    Field map2 = map("m", key2, value2);

    List<Field> right = ImmutableList.of(map2);
    assertThat(BatchSchema.difference(left, right))
        .containsExactlyElementsOf(ImmutableList.of(map("m", struct("value", varchar("a")))));
  }

  private static Field varchar(String name) {
    return Field.nullable(name, new ArrowType.Utf8());
  }

  private static Field fint(String name) {
    return Field.nullable(name, new ArrowType.Int(32, true));
  }

  private static Field bigint(String name) {
    return Field.nullable(name, new ArrowType.Int(64, true));
  }

  private static Field float8(String name) {
    return Field.nullable(name, new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
  }

  private static Field fnull(String name) {
    return Field.nullable(name, new ArrowType.Null());
  }

  private static Field struct(String name, Field... children) {
    return new Field(name, FieldType.nullable(new ArrowType.Struct()), Arrays.asList(children));
  }

  private static Field list(String name, Field child) {
    return new Field(name, FieldType.nullable(new ArrowType.List()), ImmutableList.of(child));
  }

  private static Field map(String name, Field... children) {
    return new Field(
        name,
        FieldType.nullable(new ArrowType.Map(false)),
        ImmutableList.of(struct("entries", children)));
  }

  private static Field union(String name, Field... children) {
    int[] typeIds = new int[children.length];
    for (int i = 0; i < children.length; i++) {
      typeIds[i] = Types.getMinorTypeForArrowType(children[i].getType()).ordinal();
    }
    return new Field(
        name,
        FieldType.nullable(new ArrowType.Union(UnionMode.Sparse, typeIds)),
        Arrays.asList(children));
  }
}
