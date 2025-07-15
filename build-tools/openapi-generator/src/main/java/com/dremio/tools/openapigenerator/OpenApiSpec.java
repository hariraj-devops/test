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
package com.dremio.tools.openapigenerator;

import com.dremio.tools.openapigenerator.proto.PathUtils;
import com.google.common.collect.ImmutableList;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.file.Path;
import joptsimple.internal.Strings;

/** Holds parsed Open API spec with spec file path. */
public final class OpenApiSpec {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(OpenApiSpec.class);

  private final OpenAPI spec;
  private final String baseDir;
  private final String relativePath;

  private OpenApiSpec(OpenAPI spec, String baseDir, String relativePath) {
    this.spec = spec;
    this.baseDir = baseDir;
    this.relativePath = relativePath;
  }

  /** Parsed spec. */
  public OpenAPI getSpec() {
    return spec;
  }

  /** Path spec defined under "servers", it is used to map REST resources to respective servlets. */
  public String getServerPathSpec() {
    if (spec.getServers() == null || spec.getServers().size() != 1) {
      throw new RuntimeException(
          String.format(
              "'servers' section in %s is not defined or server is not singular: %s",
              relativePath,
              spec.getServers() == null
                  ? "null"
                  : ImmutableList.copyOf( // TODO: add octet-stream media type.
                      spec.getServers())));
    }
    String url = spec.getServers().get(0).getUrl();
    if (url == null) {
      throw new RuntimeException(
          String.format("url is undefined in 'servers' section of %s", relativePath));
    }
    if (url.endsWith("/")) {
      throw new RuntimeException(
          String.format("url '%s' in 'servers' of %s ends with slash", url, relativePath));
    }
    return url + "/*";
  }

  /** Relative path to spec file. */
  public String getRelativePath() {
    return relativePath;
  }

  /** Makes Open API spec reference for a schema. */
  public String makeSchemaReference(String name) {
    return String.format("%s#/components/schemas/%s", relativePath, name);
  }

  /** Makes Open API spec reference for a parameter. */
  public String makeParameterReference(String name) {
    return String.format("%s#/components/parameters/%s", relativePath, name);
  }

  /** Makes Open API spec reference for a request body. */
  public String makeRequestBodyReference(String name) {
    return String.format("%s#/components/requestBodies/%s", relativePath, name);
  }

  /** Makes Open API spec reference for a response. */
  public String makeResponseReference(String name) {
    return String.format("%s#/components/responses/%s", relativePath, name);
  }

  /**
   * Normalized reference includes normalized file path. References within the same file are
   * converted to references with file path.
   */
  public String normalizeReference(String reference) {
    String[] parts = reference.split("#");
    if (parts.length != 2) {
      throw new RuntimeException(String.format("Invalid spec reference: %s", reference));
    }
    String filePath = parts[0];
    reference = parts[1];
    if (!Strings.isNullOrEmpty(filePath)) {
      filePath = PathUtils.normalizeRelativePath(baseDir, filePath);
    } else {
      filePath = relativePath;
    }
    return filePath + "#" + reference;
  }

  public String getLocalReferenceName(String reference) {
    String[] parts = reference.split("#");
    if (parts.length != 2) {
      throw new RuntimeException(String.format("Invalid spec reference: %s", reference));
    }
    String filePath = parts[0];
    if (!Strings.isNullOrEmpty(filePath)) {
      throw new RuntimeException(String.format("Non local reference: %s", reference));
    }
    int slashIndex = reference.lastIndexOf('/');
    if (slashIndex < 0) {
      throw new RuntimeException(String.format("Invalid reference: %s", reference));
    }
    return reference.substring(slashIndex + 1);
  }

  /** Loads open API spec. */
  public static OpenApiSpec load(String baseDir, String relativePath) {
    ParseOptions parseOptions = new ParseOptions();
    parseOptions.setResolve(false);
    String filePath = Path.of(baseDir, relativePath).toString();
    try {
      SwaggerParseResult parseResult =
          new OpenAPIV3Parser().readLocation(filePath, null, parseOptions);
      if (parseResult.getMessages() != null && !parseResult.getMessages().isEmpty()) {
        for (String message : parseResult.getMessages()) {
          logger.error("Open API spec parsing [{}]: {}", relativePath, message);
        }
        throw new RuntimeException(
            String.format(
                "Failed while reading: %s with swagger messages:\n%s",
                filePath, ImmutableList.copyOf(parseResult.getMessages())));
      }
      return new OpenApiSpec(parseResult.getOpenAPI(), baseDir, relativePath);
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed while reading: %s", filePath), e);
    }
  }
}
