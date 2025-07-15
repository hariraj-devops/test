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
package com.dremio.tools.openapigenerator.java;

import com.dremio.tools.openapigenerator.proto.ProtoModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestJavaRestResourceGenerator {
  private static final Clock CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1709319207L), ZoneId.of("UTC"));
  private static final String RESOURCES_BASE_DIR = "src/test/resources";
  private static final String GENERATED_FILES_BASE_DIR = RESOURCES_BASE_DIR + "/expected_generated";
  private static final ImmutableSet<String> SPEC_RELATIVE_PATHS =
      ImmutableSet.of("spec1.yaml", "spec2.yaml");

  private static final String RESOURCE_BASE_DIR = "src/test/resources";
  @TempDir private File tempDir;

  @Test
  public void testGenerateWithProtos() throws Exception {
    ProtoModel protoModel = ProtoModel.parse(RESOURCES_BASE_DIR, SPEC_RELATIVE_PATHS);

    JavaRestResourceGenerator.generateJavaClasses(
        CLOCK,
        tempDir.toString(),
        tempDir.toString(),
        protoModel,
        /* use protos */
        true);

    Path javaPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResource.java");
    Path javaImplPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResourceImpl");
    Assertions.assertEquals(
        Files.readString(Path.of(GENERATED_FILES_BASE_DIR, "with_protos", "RestResource.java")),
        Files.readString(javaPath));
    Assertions.assertEquals(
        Files.readString(Path.of(GENERATED_FILES_BASE_DIR, "with_protos", "RestResourceImpl.java")),
        Files.readString(javaImplPath));
  }

  @Test
  public void testGenerateWithImmutables() throws Exception {
    ProtoModel protoModel = ProtoModel.parse(RESOURCES_BASE_DIR, SPEC_RELATIVE_PATHS);

    JavaRestResourceGenerator.generateJavaClasses(
        CLOCK,
        tempDir.toString(),
        tempDir.toString(),
        protoModel,
        /* use protos */
        false);

    // Collect all generated files.
    List<String> generatedFiles = new ArrayList<>();
    Files.walkFileTree(
        tempDir.toPath(),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            generatedFiles.add(tempDir.toPath().relativize(file).toString());
            return FileVisitResult.CONTINUE;
          }
        });
    Assertions.assertEquals(
        generatedFiles.stream().sorted().collect(Collectors.toList()),
        ImmutableList.of(
            "com/dremio/test/dac/api/RestResource.java",
            "com/dremio/test/dac/api/RestResourceImpl"));

    Path javaPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResource.java");
    Path javaImplPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResourceImpl");
    Assertions.assertEquals(
        Files.readString(Path.of(GENERATED_FILES_BASE_DIR, "with_immutables", "RestResource.java")),
        Files.readString(javaPath));
    Assertions.assertEquals(
        Files.readString(
            Path.of(GENERATED_FILES_BASE_DIR, "with_immutables", "RestResourceImpl.java")),
        Files.readString(javaImplPath));
  }
}
