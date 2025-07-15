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
import com.dremio.tools.openapigenerator.OpenApiSpec;
import com.dremio.tools.openapigenerator.ProtoSpecExtension;
import com.google.common.base.CaseFormat;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import joptsimple.internal.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open API specs in proto types. The model represents a set of Open API specs that may or may not
 * reference each other.
 */
public final class ProtoModel {
  private static final Logger logger = LoggerFactory.getLogger(ProtoModel.class);

  private static final ImmutableSet<String> SPEC_PRIMITIVE_TYPES =
      ImmutableSet.of("number", "integer", "string", "boolean");

  private final ImmutableMap<String, OpenApiSpec> specs;
  private final Map<String, ProtoFileOptions> fileOptions = Maps.newHashMap();
  private final ProtoTypeRegistry typeRegistry = new ProtoTypeRegistry();
  private final Map<String, ProtoTypeRef> specReferences = Maps.newHashMap();
  private final Map<String, Parameter> specParametersByReference = Maps.newHashMap();
  private final List<ProtoMethod> methods = new ArrayList<>();
  private ImmutableMap<String, ProtoFile> files;

  private ProtoModel(Collection<OpenApiSpec> specs) {
    this.specs =
        specs.stream()
            .collect(
                ImmutableMap.toImmutableMap(OpenApiSpec::getRelativePath, Functions.identity()));
  }

  /** Creates a self-consistent "proto" model that can be serialized to proto files. */
  public static ProtoModel parse(String baseDir, Set<String> relativeSpecPaths) {
    // Load all specs.
    ImmutableList<OpenApiSpec> specs = loadSpecs(baseDir, relativeSpecPaths);

    ProtoModel model = new ProtoModel(specs);

    // Parse proto extensions.
    model.populateProtoFileOptions();

    // Create types.
    model.populateTypes();

    // Verify all type references are resolved.
    model.typeRegistry.verifyNoUnresolvedReferences();

    // Expand one-ofs after all the references are resolved.
    model.typeRegistry.expandOneOfs();

    // Group types by file.
    model.makeFiles();

    return model;
  }

  /** Writes all proto files, one per input Open API spec in the same subdirectory structure. */
  public void writeProtos(String baseDir) {
    for (ProtoFile protoFile : files.values()) {
      Path path = Path.of(baseDir, protoFile.getFileOptions().getProtoFilePath());

      // Create directory.
      File file = new File(path.getParent().toString());
      if (!file.exists()) {
        file.mkdirs();
      }

      // Write the file.
      protoFile.writeFile(typeRegistry, path.toString());
      logger.info("Generated {}", path);
    }
  }

  /**
   * Writes all java interfaces, one per proto type in the same directory structure as the specs.
   */
  public void writeImmutables(String baseDir) {
    for (ProtoFile protoFile : files.values()) {
      String baseJavaDir = protoFile.getFileOptions().getBaseJavaDir(baseDir);

      // Create directory.
      File file = new File(baseJavaDir);
      if (!file.exists()) {
        file.mkdirs();
      }

      // Write the java files.
      protoFile.writeImmutables(typeRegistry, baseJavaDir);
    }
  }

  public ProtoFieldType getTypeByTypeRef(ProtoTypeRef typeRef) {
    return typeRegistry.get(typeRef);
  }

  public ProtoFileOptions getFileOptions(String relativePath) {
    ProtoFileOptions options = fileOptions.get(relativePath);
    if (options == null) {
      throw new RuntimeException(String.format("File options not found: %s", relativePath));
    }
    return options;
  }

  public ImmutableMap<String, OpenApiSpec> getSpecs() {
    return specs;
  }

  public ImmutableList<ProtoMethod> getFileMethods(String relativePath) {
    return files.get(relativePath).getMethods();
  }

  private static ImmutableList<OpenApiSpec> loadSpecs(
      String baseDir, Set<String> relativeSpecPaths) {
    ImmutableList.Builder<OpenApiSpec> builder = ImmutableList.builder();
    baseDir = PathUtils.normalizeDir(baseDir);
    for (String relativePath : relativeSpecPaths) {
      builder.add(
          OpenApiSpec.load(baseDir, PathUtils.normalizeRelativePath(baseDir, relativePath)));
    }
    return builder.build();
  }

  private void populateProtoFileOptions() {
    for (OpenApiSpec spec : specs.values()) {
      ProtoSpecExtension extension = ProtoSpecExtension.of(spec.getSpec());
      fileOptions.put(
          spec.getRelativePath(),
          ProtoFileOptions.builder()
              .specPath(spec.getRelativePath())
              .packageName(extension.getProtoPackage())
              .javaPackageName(extension.getJavaPackage())
              .javaOuterClassName(extension.getJavaOuterClass())
              .build());
    }
  }

  private void populateTypes() {
    for (OpenApiSpec spec : specs.values()) {
      OpenAPI apiSpec = spec.getSpec();

      // Components.
      if (apiSpec.getComponents() != null) {
        ProtoFileOptions options = this.fileOptions.get(spec.getRelativePath());

        // Schemas.
        if (apiSpec.getComponents().getSchemas() != null) {
          for (Map.Entry<String, Schema> entry : apiSpec.getComponents().getSchemas().entrySet()) {
            specReferences.put(
                spec.makeSchemaReference(entry.getKey()),
                getTypeRef(options, entry.getKey(), entry.getValue()));
          }
        }

        // Parameters.
        if (apiSpec.getComponents().getParameters() != null) {
          for (Map.Entry<String, Parameter> entry :
              apiSpec.getComponents().getParameters().entrySet()) {
            String specReference = spec.makeParameterReference(entry.getKey());
            specReferences.put(
                specReference, getTypeRef(options, entry.getKey(), entry.getValue().getSchema()));
            specParametersByReference.put(specReference, entry.getValue());
          }
        }

        // Request bodies.
        if (apiSpec.getComponents().getRequestBodies() != null) {
          for (Map.Entry<String, RequestBody> entry :
              apiSpec.getComponents().getRequestBodies().entrySet()) {
            String name = entry.getKey();
            RequestBody requestBody = entry.getValue();
            if (requestBody.get$ref() != null) {
              throw new UnsupportedOperationException(
                  String.format(
                      "Aliases for request bodies are not supported: %s in %s",
                      name, options.specPath()));
            }
            getContentTypeRef(requestBody.getContent(), options, name)
                .ifPresent(
                    typeRef -> specReferences.put(spec.makeRequestBodyReference(name), typeRef));
          }
        }

        // Responses.
        if (apiSpec.getComponents().getResponses() != null) {
          for (Map.Entry<String, ApiResponse> entry :
              apiSpec.getComponents().getResponses().entrySet()) {
            String name = entry.getKey();
            ApiResponse response = entry.getValue();
            if (response.get$ref() != null) {
              throw new UnsupportedOperationException(
                  String.format(
                      "Aliases for responses are not supported: %s in %s",
                      name, options.specPath()));
            }
            getContentTypeRef(response.getContent(), options, name)
                .ifPresent(
                    typeRef -> specReferences.put(spec.makeResponseReference(name), typeRef));
          }
        }
      }

      // Methods.
      populateMethods(spec);
    }

    // Resolve references.
    typeRegistry.resolveReferences(specReferences);
  }

  private void populateMethods(OpenApiSpec spec) {
    OpenAPI apiSpec = spec.getSpec();
    if (apiSpec.getPaths() == null || apiSpec.getPaths().isEmpty()) {
      return;
    }

    ProtoFileOptions options = this.fileOptions.get(spec.getRelativePath());
    int pathPrefixLen =
        apiSpec.getPaths().size() == 1
            ? 0
            : StringUtils.getCommonPrefix(apiSpec.getPaths().keySet().toArray(String[]::new))
                .length();
    Set<String> methodNames = Sets.newHashSet();
    for (Map.Entry<String, PathItem> entry : apiSpec.getPaths().entrySet()) {
      String path = entry.getKey();
      PathItem pathItem = entry.getValue();
      if (pathItem.getGet() != null) {
        addMethod(options, "GET", path, pathItem.getGet(), pathPrefixLen, methodNames);
      }
      if (pathItem.getPut() != null) {
        addMethod(options, "PUT", path, pathItem.getPut(), pathPrefixLen, methodNames);
      }
      if (pathItem.getPost() != null) {
        addMethod(options, "POST", path, pathItem.getPost(), pathPrefixLen, methodNames);
      }
      if (pathItem.getDelete() != null) {
        addMethod(options, "DELETE", path, pathItem.getDelete(), pathPrefixLen, methodNames);
      }
      if (pathItem.getPatch() != null) {
        addMethod(options, "PATCH", path, pathItem.getPatch(), pathPrefixLen, methodNames);
      }
    }
  }

  private void addMethod(
      ProtoFileOptions fileOptions,
      String httpMethod,
      String path,
      Operation operation,
      int pathPrefixLen,
      Set<String> methodNames) {
    String methodName =
        operation.getOperationId() != null
            ? makeMethodName(operation.getOperationId(), methodNames)
            : makeMethodName(pathPrefixLen, path, httpMethod, methodNames);
    ImmutableProtoMethod.Builder methodBuilder =
        ProtoMethod.builder()
            .fileOptions(fileOptions)
            .httpMethod(httpMethod)
            .path(path)
            .name(methodName);
    if (!Strings.isNullOrEmpty(operation.getDescription())) {
      methodBuilder.comment(operation.getDescription());
    }

    OpenApiSpec spec = specs.get(fileOptions.specPath());

    // Convert request body to a parameter.
    if (operation.getRequestBody() != null) {
      RequestBody requestBody = operation.getRequestBody();
      Optional<ProtoTypeRef> optionalTypeRef;
      if (requestBody.get$ref() != null) {
        optionalTypeRef =
            Optional.of(getTypeRefBySpecReference(spec.normalizeReference(requestBody.get$ref())));
      } else {
        optionalTypeRef =
            getContentTypeRef(
                requestBody.getContent(),
                fileOptions,
                methodName + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, "Request"));
      }
      if (optionalTypeRef.isPresent()) {
        ImmutableProtoMethodParameter.Builder parameterBuilder =
            ProtoMethodParameter.builder()
                .typeRef(optionalTypeRef.get())
                .name("request")
                .in("request");
        if (!Strings.isNullOrEmpty(requestBody.getDescription())) {
          parameterBuilder.comment(requestBody.getDescription());
        }
        methodBuilder.addParameters(parameterBuilder.build());
      }
    }

    // Parameters.
    if (operation.getParameters() != null) {
      for (Parameter parameter : operation.getParameters()) {
        ProtoTypeRef typeRef;
        String inParameter;
        if (parameter.get$ref() != null) {
          String parameterRef = spec.normalizeReference(parameter.get$ref());
          typeRef = getTypeRefBySpecReference(parameterRef);
          Parameter derefParameter = specParametersByReference.get(parameterRef);
          inParameter = derefParameter.getIn();
        } else if (parameter.getIn() != null) {
          inParameter = parameter.getIn();
          typeRef =
              getPrimitiveTypeRef(
                  fileOptions,
                  parameter.getName(),
                  parameter.getSchema(),
                  (a, b) -> putTypeInRegistryIfAbsent(typeRegistry::put, a, b));
        } else {
          String name = parameter.getName();
          if (Strings.isNullOrEmpty(name)) {
            throw new RuntimeException(
                String.format("Parameter w/o name in %s: %s", fileOptions.specPath(), parameter));
          }

          // Add method name to the parameter to distinguish the same parameter across methods.
          // Either it is aliased to a primitive type or a complex type (message, enum) is created.
          name = methodName + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, name);
          typeRef = getTypeRef(fileOptions, name, parameter.getSchema());
          specReferences.put(spec.makeParameterReference(name), typeRef);
          inParameter = parameter.getIn();
        }

        // 'In' field is required.
        if (Strings.isNullOrEmpty(inParameter)) {
          throw new RuntimeException(
              String.format(
                  "Parameter w/o 'in' in %s, path %s method %s: %s",
                  fileOptions.specPath(), path, httpMethod, parameter));
        }

        ImmutableProtoMethodParameter.Builder parameterBuilder =
            ProtoMethodParameter.builder()
                .typeRef(typeRef)
                .name(typeRef.typeName())
                .in(inParameter);
        if (!Strings.isNullOrEmpty(parameter.getDescription())) {
          parameterBuilder.comment(parameter.getDescription());
        }
        methodBuilder.addParameters(parameterBuilder.build());
      }
    }

    // Responses.
    if (operation.getResponses() != null) {
      for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
        int httpStatus = Integer.parseInt(entry.getKey());
        ApiResponse response = entry.getValue();
        Optional<ProtoTypeRef> optionalTypeRef;
        if (response.get$ref() != null) {
          optionalTypeRef =
              Optional.of(getTypeRefBySpecReference(spec.normalizeReference(response.get$ref())));
        } else {
          optionalTypeRef =
              getContentTypeRef(
                  response.getContent(),
                  fileOptions,
                  methodName
                      + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, "Response" + httpStatus));
        }
        if (optionalTypeRef.isPresent()) {
          ImmutableProtoMethodResponse.Builder responseBuilder =
              ProtoMethodResponse.builder().status(httpStatus).typeRef(optionalTypeRef.get());
          if (!Strings.isNullOrEmpty(response.getDescription())) {
            responseBuilder.comment(response.getDescription());
          }
          methodBuilder.addResponses(responseBuilder.build());
        }
      }
    }

    this.methods.add(methodBuilder.build());
  }

  private static String makeMethodName(String operationId, Set<String> methodNames) {
    String methodName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, operationId);
    if (methodNames.contains(methodName)) {
      throw new UnsupportedOperationException(
          String.format(
              "Duplicate method name: %s for operationId: %s. Please provide unique operationId",
              methodName, operationId));
    }

    methodNames.add(methodName);
    return methodName;
  }

  private static String makeMethodName(
      int pathPrefixLen, String path, String httpMethod, Set<String> methodNames) {
    String methodPath = path.substring(pathPrefixLen);

    // Split relative path, split by colon.
    String[] pathParts = methodPath.split("/", -1);
    List<String> pathPartsList =
        Arrays.stream(pathParts)
            .map(p -> Arrays.stream(p.split(":")))
            .flatMap(Functions.identity())
            .collect(Collectors.toList());

    // postPathPartPathPart without parameters.
    String methodName =
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, httpMethod)
            + pathPartsList.stream()
                .filter(p -> !p.startsWith("{"))
                .map(p -> CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, p))
                .collect(Collectors.joining());
    if (methodNames.contains(methodName)) {
      int suffix = 1;
      String newMethodName;
      do {
        newMethodName = methodName + suffix;
        suffix++;
      } while (methodNames.contains(newMethodName));
      methodName = newMethodName;
    }

    methodNames.add(methodName);
    return methodName;
  }

  private void makeFiles() {
    // Files by relative spec path.
    files =
        fileOptions.entrySet().stream()
            .collect(
                ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> new ProtoFile(e.getValue())));

    // Group types by file.
    typeRegistry.groupTypesByFile(files);

    // Group methods by file.
    for (ProtoMethod method : methods) {
      files.get(method.fileOptions().specPath()).addMethod(method);
    }
  }

  private Optional<ProtoTypeRef> getContentTypeRef(
      Content content, ProtoFileOptions fileOptions, String name) {
    if (content == null || content.size() != 1) {
      return Optional.empty();
    }
    Map.Entry<String, MediaType> mediaTypeEntry = content.entrySet().iterator().next();
    if (!Constants.SUPPORTED_MEDIA_TYPES.contains(mediaTypeEntry.getKey())) {
      throw new UnsupportedOperationException(
          String.format(
              "Unsupported media type (%s): %s in %s",
              mediaTypeEntry.getKey(), name, fileOptions.specPath()));
    }
    ProtoTypeRef typeRef = getTypeRef(fileOptions, name, mediaTypeEntry.getValue().getSchema());
    if (!typeRef.typeName().equals(name)) {
      // Make alias type.
      ProtoFieldType type =
          ProtoFieldType.builder().from(typeRegistry.get(typeRef)).isAlias(true).build();
      typeRef =
          ProtoTypeRef.builder().from(typeRef).typeName(name).fileOptions(fileOptions).build();
      typeRegistry.put(typeRef, type);
    }
    return Optional.of(typeRef);
  }

  private ProtoTypeRef getTypeRef(ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    if (isPrimitiveType(schema)) {
      return getPrimitiveTypeRef(fileOptions, name, schema, typeRegistry::put);
    } else if (schema.get$ref() != null) {
      return getRefTypeRef(fileOptions, name, schema);
    } else {
      return getComplexTypeRef(fileOptions, name, schema);
    }
  }

  private ProtoTypeRef getRefTypeRef(ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    Preconditions.checkNotNull(schema.get$ref(), "$ref must be non null for schema: %s", name);

    String ref = specs.get(fileOptions.specPath()).normalizeReference(schema.get$ref());
    Pair<ProtoFileOptions, String> fileAndName = parseSpecRef(ref);
    return ProtoTypeRef.builder()
        .fileOptions(fileAndName.getLeft())
        .isRepeated(false)
        .typeName(fileAndName.getRight())
        .build();
  }

  private ProtoFieldType makeArrayType(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    Schema<?> itemsSchema = schema.getItems();

    // Check that it's not array of arrays.
    String itemsType = getSchemaType(itemsSchema);
    if (itemsType != null && itemsType.contains("array")) {
      throw new RuntimeException(String.format("Nested array in %s is not supported", name));
    }

    // Find item type.
    ProtoTypeRef itemTypeRef =
        ProtoTypeRef.builder()
            .from(getTypeRef(fileOptions, null, itemsSchema))
            .isRepeated(true)
            .build();
    ProtoFieldType itemFieldType = typeRegistry.get(itemTypeRef);
    if (itemFieldType == null) {
      // Items schema must be a reference at this point. If it was not it would've been resolved
      // to a primitive type.
      if (itemsSchema.get$ref() == null) {
        throw new RuntimeException(
            String.format(
                "Array schema w/o reference cannot be resolved in %s [%s]: %s",
                fileOptions.specPath(), name, schema));
      }
      // Postpone resolution of the type.
      OpenApiSpec spec = specs.get(fileOptions.specPath());
      itemFieldType =
          ProtoFieldType.builder()
              .specReference(spec.normalizeReference(itemsSchema.get$ref()))
              .isRepeated(false)
              .build();
    }

    ImmutableProtoFieldType.Builder fieldTypeBuilder =
        ProtoFieldType.builder().from(itemFieldType).isAlias(true).isRepeated(true);

    // Constraints.
    Optional<ProtoFieldConstraints> constraints = getSchemaConstraints(schema);
    if (constraints.isPresent()) {
      fieldTypeBuilder.constraints(constraints);
    }

    return fieldTypeBuilder.build();
  }

  private ProtoMessage makeMessage(ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
      throw new RuntimeException(
          String.format(
              "Schema w/o properties [%s] in file %s: %s", name, fileOptions.specPath(), schema));
    }

    ImmutableProtoMessage.Builder builder =
        ProtoMessage.builder().fileOptions(fileOptions).isOneOf(false);
    if (Strings.isNullOrEmpty(name)) {
      if (!isOneOf(name, schema)) {
        throw new RuntimeException(
            String.format("Object schema w/o name or oneof is not supported: %s", schema));
      }
    } else {
      builder.name(name);
    }
    if (!Strings.isNullOrEmpty(schema.getDescription())) {
      builder.comment(schema.getDescription());
    }

    Set<String> requiredProperties = ImmutableSet.of();
    if (schema.getRequired() != null) {
      requiredProperties = ImmutableSet.copyOf(schema.getRequired());
    }

    OpenApiSpec spec = specs.get(fileOptions.specPath());
    int ordinal = 1;
    for (Map.Entry<String, Schema> propertyEntry : schema.getProperties().entrySet()) {
      // Field name and ordinal.
      String propertyName = propertyEntry.getKey();
      String fieldName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, propertyName);
      Schema<?> propertySchema = propertyEntry.getValue();
      ImmutableProtoField.Builder fieldBuilder =
          ProtoField.builder().name(fieldName).ordinal(ordinal);

      // Field comment.
      if (!Strings.isNullOrEmpty(propertySchema.getDescription())) {
        fieldBuilder.comment(propertySchema.getDescription());
      }

      // Optional.
      fieldBuilder.isOptional(!requiredProperties.contains(propertyName));

      // Field type.
      if (propertySchema.get$ref() != null) {
        fieldBuilder.typeRef(
            getTypeRefBySpecReference(spec.normalizeReference(propertySchema.get$ref())));
      } else {
        // Primitive types will not use the name, inline complex types will force creation
        // of a new type with below name.
        String propertyMessageName =
            name + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, propertyName);
        ProtoTypeRef propertyTypeRef = getTypeRef(fileOptions, propertyMessageName, propertySchema);
        fieldBuilder.typeRef(propertyTypeRef);
      }

      // Field constraints.
      Optional<ProtoFieldConstraints> constraints = getSchemaConstraints(propertySchema);
      if (constraints.isPresent()) {
        fieldBuilder.constraints(constraints);
      }

      builder.addFields(fieldBuilder.build());

      ordinal++;
    }

    if (schema.getDiscriminator() != null) {
      builder.discriminator(Optional.of(schema.getDiscriminator()));
    }

    return builder.build();
  }

  private ProtoMessage makeOneOf(
      ProtoFileOptions fileOptions, String name, Schema<?> schema, int ordinal) {
    Preconditions.checkArgument(isOneOf(name, schema));

    ImmutableProtoMessage.Builder builder =
        ProtoMessage.builder().fileOptions(fileOptions).isOneOf(true);
    if (!Strings.isNullOrEmpty(schema.getDescription())) {
      builder.comment(schema.getDescription());
    }

    // Inline one-ofs don't have names.
    if (!Strings.isNullOrEmpty(name)) {
      builder.name(name);
    }

    OpenApiSpec spec = specs.get(fileOptions.specPath());
    for (Schema<?> choiceSchema : schema.getOneOf()) {
      String fieldName = choiceSchema.getName();
      String choiceSchemaType = getSchemaType(choiceSchema);
      ProtoTypeRef typeRef;
      if (choiceSchema.get$ref() == null) {
        throw new RuntimeException(
            String.format("Oneof choice w/o schema ref is not supported [%s]: %s", name, schema));
      }
      String specRef = spec.normalizeReference(choiceSchema.get$ref());
      Pair<ProtoFileOptions, String> fileAndName = parseSpecRef(specRef);
      if (Strings.isNullOrEmpty(fieldName)) {
        fieldName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fileAndName.getRight());
      }
      typeRef = getTypeRefBySpecReference(specRef);

      ImmutableProtoField.Builder fieldBuilder =
          ProtoField.builder().name(fieldName).ordinal(ordinal).typeRef(typeRef).isOptional(true);

      // Field comment.
      if (!Strings.isNullOrEmpty(choiceSchema.getDescription())) {
        fieldBuilder.comment(choiceSchema.getDescription());
      }

      builder.addFields(fieldBuilder.build());

      ordinal++;
    }

    return builder.build();
  }

  private ProtoTypeRef getComplexTypeRef(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    ProtoTypeRef typeRef;
    if (isEnumOneOf(name, schema)) {
      typeRef = getEnumOneOfTypeRef(fileOptions, name, schema);
    } else if (isOneOf(name, schema)) {
      typeRef = getOneOfTypeRef(fileOptions, name, schema);
    } else if (isAllOf(name, schema)) {
      typeRef = getAllOfTypeRef(fileOptions, name, schema);
    } else {
      String type = getSchemaType(schema);
      if (type == null) {
        throw new RuntimeException(
            String.format("Schema w/o type [%s] in %s: %s", name, fileOptions.specPath(), schema));
      }
      switch (type) {
        case "array":
          if (schema.getItems() == null) {
            throw new RuntimeException(
                String.format("Null items in schema [%s]: %s", name, schema));
          }
          ProtoFieldType arrayType = makeArrayType(fileOptions, name, schema);
          ImmutableProtoTypeRef.Builder typeRefBuilder =
              ProtoTypeRef.builder().typeName(name).isRepeated(false);
          if (arrayType.builtInType().isEmpty()) {
            typeRefBuilder.fileOptions(fileOptions);
          }
          typeRef = typeRefBuilder.build();
          typeRegistry.put(typeRef, arrayType);
          break;
        case "object":
          // Recursive call w/o key can only go to a $ref or primitive type.
          if (name == null) {
            throw new RuntimeException(
                String.format("Inline object type is not supported, schema: %s", schema));
          }
          ProtoMessage message = makeMessage(fileOptions, name, schema);
          typeRef =
              ProtoTypeRef.builder()
                  .fileOptions(fileOptions)
                  .typeName(message.name().get())
                  .isRepeated(false)
                  .build();

          // Constraints.
          ImmutableProtoFieldType.Builder builder =
              ProtoFieldType.builder().messageType(message).isRepeated(false);
          Optional<ProtoFieldConstraints> constraints = getSchemaConstraints(schema);
          if (constraints.isPresent()) {
            builder.constraints(constraints);
          }

          typeRegistry.put(typeRef, builder.build());
          break;
        default:
          throw new RuntimeException(
              String.format("Unknown schema type: %s for schema [%s]: %s", type, name, schema));
      }
    }
    return typeRef;
  }

  private ProtoTypeRef getEnumOneOfTypeRef(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    ProtoEnum protoEnum = makeEnumFromOneof(fileOptions, name, schema);
    ProtoTypeRef typeRef =
        ProtoTypeRef.builder()
            .fileOptions(fileOptions)
            .typeName(protoEnum.name())
            .isRepeated(false)
            .build();
    typeRegistry.put(
        typeRef, ProtoFieldType.builder().enumType(protoEnum).isRepeated(false).build());
    return typeRef;
  }

  private ProtoTypeRef getOneOfTypeRef(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    ProtoMessage message = makeOneOf(fileOptions, name, schema, 1);
    ProtoTypeRef typeRef =
        ProtoTypeRef.builder()
            .fileOptions(fileOptions)
            .typeName(message.name().get())
            .isRepeated(false)
            .build();
    typeRegistry.put(
        typeRef, ProtoFieldType.builder().messageType(message).isRepeated(false).build());
    return typeRef;
  }

  private ProtoTypeRef getAllOfTypeRef(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    ImmutableList.Builder<ProtoTypeRef> allOfTypeRefs = ImmutableList.builder();
    for (int subSchemaIndex = 0; subSchemaIndex < schema.getAllOf().size(); subSchemaIndex++) {
      Schema<?> subSchema = schema.getAllOf().get(subSchemaIndex);
      if (subSchemaIndex + 1 == schema.getAllOf().size()) {
        if (subSchema.get$ref() != null || isPrimitiveType(subSchema)) {
          throw new RuntimeException(
              String.format("All of %s must have all but last item as $ref", name));
        }
      } else {
        if (subSchema.get$ref() == null) {
          throw new RuntimeException(
              String.format("All of %s must have all but last item as $ref", name));
        }
      }
      ProtoTypeRef subSchemaTypeRef =
          getTypeRef(fileOptions, name + Constants.ALL_OF_SUFFIX, subSchema);

      ProtoFieldType subSchemaType = typeRegistry.get(subSchemaTypeRef);
      // may be null if child declared before parent
      if (subSchemaType != null && subSchemaType.messageType().isEmpty()) {
        throw new RuntimeException(
            String.format(
                "AllOf may only be composed of objects, [%s] is not an object", subSchemaTypeRef));
      }
      if (subSchemaType != null && subSchemaType.messageType().get().isOneOf()) {
        throw new RuntimeException(
            String.format(
                "AllOf may only be composed of non-oneof objects, [%s] is a oneof",
                subSchemaTypeRef));
      }
      allOfTypeRefs.add(subSchemaTypeRef);
    }
    ProtoTypeRef typeRef =
        ProtoTypeRef.builder().fileOptions(fileOptions).typeName(name).isRepeated(false).build();
    typeRegistry.put(
        typeRef,
        ProtoFieldType.builder().allOfTypeRefs(allOfTypeRefs.build()).isRepeated(false).build());
    return typeRef;
  }

  private ProtoTypeRef getPrimitiveTypeRef(
      ProtoFileOptions fileOptions,
      String name,
      Schema<?> schema,
      BiConsumer<ProtoTypeRef, ProtoFieldType> putOperation) {
    String type = getSchemaType(schema);
    String format = schema.getFormat();
    ProtoTypeRef protoTypeRef;
    ProtoFieldType protoFieldType = null;
    switch (type) {
      case "string":
        if ("date-time".equals(format)) {
          protoTypeRef = ProtoTypeRegistry.buildWellKnownRef(Timestamp.getDescriptor(), false);
        } else if ("duration".equals(format)) {
          protoTypeRef = ProtoTypeRegistry.buildWellKnownRef(Duration.getDescriptor(), false);
        } else if (schema.getEnum() != null) {
          protoFieldType =
              ProtoFieldType.builder()
                  .enumType(makeEnum(fileOptions, name, schema))
                  .isRepeated(false)
                  .build();
          protoTypeRef =
              ProtoTypeRef.builder()
                  .fileOptions(fileOptions)
                  .typeName(name)
                  .isRepeated(false)
                  .build();
        } else {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.STRING, false);
        }
        break;
      case "number":
        if ("float".equals(format)) {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.FLOAT, false);
        } else if ("double".equals(format)) {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.DOUBLE, false);
        } else if ("int32".equals(format)) {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.INT32, false);
        } else {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.INT64, false);
        }
        break;
      case "integer":
        if ("int32".equals(format)) {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.INT32, false);
        } else {
          protoTypeRef =
              ProtoTypeRegistry.buildPrimitiveTypeRef(
                  Descriptors.FieldDescriptor.Type.INT64, false);
        }
        break;
      case "boolean":
        protoTypeRef =
            ProtoTypeRegistry.buildPrimitiveTypeRef(Descriptors.FieldDescriptor.Type.BOOL, false);
        break;
      default:
        throw new RuntimeException(
            String.format("Type %s schema %s is not primitive", name, schema));
    }
    if (name != null) {
      if (protoFieldType == null) {
        protoFieldType = typeRegistry.get(protoTypeRef);
      }

      // Built-in types can be aliased by wrapping them into a schema.
      if (protoFieldType.builtInType().isPresent() || protoFieldType.wellKnownType().isPresent()) {
        protoFieldType = ProtoFieldType.builder().from(protoFieldType).isAlias(true).build();
        protoTypeRef =
            ProtoTypeRef.builder()
                .from(protoTypeRef)
                .typeName(name)
                .fileOptions(fileOptions)
                .build();
      }

      // Constraints.
      Optional<ProtoFieldConstraints> constraints = getSchemaConstraints(schema);
      if (constraints.isPresent()) {
        protoFieldType =
            ProtoFieldType.builder().from(protoFieldType).constraints(constraints).build();
      }

      putOperation.accept(protoTypeRef, protoFieldType);
    }
    return protoTypeRef;
  }

  private void putTypeInRegistryIfAbsent(
      BiConsumer<ProtoTypeRef, ProtoFieldType> putOperation,
      ProtoTypeRef typeRef,
      ProtoFieldType fieldType) {
    if (typeRegistry.get(typeRef) == null) {
      putOperation.accept(typeRef, fieldType);
    }
  }

  private ProtoTypeRef getTypeRefBySpecReference(String specRef) {
    ProtoTypeRef typeRef = specReferences.get(specRef);
    if (typeRef == null) {
      ProtoFieldType type =
          ProtoFieldType.builder().specReference(specRef).isRepeated(false).build();
      Pair<ProtoFileOptions, String> fileAndName = parseSpecRef(specRef);
      typeRef =
          ProtoTypeRef.builder()
              .fileOptions(fileAndName.getLeft())
              .typeName(fileAndName.getRight())
              .isRepeated(false)
              .build();
      typeRegistry.put(typeRef, type);
      specReferences.put(specRef, typeRef);
    }
    return typeRef;
  }

  private Pair<ProtoFileOptions, String> parseSpecRef(String ref) {
    String[] refParts = ref.split("#");
    String refFilePath = refParts[0];
    ProtoFileOptions options = this.fileOptions.get(refFilePath);
    if (options == null) {
      throw new RuntimeException(String.format("Invalid file in reference: %s", ref));
    }

    int index = refParts[1].lastIndexOf("/");
    if (index < 0) {
      throw new RuntimeException(String.format("No slash in reference: %s", ref));
    }
    String name = refParts[1].substring(index + 1);
    return Pair.of(options, name);
  }

  private static Optional<ProtoFieldConstraints> getSchemaConstraints(Schema<?> schema) {
    if (schema == null || schema.get$ref() != null) {
      return Optional.empty();
    }

    ImmutableProtoFieldConstraints.Builder builder = ProtoFieldConstraints.builder();
    boolean hasConstraints = false;

    // Min/max.
    if (schema.getMinimum() != null) {
      builder.minimum(schema.getMinimum());
      hasConstraints = true;
    }
    if (schema.getMaximum() != null) {
      builder.maximum(schema.getMaximum());
      hasConstraints = true;
    }

    // Pattern.
    if (schema.getPattern() != null) {
      builder.pattern(schema.getPattern());
      hasConstraints = true;
    }

    // Email.
    if ("email".equals(schema.getFormat())) {
      builder.email(true);
      hasConstraints = true;
    }

    return hasConstraints ? Optional.of(builder.build()) : Optional.empty();
  }

  /** Converts {@link Schema} to proto enum {@link ProtoEnum}. */
  private static ProtoEnum makeEnum(ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    Preconditions.checkArgument(schema.getEnum() != null, "Enum must be set.");

    ImmutableProtoEnum.Builder builder = ProtoEnum.builder().name(name).fileOptions(fileOptions);

    // Comment.
    if (!Strings.isNullOrEmpty(schema.getDescription())) {
      builder.comment(schema.getDescription());
    }

    // Value comments.
    ImmutableList<String> valueDescriptions = ImmutableList.of();
    if (schema.getExtensions() != null) {
      valueDescriptions =
          ImmutableList.copyOf((List<String>) schema.getExtensions().get("x-enum-descriptions"));
      if (valueDescriptions.size() != schema.getEnum().size()) {
        throw new RuntimeException(
            String.format("Enum %s has invalid 'x-enum-descriptions'", name));
      }
    }

    String valuePrefix = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name);
    builder.addValues(
        ProtoEnumValue.builder()
            .value(String.format("%s_UNSPECIFIED", valuePrefix))
            .ordinal(0)
            .build());
    int ordinal = 1;
    for (Object enumValue : schema.getEnum()) {
      // Per agreement with product enum values are written w/o enum name prefix, despite style:
      //   https://protobuf.dev/programming-guides/proto3/
      // There is no sensible way to add/remove prefix during serialization. For example:
      //   1. allow_alias option allows for both values, this will lead to confusion in the API.
      //   2. through reflection proto FileDescriptor.pool cache can be modified
      //   3. json_field annotation is not supported on enums.
      // When JsonFormat starts supporting customizing JSON enum values, this can change too.
      // The limitation of this approach is that enum values must be unique within proto package.
      ImmutableProtoEnumValue.Builder valueBuilder =
          ProtoEnumValue.builder().value(enumValue.toString()).ordinal(ordinal);

      // Comment.
      if (ordinal - 1 < valueDescriptions.size()) {
        valueBuilder.comment(valueDescriptions.get(ordinal - 1));
      }

      builder.addValues(valueBuilder.build());

      ordinal++;
    }
    return builder.build();
  }

  /** Converts oneOf {@link Schema} to proto enum {@link ProtoEnum}. */
  private static ProtoEnum makeEnumFromOneof(
      ProtoFileOptions fileOptions, String name, Schema<?> schema) {
    Preconditions.checkArgument(isEnumOneOf(name, schema), "Must be enum oneof.");

    ImmutableProtoEnum.Builder builder = ProtoEnum.builder().name(name).fileOptions(fileOptions);

    // Comment.
    if (!Strings.isNullOrEmpty(schema.getDescription())) {
      builder.comment(schema.getDescription());
    }

    String valuePrefix = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name);
    builder.addValues(
        ProtoEnumValue.builder()
            .value(String.format("%s_UNSPECIFIED", valuePrefix))
            .ordinal(0)
            .build());
    int ordinal = 1;
    for (Schema<?> choiceSchema : schema.getOneOf()) {
      // Per agreement with product enum values are written w/o enum name prefix, despite style:
      //   https://protobuf.dev/programming-guides/proto3/
      // There is no sensible way to add/remove prefix during serialization. For example:
      //   1. allow_alias option allows for both values, this will lead to confusion in the API.
      //   2. through reflection proto FileDescriptor.pool cache can be modified
      //   3. json_field annotation is not supported on enums.
      // When JsonFormat starts supporting customizing JSON enum values, this can change too.
      // The limitation of this approach is that enum values must be unique within proto package.
      ImmutableProtoEnumValue.Builder valueBuilder =
          ProtoEnumValue.builder().value(choiceSchema.getConst().toString()).ordinal(ordinal);

      // Comment.
      if (!Strings.isNullOrEmpty(choiceSchema.getTitle())) {
        valueBuilder.comment(choiceSchema.getTitle());
      }

      builder.addValues(valueBuilder.build());

      ordinal++;
    }
    return builder.build();
  }

  private static boolean isOneOf(String name, Schema<?> schema) {
    if (schema.getOneOf() == null) {
      return false;
    }
    if (schema.getOneOf().isEmpty()) {
      throw new RuntimeException(
          String.format("Oneof schema w/o oneof choices is not supported [%s]: %s", name, schema));
    }
    return true;
  }

  private static boolean isEnumOneOf(String name, Schema<?> schema) {
    if (!isOneOf(name, schema)) {
      return false;
    }
    return schema.getOneOf().stream()
        .allMatch(s -> s.getConst() != null && s.getConst().getClass().equals(String.class));
  }

  private static boolean isAllOf(String name, Schema<?> schema) {
    if (schema.getAllOf() == null) {
      return false;
    }
    if (schema.getAllOf().isEmpty()) {
      throw new RuntimeException(
          String.format("Allof schema w/o choices is not supported [%s]: %s", name, schema));
    }
    return true;
  }

  private static boolean isPrimitiveType(Schema<?> schema) {
    return SPEC_PRIMITIVE_TYPES.contains(getSchemaType(schema));
  }

  private static String getSchemaType(Schema<?> schema) {
    String type = schema.getType();
    if (type == null) {
      Set<String> types = schema.getTypes();
      if (types != null) {
        if (types.size() != 1) {
          throw new RuntimeException(
              String.format(
                  "Single type per schema is supported option, there are %d types in %s",
                  types.size(), schema));
        }
        type = types.iterator().next();
      }
    }
    return type;
  }
}
