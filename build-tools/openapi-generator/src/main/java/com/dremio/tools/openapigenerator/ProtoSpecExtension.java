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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Open API spec extension for custom fields to be used in code generation. */
public final class ProtoSpecExtension {
  private static final String INFO_PROTO_EXTENSION_KEY = "x-dremio";
  private static final String INFO_PROTO_EXTENSION_PROTO_PACKAGE = "protoPackage";
  private static final String INFO_PROTO_EXTENSION_JAVA_PACKAGE = "javaPackage";
  private static final String INFO_PROTO_EXTENSION_JAVA_OUTER_CLASS = "javaOuterClass";
  private static final String INFO_PROTO_EXTENSION_JAVA_RESOURCE_PACKAGE = "javaResourcePackage";
  private static final String INFO_PROTO_EXTENSION_JAVA_RESOURCE_CLASS = "javaResourceClass";
  private static final String INFO_PROTO_EXTENSION_SECURED = "secured";
  private static final String INFO_PROTO_EXTENSION_ROLES_ALLOWED = "rolesAllowed";

  private final String javaOuterClass;
  private final String javaPackage;
  @Nullable private final String javaResourcePackage;
  @Nullable private final String javaResourceClass;
  private final String protoPackage;
  private final boolean secured;
  private final ImmutableSet<String> rolesAllowed;

  private ProtoSpecExtension(
      String javaOuterClass,
      String javaPackage,
      @Nullable String javaResourcePackage,
      @Nullable String javaResourceClass,
      String protoPackage,
      boolean secured,
      ImmutableSet<String> rolesAllowed) {
    this.javaOuterClass = javaOuterClass;
    this.javaPackage = javaPackage;
    this.javaResourcePackage = javaResourcePackage;
    this.javaResourceClass = javaResourceClass;
    this.protoPackage = protoPackage;
    this.secured = secured;
    this.rolesAllowed = rolesAllowed;
  }

  public String getJavaOuterClass() {
    return javaOuterClass;
  }

  public String getJavaPackage() {
    return javaPackage;
  }

  @Nullable
  public String getJavaResourcePackage() {
    return javaResourcePackage;
  }

  @Nullable
  public String getJavaResourceClass() {
    return javaResourceClass;
  }

  public String getProtoPackage() {
    return protoPackage;
  }

  public boolean getSecured() {
    return secured;
  }

  public ImmutableSet<String> getRolesAllowed() {
    return rolesAllowed;
  }

  public static ProtoSpecExtension of(OpenAPI openApiModel) {
    Map<String, Object> protoExtension = ImmutableMap.of();
    if (openApiModel.getInfo() != null && openApiModel.getInfo().getExtensions() != null) {
      Object protoExtensionObject =
          openApiModel.getInfo().getExtensions().get(INFO_PROTO_EXTENSION_KEY);
      if (!(protoExtensionObject instanceof Map)) {
        throw new RuntimeException(
            String.format("%s is expected to be a map", INFO_PROTO_EXTENSION_KEY));
      }
      protoExtension =
          (Map<String, Object>)
              openApiModel.getInfo().getExtensions().get(INFO_PROTO_EXTENSION_KEY);
    }
    return new ProtoSpecExtension(
        getString(protoExtension, INFO_PROTO_EXTENSION_JAVA_OUTER_CLASS),
        getString(protoExtension, INFO_PROTO_EXTENSION_JAVA_PACKAGE),
        getOptionalString(protoExtension, INFO_PROTO_EXTENSION_JAVA_RESOURCE_PACKAGE, null),
        getOptionalString(protoExtension, INFO_PROTO_EXTENSION_JAVA_RESOURCE_CLASS, null),
        getString(protoExtension, INFO_PROTO_EXTENSION_PROTO_PACKAGE),
        getOptionalBoolean(protoExtension, INFO_PROTO_EXTENSION_SECURED, true),
        getOptionalSet(protoExtension, INFO_PROTO_EXTENSION_ROLES_ALLOWED));
  }

  private static String getString(Map<String, Object> protoExtension, String key) {
    Object value = protoExtension.get(key);
    if (value == null) {
      throw new RuntimeException(
          String.format("Field %s must be set in %s under info", key, INFO_PROTO_EXTENSION_KEY));
    }
    if (!(value instanceof String)) {
      throw new RuntimeException(String.format("Field %s is not of type String", key));
    }
    return (String) value;
  }

  private static String getOptionalString(
      Map<String, Object> protoExtension, String key, String defaultValue) {
    Object value = protoExtension.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof String)) {
      throw new RuntimeException(String.format("Field %s is not of type String", key));
    }
    return (String) value;
  }

  private static boolean getOptionalBoolean(
      Map<String, Object> protoExtension, String key, boolean defaultValue) {
    Object value = protoExtension.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean)) {
      throw new RuntimeException(String.format("Field %s is not of type Boolean", key));
    }
    return (Boolean) value;
  }

  private static ImmutableSet<String> getOptionalSet(
      Map<String, Object> protoExtension, String key) {
    Object value = protoExtension.get(key);
    if (value == null) {
      return ImmutableSet.of();
    }
    if (value instanceof Map) {
      return ImmutableSet.copyOf(((Map<String, ?>) value).keySet());
    } else if (value instanceof List) {
      return ImmutableSet.copyOf((List<String>) value);
    } else {
      throw new RuntimeException(
          String.format(
              "Field %s in %s must be a Map or List, it is %s",
              key, INFO_PROTO_EXTENSION_KEY, value.getClass()));
    }
  }
}
