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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Holds map from {@link ProtoTypeRef} to {@link ProtoFieldType}. */
public final class ProtoTypeRegistry {
  /**
   * Some schemas are only used for inclusion and expansion in oneOfs, they don't exist as proto
   * messages.
   */
  private static final Pattern WRAPPER_TYPE_PATTERN = Pattern.compile(".*Wrapper");

  private static final ImmutableList<Descriptors.Descriptor> WELL_KNOWN_DESCRIPTORS =
      ImmutableList.of(Timestamp.getDescriptor(), Duration.getDescriptor());
  private final Map<ProtoTypeRef, ProtoFieldType> typeMap = Maps.newHashMap();

  public ProtoTypeRegistry() {
    // Built-in types.
    for (Descriptors.FieldDescriptor.Type type : Descriptors.FieldDescriptor.Type.values()) {
      typeMap.put(
          buildPrimitiveTypeRef(type, false),
          ProtoFieldType.builder().builtInType(type).isRepeated(false).build());
      typeMap.put(
          buildPrimitiveTypeRef(type, true),
          ProtoFieldType.builder().builtInType(type).isRepeated(true).build());
    }

    // Well-known types keyed by their (short) names.
    for (Descriptors.Descriptor descriptor : WELL_KNOWN_DESCRIPTORS) {
      typeMap.put(
          buildWellKnownRef(descriptor, false),
          ProtoFieldType.builder().wellKnownType(descriptor).isRepeated(false).build());
      typeMap.put(
          buildWellKnownRef(descriptor, true),
          ProtoFieldType.builder().wellKnownType(descriptor).isRepeated(true).build());
    }
  }

  /**
   * Returns a reference for a proto {@link Descriptors.Descriptor}, it is intended for use with
   * well-known Google types like {@link Timestamp}.
   */
  public static ProtoTypeRef buildWellKnownRef(
      Descriptors.Descriptor descriptor, boolean isRepeated) {
    Preconditions.checkArgument(
        WELL_KNOWN_DESCRIPTORS.contains(descriptor),
        "Type must be one of the supported well-known descriptors.");
    return ProtoTypeRef.builder().typeName(descriptor.getName()).isRepeated(isRepeated).build();
  }

  /** Reference to a built-in proto type. */
  public static ProtoTypeRef buildPrimitiveTypeRef(
      Descriptors.FieldDescriptor.Type type, boolean isRepeated) {
    return ProtoTypeRef.builder().typeName(type.name()).isRepeated(isRepeated).build();
  }

  /**
   * Adds repeated and non-repeated types to the registry. Types that are aliases to other types or
   * are not yet resolved are added as is.
   */
  public void put(ProtoTypeRef key, ProtoFieldType value) {
    if (value.isAlias().isPresent() && value.isAlias().get() || value.specReference().isPresent()) {
      // Aliases and references are stored as-is w/o changing repeated field.
      // Check if the type is already registered and it's not a place-holder.
      ProtoFieldType existingValue = typeMap.get(key);
      if (existingValue != null && existingValue.specReference().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Type with key %s and value %s is already registered, new value %s",
                key, existingValue, value));
      }

      typeMap.put(key, value);
    } else {
      key = ProtoTypeRef.builder().from(key).isRepeated(false).build();

      // Check if the type is already registered and it's not a place-holder.
      ProtoFieldType existingValue = typeMap.get(key);
      if (existingValue != null && existingValue.specReference().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "Type with key %s and value %s is already registered", key, typeMap.get(key)));
      }
      typeMap.put(key, ProtoFieldType.builder().from(value).isRepeated(false).build());
      if (!value.isInlineOneOf()) {
        typeMap.put(
            ProtoTypeRef.builder().from(key).isRepeated(true).build(),
            ProtoFieldType.builder().from(value).isRepeated(true).build());
      }
    }
  }

  /** Returns a type from the registry. */
  public ProtoFieldType get(ProtoTypeRef key) {
    return typeMap.get(key);
  }

  /** Resolves spec references. */
  public void resolveReferences(Map<String, ProtoTypeRef> knownReferences) {
    Map<ProtoTypeRef, ProtoFieldType> updates = Maps.newHashMap();
    for (Map.Entry<ProtoTypeRef, ProtoFieldType> entry : typeMap.entrySet()) {
      ProtoFieldType type = entry.getValue();
      if (type.specReference().isPresent()) {
        ProtoTypeRef typeRef = knownReferences.get(type.specReference().get());
        if (typeRef != null) {
          ProtoFieldType resolvedType = typeMap.get(typeRef);
          if (resolvedType != null && resolvedType.specReference().isEmpty()) {
            ImmutableProtoFieldType.Builder builder =
                ProtoFieldType.builder().from(resolvedType).isRepeated(type.isRepeated());
            if (type.isAlias().isPresent()) {
              builder.isAlias(type.isAlias().get());
            }
            updates.put(entry.getKey(), builder.build());
          }
        }
      }
    }

    // Apply updates.
    typeMap.putAll(updates);
  }

  /** Verifies that there are no unresolved references in the registry. */
  public void verifyNoUnresolvedReferences() {
    List<Map.Entry<ProtoTypeRef, ProtoFieldType>> unresolvedReferences = new ArrayList<>();
    for (Map.Entry<ProtoTypeRef, ProtoFieldType> entry : typeMap.entrySet()) {
      if (entry.getValue().specReference().isPresent()) {
        unresolvedReferences.add(entry);
      }
    }

    if (!unresolvedReferences.isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Unresolved references: %s",
              unresolvedReferences.stream()
                  .map(
                      e ->
                          String.format(
                              "%s [repeated = %s]: %s",
                              e.getKey().fileOptions().get().specPath(),
                              e.getKey().isRepeated(),
                              e.getValue().specReference().get()))
                  .collect(Collectors.joining("\n"))));
    }
  }

  /**
   * OnneOf choices in Open API spec are references. The referenced tyoes may not be known until
   * after all types are loaded. So, expansion of the referenced types is done as a separate step.
   */
  public void expandOneOfs() {
    Map<ProtoTypeRef, ProtoFieldType> updates = Maps.newHashMap();
    for (Map.Entry<ProtoTypeRef, ProtoFieldType> entry : typeMap.entrySet()) {
      ProtoTypeRef typeRef = entry.getKey();
      ProtoFieldType type = entry.getValue();
      if (type.messageType().isEmpty()) {
        continue;
      }

      // One-of type.
      ProtoMessage message = type.messageType().get();
      if (message.isOneOf()) {
        ProtoMessage newMessage = expandOnfOfFields(typeRef, message);

        // Verify there are no duplicate field names as result.
        checkForDuplicateFieldNames(typeRef, message.fields(), newMessage.fields());

        updates.put(typeRef, ProtoFieldType.builder().from(type).messageType(newMessage).build());
      } else if (message.fields().stream().anyMatch(f -> f.inlineOneOf().isPresent())) {
        // Expand fields in the inline one-ofs.
        List<ProtoField> newFields = new ArrayList<>();
        List<ProtoField> allNewFields = new ArrayList<>();
        List<ProtoField> allOldFields = new ArrayList<>();
        for (ProtoField field : message.fields()) {
          if (field.inlineOneOf().isPresent()) {
            ProtoMessage oldFieldMessage = field.inlineOneOf().get();
            ProtoMessage newFieldMessage = expandOnfOfFields(typeRef, oldFieldMessage);

            allOldFields.addAll(oldFieldMessage.fields());
            allNewFields.addAll(newFieldMessage.fields());

            newFields.add(
                ProtoField.builder().from(field).inlineOneOf(Optional.of(newFieldMessage)).build());
          } else {
            newFields.add(field);
            allOldFields.add(field);
            allNewFields.add(field);
          }
        }

        checkForDuplicateFieldNames(typeRef, allOldFields, allNewFields);

        ProtoMessage newMessage = ProtoMessage.builder().from(message).fields(newFields).build();
        updates.put(typeRef, ProtoFieldType.builder().from(type).messageType(newMessage).build());
      }
    }

    typeMap.putAll(updates);
  }

  /** Adds messages and enums to corresponding files. */
  public void groupTypesByFile(Map<String, ProtoFile> files) {
    for (Map.Entry<ProtoTypeRef, ProtoFieldType> entry : typeMap.entrySet()) {
      ProtoTypeRef typeRef = entry.getKey();
      ProtoFieldType type = entry.getValue();

      // The registry holds repeated and non-repeated types, skip repeated.
      if (type.isRepeated()) {
        continue;
      }

      // Skip types w/o spec files.
      if (typeRef.fileOptions().isEmpty()) {
        continue;
      }

      // Skip alias types, for example array type.
      if (type.isAlias().isPresent() && type.isAlias().get()) {
        continue;
      }

      // Skip wrapper types.
      if (WRAPPER_TYPE_PATTERN.matcher(typeRef.typeName()).matches()) {
        continue;
      }

      // Skip generated message for last item in allof.
      if (typeRef.typeName().endsWith(Constants.ALL_OF_SUFFIX)) {
        continue;
      }

      ProtoFile protoFile = files.get(typeRef.fileOptions().get().specPath());
      if (protoFile == null) {
        throw new RuntimeException(
            String.format(
                "Proto file not found for spec: %s",
                entry.getKey().fileOptions().get().specPath()));
      }
      if (type.enumType().isPresent()) {
        protoFile.addEnum(type.enumType().get());
      } else if (type.messageType().isPresent()) {
        protoFile.addMessage(this, type.messageType().get());
      } else if (type.allOfTypeRefs().isPresent()) {
        protoFile.addAllOfMessages(
            this,
            type.allOfTypeRefs().get().stream()
                .map(ref -> get(ref).messageType().get())
                .collect(ImmutableList.toImmutableList()));
      } else {
        throw new RuntimeException(
            String.format(
                "Unexpected type %s [%s] in spec %s",
                type, entry.getKey().typeName(), entry.getKey().fileOptions().get().specPath()));
      }
    }
  }

  private void checkForDuplicateFieldNames(
      ProtoTypeRef typeRef, List<ProtoField> oldFields, List<ProtoField> newFields) {
    Set<String> fieldNames = Sets.newHashSet();
    for (int i = 0; i < newFields.size(); i++) {
      ProtoField newField = newFields.get(i);
      ProtoField oldField = oldFields.get(i);
      if (fieldNames.contains(newField.name())) {
        throw new RuntimeException(
            String.format(
                "Cannot expand field %s in oneof %s as %s is not unique",
                oldField.name(), typeRef, newField.name()));
      }
      fieldNames.add(newField.name());
    }
  }

  private ProtoMessage expandOnfOfFields(ProtoTypeRef typeRef, ProtoMessage message) {
    return ProtoMessage.builder()
        .from(message)
        .fields(
            message.fields().stream()
                .map(field -> expandOneOfField(typeRef, field))
                .collect(Collectors.toList()))
        .build();
  }

  private ProtoField expandOneOfField(ProtoTypeRef oneOfTypeRef, ProtoField field) {
    // All types are resolved by now, the field must exist at this point.
    ProtoFieldType fieldType = typeMap.get(field.typeRef());
    if (fieldType.messageType().isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Cannot expand field %s in oneof %s as it's not a message type",
              field.name(), oneOfTypeRef));
    }
    ProtoMessage fieldMessage = fieldType.messageType().get();
    if (fieldMessage.fields().size() != 1) {
      throw new RuntimeException(
          String.format(
              "Cannot expand field %s in oneof %s as it has %d fields instead of 1",
              field.name(), oneOfTypeRef, fieldMessage.fields().size()));
    }

    ProtoField subField = fieldMessage.fields().get(0);
    ImmutableProtoField.Builder builder =
        ProtoField.builder().from(field).name(subField.name()).typeRef(subField.typeRef());
    if (fieldMessage.comment().isPresent()) {
      builder.comment(fieldMessage.comment().get());
    }
    return builder.build();
  }
}
