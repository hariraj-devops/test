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

import com.google.common.base.Strings;
import java.io.BufferedWriter;
import java.io.IOException;

public final class ProtoWriterUtils {
  private ProtoWriterUtils() {}

  public static void writeComment(String text, String padding, BufferedWriter writer)
      throws IOException {
    if (!Strings.isNullOrEmpty(text)) {
      for (String comment : text.split("\n", -1)) {
        if (!comment.trim().isEmpty()) {
          writer.write(String.format("%s// %s", padding, comment));
          writer.newLine();
        }
      }
    }
  }

  public static void writeJavaComment(String text, String padding, BufferedWriter writer)
      throws IOException {
    if (!Strings.isNullOrEmpty(text)) {
      writer.write(String.format("%s/**", padding));
      writer.newLine();
      for (String comment : text.split("\n", -1)) {
        if (!comment.trim().isEmpty()) {
          writer.write(String.format("%s * %s", padding, comment));
          writer.newLine();
        }
      }
      writer.write(String.format("%s*/", padding));
      writer.newLine();
    }
  }
}
