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
package com.dremio.service.namespace;

import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.DATASET;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.FOLDER;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.FUNCTION;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.HOME;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.SOURCE;
import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.SPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.IndexedStore;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import com.dremio.test.DremioTest;
import com.google.common.collect.Sets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestNamespaceStore {
  private static final NameSpaceContainerVersionExtractor CONTAINER_VERSION_EXTRACTOR =
      new NameSpaceContainerVersionExtractor();
  private KVStoreProvider kvStoreProvider;

  @BeforeEach
  public void setup() throws Exception {
    kvStoreProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    kvStoreProvider.start();
  }

  @AfterEach
  public void shutdown() throws Exception {
    kvStoreProvider.close();
  }

  @Test
  public void testPut_propagatesConcurrentModificationException() {
    KVStoreProvider kvStoreProvider = mock(KVStoreProvider.class);
    IndexedStore<String, NameSpaceContainer> kvStore = mock(IndexedStore.class);
    when(kvStoreProvider.getStore(any(Class.class))).thenReturn(kvStore);
    NamespaceStore namespaceStore = new NamespaceStore(() -> kvStoreProvider);

    String key = "key";
    NameSpaceContainer value = new NameSpaceContainer().setSpace(new SpaceConfig()).setType(SPACE);
    when(kvStore.put(eq(key), eq(value), any()))
        .thenThrow(new ConcurrentModificationException("test"));

    ConcurrentModificationException e =
        assertThrows(ConcurrentModificationException.class, () -> namespaceStore.put(key, value));
    assertThat(e.getMessage()).isEqualTo("test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SPACE", "SOURCE", "HOME", "DATASET", "FUNCTION"})
  public void testPut_setTimestamps(String type) {
    String key = "key";
    Clock clock = Clock.fixed(Instant.now().plusSeconds(1), ZoneId.of("UTC"));
    NamespaceStore namespaceStore = new NamespaceStore(() -> kvStoreProvider, clock);

    // Create
    NameSpaceContainer createValue = createContainer(type);
    Document<String, NameSpaceContainer> created = namespaceStore.put(key, createValue);
    assertEquals(clock.millis(), NameSpaceTimestampExtractor.getCreatedAt(created.getValue()));
    if (Sets.newHashSet("SOURCE", "DATASET", "FUNCTION").contains(type)) {
      assertEquals(
          clock.millis(), NameSpaceTimestampExtractor.getLastModifiedAt(created.getValue()));
    }
    // Update
    clock = Clock.fixed(Instant.now().plusSeconds(2), ZoneId.of("UTC"));
    namespaceStore = new NamespaceStore(() -> kvStoreProvider, clock);

    NameSpaceContainer updateValue = createContainer(type);
    CONTAINER_VERSION_EXTRACTOR.setTag(
        updateValue, CONTAINER_VERSION_EXTRACTOR.getTag(created.getValue()));
    NameSpaceTimestampExtractor.setCreatedAt(
        updateValue, NameSpaceTimestampExtractor.getCreatedAt(created.getValue()));

    Document<String, NameSpaceContainer> updated = namespaceStore.put(key, updateValue);

    assertEquals(
        NameSpaceTimestampExtractor.getCreatedAt(created.getValue()),
        NameSpaceTimestampExtractor.getCreatedAt(updated.getValue()));

    if (Sets.newHashSet("SOURCE", "DATASET", "FUNCTION").contains(type)) {
      assertEquals(
          clock.millis(), NameSpaceTimestampExtractor.getLastModifiedAt(updated.getValue()));
    }
  }

  private NameSpaceContainer createContainer(String type) {
    switch (type) {
      case "SPACE":
        return new NameSpaceContainer()
            .setFullPathList(List.of("space1"))
            .setType(SPACE)
            .setSpace(new SpaceConfig().setId(EntityId.getDefaultInstance().setId("id")));
      case "SOURCE":
        return new NameSpaceContainer()
            .setFullPathList(List.of("source1"))
            .setType(SOURCE)
            .setSource(new SourceConfig().setId(EntityId.getDefaultInstance().setId("id")));
      case "HOME":
        return new NameSpaceContainer()
            .setFullPathList(List.of("home"))
            .setType(HOME)
            .setHome(new HomeConfig().setId(EntityId.getDefaultInstance().setId("id")));
      case "FOLDER":
        return new NameSpaceContainer()
            .setFullPathList(List.of("space1", "folder1"))
            .setType(FOLDER)
            .setFolder(new FolderConfig().setId(EntityId.getDefaultInstance().setId("id")));
      case "DATASET":
        return new NameSpaceContainer()
            .setFullPathList(List.of("space1", "dataset1"))
            .setType(DATASET)
            .setDataset(
                new DatasetConfig()
                    .setId(EntityId.getDefaultInstance().setId("id"))
                    .setType(DatasetType.OTHERS));
      case "FUNCTION":
        return new NameSpaceContainer()
            .setFullPathList(List.of("space1", "function1"))
            .setType(FUNCTION)
            .setFunction(new FunctionConfig().setId(EntityId.getDefaultInstance().setId("id")));
      default:
        throw new RuntimeException("bad type");
    }
  }
}
