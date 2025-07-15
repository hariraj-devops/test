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
package com.dremio.exec.store.iceberg.deletes;

import java.util.Iterator;

/**
 * Provides a simple Iterator interface on top of Iceberg positional delete information for a single
 * data file. This may either be positions sourced from a single delete file, or the merged set from
 * multiple delete files.
 */
public interface PositionalDeleteIterator extends Iterator<Long>, AutoCloseable {

  /**
   * Long.MAX_VALUE is reserved for use as an end-of-iteration sentinel value in
   * PositionDeleteFilter.
   */
  long END_POS = Long.MAX_VALUE;
}
