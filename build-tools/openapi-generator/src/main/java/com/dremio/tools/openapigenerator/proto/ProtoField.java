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

import com.dremio.tools.openapigenerator.Constants;
import com.google.common.base.CaseFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Field in a message. */
@Value.Immutable
public abstract class ProtoField {
  public abstract Optional<String> comment();

  public abstract ProtoTypeRef typeRef();

  public abstract Optional<ProtoMessage> inlineOneOf();

  public abstract String name();

  public abstract int ordinal();

  public abstract boolean isOptional();

  /** Constraints on the field. */
  public abstract Optional<ProtoFieldConstraints> constraints();

  public String javaGetterName() {
    // get prefix is required for validation constraints to work.
    return "get" + CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name());
  }

  public void write(
      ProtoTypeRegistry typeRegistry,
      String padding,
      ProtoFileOptions referencingFileOptions,
      BufferedWriter writer,
      boolean isOneOf)
      throws IOException {
    // Add field comment. Constraints are added in the comments and later are added
    // as annotation to properties.
    StringBuilder comment = new StringBuilder();
    if (comment().isPresent()) {
      comment.append(comment().get());
    }
    if (inlineOneOf().isEmpty()) {
      // Field constraints overwrite type constraints.
      if (constraints().isPresent()) {
        for (String constraint : constraints().get().toValidationAnnotations()) {
          comment.append("\n" + Constants.CONSTRAINT_PREFIX).append(constraint);
        }
      } else {
        ProtoFieldType type = typeRegistry.get(typeRef());
        if (type.constraints().isPresent()) {
          for (String constraint : type.constraints().get().toValidationAnnotations()) {
            comment.append("\n" + Constants.CONSTRAINT_PREFIX).append(constraint);
          }
        }
      }
    }
    if (comment.length() > 0) {
      ProtoWriterUtils.writeComment(comment.toString(), padding, writer);
    }

    // Write field declaration.
    if (inlineOneOf().isPresent()) {
      inlineOneOf().get().write(typeRegistry, writer, true);
    } else {
      ProtoFieldType type = typeRegistry.get(typeRef());
      writer.write(
          String.format(
              "%s%s%s %s = %d;",
              padding,
              isOptional() && !type.isRepeated() && !isOneOf ? "optional " : "",
              type.toString(referencingFileOptions),
              name(),
              ordinal()));
      writer.newLine();
    }
  }

  public void writeJavaInterfaceMethod(
      ProtoTypeRegistry typeRegistry,
      ProtoFileOptions referencingFileOptions,
      BufferedWriter writer,
      boolean isOneOf)
      throws IOException {
    // Comment.
    if (comment().isPresent()) {
      ProtoWriterUtils.writeJavaComment(comment().get(), "  ", writer);
    }

    // Validation annotations.
    if (constraints().isPresent()) {
      for (String constraint : constraints().get().toValidationAnnotations()) {
        writer.write(String.format("  @%s\n", constraint));
      }
    }

    // Field accessor method.
    ProtoFieldType type = typeRegistry.get(typeRef());
    String typeString = type.toJavaTypeString(referencingFileOptions);
    boolean optionalType = typeString.startsWith("Optional");
    if (isOneOf) {
      if (optionalType) {
        writer.write(String.format("  public abstract %s %s();", typeString, javaGetterName()));
      } else {
        writer.write(
            String.format("  public abstract @Nullable %s %s();", typeString, javaGetterName()));
      }
    } else {
      if (isOptional() && !optionalType) {
        writer.write(String.format("  @Nullable %s %s();", typeString, javaGetterName()));
      } else {
        writer.write(String.format("  %s %s();", typeString, javaGetterName()));
      }
    }
    writer.newLine();
  }

  /** Adds an import that is required to declare the field. */
  public void updateJavaImports(
      ProtoTypeRegistry typeRegistry, Set<String> imports, boolean isOneOf) {
    ProtoFieldType type = typeRegistry.get(typeRef());
    if (isOneOf) {
      imports.add(Nullable.class.getName());
    }
    if (isOptional() && !type.isAtomicOptional()) {
      imports.add(Nullable.class.getName());
    }
    if (constraints().isPresent()) {
      constraints().get().updateJavaImports(imports);
    }
    type.updateJavaImports(imports);
  }

  static ImmutableProtoField.Builder builder() {
    return ImmutableProtoField.builder();
  }
}
