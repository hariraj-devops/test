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
package com.dremio.service.flight.utils;

import com.dremio.exec.proto.UserProtos;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.table.Row;
import org.apache.arrow.vector.table.Table;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.commons.lang3.ArrayUtils;

public class DremioFlightPreparedStatementUtils {

  /**
   * Converts prepared statement parameters in arrow vectors into a format that the Dremio query
   * planner understands.
   *
   * @param params Prepared statement parameters
   * @return List of parameters in Dremio's format
   */
  public static List<UserProtos.PreparedStatementParameterValue> convertParameters(
      VectorSchemaRoot params) {
    List<UserProtos.PreparedStatementParameterValue> dremioParameters = new ArrayList<>();
    try (Table table = new Table(params)) {
      for (Row row = table.immutableRow(); row.hasNext(); row.next()) {
        for (int i = 0; i < table.getVectorCount(); i++) {
          ArrowType fieldType = table.getSchema().getFields().get(i).getType();
          UserProtos.PreparedStatementParameterValue dremioParameter =
              fieldType.accept(new ParameterTypeConverter(row, i));
          dremioParameters.add(dremioParameter);
        }
      }
    }
    return dremioParameters;
  }

  private static final class ParameterTypeConverter
      implements ArrowType.ArrowTypeVisitor<UserProtos.PreparedStatementParameterValue> {

    private static final UserProtos.PreparedStatementParameterValue NULL =
        UserProtos.PreparedStatementParameterValue.newBuilder().setIsNullValue(true).build();

    private final Row row;
    private final int colIndex;

    ParameterTypeConverter(Row row, int colIndex) {
      this.row = row;
      this.colIndex = colIndex;
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Null type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Struct type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.List type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.ListView type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.LargeList type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.LargeListView type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.RunEndEncoded type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.FixedSizeList type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Union type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Map type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Int type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      if (!type.getIsSigned()) {
        throw new UnsupportedOperationException(
            "Arrow type " + type + " not supported for unsigned");
      }

      switch (type.getBitWidth()) {
        case 8:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setShortValue(row.getTinyInt(colIndex))
              .build();
        case 16:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setShortValue(row.getSmallInt(colIndex))
              .build();
        case 32:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setIntValue(row.getInt(colIndex))
              .build();
        case 64:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setLongValue(row.getBigInt(colIndex))
              .build();
        default:
          throw new UnsupportedOperationException(
              "Arrow type " + type + " not supported for width " + type.getBitWidth());
      }
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.FloatingPoint type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      switch (type.getPrecision()) {
        case SINGLE:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setFloatValue(row.getFloat4(colIndex))
              .build();
        case DOUBLE:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setDoubleValue(row.getFloat8(colIndex))
              .build();
        default:
          throw new UnsupportedOperationException(
              "Arrow type " + type + " not supported for precision " + type.getPrecision());
      }
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Utf8 type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setStringValue(row.getVarCharObj(colIndex))
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Utf8View type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.LargeUtf8 type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.BinaryView type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Binary type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setByteArrayValue(ByteString.copyFrom(row.getVarBinary(colIndex)))
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.LargeBinary type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.FixedSizeBinary type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setByteArrayValue(ByteString.copyFrom(row.getFixedSizeBinary(colIndex)))
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Bool type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setBoolValue(row.getBit(colIndex) == 1)
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Decimal type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      // Dremio server expects decimal value byte order of big endian.
      // Arrow default byte order is little endian.
      ByteBuffer buffer = row.getDecimal(colIndex).nioBuffer();
      byte[] valueBytes = new byte[buffer.remaining()];
      buffer.get(valueBytes);
      ArrayUtils.reverse(valueBytes);

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setBigDecimalValue(
              UserProtos.BigDecimalMsg.newBuilder()
                  .setScale(type.getScale())
                  .setPrecision(type.getPrecision())
                  .setValue(ByteString.copyFrom(valueBytes))
                  .build())
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Date type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      switch (type.getUnit()) {
        case DAY:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setDateValue(TimeUnit.DAYS.toMillis(row.getDateDay(colIndex)))
              .build();
        case MILLISECOND:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setDateValue(row.getDateMilli(colIndex))
              .build();
        default:
          throw new UnsupportedOperationException(
              "Arrow type " + type + " not supported for unit " + type.getUnit());
      }
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Time type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      switch (type.getUnit()) {
        case SECOND:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setTimeValue(TimeUnit.SECONDS.toMillis(row.getTimeSec(colIndex)))
              .build();
        case MILLISECOND:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setTimeValue(row.getTimeMilli(colIndex))
              .build();
        case MICROSECOND:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setTimeValue(TimeUnit.MICROSECONDS.toMillis(row.getTimeMicro(colIndex)))
              .build();
        case NANOSECOND:
          return UserProtos.PreparedStatementParameterValue.newBuilder()
              .setTimeValue(TimeUnit.NANOSECONDS.toMillis(row.getTimeNano(colIndex)))
              .build();
        default:
          throw new UnsupportedOperationException(
              "Arrow type " + type + " not supported for unit " + type.getUnit());
      }
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Timestamp type) {
      if (row.isNull(colIndex)) {
        return NULL;
      }

      if (type.getTimezone() != null && !type.getTimezone().isBlank()) {
        throw new UnsupportedOperationException(
            "Arrow type " + type + " not supported with timezone");
      }

      Timestamp ts;
      switch (type.getUnit()) {
        case SECOND:
          ts = new Timestamp(TimeUnit.SECONDS.toMillis(row.getTimeStampSec(colIndex)));
          break;
        case MILLISECOND:
          ts = new Timestamp(row.getTimeStampMilli(colIndex));
          break;
        case MICROSECOND:
          ts = new Timestamp(TimeUnit.MICROSECONDS.toMillis(row.getTimeStampMicro(colIndex)));
          ts.setNanos(Math.floorMod(row.getTimeStampMicro(colIndex), 1000_000) * 1000);
          break;
        case NANOSECOND:
          ts = new Timestamp(TimeUnit.NANOSECONDS.toMillis(row.getTimeStampNano(colIndex)));
          ts.setNanos(Math.floorMod(row.getTimeStampNano(colIndex), 1000_000_000));
          break;
        default:
          throw new UnsupportedOperationException(
              "Arrow type " + type + " not supported for unit " + type.getUnit());
      }

      return UserProtos.PreparedStatementParameterValue.newBuilder()
          .setTimestampValue(
              UserProtos.TimeStamp.newBuilder()
                  .setSeconds(ts.getTime())
                  .setNanos(ts.getNanos())
                  .build())
          .build();
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Interval type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }

    @Override
    public UserProtos.PreparedStatementParameterValue visit(ArrowType.Duration type) {
      throw new UnsupportedOperationException("Arrow type " + type + " not supported.");
    }
  }
}
