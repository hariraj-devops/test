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

import com.dremio.tools.openapigenerator.Constants;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;

/** Custom generator of java from protos so that resulting java files can be customized. */
public class JavaProtoGenerator {
  private static final String PROTOC_NAME = "protoc";
  private static final String PROTOBUF_DEPS_DIR = "protobuf-deps";

  // Regex with three groups:
  //   1. Constraint string.
  //   2. Space padding before @java.lang.Override annotation.
  //   3. Method signature first line.
  // The captured constraint annotations are inserted before the method signature.
  private static final Pattern CONSTRAINT_PATTERN =
      Pattern.compile(
          String.format(
              "%s([^\n]*)$[^{]*^(\\s*)@java\\.lang\\.Override$[^{]*(^\\s*public\\s+"
                  + "(long|int|java.lang.String)\\s+get[^\\n]*$)",
              Constants.CONSTRAINT_PREFIX),
          Pattern.DOTALL | Pattern.MULTILINE);

  private static final ImmutableList<String> RESOURCES_TO_EXTRACT =
      ImmutableList.of(
          PROTOBUF_DEPS_DIR + "/google/protobuf/any.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/api.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/descriptor.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/duration.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/empty.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/field_mask.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/source_context.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/struct.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/timestamp.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/type.proto",
          PROTOBUF_DEPS_DIR + "/google/protobuf/wrappers.proto",
          PROTOC_NAME);

  /** Generates Java protos from a proto dir. */
  public static void generateProtoJava(String protoDir, String javaDir) throws IOException {
    File tempDir = extractResources();
    List<String> args = new ArrayList<>();
    args.add(Path.of(tempDir.getPath(), PROTOC_NAME).toString());
    args.add(String.format("--java_out=%s", javaDir));
    args.add(String.format("--proto_path=%s", protoDir));
    args.add(String.format("--proto_path=%s", Path.of(tempDir.getPath(), PROTOBUF_DEPS_DIR)));
    args.addAll(
        getProtoFilePaths(protoDir).stream().map(Path::toString).collect(Collectors.toList()));
    try {
      ProcessBuilder processBuilder = new ProcessBuilder(args);
      processBuilder.redirectErrorStream(true);
      processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      Process process = processBuilder.start();
      if (!process.waitFor(1, TimeUnit.MINUTES)) {
        process.destroy();
        throw new RuntimeException(
            String.format("Timed out waiting for:%s ", ImmutableList.copyOf(args)));
      }
      if (process.exitValue() != 0) {
        throw new RuntimeException(
            String.format("Exit code %d of:%s ", process.exitValue(), ImmutableList.copyOf(args)));
      }

      // Add constraints.
      addAllPropertyConstraints(javaDir);
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      FileUtils.deleteDirectory(tempDir);
    }
  }

  /** Gathers input proto paths. */
  private static ImmutableList<Path> getProtoFilePaths(String dir) {
    ImmutableList.Builder<Path> protoFiles = ImmutableList.builder();
    try {
      Files.walkFileTree(
          Path.of(dir),
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (file.toString().endsWith(".proto")) {
                protoFiles.add(file);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
              throw new RuntimeException(String.format("Failed to access %s with %s", file, e));
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return protoFiles.build();
  }

  /** Iterates over all generated java files and adds constraint annotations. */
  private static void addAllPropertyConstraints(String javaDir) {
    try {
      Files.walkFileTree(
          Path.of(javaDir),
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (file.toString().endsWith(".java")) {
                addPropertyConstraints(file.toString());
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
              throw new RuntimeException(String.format("Failed to access %s with %s", file, e));
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Adds constraint annotations to properties. */
  private static void addPropertyConstraints(String javaFilePath) {
    try {
      String text = Files.readString(Path.of(javaFilePath));
      StringBuilder outputBuilder = new StringBuilder();
      String remainingText = text;
      boolean changed = false;
      boolean found;
      do {
        Matcher matcher = CONSTRAINT_PATTERN.matcher(remainingText);
        found = matcher.find();
        if (found) {
          // Get constraint and output text until the end of the constraint in the comment.
          String constraint = matcher.group(1);
          outputBuilder.append(remainingText, 0, matcher.end(1));

          // Add constraint annotation to the remaining text.
          String beforeGet = remainingText.substring(matcher.end(1), matcher.start(3));
          String afterGet = remainingText.substring(matcher.start(3));
          String padding = matcher.group(2);
          remainingText = beforeGet + String.format("%s@%s\n", padding, constraint) + afterGet;

          changed = true;
        }
      } while (found);

      // Write the remainder.
      outputBuilder.append(remainingText);

      // Write updated file.
      if (changed) {
        Files.writeString(Path.of(javaFilePath), outputBuilder.toString());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Extracts protoc and well-known protos to a temp dir. */
  private static File extractResources() {
    try {
      File dir =
          new File(
              Files.createTempDirectory(JavaProtoGenerator.class.getSimpleName().toLowerCase())
                  .toString());
      ClassLoader classLoader = JavaProtoGenerator.class.getClassLoader();
      for (String resourceFile : RESOURCES_TO_EXTRACT) {
        try (InputStream inputStream = classLoader.getResourceAsStream(resourceFile)) {
          if (inputStream == null) {
            throw new RuntimeException(String.format("Resource %s is not found", resourceFile));
          }
          byte[] data = inputStream.readAllBytes();
          Path destPath = Path.of(dir.getPath(), resourceFile);
          File destDir = new File(destPath.getParent().toString());
          if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
              throw new RuntimeException(
                  String.format("Failed to created directory %s", destDir.getPath()));
            }
          }
          Files.write(destPath, data);
          if (resourceFile.equals(PROTOC_NAME)) {
            if (!new File(destPath.toString()).setExecutable(true)) {
              throw new RuntimeException(String.format("Failed to mark %s executable", destPath));
            }
          }
        }
      }
      return dir;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
