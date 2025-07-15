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

import com.google.common.base.Preconditions;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.util.Text;

/** Utility for writing Java objects to arrow vector containers. */
public class JavaObjectVectorWriter extends ArrowType.PrimitiveTypeVisitor<Void> {

  private final FieldVector vector;
  private final Object value;

  JavaObjectVectorWriter(FieldVector vector, Object value) {
    this.vector = vector;
    this.value = value;
  }

  public static void writeObject(FieldVector vector, Object value) {
    vector.getField().getType().accept(new JavaObjectVectorWriter(vector, value));
  }

  @Override
  public Void visit(ArrowType.Null aNull) {
    return unsupported(aNull);
  }

  @Override
  public Void visit(ArrowType.Int anInt) {
    if (vector instanceof IntVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Integer);
        ((IntVector) vector).set(0, (Integer) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    if (vector instanceof BigIntVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Long);
        ((BigIntVector) vector).set(0, (Long) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.FloatingPoint floatingPoint) {
    if (vector instanceof Float4Vector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Float);
        ((Float4Vector) vector).set(0, (Float) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    if (vector instanceof Float8Vector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Double);
        ((Float8Vector) vector).set(0, (Double) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Utf8 utf8) {
    if (vector instanceof VarCharVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Text);
        ((VarCharVector) vector).set(0, (Text) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Utf8View utf8) {
    return unsupported(utf8);
  }

  @Override
  public Void visit(ArrowType.LargeUtf8 largeUtf8) {
    return unsupported(largeUtf8);
  }

  @Override
  public Void visit(ArrowType.Binary binary) {
    if (vector instanceof VarBinaryVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof byte[]);
        ((VarBinaryVector) vector).set(0, (byte[]) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.BinaryView binary) {
    return unsupported(binary);
  }

  @Override
  public Void visit(ArrowType.LargeBinary largeBinary) {
    return unsupported(largeBinary);
  }

  @Override
  public Void visit(ArrowType.FixedSizeBinary fixedSizeBinary) {
    return unsupported(fixedSizeBinary);
  }

  @Override
  public Void visit(ArrowType.Bool bool) {
    if (vector instanceof BitVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof Boolean);
        ((BitVector) vector).set(0, (Boolean) value ? 1 : 0);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Decimal decimal) {
    if (vector instanceof DecimalVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof BigDecimal);
        ((DecimalVector) vector).set(0, (BigDecimal) value);
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Date date) {
    if (vector instanceof DateMilliVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof LocalDateTime);
        ((DateMilliVector) vector).set(0, toMillis((LocalDateTime) value));
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Time time) {
    if (vector instanceof TimeMilliVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof LocalDateTime);
        ((TimeMilliVector) vector).set(0, (int) toMillis((LocalDateTime) value));
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Timestamp timestamp) {
    if (vector instanceof TimeStampMilliVector) {
      if (Objects.nonNull(value)) {
        Preconditions.checkState(value instanceof LocalDateTime);
        ((TimeStampMilliVector) vector).set(0, toMillis((LocalDateTime) value));
      }
      vector.setValueCount(vector.getValueCount() + 1);
      return null;
    }

    return unexpected(vector);
  }

  @Override
  public Void visit(ArrowType.Interval interval) {
    return unsupported(interval);
  }

  @Override
  public Void visit(ArrowType.Duration duration) {
    return unsupported(duration);
  }

  private long toMillis(LocalDateTime localDateTime) {
    return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  private Void unsupported(ArrowType type) {
    throw new UnsupportedOperationException("Unsupported primitive type: " + type);
  }

  private Void unexpected(FieldVector vector) {
    throw new UnsupportedOperationException(
        "Unexpected vector type: " + vector.getClass().getName());
  }
}
