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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import io.swagger.v3.oas.models.media.Discriminator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Represents proto message. */
@Value.Immutable
public abstract class ProtoMessage {
  /** Proto file options. */
  public abstract ProtoFileOptions fileOptions();

  /** Message name. The name is not set for inline one-ofs. * */
  public abstract Optional<String> name();

  /** Message comment. */
  public abstract Optional<String> comment();

  /** Fields. */
  public abstract ImmutableList<ProtoField> fields();

  /** Whether the fields are wrapped into a "oneof". */
  public abstract boolean isOneOf();

  /** Name of parent message. Only for immutable models. Taken from "allOf" ref. */
  public abstract Optional<String> parentName();

  /** Only for immutable models. */
  public abstract Optional<Discriminator> discriminator();

  /** Writes a message to proto file. */
  public void write(ProtoTypeRegistry typeRegistry, BufferedWriter writer, boolean inlineOneOf)
      throws IOException {
    // Comment.
    if (!inlineOneOf && comment().isPresent()) {
      ProtoWriterUtils.writeComment(comment().get(), "", writer);
    }

    if (name().isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Message with empty name in %s is attempted for write", fileOptions().specPath()));
    }

    // Message start.
    if (!inlineOneOf) {
      writer.write(String.format("message %s {", name().get()));
      writer.newLine();
    }

    // Fields.
    if (isOneOf()) {
      if (inlineOneOf) {
        writer.write(String.format("  oneof %s {", name().get()));
      } else {
        writer.write("  oneof choice {");
      }
      writer.newLine();
      for (int i = 0; i < fields().size(); i++) {
        ProtoField field = fields().get(i);
        field.write(typeRegistry, "    ", fileOptions(), writer, true);
        if (i + 1 < fields().size()) {
          writer.newLine();
        }
      }
      writer.write("  }");
      writer.newLine();
    } else {
      for (int i = 0; i < fields().size(); i++) {
        ProtoField field = fields().get(i);
        field.write(typeRegistry, "  ", fileOptions(), writer, false);
        if (i + 1 < fields().size()) {
          writer.newLine();
        }
      }
    }

    // Message end.
    if (!inlineOneOf) {
      writer.write("}");
      writer.newLine();
    }
  }

  /** Writes an immutable interface to Java file. */
  public void writeImmutable(
      String packageName, ProtoTypeRegistry typeRegistry, BufferedWriter writer)
      throws IOException {
    if (name().isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Message with empty name in %s is attempted for write", fileOptions().specPath()));
    }

    // License header.
    Header.write(writer);

    // Package.
    writer.write(String.format("package %s;\n\n", packageName));

    // Imports.
    Set<String> javaImports = new TreeSet<>();
    javaImports.add("org.immutables.value.Value");
    javaImports.add(JsonSerialize.class.getName());
    javaImports.add(JsonDeserialize.class.getName());
    if (discriminator().isPresent()) {
      javaImports.add(JsonTypeInfo.class.getName());
      javaImports.add(JsonSubTypes.class.getName());
      javaImports.add(JsonInclude.class.getName());
      javaImports.add(Nullable.class.getName());
    }
    if (parentName().isPresent()) {
      javaImports.add(String.format("%s.Immutable%s.Builder", packageName, parentName().get()));
    }
    for (ProtoField field : fields()) {
      field.updateJavaImports(typeRegistry, javaImports, isOneOf());
    }
    for (String javaImport : javaImports) {
      writer.write(String.format("import %s;\n", javaImport));
    }
    if (!javaImports.isEmpty()) {
      writer.newLine();
    }

    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeJavaComment(comment().get(), "", writer);
    }

    // Class/interface declaration.
    String className = name().get();
    writer.write("@Value.Immutable\n");
    if (this.parentName().isEmpty()) {
      writer.write("@Value.Style(builderVisibility = Value.Style.BuilderVisibility.PUBLIC)\n");
    }
    writer.write(String.format("@JsonDeserialize(as = Immutable%s.class)\n", className));
    writer.write(String.format("@JsonSerialize(as = Immutable%s.class)\n", className));
    if (this.discriminator().isPresent()) {
      Discriminator discriminator = this.discriminator().get();
      writer.write(
          String.format(
              "@JsonTypeInfo("
                  + "use = JsonTypeInfo.Id.NAME, "
                  + "include = JsonTypeInfo.As.EXISTING_PROPERTY, "
                  + "property = \"%s\", "
                  + "visible = true)\n",
              discriminator.getPropertyName()));
      if (!discriminator.getMapping().entrySet().isEmpty()) {
        writer.write("@JsonSubTypes({\n");
        for (Entry<String, String> mappingEntry : discriminator.getMapping().entrySet()) {
          writer.write(
              String.format(
                  "  @JsonSubTypes.Type(value = %s.class, name = \"%s\"),\n",
                  PathUtils.getRefName(mappingEntry.getValue()), mappingEntry.getKey()));
        }
        writer.write("})\n");
      }
    }
    if (isOneOf()) {
      // Abstract class with validate method.
      writer.write(String.format("public abstract class %s {\n", className));
    } else {
      // Interface.
      if (this.parentName().isEmpty()) {
        writer.write(String.format("public interface %s {\n", className));
      } else {
        writer.write(
            String.format(
                "public interface %s extends %s {\n", className, this.parentName().get()));
      }
    }

    // Fields.
    for (int i = 0; i < fields().size(); i++) {
      ProtoField field = fields().get(i);
      field.writeJavaInterfaceMethod(typeRegistry, fileOptions(), writer, isOneOf());
      if (i + 1 < fields().size()) {
        writer.newLine();
      }
    }

    // toBuilder method.
    writer.newLine();
    if (this.parentName().isPresent()) {
      writer.write("  @Override\n");
    }
    if (isOneOf()) {
      writer.write(String.format("  public Immutable%s.Builder toBuilder() {\n", name().get()));
      writer.write(String.format("    return Immutable%s.builder().from(this);\n", name().get()));
      writer.write("  }\n");
    } else if (this.discriminator().isPresent()) {
      writer.write("  @Nullable\n");
      writer.write(String.format("  Immutable%s.Builder toBuilder();\n", name().get()));
    } else if (this.parentName().isPresent()) {
      writer.write("  Builder toBuilder();\n");
    } else {
      writer.write(String.format("  default Immutable%s.Builder toBuilder() {\n", name().get()));
      writer.write(String.format("    return Immutable%s.builder().from(this);\n", name().get()));
      writer.write("  }\n");
    }

    // Validate method for oneof.
    if (isOneOf()) {
      writer.newLine();
      writer.write("  @Value.Check\n");
      writer.write("  protected void validate() {\n");
      writer.write("    int setFields = 0;\n");
      for (ProtoField field : fields()) {
        writer.write(String.format("    if (%s() != null) {\n", field.javaGetterName()));
        writer.write("      setFields++;\n");
        writer.write("    }\n");
      }
      writer.write("    if (setFields > 1) {\n");
      writer.write(
          "      throw new RuntimeException(\"More than one field is set in a oneof.\");\n");
      writer.write("    }\n");
      writer.write("  }\n");
    }

    // Class closing brace.
    writer.write("}\n");
  }

  static ImmutableProtoMessage.Builder builder() {
    return ImmutableProtoMessage.builder();
  }
}
