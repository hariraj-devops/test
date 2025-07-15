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
package com.dremio.exec.store.parquet;

import com.dremio.exec.store.iceberg.deletes.EqualityDeleteFilter;
import com.dremio.exec.store.iceberg.deletes.PositionalDeleteFilter;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Encapsulates the various types of filters that can be applied to a Parquet file. */
public class ParquetFilters implements AutoCloseable {
  private final List<ParquetFilterCondition> pushdownFilters;
  private final PositionalDeleteFilter positionalDeleteFilter;
  private final EqualityDeleteFilter equalityDeleteFilter;

  public static final ParquetFilters NONE = new ParquetFilters();

  public ParquetFilters() {
    this(null, null, null);
  }

  public ParquetFilters(List<ParquetFilterCondition> pushdownFilters) {
    this(pushdownFilters, null, null);
  }

  public ParquetFilters(
      List<ParquetFilterCondition> pushdownFilters,
      PositionalDeleteFilter positionalDeleteFilter,
      EqualityDeleteFilter equalityDeleteFilter) {
    this.pushdownFilters = pushdownFilters == null ? ImmutableList.of() : pushdownFilters;
    this.positionalDeleteFilter = positionalDeleteFilter;
    this.equalityDeleteFilter = equalityDeleteFilter;
  }

  public boolean hasPushdownFilters() {
    return !pushdownFilters.isEmpty();
  }

  public List<ParquetFilterCondition> getPushdownFilters() {
    return pushdownFilters;
  }

  public boolean hasPositionalDeleteFilter() {
    return positionalDeleteFilter != null;
  }

  public PositionalDeleteFilter getPositionalDeleteFilter() {
    return positionalDeleteFilter;
  }

  public boolean hasEqualityDeleteFilter() {
    return equalityDeleteFilter != null;
  }

  public EqualityDeleteFilter getEqualityDeleteFilter() {
    return equalityDeleteFilter;
  }

  public ParquetFilters withRowLevelDeleteFilters(
      PositionalDeleteFilter positionalDeleteFilter, EqualityDeleteFilter equalityDeleteFilter) {
    return new ParquetFilters(this.pushdownFilters, positionalDeleteFilter, equalityDeleteFilter);
  }

  @Override
  public void close() throws Exception {
    if (positionalDeleteFilter != null) {
      positionalDeleteFilter.release();
    }
    if (equalityDeleteFilter != null) {
      equalityDeleteFilter.release();
    }
  }
}
