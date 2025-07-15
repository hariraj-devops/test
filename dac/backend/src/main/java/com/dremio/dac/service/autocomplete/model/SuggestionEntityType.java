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
package com.dremio.dac.service.autocomplete.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * Make sure these types are not conflict with ArrowType which is used for Dataset Field Types
 * https://arrow.apache.org/docs/java/reference/org/apache/arrow/vector/types/pojo/ArrowType.html
 */
public enum SuggestionEntityType {
  // Container types
  FOLDER("folder"),
  HOME("home"),
  SOURCE("source"),
  SPACE("space"),
  FUNCTION("function"),

  FILE("file"),

  // Dataset types
  VIRTUAL("virtual"),
  PROMOTED("promoted"),
  DIRECT("direct"),

  // Versioned Source types
  BRANCH("branch"),
  COMMIT("commit"),
  TAG("tag");

  private String type;

  private SuggestionEntityType(String type) {
    this.type = type;
  }

  @JsonCreator
  public static SuggestionEntityType fromString(String type) {
    for (SuggestionEntityType entityType : values()) {
      if (entityType.type.equalsIgnoreCase(type)) {
        return entityType;
      }
    }
    throw new IllegalArgumentException(
        String.format(
            "Unknown enum type %s. Allowed values are %s.", type, Arrays.toString(values())));
  }

  @JsonValue
  public String getType() {
    return type;
  }
}
