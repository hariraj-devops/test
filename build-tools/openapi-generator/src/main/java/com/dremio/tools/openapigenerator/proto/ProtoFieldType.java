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

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import joptsimple.internal.Strings;
import org.immutables.value.Value;

/** Field type as one of message, enum, built-in or well-known type. */
@Value.Immutable
public abstract class ProtoFieldType {
  private static final ImmutableSet<Descriptors.FieldDescriptor.JavaType> ATOMIC_OPTIONAL =
      ImmutableSet.of();

  public abstract boolean isRepeated();

  /** Open API spec reference stored before message type is created. */
  public abstract Optional<String> specReference();

  public abstract Optional<ProtoMessage> messageType();

  public abstract Optional<ProtoEnum> enumType();

  /** Protobuf built-in type. */
  public abstract Optional<Descriptors.FieldDescriptor.Type> builtInType();

  /** A well-known type such as {@link com.google.protobuf.Timestamp}. */
  public abstract Optional<Descriptors.Descriptor> wellKnownType();

  /** References to types from allOf. */
  public abstract Optional<ImmutableList<ProtoTypeRef>> allOfTypeRefs();

  /**
   * Whether the type is an alias of another type. For example, named array is an alias of a
   * repeated type.
   */
  public abstract Optional<Boolean> isAlias();

  /** Constraints on the type. */
  public abstract Optional<ProtoFieldConstraints> constraints();

  public boolean isInlineOneOf() {
    return messageType().isPresent()
        && messageType().get().name().isEmpty()
        && messageType().get().isOneOf();
  }

  /** String for the type when referenced from a proto file. */
  public String toString(ProtoFileOptions referencingFile) {
    String packageName = "";
    String typeName;
    if (messageType().isPresent()) {
      ProtoMessage message = messageType().get();
      if (!message.fileOptions().packageName().equals(referencingFile.packageName())) {
        packageName = message.fileOptions().packageName();
      }
      if (message.name().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Empty name for message %s referenced by file %s", message, referencingFile));
      }
      typeName = message.name().get();
    } else if (enumType().isPresent()) {
      ProtoEnum protoEnum = enumType().get();
      if (!protoEnum.fileOptions().packageName().equals(referencingFile.packageName())) {
        packageName = protoEnum.fileOptions().packageName();
      }
      typeName = protoEnum.name();
    } else if (builtInType().isPresent()) {
      typeName =
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_UNDERSCORE, builtInType().get().name());
    } else {
      if (wellKnownType().isEmpty()) {
        throw new RuntimeException(
            String.format("Empty type %s referenced by %s", this, referencingFile));
      }
      typeName = wellKnownType().get().getFullName();
    }

    typeName = Strings.isNullOrEmpty(packageName) ? typeName : packageName + "." + typeName;
    return isRepeated() ? "repeated " + typeName : typeName;
  }

  public String toJavaTypeString(ProtoFileOptions referencingFile) {
    String packageName = "";
    String typeName;
    if (messageType().isPresent()) {
      ProtoMessage message = messageType().get();
      if (!message.fileOptions().javaPackageName().equals(referencingFile.javaPackageName())) {
        packageName = message.fileOptions().javaPackageName();
      }
      if (message.name().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Empty name for message %s referenced by file %s", message, referencingFile));
      }
      typeName = message.name().get();
    } else if (enumType().isPresent()) {
      ProtoEnum protoEnum = enumType().get();
      if (!protoEnum.fileOptions().javaPackageName().equals(referencingFile.javaPackageName())) {
        packageName = protoEnum.fileOptions().javaPackageName();
      }
      typeName = protoEnum.name();
    } else if (builtInType().isPresent()) {
      Descriptors.FieldDescriptor.JavaType javaType = builtInType().get().getJavaType();
      if (isAtomicOptional()) {
        typeName =
            String.format(
                "Optional%s",
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, javaType.name()));
      } else if (javaType.equals(Descriptors.FieldDescriptor.JavaType.INT)) {
        typeName = Integer.class.getSimpleName();
      } else {
        typeName = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, javaType.name());
      }
    } else {
      if (wellKnownType().isEmpty()) {
        throw new RuntimeException(
            String.format("Empty type %s referenced by %s", this, referencingFile));
      }
      Descriptors.Descriptor descriptor = wellKnownType().get();
      if (descriptor.equals(Timestamp.getDescriptor())) {
        typeName = ZonedDateTime.class.getSimpleName();
      } else if (descriptor.equals(Duration.getDescriptor())) {
        typeName = java.time.Duration.class.getSimpleName();
      } else {
        throw new RuntimeException(String.format("Unsupported well-known type: %s", descriptor));
      }
    }

    typeName = Strings.isNullOrEmpty(packageName) ? typeName : packageName + "." + typeName;
    if (isRepeated()) {
      return String.format("List<%s>", typeName);
    } else {
      return typeName;
    }
  }

  public void updateJavaImports(Set<String> imports) {
    if (isRepeated()) {
      imports.add(List.class.getName());
    }
    if (wellKnownType().isPresent()) {
      Descriptors.Descriptor descriptor = wellKnownType().get();
      if (descriptor.equals(Timestamp.getDescriptor())) {
        imports.add(ZonedDateTime.class.getName());
      } else if (descriptor.equals(Duration.getDescriptor())) {
        imports.add(java.time.Duration.class.getName());
      } else {
        throw new RuntimeException(String.format("Unsupported well-known type: %s", descriptor));
      }
    }
    if (builtInType().isPresent() && isAtomicOptional()) {
      imports.add(
          String.format(
              "java.util.Optional%s",
              CaseFormat.UPPER_UNDERSCORE.to(
                  CaseFormat.UPPER_CAMEL, builtInType().get().getJavaType().name())));
    }
  }

  public boolean isAtomicOptional() {
    return builtInType().isPresent() && ATOMIC_OPTIONAL.contains(builtInType().get().getJavaType());
  }

  public static ImmutableProtoFieldType.Builder builder() {
    return ImmutableProtoFieldType.builder();
  }
}
