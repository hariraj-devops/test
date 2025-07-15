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
package com.dremio.tools.openapigenerator;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MainTest {

  private static final String WORKING_PATH = Paths.get("").toAbsolutePath().toString();

  @TempDir private File tempDir;

  @Test
  public void testRunProtos() throws Exception {
    String generatedJavaDir = Paths.get(tempDir.toString(), "java").toString();
    Main.Options options =
        new Main.Options()
            .setBaseDir(WORKING_PATH + "/src/test/resources")
            .setInputFiles(ImmutableList.of("spec1.yaml", "spec2.yaml"))
            .setOutputProtoDir(Paths.get(tempDir.toString(), "proto").toString())
            .setOutputJavaDir(generatedJavaDir)
            .setWriteProtos(true);
    Main.run(options);

    List<String> generatedFilePaths = new ArrayList<>();
    Path generatedJavaPath = Path.of(generatedJavaDir);
    Files.walkFileTree(
        generatedJavaPath,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            generatedFilePaths.add(generatedJavaPath.relativize(file).toString());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            throw new RuntimeException(String.format("Failed to access %s with %s", file, e));
          }
        });
    Assertions.assertEquals(
        ImmutableList.of(
            "com/dremio/test/api/TestApiProto.java",
            "com/dremio/test/api2/TestApiProto2.java",
            "com/dremio/test/dac/api/RestResource.java"),
        generatedFilePaths.stream().sorted().collect(Collectors.toList()));

    // Verify constraint annotation was added.
    String javaText =
        Files.readString(Path.of(generatedJavaDir, "com/dremio/test/api/TestApiProto.java"));
    Pattern pattern =
        Pattern.compile(
            "@java.lang.Override\n"
                + "\\s*@Min\\(value = 10\\)\n"
                + "\\s*@Max\\(value = 20\\)\n"
                + "\\s*public int getIntValue1",
            Pattern.DOTALL | Pattern.MULTILINE);
    Assertions.assertTrue(pattern.matcher(javaText).find());
  }

  @Test
  public void testRegex() {
    Pattern pattern =
        Pattern.compile(
            String.format(
                "%s([^\n]*)$[^{]*^(\\s*)@java\\.lang\\.Override$[^{]*(^\\s*public\\s+"
                    + "(long|int|java.lang.String)\\s+get[^\\n]*$)",
                Constants.CONSTRAINT_PREFIX),
            Pattern.DOTALL | Pattern.MULTILINE);
    String setString =
        "      /**\n"
            + "       * <pre>\n"
            + "       * Int value.\n"
            + "       * CONSTRAINT: jakarta.validation.constraints.Min(value = 10)\n"
            + "       * CONSTRAINT: jakarta.validation.constraints.Max(value = 20)\n"
            + "       * </pre>\n"
            + "       *\n"
            + "       * <code>optional int32 int_value1 = 2;</code>\n"
            + "       * @param value The intValue1 to set.\n"
            + "       * @return This builder for chaining.\n"
            + "       */\n"
            + "      @java.lang.Override\n"
            + "      public Builder setIntValue1(int value) {\n";
    Assertions.assertFalse(pattern.matcher(setString).find());
    String getString =
        "      /**\n"
            + "       * <pre>\n"
            + "       * Int value.\n"
            + "       * CONSTRAINT: jakarta.validation.constraints.Min(value = 10)\n"
            + "       * CONSTRAINT: jakarta.validation.constraints.Max(value = 20)\n"
            + "       * </pre>\n"
            + "       *\n"
            + "       * <code>optional int32 int_value1 = 2;</code>\n"
            + "       * @param value The intValue1 to set.\n"
            + "       * @return This builder for chaining.\n"
            + "       */\n"
            + "      @java.lang.Override\n"
            + "      public int getIntValue1() {\n";
    Matcher matcher = pattern.matcher(getString);
    Assertions.assertTrue(matcher.find());
    Assertions.assertEquals("jakarta.validation.constraints.Min(value = 10)", matcher.group(1));
    Assertions.assertEquals("      ", matcher.group(2));
    Assertions.assertEquals("      public int getIntValue1() {", matcher.group(3));
    matcher = pattern.matcher(getString.substring(matcher.end(1)));
    Assertions.assertTrue(matcher.find());
    Assertions.assertEquals("jakarta.validation.constraints.Max(value = 20)", matcher.group(1));
    Assertions.assertEquals("      ", matcher.group(2));
    Assertions.assertEquals("      public int getIntValue1() {", matcher.group(3));
  }
}
