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
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableBitHolder;

public class IsListFunction {
  @FunctionTemplate(
      name = "IS_LIST",
      scope = FunctionTemplate.FunctionScope.SIMPLE,
      nulls = FunctionTemplate.NullHandling.INTERNAL)
  public static class IsList implements SimpleFunction {

    @Param private FieldReader in;
    @Output private NullableBitHolder out;

    @Override
    public void setup() {}

    @Override
    public void eval() {
      out.isSet = 1;
      if (in.isSet()) {
        out.value = in.getMinorType() == org.apache.arrow.vector.types.Types.MinorType.LIST ? 1 : 0;
      } else {
        out.value = 0;
      }
    }
  }
}
