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
package com.dremio.service.embedded.catalog;

import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
public interface EmbeddedContent {
  String NAMESPACE_LOCATION = "<namespace>";

  String id();

  Type type();

  String location();

  @Nullable
  EmbeddedContentKey key();

  static EmbeddedContent table(String location, String id) {
    return new ImmutableEmbeddedContent.Builder()
        .setId(id)
        .setType(Type.ICEBERG_TABLE)
        .setLocation(location)
        .build();
  }

  static EmbeddedContent namespace(EmbeddedContentKey namespace, String id) {
    return new ImmutableEmbeddedContent.Builder()
        .setKey(namespace)
        .setId(id)
        .setType(Type.NAMESPACE)
        .setLocation(NAMESPACE_LOCATION)
        .build();
  }

  static EmbeddedContent fromLocation(String metadataLocation, EmbeddedContentKey key, String id) {
    if (NAMESPACE_LOCATION.equals(metadataLocation)) {
      return EmbeddedContent.namespace(key, id);
    } else {
      return EmbeddedContent.table(metadataLocation, id);
    }
  }

  enum Type {
    NAMESPACE,
    ICEBERG_TABLE,
  }
}
