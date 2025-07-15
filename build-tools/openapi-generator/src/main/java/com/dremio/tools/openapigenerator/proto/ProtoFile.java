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
import com.dremio.tools.openapigenerator.Header;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Describes one proto file. */
public final class ProtoFile {
  private static final Logger logger = LoggerFactory.getLogger(ProtoFile.class);

  private final ProtoFileOptions fileOptions;
  private final Set<String> imports = new TreeSet<>();
  private final Map<String, ProtoMessage> messages = new HashMap<>();
  private final Map<String, ProtoEnum> enums = new HashMap<>();
  private final Map<String, ImmutableList<ProtoMessage>> allOfMessages = new HashMap<>();
  private final Map<String, ProtoMethod> methods = new HashMap<>();

  public ProtoFile(ProtoFileOptions fileOptions) {
    this.fileOptions = fileOptions;
  }

  public ProtoFileOptions getFileOptions() {
    return fileOptions;
  }

  /** Adds a proto message to the file. */
  public void addMessage(ProtoTypeRegistry typeRegistry, ProtoMessage message) {
    if (!message.fileOptions().specPath().equals(fileOptions.specPath())) {
      throw new RuntimeException(
          String.format(
              "Message %s from %s cannot be added to file from %s",
              message.name(), message.fileOptions().specPath(), fileOptions.specPath()));
    }
    if (message.name().isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Message w/o name from %s cannot be added to proto file from %s",
              message.fileOptions().specPath(), fileOptions.specPath()));
    }
    if (this.messages.containsKey(message.name().get())) {
      throw new RuntimeException(
          String.format(
              "Message %s from %s %s already exists",
              message.name(), message.fileOptions().specPath(), fileOptions.specPath()));
    }
    this.messages.put(message.name().get(), message);

    // Add imports for well-known types and messages/enums defined in other proto files.
    addImports(typeRegistry, message);
  }

  /** Adds a enum to the file. */
  public void addEnum(ProtoEnum protoEnum) {
    if (!protoEnum.fileOptions().specPath().equals(fileOptions.specPath())) {
      throw new RuntimeException(
          String.format(
              "Enum %s from %s cannot be added to file from %s",
              protoEnum.name(), protoEnum.fileOptions().specPath(), fileOptions.specPath()));
    }
    if (this.enums.containsKey(protoEnum.name())) {
      throw new RuntimeException(
          String.format(
              "Enum %s from %s %s already exists",
              protoEnum.name(), protoEnum.fileOptions().specPath(), fileOptions.specPath()));
    }
    this.enums.put(protoEnum.name(), protoEnum);
  }

  /** Adds a allOf messages to the file. */
  public void addAllOfMessages(
      ProtoTypeRegistry typeRegistry, ImmutableList<ProtoMessage> messages) {
    for (ProtoMessage message : messages) {
      if (!message.fileOptions().specPath().equals(fileOptions.specPath())) {
        throw new RuntimeException(
            String.format(
                "Message %s from %s cannot be added to file from %s",
                message.name(), message.fileOptions().specPath(), fileOptions.specPath()));
      }
      if (message.name().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Message w/o name from %s cannot be added to proto file from %s",
                message.fileOptions().specPath(), fileOptions.specPath()));
      }

      // Add imports for well-known types and messages/enums defined in other proto files.
      // Only the last message requires this.
      addImports(typeRegistry, message);
    }

    // Trim suffix from the name.
    String name = messages.get(messages.size() - 1).name().get();
    name = name.substring(0, name.lastIndexOf(Constants.ALL_OF_SUFFIX));
    this.allOfMessages.put(name, messages);
  }

  /**
   * Adds a method to the proto file. The methods are not used for GRPC service generation, they are
   * used for Java class generation.
   */
  public void addMethod(ProtoMethod method) {
    if (!method.fileOptions().specPath().equals(fileOptions.specPath())) {
      throw new RuntimeException(
          String.format(
              "Method %s from %s cannot be added to file from %s",
              method.name(), method.fileOptions().specPath(), fileOptions.specPath()));
    }
    if (this.methods.containsKey(method.name())) {
      throw new RuntimeException(
          String.format(
              "Method %s from %s already exists, spec = %s",
              method.name(), method.fileOptions().specPath(), fileOptions.specPath()));
    }
    this.methods.put(method.name(), method);
  }

  /** Writes proto file with imports, enums, messages. */
  public void writeFile(ProtoTypeRegistry typeRegistry, String path) {
    try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(path))) {
      // License header.
      Header.write(writer);

      // Syntax.
      writer.write("syntax = \"proto3\";");
      writer.newLine();
      writer.newLine();

      // Proto package.
      writer.write(String.format("package %s;", fileOptions.packageName()));
      writer.newLine();
      writer.newLine();

      // Imports.
      for (String importProto : imports) {
        writer.write(String.format("import \"%s\";", importProto));
        writer.newLine();
      }
      if (!imports.isEmpty()) {
        writer.newLine();
      }

      // Java package.
      writer.write(String.format("option java_package = \"%s\";", fileOptions.javaPackageName()));
      writer.newLine();

      // Java outer class.
      writer.write(
          String.format("option java_outer_classname = \"%s\";", fileOptions.javaOuterClassName()));
      writer.newLine();

      // Messages.
      for (ProtoMessage message : messages.values()) {
        writer.newLine();
        message.write(typeRegistry, writer, false);
      }

      // Enums.
      for (ProtoEnum protoEnum : enums.values()) {
        writer.newLine();
        protoEnum.write(writer);
      }

      // AllOfs combine fields from all the messages. This is a convenience to spec writers,
      // semantics of "inheritance" is not preserved.
      for (Map.Entry<String, ImmutableList<ProtoMessage>> entry : allOfMessages.entrySet()) {
        writer.newLine();
        ProtoMessage message = combineMessageFields(entry.getKey(), entry.getValue(), false);
        message.write(typeRegistry, writer, false);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Writes all messages and enums with respective import statements to the given directory. */
  public void writeImmutables(ProtoTypeRegistry typeRegistry, String directory) {
    // Messages.
    for (ProtoMessage message : messages.values()) {
      if (message.name().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Message with empty name in %s is attempted for write", fileOptions.specPath()));
      }
      Path path = Path.of(directory, message.name().get() + ".java");
      try (BufferedWriter writer = Files.newBufferedWriter(path)) {
        message.writeImmutable(fileOptions.javaPackageName(), typeRegistry, writer);
        logger.info("Generated {}", path);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    // Enums.
    for (ProtoEnum protoEnum : enums.values()) {
      Path path = Path.of(directory, protoEnum.name() + ".java");
      try (BufferedWriter writer = Files.newBufferedWriter(path)) {
        protoEnum.writeJava(fileOptions.javaPackageName(), writer);
        logger.info("Generated {}", path);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    // AllOfs combine fields from all the messages. This is a convenience to spec writers,
    // semantics of "inheritance" is preserved for java immutable.
    for (Map.Entry<String, ImmutableList<ProtoMessage>> entry : allOfMessages.entrySet()) {
      ImmutableList<ProtoMessage> value = entry.getValue();
      ProtoMessage message =
          combineMessageFields(entry.getKey(), value, isInheritanceSupported(value));
      Path path = Path.of(directory, message.name().get() + ".java");
      try (BufferedWriter writer = Files.newBufferedWriter(path)) {
        message.writeImmutable(fileOptions.javaPackageName(), typeRegistry, writer);
        logger.info("Generated {}", path);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** Returns methods. */
  public ImmutableList<ProtoMethod> getMethods() {
    return ImmutableList.copyOf(methods.values());
  }

  /** Recursively collects imports from message fields. */
  private void addImports(ProtoTypeRegistry typeRegistry, ProtoMessage message) {
    for (ProtoField field : message.fields()) {
      if (field.inlineOneOf().isPresent()) {
        addImports(typeRegistry, field.inlineOneOf().get());
        continue;
      }
      ProtoFieldType type = typeRegistry.get(field.typeRef());
      if (type.wellKnownType().isPresent()) {
        imports.add(type.wellKnownType().get().getFile().getFullName());
      } else if (type.messageType().isPresent()) {
        ProtoMessage otherMessage = type.messageType().get();
        if (!otherMessage.fileOptions().equals(this.fileOptions)) {
          imports.add(String.format("%s", otherMessage.fileOptions().getProtoFileName()));
        }
      } else if (type.enumType().isPresent()) {
        ProtoEnum otherEnum = type.enumType().get();
        if (!otherEnum.fileOptions().equals(this.fileOptions)) {
          imports.add(String.format("%s", otherEnum.fileOptions().getProtoFileName()));
        }
      }
    }
  }

  /** Combines fields from all messages into one message. */
  private ProtoMessage combineMessageFields(
      String name, List<ProtoMessage> messages, boolean isInheritanceSupported) {
    List<ProtoField> fields = new ArrayList<>();
    int ordinal = 1;
    for (ProtoField field :
        messages.stream().flatMap(m -> m.fields().stream()).collect(Collectors.toList())) {
      fields.add(ProtoField.builder().from(field).ordinal(ordinal++).build());
    }
    ImmutableProtoMessage.Builder builder =
        ProtoMessage.builder()
            .name(name)
            .fileOptions(fileOptions)
            .isOneOf(false)
            .addAllFields(fields);
    ProtoMessage last = messages.get(messages.size() - 1);
    if (last.comment().isPresent()) {
      builder.comment(last.comment().get());
    }
    if (isInheritanceSupported) {
      combineMessageFieldsWithInheritance(messages, fields, builder);
    }
    return builder.build();
  }

  private void combineMessageFieldsWithInheritance(
      List<ProtoMessage> messages, List<ProtoField> fields, ImmutableProtoMessage.Builder builder) {
    if (isInheritanceSupported(messages)) {
      // Parent message is always first.
      ProtoMessage parentMessage = messages.get(0);
      if (parentMessage.discriminator().isPresent()) {
        ImmutableList<ProtoField> parentFields = parentMessage.fields();
        builder.fields(Sets.difference(Sets.newHashSet(fields), Sets.newHashSet(parentFields)));
        builder.parentName(parentMessage.name());
      }
    }
  }

  private boolean isInheritanceSupported(List<ProtoMessage> messages) {
    if (messages.size() == 2) {
      ProtoMessage message = messages.get(0);
      return message.discriminator().isPresent();
    }

    return false;
  }
}
