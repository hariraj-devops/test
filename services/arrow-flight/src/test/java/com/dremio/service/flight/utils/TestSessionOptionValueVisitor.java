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

import org.apache.arrow.flight.SessionOptionValueVisitor;

public class TestSessionOptionValueVisitor<T> implements SessionOptionValueVisitor<T> {
  @Override
  public T visit(String value) {
    return (T) value;
  }

  @Override
  public T visit(boolean value) {
    return (T) Boolean.valueOf(value);
  }

  @Override
  public T visit(long value) {
    return (T) Long.valueOf(value);
  }

  @Override
  public T visit(double value) {
    return (T) Double.valueOf(value);
  }

  @Override
  public T visit(String[] value) {
    return (T) value;
  }

  @Override
  public T visit(Void unused) {
    return null;
  }
}
