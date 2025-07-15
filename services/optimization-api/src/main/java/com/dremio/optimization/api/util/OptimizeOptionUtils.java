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
package com.dremio.optimization.api.util;

import com.google.common.base.Preconditions;

public final class OptimizeOptionUtils {

  private OptimizeOptionUtils() {}

  public static void validateOptions(
      Long targetFileSizeMB, Long minFileSizeMB, Long maxFileSizeMB, Long minInputFiles) {
    validateOptionsWithUnitName(
        targetFileSizeMB, minFileSizeMB, maxFileSizeMB, minInputFiles, "MB");
  }

  public static void validateOptionsInBytes(
      Long targetFileSizeBytes, Long minFileSizeBytes, Long maxFileSizeBytes, Long minInputFiles) {
    validateOptionsWithUnitName(
        targetFileSizeBytes, minFileSizeBytes, maxFileSizeBytes, minInputFiles, "BYTES");
  }

  private static void validateOptionsWithUnitName(
      Long targetFileSize,
      Long minFileSize,
      Long maxFileSize,
      Long minInputFiles,
      String unitName) {
    Preconditions.checkArgument(
        targetFileSize > 0,
        "TARGET_FILE_SIZE_%s [%s] should be a positive integer value.",
        unitName,
        targetFileSize);

    Preconditions.checkArgument(
        minFileSize >= 0,
        "MIN_FILE_SIZE_%s [%s] should be a non-negative integer value.",
        unitName,
        minFileSize);

    Preconditions.checkArgument(
        maxFileSize > 0,
        "MAX_FILE_SIZE_%s [%s] should be a positive integer value.",
        unitName,
        maxFileSize);

    Preconditions.checkArgument(
        maxFileSize >= minFileSize,
        "Value of MIN_FILE_SIZE_%s [%s] cannot be greater than MAX_FILE_SIZE_%s [%s].",
        unitName,
        minFileSize,
        unitName,
        maxFileSize);

    Preconditions.checkArgument(
        targetFileSize >= minFileSize,
        "Value of TARGET_FILE_SIZE_%s [%s] cannot be less than MIN_FILE_SIZE_%s [%s].",
        unitName,
        targetFileSize,
        unitName,
        minFileSize);

    Preconditions.checkArgument(
        maxFileSize >= targetFileSize,
        "Value of TARGET_FILE_SIZE_%s [%s] cannot be greater than MAX_FILE_SIZE_%s [%s].",
        unitName,
        targetFileSize,
        unitName,
        maxFileSize);

    Preconditions.checkArgument(
        minInputFiles > 0, "Value of MIN_INPUT_FILES [%s] cannot be less than 1.", minInputFiles);
  }
}
