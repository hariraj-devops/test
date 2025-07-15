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

package com.dremio.exec.expr.fn.ItemsSketch;

import com.dremio.exec.expr.AggrFunction;
import com.dremio.exec.expr.annotations.FunctionTemplate;
import com.dremio.exec.expr.annotations.Output;
import com.dremio.exec.expr.annotations.Param;
import com.dremio.exec.expr.annotations.Workspace;
import com.dremio.options.OptionResolver;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;
import javax.inject.Inject;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.holders.IntHolder;
import org.apache.arrow.vector.holders.NullableBigIntHolder;
import org.apache.arrow.vector.holders.NullableBitHolder;
import org.apache.arrow.vector.holders.NullableDateMilliHolder;
import org.apache.arrow.vector.holders.NullableFloat4Holder;
import org.apache.arrow.vector.holders.NullableFloat8Holder;
import org.apache.arrow.vector.holders.NullableIntHolder;
import org.apache.arrow.vector.holders.NullableIntervalDayHolder;
import org.apache.arrow.vector.holders.NullableIntervalYearHolder;
import org.apache.arrow.vector.holders.NullableTimeMilliHolder;
import org.apache.arrow.vector.holders.NullableTimeStampMilliHolder;
import org.apache.arrow.vector.holders.NullableVarBinaryHolder;
import org.apache.arrow.vector.holders.NullableVarCharHolder;
import org.apache.arrow.vector.holders.ObjectHolder;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.datasketches.ArrayOfBooleansSerDe;
import org.apache.datasketches.ArrayOfDoublesSerDe;
import org.apache.datasketches.ArrayOfItemsSerDe;
import org.apache.datasketches.ArrayOfLongsSerDe;
import org.apache.datasketches.ArrayOfNumbersSerDe;
import org.apache.datasketches.ArrayOfStringsSerDe;

@SuppressWarnings({"deprecation"})
public class ItemsSketchFunctions {
  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ItemsSketchFunctions.class);
  public static final String FUNCTION_NAME = "ITEMS_SKETCH";

  /** Computes the items_sketch for a column of doubles */
  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableIntegerItemsSketchFunction implements AggrFunction {
    @Param private NullableIntHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Integer>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableBigIntItemsSketchFunction implements AggrFunction {
    @Param private NullableBigIntHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Long> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj);
      byte[] serialized = itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfLongsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableInternalDayItemsSketchFunction implements AggrFunction {
    @Param private NullableIntervalDayHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj)
            .update(in.milliseconds);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          (org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj;
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableIntervalYearsItemsSketchFunction implements AggrFunction {
    @Param private NullableIntervalYearHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          (org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj;
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableFloat4ItemsSketchFunction implements AggrFunction {
    @Param private NullableFloat4Holder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          (org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj;
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableFloat8ItemsSketchFunction implements AggrFunction {
    @Param private NullableFloat8Holder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Double>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Double> itemsSketch =
          (org.apache.datasketches.frequencies.ItemsSketch<Double>) sketch.obj;
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfDoublesSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableDateItemsSketchFunction implements AggrFunction {
    @Param private NullableDateMilliHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Long> itemsSketch =
          (org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj;
      byte[] serialized = itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfLongsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableTimeItemsSketchFunction implements AggrFunction {
    @Param private NullableTimeMilliHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj)
            .update((long) in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableTimeStampItemsSketchFunction implements AggrFunction {
    @Param private NullableTimeStampMilliHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj).update(in.value);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Long> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj);
      byte[] serialized = itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfLongsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableBitItemsSketchFunction implements AggrFunction {
    @Param private NullableBitHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Boolean>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<Boolean>) sketch.obj)
            .update(in.value == 1);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Boolean> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Boolean>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfBooleansSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = ItemsSketchFunctions.FUNCTION_NAME,
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarCharItemsSketchFunction implements AggrFunction {
    @Param private NullableVarCharHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<String>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        ((org.apache.datasketches.frequencies.ItemsSketch<String>) sketch.obj)
            .update(
                com.dremio.exec.expr.fn.impl.StringFunctionHelpers.toStringFromUTF8(
                    in.start, in.end, in.buffer));
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<String> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<String>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfStringsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  /** Merges the items_sketches to produce a new items_sketch */
  @FunctionTemplate(
      name = "items_sketch_merge_number",
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarBinaryItemsSketchMergeNumber implements AggrFunction {
    @Param private NullableVarBinaryHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
            org.apache.datasketches.frequencies.ItemsSketch.getInstance(
                org.apache.datasketches.memory.Memory.wrap(
                    in.buffer
                        .nioBuffer(in.start, in.end - in.start)
                        .order(java.nio.ByteOrder.nativeOrder())),
                new org.apache.datasketches.ArrayOfNumbersSerDe());
        ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj).merge(itemsSketch);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Number> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Number>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfNumbersSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Number>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = "items_sketch_merge_double",
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarBinaryItemsSketchMergeDouble implements AggrFunction {
    @Param private NullableVarBinaryHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        org.apache.datasketches.frequencies.ItemsSketch<Double> itemsSketch =
            org.apache.datasketches.frequencies.ItemsSketch.getInstance(
                org.apache.datasketches.memory.Memory.wrap(
                    in.buffer
                        .nioBuffer(in.start, in.end - in.start)
                        .order(java.nio.ByteOrder.nativeOrder())),
                new org.apache.datasketches.ArrayOfDoublesSerDe());
        ((org.apache.datasketches.frequencies.ItemsSketch<Double>) sketch.obj).merge(itemsSketch);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Double> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Double>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfDoublesSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Double>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = "items_sketch_merge_varchar",
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarBinaryItemsSketchMergeVarchar implements AggrFunction {
    @Param private NullableVarBinaryHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<String>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        org.apache.datasketches.frequencies.ItemsSketch<String> itemsSketch =
            org.apache.datasketches.frequencies.ItemsSketch.getInstance(
                org.apache.datasketches.memory.Memory.wrap(
                    in.buffer
                        .nioBuffer(in.start, in.end - in.start)
                        .order(java.nio.ByteOrder.nativeOrder())),
                new org.apache.datasketches.ArrayOfStringsSerDe());
        ((org.apache.datasketches.frequencies.ItemsSketch<String>) sketch.obj).merge(itemsSketch);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<String> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<String>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfStringsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<String>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = "items_sketch_merge_long",
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarBinaryItemsSketchMergeLong implements AggrFunction {
    @Param private NullableVarBinaryHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        org.apache.datasketches.frequencies.ItemsSketch<Long> itemsSketch =
            org.apache.datasketches.frequencies.ItemsSketch.getInstance(
                org.apache.datasketches.memory.Memory.wrap(
                    in.buffer
                        .nioBuffer(in.start, in.end - in.start)
                        .order(java.nio.ByteOrder.nativeOrder())),
                new org.apache.datasketches.ArrayOfLongsSerDe());
        ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj).merge(itemsSketch);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Long> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Long>) sketch.obj);
      byte[] serialized = itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfLongsSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Long>(maxSize.value);
    }
  }

  @FunctionTemplate(
      name = "items_sketch_merge_boolean",
      scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableVarBinaryItemsSketchMergeBoolean implements AggrFunction {
    @Param private NullableVarBinaryHolder in;
    @Workspace private ObjectHolder sketch;
    @Output private NullableVarBinaryHolder out;
    @Inject private ArrowBuf buffer;
    @Workspace IntHolder maxSize;
    @Inject OptionResolver options;

    @Override
    public void setup() {
      sketch = new ObjectHolder();
      maxSize.value = (int) options.getOption(com.dremio.exec.ExecConstants.ITEMS_SKETCH_MAX_SIZE);
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Boolean>(maxSize.value);
    }

    @Override
    public void add() {
      if (in.isSet == 1) {
        org.apache.datasketches.frequencies.ItemsSketch<Boolean> itemsSketch =
            org.apache.datasketches.frequencies.ItemsSketch.getInstance(
                org.apache.datasketches.memory.Memory.wrap(
                    in.buffer
                        .nioBuffer(in.start, in.end - in.start)
                        .order(java.nio.ByteOrder.nativeOrder())),
                new org.apache.datasketches.ArrayOfBooleansSerDe());
        ((org.apache.datasketches.frequencies.ItemsSketch<Boolean>) sketch.obj).merge(itemsSketch);
      }
    }

    @Override
    public void output() {
      org.apache.datasketches.frequencies.ItemsSketch<Boolean> itemsSketch =
          ((org.apache.datasketches.frequencies.ItemsSketch<Boolean>) sketch.obj);
      byte[] serialized =
          itemsSketch.toByteArray(new org.apache.datasketches.ArrayOfBooleansSerDe());
      buffer = buffer.reallocIfNeeded(serialized.length);
      out.buffer = buffer;
      out.start = 0;
      out.buffer.setBytes(0, serialized);
      out.end = serialized.length;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      sketch.obj = new org.apache.datasketches.frequencies.ItemsSketch<Boolean>(maxSize.value);
    }
  }

  public static class SqlItemsSketchMergeNumbersAggFunction extends SqlAggFunction {
    private final RelDataType type;

    public SqlItemsSketchMergeNumbersAggFunction(RelDataType type) {
      super(
          "items_sketch_merge_number",
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0, // use the inferred return type of SqlCountAggFunction
          null,
          OperandTypes.BINARY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false);

      this.type = type;
    }

    public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
      return ImmutableList.of(type);
    }

    public RelDataType getType() {
      return type;
    }

    public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
      return type;
    }
  }

  public static class SqlItemsSketchMergeLongAggFunction extends SqlAggFunction {
    private final RelDataType type;

    public SqlItemsSketchMergeLongAggFunction(RelDataType type) {
      super(
          "items_sketch_merge_long",
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0, // use the inferred return type of SqlCountAggFunction
          null,
          OperandTypes.BINARY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false);

      this.type = type;
    }

    public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
      return ImmutableList.of(type);
    }

    public RelDataType getType() {
      return type;
    }

    public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
      return type;
    }
  }

  public static class SqlItemsSketchMergeBooleanAggFunction extends SqlAggFunction {
    private final RelDataType type;

    public SqlItemsSketchMergeBooleanAggFunction(RelDataType type) {
      super(
          "items_sketch_merge_boolean",
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0, // use the inferred return type of SqlCountAggFunction
          null,
          OperandTypes.BINARY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false);

      this.type = type;
    }

    public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
      return ImmutableList.of(type);
    }

    public RelDataType getType() {
      return type;
    }

    public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
      return type;
    }
  }

  public static class SqlItemsSketchMergeVarCharAggFunction extends SqlAggFunction {
    private final RelDataType type;

    public SqlItemsSketchMergeVarCharAggFunction(RelDataType type) {
      super(
          "items_sketch_merge_varchar",
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0, // use the inferred return type of SqlCountAggFunction
          null,
          OperandTypes.BINARY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false);

      this.type = type;
    }

    public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
      return ImmutableList.of(type);
    }

    public RelDataType getType() {
      return type;
    }

    public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
      return type;
    }
  }

  public static class SqlItemsSketchMergeDoubleAggFunction extends SqlAggFunction {
    private final RelDataType type;

    public SqlItemsSketchMergeDoubleAggFunction(RelDataType type) {
      super(
          "items_sketch_merge_double",
          null,
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0, // use the inferred return type of SqlCountAggFunction
          null,
          OperandTypes.BINARY,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          false,
          false);

      this.type = type;
    }

    public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
      return ImmutableList.of(type);
    }

    public RelDataType getType() {
      return type;
    }

    public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
      return type;
    }
  }

  public static ArrayOfItemsSerDe<? extends Serializable> getSerdeFromSqlTypeName(
      SqlTypeName typeName) {
    switch (typeName) {
      case BOOLEAN:
        return new ArrayOfBooleansSerDe();
      case DOUBLE:
      case DECIMAL:
        return new ArrayOfDoublesSerDe();
      case VARCHAR:
        return new ArrayOfStringsSerDe();
      case FLOAT:
      case INTEGER:
      case SMALLINT:
      case TINYINT:
      case VARBINARY:
      case INTERVAL_DAY:
      case TIME:
        return new ArrayOfNumbersSerDe();
      case BIGINT:
      case DATE:
      case TIMESTAMP:
        return new ArrayOfLongsSerDe();
      default:
        throw new UnsupportedOperationException(
            String.format("Cannot Create Serde for type %s.", typeName.getName()));
    }
  }
}
