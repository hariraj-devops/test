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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestProtoModel {
  private static final String RESOURCE_BASE_DIR = "src/test/resources";
  private static final String GENERATED_FILES_BASE_DIR = RESOURCE_BASE_DIR + "/expected_generated";
  private static final String JAVA_PACKAGE_DIR = "com/dremio";
  private static final ImmutableSet<String> SPEC_RELATIVE_PATHS =
      ImmutableSet.of("spec1.yaml", "spec2.yaml");
  private static final ImmutableList<String> EXPECTED_JAVA_FILES =
      ImmutableList.of(
          "com/dremio/test/api/Enum.java",
          "com/dremio/test/api/EnumOneOf.java",
          "com/dremio/test/api/Error.java",
          "com/dremio/test/api/GetBody.java",
          "com/dremio/test/api/GetBodyEnumOneof.java",
          "com/dremio/test/api/GetBodyInlineOneof.java",
          "com/dremio/test/api/IntChoice.java",
          "com/dremio/test/api/OneOf.java",
          "com/dremio/test/api/PostBody.java",
          "com/dremio/test/api/PostChunk.java",
          "com/dremio/test/api/Problem.java",
          "com/dremio/test/api/ResponseContent.java",
          "com/dremio/test/api/RuntimeProblem.java",
          "com/dremio/test/api/StringChoice.java",
          "com/dremio/test/api/ValidationProblem.java",
          "com/dremio/test/api2/Body2.java");

  @TempDir private File tempDir;

  @Test
  public void testGenerateProtos() throws Exception {
    ProtoModel protoModel = ProtoModel.parse(RESOURCE_BASE_DIR, SPEC_RELATIVE_PATHS);

    protoModel.writeProtos(tempDir.toString());

    for (String specRelativePath : SPEC_RELATIVE_PATHS) {
      String protoFileName = specRelativePath.replace(".yaml", ".proto");
      Path expectedProtoPath = Path.of(GENERATED_FILES_BASE_DIR, protoFileName);
      Path protoPath = Path.of(tempDir.toString(), protoFileName);
      Assertions.assertEquals(Files.readString(expectedProtoPath), Files.readString(protoPath));
    }
  }

  @Test
  public void testGenerateImmutables() throws Exception {
    ProtoModel protoModel = ProtoModel.parse(RESOURCE_BASE_DIR, SPEC_RELATIVE_PATHS);

    // Generate java files.
    protoModel.writeImmutables(tempDir.toString());

    // Collect generated java files.
    List<String> javaFiles = new ArrayList<>();
    Files.walkFileTree(
        Path.of(tempDir.toString(), JAVA_PACKAGE_DIR),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.toString().endsWith(".java")) {
              javaFiles.add(tempDir.toPath().relativize(file).toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    Assertions.assertEquals(
        EXPECTED_JAVA_FILES, javaFiles.stream().sorted().collect(Collectors.toList()));

    for (String relativePath : EXPECTED_JAVA_FILES) {
      Path actualPath = Path.of(tempDir.toString(), relativePath);
      Path expectedPath = Path.of(GENERATED_FILES_BASE_DIR, relativePath);
      Assertions.assertEquals(Files.readString(expectedPath), Files.readString(actualPath));
    }
  }
}
