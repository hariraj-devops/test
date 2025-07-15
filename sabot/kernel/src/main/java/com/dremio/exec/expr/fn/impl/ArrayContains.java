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
package com.dremio.exec.expr.fn.impl;

import com.dremio.exec.expr.SimpleFunction;
import com.dremio.exec.expr.annotations.FunctionTemplate;
import com.dremio.exec.expr.annotations.Output;
import com.dremio.exec.expr.annotations.Param;
import com.dremio.exec.expr.fn.FunctionErrorContext;
import javax.inject.Inject;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableBitHolder;

public class ArrayContains {

  @FunctionTemplate(
      name = "array_contains",
      scope = FunctionTemplate.FunctionScope.SIMPLE,
      nulls = FunctionTemplate.NullHandling.INTERNAL)
  public static class ArrayContain implements SimpleFunction {

    @Param private FieldReader in;

    @Param(constant = true)
    private FieldReader value;

    @Output private NullableBitHolder out;
    @Inject private FunctionErrorContext errCtx;

    @Override
    public void setup() {}

    @Override
    public void eval() {
      Object inputValue = value.readObject();

      if (!in.isSet() || in.readObject() == null || inputValue == null) {
        out.isSet = 0;
        return;
      }
      if (!in.reader().getMinorType().equals(value.getMinorType())) {
        throw new UnsupportedOperationException(
            String.format(
                "List of %s is not comparable with %s",
                in.reader().getMinorType().toString(), value.getMinorType().toString()));
      }
      org.apache.arrow.vector.complex.impl.UnionListReader listReader =
          (org.apache.arrow.vector.complex.impl.UnionListReader) in;
      boolean containsNull = false;
      while (listReader.next()) {
        if (!listReader.reader().isSet()) {
          containsNull = true;
        } else if (com.dremio.exec.expr.fn.impl.array.ArrayHelper.isReaderValueEquals(
            listReader.reader(), value)) {
          out.isSet = 1;
          out.value = 1;
          return;
        }
      }

      if (containsNull) {
        out.isSet = 0;
      } else {
        out.isSet = 1;
        out.value = 0;
      }
    }
  }
}
