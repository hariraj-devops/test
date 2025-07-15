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
package com.dremio.tools.openapigenerator.proto;

import java.nio.file.FileSystems;
import java.nio.file.Path;

public final class PathUtils {
  private static final String PATH_SEPARATOR = FileSystems.getDefault().getSeparator();

  private PathUtils() {}

  /** Adds path separator to path if the path doesn't end with it. */
  public static String normalizeDir(String dir) {
    if (!dir.endsWith(PATH_SEPARATOR)) {
      dir += PATH_SEPARATOR;
    }
    return dir;
  }

  /** Checks if the relative path is under the base dir and returns normalized form of it. */
  public static String normalizeRelativePath(String baseDir, String relativePath) {
    baseDir = normalizeDir(baseDir);
    String normalizedRelativePath = Path.of(relativePath).normalize().toString();
    String absolutePath = Path.of(baseDir, normalizedRelativePath).toString();
    if (!absolutePath.substring(baseDir.length()).equals(normalizedRelativePath)) {
      throw new RuntimeException(
          String.format("Relative path %s is not under %s", relativePath, baseDir));
    }
    return normalizedRelativePath;
  }

  public static String getRefName(String input) {
    String[] parts = input.split("/");
    return parts[parts.length - 1];
  }
}
