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
package com.dremio.exec.store.hive.exec;

import java.util.List;

import com.dremio.exec.store.parquet.ParquetFilterCondition;
import com.dremio.exec.store.parquet.ParquetFilters;

/**
 * Hive ParquetFilters implementation which disallows positional deletes.  Iceberg tables in Hive will have Parquet
 * files processed using the filesystem based Parquet split processing, as the Parquet files themselves are not managed
 * by Hive directly.
 */
public class HiveParquetFilters extends ParquetFilters {

  public HiveParquetFilters(List<ParquetFilterCondition> pushdownFilters) {
    super(pushdownFilters, null, null);
  }
}
