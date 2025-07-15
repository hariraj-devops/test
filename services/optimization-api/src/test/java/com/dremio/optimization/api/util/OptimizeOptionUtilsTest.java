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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class OptimizeOptionUtilsTest {

  private static Stream<Arguments> provideValidOptions() {
    return Stream.of(
        Arguments.of(256L, 128L, 512L, 5L),
        Arguments.of(100L, 50L, 200L, 10L),
        Arguments.of(1L, 0L, 2L, 1L));
  }

  private static Stream<Arguments> provideInvalidOptions() {
    return Stream.of(
        Arguments.of(
            -1L, 128L, 512L, 5L, "TARGET_FILE_SIZE_MB [-1] should be a positive integer value."),
        Arguments.of(
            256L, -1L, 512L, 5L, "MIN_FILE_SIZE_MB [-1] should be a non-negative integer value."),
        Arguments.of(
            256L, 128L, -1L, 5L, "MAX_FILE_SIZE_MB [-1] should be a positive integer value."),
        Arguments.of(
            256L,
            512L,
            128L,
            5L,
            "Value of MIN_FILE_SIZE_MB [512] cannot be greater than MAX_FILE_SIZE_MB [128]."),
        Arguments.of(
            128L,
            256L,
            512L,
            5L,
            "Value of TARGET_FILE_SIZE_MB [128] cannot be less than MIN_FILE_SIZE_MB [256]."),
        Arguments.of(
            512L,
            128L,
            256L,
            5L,
            "Value of TARGET_FILE_SIZE_MB [512] cannot be greater than MAX_FILE_SIZE_MB [256]."),
        Arguments.of(256L, 128L, 512L, 0L, "Value of MIN_INPUT_FILES [0] cannot be less than 1."));
  }

  private static Stream<Arguments> provideInvalidByteOptions() {
    return Stream.of(
        Arguments.of(
            -1048576L,
            134217728L,
            536870912L,
            5L,
            "TARGET_FILE_SIZE_BYTES [-1048576] should be a positive integer value."),
        Arguments.of(
            268435456L,
            -1048576L,
            536870912L,
            5L,
            "MIN_FILE_SIZE_BYTES [-1048576] should be a non-negative integer value."),
        Arguments.of(
            268435456L,
            134217728L,
            -1048576L,
            5L,
            "MAX_FILE_SIZE_BYTES [-1048576] should be a positive integer value."),
        Arguments.of(
            268435456L,
            536870912L,
            134217728L,
            5L,
            "Value of MIN_FILE_SIZE_BYTES [536870912] cannot be greater than MAX_FILE_SIZE_BYTES [134217728]."),
        Arguments.of(
            134217728L,
            268435456L,
            536870912L,
            5L,
            "Value of TARGET_FILE_SIZE_BYTES [134217728] cannot be less than MIN_FILE_SIZE_BYTES [268435456]."),
        Arguments.of(
            536870912L,
            134217728L,
            268435456L,
            5L,
            "Value of TARGET_FILE_SIZE_BYTES [536870912] cannot be greater than MAX_FILE_SIZE_BYTES [268435456]."),
        Arguments.of(
            268435456L,
            134217728L,
            536870912L,
            0L,
            "Value of MIN_INPUT_FILES [0] cannot be less than 1."));
  }

  @ParameterizedTest
  @MethodSource("provideValidOptions")
  void testValidateOptionsWithValidInputs(
      Long targetFileSizeMB, Long minFileSizeMB, Long maxFileSizeMB, Long minInputFiles) {
    OptimizeOptionUtils.validateOptions(
        targetFileSizeMB, minFileSizeMB, maxFileSizeMB, minInputFiles);
  }

  @ParameterizedTest
  @MethodSource("provideInvalidOptions")
  void testValidateOptionsWithInvalidInputs(
      Long targetFileSizeMB,
      Long minFileSizeMB,
      Long maxFileSizeMB,
      Long minInputFiles,
      String expectedMessage) {
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OptimizeOptionUtils.validateOptions(
                    targetFileSizeMB, minFileSizeMB, maxFileSizeMB, minInputFiles));
    assertEquals(expectedMessage, exception.getMessage());
  }

  @ParameterizedTest
  @MethodSource("provideValidOptions")
  void testValidateOptionsInBytesWithValidInputs(
      Long targetFileSizeBytes, Long minFileSizeBytes, Long maxFileSizeBytes, Long minInputFiles) {
    OptimizeOptionUtils.validateOptionsInBytes(
        targetFileSizeBytes, minFileSizeBytes, maxFileSizeBytes, minInputFiles);
  }

  @ParameterizedTest
  @MethodSource("provideInvalidByteOptions")
  void testValidateOptionsInBytesWithInvalidInputs(
      Long targetFileSizeBytes,
      Long minFileSizeBytes,
      Long maxFileSizeBytes,
      Long minInputFiles,
      String expectedMessage) {
    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OptimizeOptionUtils.validateOptionsInBytes(
                    targetFileSizeBytes, minFileSizeBytes, maxFileSizeBytes, minInputFiles));
    assertEquals(expectedMessage, exception.getMessage());
  }
}
