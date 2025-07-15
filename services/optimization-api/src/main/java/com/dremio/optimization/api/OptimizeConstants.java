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
package com.dremio.optimization.api;

import static org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT;

public final class OptimizeConstants {
  public static final long TARGET_FILE_SIZE_MB =
      WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT / (1024 * 1024);
  // Minimum file size default ration should be 24% to maintain backward compatible from Iceberg
  // target file size to Dremio target file size
  public static final double MINIMUM_FILE_SIZE_DEFAULT_RATIO = 0.24;
  public static final double MAXIMUM_FILE_SIZE_DEFAULT_RATIO = 1.8;
  public static final long MINIMUM_INPUT_FILES = 5L;

  public static final String OPTIMIZE_MIN_FILE_SIZE_MB_PROPERTY =
      "dremio.iceberg.optimize.minimal_file_size_mb";
  public static final String OPTIMIZE_MAX_FILE_SIZE_MB_PROPERTY =
      "dremio.iceberg.optimize.maximal_file_size_mb";
  public static final String OPTIMIZE_MINIMAL_INPUT_FILES =
      "dremio.iceberg.optimize.minimal_input_files";

  private OptimizeConstants() {}
}
