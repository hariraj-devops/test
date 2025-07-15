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
package com.dremio.service.flight;

import static com.dremio.service.flight.utils.DremioFlightPreparedStatementUtils.convertParameters;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.exec.proto.UserProtos;
import com.google.protobuf.ByteString;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.DurationVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.IntervalYearVector;
import org.apache.arrow.vector.LargeVarBinaryVector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeSecVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecTZVector;
import org.apache.arrow.vector.TimeStampSecVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.LargeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.UnionVector;
import org.apache.arrow.vector.complex.impl.NullableStructWriter;
import org.apache.arrow.vector.complex.impl.UnionFixedSizeListWriter;
import org.apache.arrow.vector.complex.impl.UnionLargeListWriter;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.complex.impl.UnionMapWriter;
import org.apache.arrow.vector.complex.impl.UnionWriter;
import org.apache.arrow.vector.complex.writer.VarCharWriter;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.junit.Test;

public class TestPreparedStatementParameterConversion {

  @Test(expected = UnsupportedOperationException.class)
  public void testNullTypeNotSupported() {
    Schema paramSchema = schema(field("?0", ArrowType.Null.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        NullVector param = (NullVector) root.getVector(0)) {

      param.allocateNew();
      param.setNull(0);
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testStructTypeNotSupported() {
    Schema paramSchema =
        schema(
            field(
                "?0",
                ArrowType.Struct.INSTANCE,
                field("f1", ArrowType.Utf8.INSTANCE),
                field("f2", ArrowType.Utf8.INSTANCE)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        StructVector param = (StructVector) root.getVector(0)) {

      param.allocateNew();
      NullableStructWriter writer = param.getWriter();
      VarCharWriter f1 = writer.varChar("f1");
      VarCharWriter f2 = writer.varChar("f2");
      writer.setPosition(0);
      writer.start();
      f1.writeVarChar("foo");
      f2.writeVarChar("bar");
      writer.end();
      writer.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testListTypeNotSupported() {
    Schema paramSchema =
        schema(field("?0", ArrowType.List.INSTANCE, field(null, ArrowType.Utf8.INSTANCE)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        ListVector param = (ListVector) root.getVector(0)) {

      param.allocateNew();
      UnionListWriter writer = param.getWriter();
      writer.setPosition(0);
      writer.startList();
      writer.varChar().writeVarChar("foo");
      writer.varChar().writeVarChar("bar");
      writer.endList();
      writer.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testLargeListTypeNotSupported() {
    Schema paramSchema =
        schema(field("?0", ArrowType.LargeList.INSTANCE, field(null, ArrowType.Utf8.INSTANCE)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        LargeListVector param = (LargeListVector) root.getVector(0)) {

      param.allocateNew();
      UnionLargeListWriter writer = param.getWriter();
      writer.setPosition(0);
      writer.startList();
      writer.varChar().writeVarChar("foo");
      writer.varChar().writeVarChar("bar");
      writer.endList();
      writer.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testFixedSizeListTypeNotSupported() {
    Schema paramSchema =
        schema(field("?0", new ArrowType.FixedSizeList(2), field(null, ArrowType.Utf8.INSTANCE)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        FixedSizeListVector param = (FixedSizeListVector) root.getVector(0)) {

      param.allocateNew();
      UnionFixedSizeListWriter writer = param.getWriter();
      writer.setPosition(0);
      writer.startList();
      writer.varChar().writeVarChar("foo");
      writer.varChar().writeVarChar("bar");
      writer.endList();
      writer.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnionTypeNotSupported() {
    Schema paramSchema =
        schema(
            field(
                "?0",
                new ArrowType.Union(
                    UnionMode.Sparse,
                    new int[] {Types.MinorType.VARCHAR.ordinal(), Types.MinorType.INT.ordinal()}),
                field("f1", ArrowType.Utf8.INSTANCE),
                field("f2", new ArrowType.Int(32, true))));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        UnionVector param = (UnionVector) root.getVector(0)) {

      param.allocateNew();
      UnionWriter writer = (UnionWriter) param.getWriter();
      writer.setPosition(0);
      writer.varChar().writeVarChar("foo");
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testMapTypeNotSupported() {
    Schema paramSchema =
        schema(
            field(
                "?0",
                new ArrowType.Map(false),
                field(
                    null,
                    ArrowType.Struct.INSTANCE,
                    field("k", ArrowType.Utf8.INSTANCE),
                    field("v", ArrowType.Utf8.INSTANCE))));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        MapVector param = (MapVector) root.getVector(0)) {

      param.allocateNew();
      UnionMapWriter writer = param.getWriter();
      writer.startMap();
      writer.startEntry();
      writer.key().varChar().writeVarChar("foo");
      writer.value().varChar().writeVarChar("bar");
      writer.endEntry();
      writer.startEntry();
      writer.key().varChar().writeVarChar("bar");
      writer.value().varChar().writeVarChar("baz");
      writer.endEntry();
      writer.endMap();
      writer.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test
  public void testSignedIntTypes() {
    Schema paramSchema =
        schema(
            field("?0", new ArrowType.Int(8, true)),
            field("?1", new ArrowType.Int(16, true)),
            field("?2", new ArrowType.Int(32, true)),
            field("?3", new ArrowType.Int(64, true)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        TinyIntVector byteParam = (TinyIntVector) root.getVector(0);
        SmallIntVector shortParam = (SmallIntVector) root.getVector(1);
        IntVector intParam = (IntVector) root.getVector(2);
        BigIntVector longParam = (BigIntVector) root.getVector(3)) {

      byteParam.allocateNew();
      byteParam.set(0, (byte) 1);
      byteParam.setValueCount(1);

      shortParam.allocateNew();
      shortParam.set(0, (short) 1);
      shortParam.setValueCount(1);

      intParam.allocateNew();
      intParam.set(0, 1);
      intParam.setValueCount(1);

      longParam.allocateNew();
      longParam.set(0, 1L);
      longParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams).hasSize(4);
      assertThat(dremioParams.get(0))
          .matches(val -> val.hasShortValue() && val.getShortValue() == 1);
      assertThat(dremioParams.get(1))
          .matches(val -> val.hasShortValue() && val.getShortValue() == 1);
      assertThat(dremioParams.get(2)).matches(val -> val.hasIntValue() && val.getIntValue() == 1);
      assertThat(dremioParams.get(3))
          .matches(val -> val.hasLongValue() && val.getLongValue() == 1L);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testUnsignedIntTypeNotSupported() {
    Schema paramSchema = schema(field("?0", new ArrowType.Int(32, false)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        UInt4Vector param = (UInt4Vector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, 1);
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test
  public void testFloatingPointTypes() {
    Schema paramSchema =
        schema(
            field("?0", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            field("?1", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        Float4Vector singleFloatParam = (Float4Vector) root.getVector(0);
        Float8Vector doubleFloatParam = (Float8Vector) root.getVector(1)) {

      singleFloatParam.allocateNew();
      singleFloatParam.set(0, 1.1F);
      singleFloatParam.setValueCount(1);

      doubleFloatParam.allocateNew();
      doubleFloatParam.set(0, 1.1D);
      doubleFloatParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams).hasSize(2);
      assertThat(dremioParams.get(0))
          .matches(val -> val.hasFloatValue() && val.getFloatValue() == 1.1F);
      assertThat(dremioParams.get(1))
          .matches(val -> val.hasDoubleValue() && val.getDoubleValue() == 1.1D);
    }
  }

  @Test
  public void testStringType() {
    Schema paramSchema = schema(field("?0", ArrowType.Utf8.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        VarCharVector param = (VarCharVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, new Text("foo"));
      param.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(1)
          .allMatch(val -> val.hasStringValue() && val.getStringValue().equals("foo"));
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testLargeStringTypeNotSupported() {
    Schema paramSchema = schema(field("?0", ArrowType.LargeUtf8.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        LargeVarCharVector param = (LargeVarCharVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, new Text("foo"));
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test
  public void testBinaryType() {
    Schema paramSchema =
        schema(
            field("?0", ArrowType.Binary.INSTANCE), field("?1", new ArrowType.FixedSizeBinary(4)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        VarBinaryVector binParam = (VarBinaryVector) root.getVector(0);
        FixedSizeBinaryVector fixedBinParam = (FixedSizeBinaryVector) root.getVector(1)) {

      ByteString bytes = ByteString.copyFrom(new byte[] {0, 1, 0, 1});

      binParam.allocateNew();
      binParam.set(0, bytes.toByteArray());
      binParam.setValueCount(1);

      fixedBinParam.allocateNew();
      fixedBinParam.set(0, bytes.toByteArray());
      fixedBinParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(2)
          .allMatch(val -> val.hasByteArrayValue() && val.getByteArrayValue().equals(bytes));
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testLargeBinaryTypeNotSupported() {
    Schema paramSchema = schema(field("?0", ArrowType.LargeBinary.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        LargeVarBinaryVector param = (LargeVarBinaryVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, new byte[] {1});
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test
  public void testBooleanType() {
    Schema paramSchema = schema(field("?0", ArrowType.Bool.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        BitVector param = (BitVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, 1);
      param.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams).hasSize(1).allMatch(val -> val.hasBoolValue() && val.getBoolValue());
    }
  }

  @Test
  public void testDecimalType() {
    Schema paramSchema = schema(field("?0", new ArrowType.Decimal(5, 2, 16)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        DecimalVector param = (DecimalVector) root.getVector(0)) {

      BigDecimal decimal = new BigDecimal("123.45");
      param.allocateNew();
      param.set(0, decimal);
      param.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(1)
          .allMatch(
              val ->
                  val.hasBigDecimalValue()
                      && decimal
                          .unscaledValue()
                          .equals(new BigInteger(val.getBigDecimalValue().getValue().toByteArray()))
                      && val.getBigDecimalValue().getPrecision() == 5
                      && val.getBigDecimalValue().getScale() == 2);
    }
  }

  @Test
  public void testDateType() {
    Schema paramSchema =
        schema(
            field("?0", new ArrowType.Date(DateUnit.DAY)),
            field("?1", new ArrowType.Date(DateUnit.MILLISECOND)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        DateDayVector dayParam = (DateDayVector) root.getVector(0);
        DateMilliVector milliParam = (DateMilliVector) root.getVector(1)) {

      int days = 1;
      long millis = DAYS.toMillis(days);

      dayParam.allocateNew();
      dayParam.set(0, days);
      dayParam.setValueCount(1);

      milliParam.allocateNew();
      milliParam.set(0, millis);
      milliParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(2)
          .allMatch(val -> val.hasDateValue() && val.getDateValue() == millis);
    }
  }

  @Test
  public void testTimeType() {
    Schema paramSchema =
        schema(
            field("?0", new ArrowType.Time(TimeUnit.SECOND, 64)),
            field("?1", new ArrowType.Time(TimeUnit.MILLISECOND, 64)),
            field("?2", new ArrowType.Time(TimeUnit.MICROSECOND, 64)),
            field("?3", new ArrowType.Time(TimeUnit.NANOSECOND, 64)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        TimeSecVector secParam = (TimeSecVector) root.getVector(0);
        TimeMilliVector milliParam = (TimeMilliVector) root.getVector(1);
        TimeMicroVector microParam = (TimeMicroVector) root.getVector(2);
        TimeNanoVector nanoParam = (TimeNanoVector) root.getVector(3)) {

      int secs = 1;
      long millis = SECONDS.toMillis(secs);
      long micros = SECONDS.toMicros(secs);
      long nanos = SECONDS.toNanos(secs);

      secParam.allocateNew();
      secParam.set(0, secs);
      secParam.setValueCount(1);

      milliParam.allocateNew();
      milliParam.set(0, (int) millis);
      milliParam.setValueCount(1);

      microParam.allocateNew();
      microParam.set(0, micros);
      microParam.setValueCount(1);

      nanoParam.allocateNew();
      nanoParam.set(0, nanos);
      nanoParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(4)
          .allMatch(val -> val.hasTimeValue() && val.getTimeValue() == millis);
    }
  }

  @Test
  public void testTimestampType() {
    Schema paramSchema =
        schema(
            field("?0", new ArrowType.Timestamp(TimeUnit.SECOND, null)),
            field("?1", new ArrowType.Timestamp(TimeUnit.MILLISECOND, null)),
            field("?2", new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
            field("?3", new ArrowType.Timestamp(TimeUnit.NANOSECOND, null)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        TimeStampSecVector secParam = (TimeStampSecVector) root.getVector(0);
        TimeStampMilliVector milliParam = (TimeStampMilliVector) root.getVector(1);
        TimeStampMicroVector microParam = (TimeStampMicroVector) root.getVector(2);
        TimeStampNanoVector nanoParam = (TimeStampNanoVector) root.getVector(3)) {

      long secs = 1;
      long millis = SECONDS.toMillis(secs);
      long micros = SECONDS.toMicros(secs);
      long nanos = SECONDS.toNanos(secs);

      secParam.allocateNew();
      secParam.set(0, secs);
      secParam.setValueCount(1);

      milliParam.allocateNew();
      milliParam.set(0, millis);
      milliParam.setValueCount(1);

      microParam.allocateNew();
      microParam.set(0, micros + 1);
      microParam.setValueCount(1);

      nanoParam.allocateNew();
      nanoParam.set(0, nanos + 1);
      nanoParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(4)
          .allMatch(
              val -> val.hasTimestampValue() && val.getTimestampValue().getSeconds() == millis);
      assertThat(dremioParams.get(2)).matches(val -> val.getTimestampValue().getNanos() == 1000);
      assertThat(dremioParams.get(3)).matches(val -> val.getTimestampValue().getNanos() == 1);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testTimestampWithTimeZoneTypeNotSupported() {
    Schema paramSchema = schema(field("?0", new ArrowType.Timestamp(TimeUnit.SECOND, "GMT")));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        TimeStampSecTZVector param = (TimeStampSecTZVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, 1);
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testIntervalTypeNotSupported() {
    Schema paramSchema = schema(field("?0", new ArrowType.Interval(IntervalUnit.YEAR_MONTH)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        IntervalYearVector param = (IntervalYearVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, 16);
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testDurationTypeNotSupported() {
    Schema paramSchema = schema(field("?0", new ArrowType.Duration(TimeUnit.MILLISECOND)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        DurationVector param = (DurationVector) root.getVector(0)) {

      param.allocateNew();
      param.set(0, 1000);
      param.setValueCount(1);

      root.setRowCount(1);

      convertParameters(root);
    }
  }

  @Test
  public void testNullability() {
    Schema paramSchema =
        schema(
            nullableField("?0", new ArrowType.Int(32, true)),
            nullableField("?1", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            nullableField("?2", ArrowType.Utf8.INSTANCE),
            nullableField("?3", ArrowType.Binary.INSTANCE),
            nullableField("?4", new ArrowType.FixedSizeBinary(128)),
            nullableField("?5", ArrowType.Bool.INSTANCE),
            nullableField("?6", new ArrowType.Decimal(5, 2, 32)),
            nullableField("?7", new ArrowType.Date(DateUnit.MILLISECOND)),
            nullableField("?8", new ArrowType.Time(TimeUnit.MILLISECOND, 64)),
            nullableField("?9", new ArrowType.Timestamp(TimeUnit.MILLISECOND, null)));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        IntVector intParam = (IntVector) root.getVector(0);
        Float4Vector fltParam = (Float4Vector) root.getVector(1);
        VarCharVector strParam = (VarCharVector) root.getVector(2);
        VarBinaryVector binParam = (VarBinaryVector) root.getVector(3);
        FixedSizeBinaryVector fixedBinParam = (FixedSizeBinaryVector) root.getVector(4);
        BitVector bitParam = (BitVector) root.getVector(5);
        DecimalVector decParam = (DecimalVector) root.getVector(6);
        DateMilliVector dtParam = (DateMilliVector) root.getVector(7);
        TimeMilliVector tmParam = (TimeMilliVector) root.getVector(8);
        TimeStampMilliVector tsParam = (TimeStampMilliVector) root.getVector(9)) {

      intParam.allocateNew();
      intParam.setValueCount(1);

      fltParam.allocateNew();
      fltParam.setValueCount(1);

      strParam.allocateNew();
      strParam.setValueCount(1);

      binParam.allocateNew();
      binParam.setValueCount(1);

      fixedBinParam.allocateNew();
      fixedBinParam.setValueCount(1);

      bitParam.allocateNew();
      bitParam.setValueCount(1);

      decParam.allocateNew();
      decParam.setValueCount(1);

      dtParam.allocateNew();
      dtParam.setValueCount(1);

      tmParam.allocateNew();
      tmParam.setValueCount(1);

      tsParam.allocateNew();
      tsParam.setValueCount(1);

      root.setRowCount(1);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(10)
          .allMatch(val -> val.hasIsNullValue() && val.getIsNullValue());
    }
  }

  @Test
  public void testMultipleRows() {
    Schema paramSchema =
        schema(
            field("?0", ArrowType.Utf8.INSTANCE),
            field("?1", ArrowType.Utf8.INSTANCE),
            field("?2", ArrowType.Utf8.INSTANCE));
    try (BufferAllocator allocator = new RootAllocator();
        VectorSchemaRoot root = VectorSchemaRoot.create(paramSchema, allocator);
        VarCharVector vector0 = (VarCharVector) root.getVector(0);
        VarCharVector vector1 = (VarCharVector) root.getVector(1);
        VarCharVector vector2 = (VarCharVector) root.getVector(2)) {

      vector0.allocateNew();
      vector0.set(0, new Text("a"));
      vector0.set(1, new Text("d"));
      vector0.setValueCount(2);

      vector1.allocateNew();
      vector1.set(0, new Text("b"));
      vector1.set(1, new Text("e"));
      vector1.setValueCount(2);

      vector2.allocateNew();
      vector2.set(0, new Text("c"));
      vector2.setValueCount(1);

      root.setRowCount(2);

      List<UserProtos.PreparedStatementParameterValue> dremioParams = convertParameters(root);

      assertThat(dremioParams)
          .hasSize(6)
          .map(val -> val.hasIsNullValue() ? "null" : val.getStringValue())
          .containsExactly("a", "b", "c", "d", "e", "null");
    }
  }

  private static Schema schema(Field... fields) {
    return new Schema(asList(fields));
  }

  private static Field field(String name, ArrowType type, Field... children) {
    return new Field(name, FieldType.notNullable(type), asList(children));
  }

  private static Field nullableField(String name, ArrowType type, Field... children) {
    return new Field(name, FieldType.nullable(type), asList(children));
  }
}
