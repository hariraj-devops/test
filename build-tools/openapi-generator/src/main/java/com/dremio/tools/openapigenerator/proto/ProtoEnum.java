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

import com.dremio.tools.openapigenerator.Header;
import com.google.common.collect.ImmutableList;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Optional;
import org.immutables.value.Value;

/** Represents proto enum with all values excluding UNSPECIFIED 0-ordinal. */
@Value.Immutable
public interface ProtoEnum {
  ProtoFileOptions fileOptions();

  String name();

  Optional<String> comment();

  ImmutableList<ProtoEnumValue> values();

  default void write(BufferedWriter writer) throws IOException {
    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeComment(comment().get(), "", writer);
    }

    // Enum start.
    writer.write(String.format("enum %s {", name()));
    writer.newLine();

    // Values.
    for (int i = 0; i < values().size(); i++) {
      ProtoEnumValue value = values().get(i);
      value.write("  ", writer);
      if (i + 1 < values().size()) {
        writer.newLine();
      }
    }

    // Enum end.
    writer.write("}");
    writer.newLine();
  }

  default void writeJava(String packageName, BufferedWriter writer) throws IOException {
    // License header.
    Header.write(writer);

    // Package.
    writer.write(String.format("package %s;", packageName));
    writer.newLine();
    writer.newLine();

    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeJavaComment(comment().get(), "", writer);
    }

    // Enum start.
    writer.write(String.format("public enum %s {", name()));
    writer.newLine();

    // Values.
    for (int i = 0; i < values().size(); i++) {
      ProtoEnumValue value = values().get(i);
      if (value.ordinal() == 0) {
        // Skip unspecified.
        continue;
      }
      value.writeJava("  ", writer);
      if (i + 1 < values().size()) {
        writer.newLine();
      }
    }

    // Enum end.
    writer.write("}");
    writer.newLine();
  }

  static ImmutableProtoEnum.Builder builder() {
    return ImmutableProtoEnum.builder();
  }
}
