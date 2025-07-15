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

import com.dremio.tools.openapigenerator.Header;
import com.dremio.tools.openapigenerator.OpenApiSpec;
import com.dremio.tools.openapigenerator.ProtoSpecExtension;
import com.dremio.tools.openapigenerator.proto.ProtoFieldType;
import com.dremio.tools.openapigenerator.proto.ProtoFileOptions;
import com.dremio.tools.openapigenerator.proto.ProtoMethod;
import com.dremio.tools.openapigenerator.proto.ProtoMethodParameter;
import com.dremio.tools.openapigenerator.proto.ProtoMethodResponse;
import com.dremio.tools.openapigenerator.proto.ProtoModel;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.ws.rs.core.Response;
import joptsimple.internal.Strings;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Generates a class with method stubs, one per operation in Open API spec. */
public final class JavaRestResourceGenerator {
  private static final Logger logger = LoggerFactory.getLogger(JavaRestResourceGenerator.class);

  private static final ImmutableMap<Descriptors.FieldDescriptor.Type, String>
      PROTO_PRIMITIVE_TYPES =
          ImmutableMap.of(
              Descriptors.FieldDescriptor.Type.STRING, String.class.getSimpleName(),
              Descriptors.FieldDescriptor.Type.INT32, Integer.class.getSimpleName(),
              Descriptors.FieldDescriptor.Type.INT64, Long.class.getSimpleName(),
              Descriptors.FieldDescriptor.Type.FLOAT, Float.class.getSimpleName(),
              Descriptors.FieldDescriptor.Type.DOUBLE, Double.class.getSimpleName(),
              Descriptors.FieldDescriptor.Type.BOOL, Boolean.class.getSimpleName());
  private final Clock clock;
  private final ProtoModel protoModel;
  private final boolean importProtoOrImmutables;
  private final ProtoSpecExtension protoSpecExtension;

  private final ImmutableList<ProtoMethod> methods;
  private final String serverPathSpec;

  private JavaRestResourceGenerator(
      Clock clock,
      OpenApiSpec openApiSpec,
      ProtoModel protoModel,
      boolean importProtoOrImmutables) {
    this.clock = clock;
    this.protoModel = protoModel;
    this.importProtoOrImmutables = importProtoOrImmutables;
    this.protoSpecExtension = ProtoSpecExtension.of(openApiSpec.getSpec());
    this.methods = protoModel.getFileMethods(openApiSpec.getRelativePath());
    this.serverPathSpec = this.methods.isEmpty() ? null : openApiSpec.getServerPathSpec();
  }

  /** Generates Java classes and interfaces for all Open API specs. */
  public static void generateJavaClasses(
      Clock clock,
      String interfaceOutputDir,
      String implOutputDir,
      ProtoModel protoModel,
      boolean importProtoOrImmutables) {
    for (Map.Entry<String, OpenApiSpec> entry : protoModel.getSpecs().entrySet()) {
      OpenApiSpec openApiSpec = entry.getValue();
      ProtoSpecExtension protoSpecExtension = ProtoSpecExtension.of(openApiSpec.getSpec());
      if (protoSpecExtension.getJavaResourceClass() != null) {
        if (protoSpecExtension.getJavaResourcePackage() == null) {
          throw new RuntimeException(
              String.format("javaResourcePackage is not set in %s", openApiSpec.getRelativePath()));
        }

        // Generate interface and initial implementation.
        JavaRestResourceGenerator generator =
            new JavaRestResourceGenerator(clock, openApiSpec, protoModel, importProtoOrImmutables);
        Path pathToInterfaceJavaFile =
            getJavaFilePath(
                interfaceOutputDir,
                protoSpecExtension.getJavaResourcePackage(),
                protoSpecExtension.getJavaResourceClass() + ".java");
        mkdirs(pathToInterfaceJavaFile);
        generator.generate(pathToInterfaceJavaFile, true, true);
        logger.info("Generated Interface {}", pathToInterfaceJavaFile);

        Path pathToImplJavaFile =
            getJavaFilePath(
                implOutputDir,
                protoSpecExtension.getJavaResourcePackage(),
                protoSpecExtension.getJavaResourceClass() + ".java");
        mkdirs(pathToImplJavaFile);
        generator.generate(pathToImplJavaFile, false, false);
        logger.info("Generated Impl {}", pathToImplJavaFile);
      }
    }
  }

  private static void mkdirs(Path filePath) {
    File file = new File(filePath.getParent().toString());
    if (!file.exists()) {
      file.mkdirs();
    }
  }

  private static Path getJavaFilePath(String outputDir, String packageName, String javaFileName) {
    return Path.of(
        outputDir, packageName.replace(".", FileSystems.getDefault().getSeparator()), javaFileName);
  }

  private void generate(
      Path pathToJavaFile, boolean generateInterfaceOrImpl, boolean addJaxRsAnnotations) {
    if (methods.isEmpty()) {
      // No methods - no class.
      return;
    }

    Set<String> imports = new TreeSet<>();
    List<String> lines = new ArrayList<>();
    String commonPath = "";

    // Class annotations.
    if (!generateInterfaceOrImpl) {
      imports.add("javax.ws.rs.WebApplicationException");
      imports.add("com.dremio.dac.annotations.OpenApiResource");
      imports.add("com.dremio.dac.api.ErrorResponseConverter");
      lines.add(String.format("@OpenApiResource(serverPathSpec = \"%s\")", serverPathSpec));
    }
    if (addJaxRsAnnotations) {
      imports.add("javax.ws.rs.Consumes");
      imports.add("javax.ws.rs.Path");
      imports.add("javax.ws.rs.Produces");
      imports.add("static javax.ws.rs.core.MediaType.APPLICATION_JSON");
      if (protoSpecExtension.getSecured()) {
        imports.add("com.dremio.dac.annotations.Secured");
        lines.add("@Secured");
      }
      if (!protoSpecExtension.getRolesAllowed().isEmpty()) {
        imports.add("javax.annotation.security.RolesAllowed");
        lines.add(
            String.format(
                "@RolesAllowed({%s})",
                protoSpecExtension.getRolesAllowed().stream()
                    .map(v -> String.format("\"%s\"", v))
                    .collect(Collectors.joining(", "))));
      }
      lines.add("@Consumes(APPLICATION_JSON)");
      lines.add("@Produces(APPLICATION_JSON)");
      // Path on the class is required, there is no recommendation from Java docs if root has any
      // performance
      // implications. There could be conflicts between resource classes, that is out of scope for
      // this class.
      commonPath = resolveCommonPath();
      if (commonPath.isEmpty()) {
        lines.add("@Path(\"/\")");
      } else {
        lines.add(String.format("@Path(\"%s\")", commonPath));
      }
    }
    if (generateInterfaceOrImpl) {
      imports.add("javax.annotation.Generated");
      // Add @Generated for interface only.
      lines.add(
          String.format(
              "@Generated(value = { \"%s\" }, date = \"%s\")",
              getClass().getName(), clock.instant()));
    }
    String className =
        makeClassNameFromFileName(pathToJavaFile.toString(), generateInterfaceOrImpl);
    if (generateInterfaceOrImpl) {
      lines.add(String.format("public interface %s {", className));
    } else {
      String interfaceName = makeClassNameFromFileName(pathToJavaFile.toString(), true);
      lines.add(String.format("public class %s implements %s {", className, interfaceName));
    }

    // Methods.
    for (ProtoMethod protoMethod : methods) {
      generateMethod(
          protoMethod, imports, lines, generateInterfaceOrImpl, addJaxRsAnnotations, commonPath);
    }
    // Close class/interface.
    lines.add("}");

    // Write out the file.
    String javaResourcePackage = protoSpecExtension.getJavaResourcePackage();
    if (javaResourcePackage == null) {
      throw new RuntimeException(
          String.format("Java resource package must be specified for %s", pathToJavaFile));
    }
    writeJavaFile(
        java.nio.file.Paths.get(
            pathToJavaFile.getParent().toString(),
            // Do not add .java extension to the implementation not to compile it.
            String.format("%s%s", className, generateInterfaceOrImpl ? ".java" : "")),
        javaResourcePackage,
        lines,
        imports);
  }

  private String resolveCommonPath() {
    List<String> paths = methods.stream().map(ProtoMethod::path).collect(Collectors.toList());

    if (paths.isEmpty()) {
      return "";
    }

    String prefix = paths.get(0);
    for (int i = 1; i < paths.size(); i++) {
      // Iterate until
      String currentPath = paths.get(i);
      while (!currentPath.startsWith(prefix)
          || currentPath.length() > prefix.length() && currentPath.charAt(prefix.length()) != '/') {
        prefix = prefix.substring(0, prefix.length() - 1);
        if (prefix.isEmpty()) {
          return "";
        }
      }
    }

    if (prefix.endsWith("/")) {
      prefix = prefix.substring(0, prefix.length() - 1);
    }

    return prefix;
  }

  private void generateMethod(
      ProtoMethod protoMethod,
      Set<String> imports,
      List<String> lines,
      boolean generateInterfaceOrImpl,
      boolean addJaxRsAnnotations,
      String commonPath) {
    // Line between methods.
    lines.add("");

    // Comment on interface.
    if (generateInterfaceOrImpl) {
      generateMethodComment(lines, protoMethod);
    }

    if (addJaxRsAnnotations) {
      lines.add(String.format("  @%s", protoMethod.httpMethod()));
      imports.add(String.format("javax.ws.rs.%s", protoMethod.httpMethod()));
      String subPath = protoMethod.path().replace(commonPath, "");
      if (!subPath.isEmpty()) {
        lines.add(String.format("  @Path(\"%s\")", subPath));
      }
      imports.add("javax.ws.rs.Path");
    }

    // Annotations on implementation.
    if (!generateInterfaceOrImpl) {
      lines.add("  @Override");
    }

    // Method signature.
    String methodName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, protoMethod.name());
    List<ProtoMethodResponse> okResponses =
        protoMethod.responses().stream()
            .filter(r -> isOkStatus(r.status()))
            .collect(Collectors.toList());
    if (okResponses.size() > 1) {
      throw new RuntimeException(
          String.format(
              "Path %s for method %s has more than one ok response."
                  + " This could be intentional for the API,\n"
                  + " if so this generator can be changed to return Java RS Response instead of throwing.\n"
                  + "Please consider carefully.",
              protoMethod.path(), protoMethod.httpMethod()));
    }
    if (!okResponses.isEmpty()) {
      ProtoMethodResponse response = okResponses.get(0);
      ProtoFieldType returnProtoType = protoModel.getTypeByTypeRef(response.typeRef());
      if (returnProtoType == null) {
        throw new RuntimeException(
            String.format(
                "Cannot find response type for path %s method %s ref %s",
                protoMethod.path(), protoMethod.httpMethod(), response.typeRef()));
      }
      lines.add(
          String.format(
              "  %s%s %s(",
              generateInterfaceOrImpl ? "" : "public ",
              convertProtoTypeToJavaType(protoMethod, returnProtoType, imports, false),
              methodName));
    } else {
      // Response w/o schema - void method.
      lines.add(
          String.format("  %svoid %s(", generateInterfaceOrImpl ? "" : "public ", methodName));
    }

    // Method parameters.
    for (int parameterIndex = 0;
        parameterIndex < protoMethod.parameters().size();
        parameterIndex++) {
      ProtoMethodParameter parameter = protoMethod.parameters().get(parameterIndex);
      ProtoFieldType parameterTypeInfo = protoModel.getTypeByTypeRef(parameter.typeRef());
      String name = parameter.name();
      String inParam = parameter.in();
      String parameterType =
          convertProtoTypeToJavaType(protoMethod, parameterTypeInfo, imports, true);
      String line;
      switch (inParam) {
        case "path":
          if (!protoMethod.path().contains(String.format("{%s}", name))) {
            throw new RuntimeException(
                String.format(
                    "Parameter %s is not found in path %s for %s",
                    name, protoMethod.path(), protoMethod.httpMethod()));
          }
          if (addJaxRsAnnotations) {
            line = String.format("    @PathParam(\"%s\") %s %s", name, parameterType, name);
            imports.add("javax.ws.rs.PathParam");
          } else {
            line = String.format("    %s %s", parameterType, name);
          }
          break;
        case "query":
          if (addJaxRsAnnotations) {
            line = String.format("    @QueryParam(\"%s\") %s %s", name, parameterType, name);
            imports.add("javax.ws.rs.QueryParam");
          } else {
            line = String.format("    %s %s", parameterType, name);
          }
          break;
        case "request":
          line = String.format("    %s %s", parameterType, name);
          break;
        default:
          throw new RuntimeException(
              String.format(
                  "Unexpected in %s in path %s parameter %s", inParam, protoMethod.path(), name));
      }
      if (parameterIndex + 1 < protoMethod.parameters().size()) {
        line += ",";
      }
      lines.add(line);
    }

    // Close the method signature.
    int lastIndex = lines.size() - 1;
    String line = lines.get(lastIndex);
    // Close method signature.
    if (generateInterfaceOrImpl) {
      line += ");";
    } else {
      line += ") {";
    }
    lines.set(lastIndex, line);

    if (!generateInterfaceOrImpl) {
      lines.add("    throw new WebApplicationException(");
      lines.add(
          String.format(
              "        ErrorResponseConverter.notImplemented(\"%s\"));", protoMethod.path()));

      // Close the method.
      lines.add("  }");
    }
  }

  private static void generateMethodComment(List<String> lines, ProtoMethod protoMethod) {
    // TODO: convert mark-down to html, remove <br>.
    boolean parameterComments =
        protoMethod.parameters().stream().anyMatch(p -> p.comment().isPresent());
    boolean methodComment = protoMethod.comment().isPresent();
    Optional<ProtoMethodResponse> okResponse =
        protoMethod.responses().stream().filter(r -> r.status() == HttpStatus.SC_OK).findFirst();
    boolean responseComment = okResponse.isPresent() && okResponse.get().comment().isPresent();
    if (methodComment || parameterComments || responseComment) {
      lines.add("  /**");
      if (methodComment) {
        for (String line : protoMethod.comment().get().split("\n")) {
          lines.add(String.format("   * %s", line));
        }
      }
      if (parameterComments || responseComment) {
        lines.add("   *");
      }
      if (parameterComments) {
        for (ProtoMethodParameter parameter : protoMethod.parameters()) {
          if (parameter.comment().isPresent()) {
            String[] commentLines = parameter.comment().get().split("\n");
            lines.add(String.format("   *   @param %s %s", parameter.name(), commentLines[0]));
            if (commentLines.length > 1) {
              for (int i = 1; i < commentLines.length; i++) {
                String commentLine = commentLines[i].trim();
                if (Strings.isNullOrEmpty(commentLine)) {
                  lines.add("   * <br>");
                } else {
                  lines.add(String.format("   *     %s", commentLine));
                }
              }
            }
          }
        }
        if (responseComment) {
          String[] commentLines = okResponse.get().comment().get().split("\n");
          lines.add(String.format("   *   @return %s", commentLines[0]));
          if (commentLines.length > 1) {
            for (int i = 1; i < commentLines.length; i++) {
              String commentLine = commentLines[i].trim();
              if (Strings.isNullOrEmpty(commentLine)) {
                lines.add("   * <br>");
              } else {
                lines.add(String.format("   *     %s", commentLine));
              }
            }
          }
        }
      }
      lines.add("  */");
    }
  }

  private String convertProtoTypeToJavaType(
      ProtoMethod protoMethod, ProtoFieldType protoType, Set<String> imports, boolean isParameter) {
    String type;
    // Primitive type.
    if (protoType.builtInType().isPresent()) {
      type = PROTO_PRIMITIVE_TYPES.get(protoType.builtInType().get());
      if (type == null) {
        throw new RuntimeException(
            String.format("Failed to map proto type: %s", protoType.builtInType().get()));
      }
    } else if (protoType.wellKnownType().isPresent()) {
      // For example: com.google.protobuf.Timestamp
      String javaPackage = protoType.wellKnownType().get().getFile().getOptions().getJavaPackage();
      type = protoType.wellKnownType().get().getName();
      imports.add(String.format("%s.%s", javaPackage, type));
    } else {
      ProtoFileOptions options;
      if (protoType.messageType().isPresent()) {
        options = protoType.messageType().get().fileOptions();
        type = protoType.messageType().get().name().get();
      } else if (protoType.enumType().isPresent()) {
        options = protoType.enumType().get().fileOptions();
        type = protoType.enumType().get().name();
      } else {
        throw new RuntimeException(String.format("Unexpected proto type: %s", protoType));
      }
      if (importProtoOrImmutables) {
        // Import outer class and use it.
        imports.add(
            String.format("%s.%s", options.javaPackageName(), options.javaOuterClassName()));
        type = String.format("%s.%s", options.javaOuterClassName(), type);
      } else {
        // Import class/interface/enum.
        if (!options.javaPackageName().equals(this.protoSpecExtension.getJavaResourcePackage())) {
          imports.add(String.format("%s.%s", options.javaPackageName(), type));
        }
      }
    }

    if (protoType.isRepeated()) {
      if (isParameter) {
        throw new RuntimeException(
            String.format(
                "Repeated fields are not supported"
                    + " as parameters (please wrap them to a schema): %s in %s",
                protoType, protoMethod));
      }

      // Repeated return type is treated as a stream, it is up to the implementation to
      // implement streaming.
      imports.add(Response.class.getName());
      type = Response.class.getSimpleName();
    }

    return type;
  }

  private static String makeClassNameFromFileName(String name, boolean generateInterfaceOrClass) {
    name = Path.of(name).getFileName().toString();
    int extensionIndex = name.lastIndexOf('.');
    if (extensionIndex >= 0) {
      name = name.substring(0, extensionIndex);
    }
    if (!generateInterfaceOrClass) {
      name += "Impl";
    }
    return name;
  }

  private static void writeJavaFile(
      Path javaFilePath, String packageName, List<String> lines, Set<String> imports) {
    try (BufferedWriter writer = Files.newBufferedWriter(javaFilePath)) {
      // License header.
      Header.write(writer);

      // Package.
      writer.write(String.format("package %s;", packageName));
      writer.newLine();
      writer.newLine();

      // Imports.
      writeImports(writer, imports);

      // Class with methods.
      for (String line : lines) {
        writer.write(line);
        writer.newLine();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void writeImports(BufferedWriter writer, Set<String> imports) throws IOException {
    // Write static imports if any.
    boolean statics = false;
    for (String importSymbol : imports) {
      if (importSymbol.startsWith("static ")) {
        writer.write(String.format("import %s;", importSymbol));
        writer.newLine();
        statics = true;
      }
    }
    if (statics) {
      writer.newLine();
    }

    // Write all the other imports.
    boolean nonStatics = false;
    for (String importSymbol : imports) {
      if (!importSymbol.startsWith("static ")) {
        writer.write(String.format("import %s;", importSymbol));
        writer.newLine();
        nonStatics = true;
      }
    }
    if (nonStatics) {
      writer.newLine();
    }
  }

  private static boolean isOkStatus(int httpStatus) {
    return 200 <= httpStatus && httpStatus < 300;
  }
}
