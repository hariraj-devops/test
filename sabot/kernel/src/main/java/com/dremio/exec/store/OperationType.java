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
package com.dremio.exec.store;

import java.util.HashMap;
import java.util.Map;

/** Type of operation on metadata files. */
public enum OperationType {
  ADD_DATAFILE(0), // Add data file(s) to the table
  DELETE_DATAFILE(1), // Remove data file(s) from the table
  ADD_MANIFESTFILE(2), // Add manifest file(s) to the table metadata
  DELETE_DELETEFILE(3), // Remove Positional-delete || Equality-delete file(s) from the table
  ORPHAN_DATAFILE(4), // Add orphan data file(s) to the table
  COPY_HISTORY_EVENT(5),
  ADD_DELETEFILE(6); // Add positional-delete file(s) to the table

  public final Integer value;

  OperationType(Integer value) {
    this.value = value;
  }

  private static final Map<Integer, OperationType> TYPES = new HashMap<>();

  static {
    for (OperationType type : values()) {
      TYPES.put(type.value, type);
    }
  }

  public static OperationType valueOf(Integer value) {
    if (TYPES.containsKey(value)) {
      return TYPES.get(value);
    } else {
      throw new UnsupportedOperationException(String.format("Value %d is not recognized.", value));
    }
  }
}
