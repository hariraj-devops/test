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

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Optional;
import org.immutables.value.Value;

/** Represents proto enum value. */
@Value.Immutable
public interface ProtoEnumValue {
  String value();

  Optional<String> comment();

  int ordinal();

  default void write(String padding, BufferedWriter writer) throws IOException {
    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeComment(comment().get(), padding, writer);
    }

    writer.write(String.format("%s%s = %d;", padding, value(), ordinal()));
    writer.newLine();
  }

  default void writeJava(String padding, BufferedWriter writer) throws IOException {
    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeJavaComment(comment().get(), padding, writer);
    }

    writer.write(String.format("%s%s,", padding, value()));
    writer.newLine();
  }

  static ImmutableProtoEnumValue.Builder builder() {
    return ImmutableProtoEnumValue.builder();
  }
}
