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

import static com.dremio.service.embedded.catalog.EmbeddedContent.Type.ICEBERG_TABLE;
import static com.dremio.service.embedded.catalog.EmbeddedContent.Type.NAMESPACE;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.ImmutableDocument;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreProvider;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Manages the Metadata Pointer KV store for the embedded (internal) Iceberg tables. */
public class EmbeddedPointerStore {

  private static final String ID_VALUE_SEPARATOR = ":";

  private final KVStore<String, String> store;

  public EmbeddedPointerStore(KVStoreProvider storeProvider) {
    this.store = storeProvider.getStore(EmbeddedPointerStoreBuilder.class);
  }

  public void delete(EmbeddedContentKey key) {
    store.delete(key.toPathString());
  }

  public EmbeddedContent get(EmbeddedContentKey key) {
    Document<String, String> doc = store.get(key.toPathString());
    return toContent(doc);
  }

  public void put(EmbeddedContentKey key, EmbeddedContent content) {
    String id;
    String loc = content.location();
    if (content.type() == ICEBERG_TABLE) {
      id = UUID.randomUUID().toString(); // new ID for every commit
    } else if (content.type() == NAMESPACE) {
      id = asNamespaceId(key);
    } else {
      throw new IllegalArgumentException("Unsupported content type: " + content.type());
    }

    put(key, id, loc);
  }

  private void put(EmbeddedContentKey key, String id, String metadataLocation) {
    store.put(key.toPathString(), id + ID_VALUE_SEPARATOR + metadataLocation);
  }

  public Stream<Document<EmbeddedContentKey, EmbeddedContent>> findAll() {
    return StreamSupport.stream(store.find().spliterator(), false)
        .map(this::toContentDoc)
        .filter(Objects::nonNull);
  }

  private Document<EmbeddedContentKey, EmbeddedContent> toContentDoc(Document<String, String> doc) {
    EmbeddedContent content = toContent(doc);
    if (content == null) {
      return null;
    }

    EmbeddedContentKey key = EmbeddedContentKey.fromPathString(doc.getKey());
    return new ImmutableDocument.Builder<EmbeddedContentKey, EmbeddedContent>()
        .setKey(key)
        .setValue(content)
        .build();
  }

  private EmbeddedContent toContent(Document<String, String> doc) {
    if (doc == null) {
      return null;
    }

    EmbeddedContentKey key = EmbeddedContentKey.fromPathString(doc.getKey());
    String value = doc.getValue();
    int idx = value.indexOf(ID_VALUE_SEPARATOR);
    if (idx <= 0) {
      throw new IllegalStateException("Unsupported value format: " + value);
    }

    String id = value.substring(0, idx);
    String metadataLocation = value.substring(idx + 1);
    return EmbeddedContent.fromLocation(metadataLocation, key, id);
  }

  public static String asNamespaceId(EmbeddedContentKey key) {
    return UUID.nameUUIDFromBytes(key.toPathString().getBytes(StandardCharsets.UTF_8)).toString();
  }
}
