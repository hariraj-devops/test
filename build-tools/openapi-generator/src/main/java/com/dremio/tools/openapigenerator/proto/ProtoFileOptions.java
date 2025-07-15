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

import java.nio.file.Path;
import org.immutables.value.Value;

/** Holds path to the spec file and proto file-level options. */
@Value.Immutable
public interface ProtoFileOptions {
  String specPath();

  String packageName();

  String javaPackageName();

  String javaOuterClassName();

  default String getProtoFileName() {
    String specFileName = specPath(); // Path.of(specPath()).getFileName().toString();
    if (!specFileName.endsWith(".yaml")) {
      throw new RuntimeException(String.format("Spec extension is unexpected: %s", specPath()));
    }
    return specFileName.replace(".yaml", ".proto");
  }

  default String getProtoFilePath() {
    if (!specPath().endsWith(".yaml")) {
      throw new RuntimeException(String.format("Spec extension is unexpected: %s", specPath()));
    }
    return specPath().replace(".yaml", ".proto");
  }

  default String getBaseJavaDir(String baseDir) {
    return Path.of(baseDir, javaPackageName().split("\\.")).toString();
  }

  static ImmutableProtoFileOptions.Builder builder() {
    return ImmutableProtoFileOptions.builder();
  }
}
