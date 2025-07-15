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
package com.dremio.service.nessie.upgrade.storage;

import static com.dremio.legacy.org.projectnessie.versioned.CommitMetaSerializer.METADATA_SERIALIZER;
import static com.dremio.test.DremioTest.CLASSPATH_SCAN_RESULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.legacy.org.projectnessie.model.CommitMeta;
import com.dremio.legacy.org.projectnessie.model.ContentKey;
import com.dremio.legacy.org.projectnessie.model.IcebergTable;
import com.dremio.legacy.org.projectnessie.nessie.relocated.protobuf.ByteString;
import com.dremio.legacy.org.projectnessie.server.store.proto.ObjectTypes;
import com.dremio.legacy.org.projectnessie.versioned.BranchName;
import com.dremio.legacy.org.projectnessie.versioned.Hash;
import com.dremio.legacy.org.projectnessie.versioned.ImmutablePut;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceConflictException;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceNotFoundException;
import com.dremio.legacy.org.projectnessie.versioned.VersionStore;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.ContentId;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.ImmutableCommitParams;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyWithBytes;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.spi.DatabaseAdapterUtil;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.ImmutableAdjustableNonTransactionalDatabaseAdapterConfig;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapterConfig;
import com.dremio.legacy.org.projectnessie.versioned.persist.serialize.AdapterTypes;
import com.dremio.legacy.org.projectnessie.versioned.persist.store.PersistVersionStore;
import com.dremio.service.embedded.catalog.EmbeddedContent;
import com.dremio.service.embedded.catalog.EmbeddedContentKey;
import com.dremio.service.embedded.catalog.EmbeddedUnversionedStore;
import com.dremio.service.nessie.DatastoreDatabaseAdapterFactory;
import com.dremio.service.nessie.ImmutableDatastoreDbConfig;
import com.dremio.service.nessie.NessieDatastoreInstance;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link MigrateToUnversionedStore} covering the migration of legacy on-reference
 * state entries using the {@code ICEBERG_METADATA_POINTER} type to current format.
 */
class TestMigrateIcebergMetadataPointer {

  // This legacy data was produced using Nessie 0.14 code.
  // The data encodes IcebergTable.of("test-metadata-location", "test-id-data", "test-content-id")
  private static final String LEGACY_REF_STATE_BASE64 =
      "ChgKFnRlc3QtbWV0YWRhdGEtbG9jYXRpb24qD3Rlc3QtY29udGVudC1pZA==";

  private final MigrateToUnversionedStore task = new MigrateToUnversionedStore();
  private LocalKVStoreProvider storeProvider;
  private NessieDatastoreInstance nessieDatastore;
  private DatabaseAdapter adapter;
  private EmbeddedUnversionedStore unversionedStore;

  @BeforeEach
  void createKVStore() throws Exception {
    storeProvider = new LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false); // in-memory
    storeProvider.start();

    unversionedStore = new EmbeddedUnversionedStore(() -> storeProvider);

    nessieDatastore = new NessieDatastoreInstance();
    nessieDatastore.configure(
        new ImmutableDatastoreDbConfig.Builder().setStoreProvider(() -> storeProvider).build());
    nessieDatastore.initialize();

    NonTransactionalDatabaseAdapterConfig adapterCfg =
        ImmutableAdjustableNonTransactionalDatabaseAdapterConfig.builder()
            .validateNamespaces(false)
            .build();
    adapter =
        new DatastoreDatabaseAdapterFactory()
            .newBuilder()
            .withConfig(adapterCfg)
            .withConnector(nessieDatastore)
            .build();
  }

  @AfterEach
  void stopKVStore() throws Exception {
    if (storeProvider != null) {
      storeProvider.close();
    }
  }

  // Nessie Database Adapters.
  private void commitLegacyData(ContentKey key, ContentId contentId)
      throws ReferenceNotFoundException, ReferenceConflictException {

    ByteString refState = ByteString.copyFrom(Base64.getDecoder().decode(LEGACY_REF_STATE_BASE64));
    commit(key, contentId, refState);
  }

  private void commitGlobalStateData(ContentKey key, ContentId contentId)
      throws ReferenceNotFoundException, ReferenceConflictException {

    Hash globalLogId = DatabaseAdapterUtil.randomHash();
    AdapterTypes.GlobalStateLogEntry.Builder globalLogEntry =
        AdapterTypes.GlobalStateLogEntry.newBuilder()
            .addPuts(
                AdapterTypes.ContentIdWithBytes.newBuilder()
                    .setContentId(
                        AdapterTypes.ContentId.newBuilder().setId(contentId.getId()).build())
                    .setPayload(1) // Iceberg Table
                    .setValue(
                        ByteString.copyFrom(Base64.getDecoder().decode(LEGACY_REF_STATE_BASE64)))
                    .build());
    nessieDatastore
        .getGlobalLog()
        .put(globalLogId.asString(), globalLogEntry.build().toByteArray());

    AdapterTypes.GlobalStatePointer.Builder globalPointer =
        AdapterTypes.GlobalStatePointer.newBuilder().setGlobalId(globalLogId.asBytes());
    nessieDatastore.getGlobalPointer().put("", globalPointer.build().toByteArray());

    ObjectTypes.Content.Builder builder = ObjectTypes.Content.newBuilder();
    builder.getIcebergRefStateBuilder().setSnapshotId(123);
    ByteString refState = builder.build().toByteString();

    commit(key, contentId, refState);
  }

  private void commit(ContentKey key, ContentId contentId, ByteString refState)
      throws ReferenceNotFoundException, ReferenceConflictException {
    adapter.commit(
        ImmutableCommitParams.builder()
            .toBranch(BranchName.of("main"))
            .commitMetaSerialized(METADATA_SERIALIZER.toBytes(CommitMeta.fromMessage("test")))
            .addPuts(
                KeyWithBytes.of(
                    key, contentId, (byte) 1, // Iceberg Table
                    refState))
            .build());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 19, 20, 21, 40, 99, 100, 101})
  void testUpgrade(int numExtraTables) throws Exception {
    adapter.initializeRepo("main");
    // Load a legacy entry into the adapter
    List<ContentKey> keys = new ArrayList<>();
    ContentKey key1 = ContentKey.of("test", "table", "11111");
    ContentId contentId1 = ContentId.of("test-content-id");
    commitLegacyData(key1, contentId1);
    keys.add(key1);

    VersionStore versionStore = new PersistVersionStore(adapter);

    // Create some extra Iceberg tables in current Nessie format
    for (int i = 0; i < numExtraTables; i++) {
      ContentKey extraKey = ContentKey.of("test", "table", "current-" + i);
      IcebergTable table =
          IcebergTable.of("test-metadata-location", 1, 2, 3, 4, "extra-content-id-" + i);
      versionStore.commit(
          BranchName.of("main"),
          Optional.empty(),
          CommitMeta.fromMessage("test"),
          Collections.singletonList(
              ImmutablePut.builder().key(extraKey).valueSupplier(() -> table).build()));
      keys.add(extraKey);
    }

    task.upgrade(storeProvider, 1);

    assertThat(unversionedStore.getEntries(Integer.MAX_VALUE).keySet())
        .containsExactlyInAnyOrderElementsOf(
            keys.stream()
                .map(k -> EmbeddedContentKey.of(k.getElements()))
                .collect(Collectors.toList()));
    assertThat(keys)
        .allSatisfy(
            key -> {
              assertThat(unversionedStore.getValue(EmbeddedContentKey.of(key.getElements())))
                  .isInstanceOf(EmbeddedContent.class)
                  .extracting("location")
                  .isEqualTo("test-metadata-location"); // encoded in LEGACY_REF_STATE_BASE64
            });
  }

  @Test
  void testUpgradeWithGlobalState() throws Exception {
    adapter.initializeRepo("main");
    // Load a legacy entry into the adapter
    List<ContentKey> keys = new ArrayList<>();
    ContentKey key1 = ContentKey.of("test", "table", "11111");
    ContentId contentId1 = ContentId.of("test-content-id1");
    commitLegacyData(key1, contentId1);
    keys.add(key1);

    // Load a global state entry into the adapter
    ContentKey key2 = ContentKey.of("test", "table", "22222");
    ContentId contentId2 = ContentId.of("test-content-id2");
    commitGlobalStateData(key2, contentId2);
    keys.add(key2);

    VersionStore versionStore = new PersistVersionStore(adapter);

    // Load an entry in current format
    ContentKey key3 = ContentKey.of("test", "table", "33333");
    IcebergTable table = IcebergTable.of("test-metadata-location", 1, 2, 3, 4, "extra-content-id-");
    versionStore.commit(
        BranchName.of("main"),
        Optional.empty(),
        CommitMeta.fromMessage("test"),
        Collections.singletonList(
            ImmutablePut.builder().key(key3).valueSupplier(() -> table).build()));
    keys.add(key3);

    task.upgrade(storeProvider, 1);

    assertThat(unversionedStore.getEntries(Integer.MAX_VALUE).keySet())
        .containsExactlyInAnyOrderElementsOf(
            keys.stream()
                .map(k -> EmbeddedContentKey.of(k.getElements()))
                .collect(Collectors.toList()));
    assertThat(keys)
        .allSatisfy(
            key -> {
              assertThat(unversionedStore.getValue(EmbeddedContentKey.of(key.getElements())))
                  .isInstanceOf(EmbeddedContent.class)
                  .extracting("location")
                  .isEqualTo("test-metadata-location"); // encoded in LEGACY_REF_STATE_BASE64
            });
  }

  @Test
  void testEmptyUpgrade() throws Exception {
    task.upgrade(storeProvider, 10);

    assertThat(unversionedStore.getEntries(Integer.MAX_VALUE)).isEmpty();
  }

  @Test
  void testUnnecessaryUpgradeOfDeletedEntry() throws Exception {
    adapter.initializeRepo("main");
    // Load a legacy entry into the adapter
    ContentKey key1 = ContentKey.of("test", "table", "11111");
    ContentId contentId1 = ContentId.of("test-content-id");
    commitLegacyData(key1, contentId1);

    // Delete the legacy entry
    adapter
        .commit(
            ImmutableCommitParams.builder()
                .toBranch(BranchName.of("main"))
                .commitMetaSerialized(
                    METADATA_SERIALIZER.toBytes(CommitMeta.fromMessage("test delete")))
                .addDeletes(key1)
                .build())
        .getCommitHash();

    task.upgrade(storeProvider, 1);

    assertThat(unversionedStore.getValue(EmbeddedContentKey.of(key1.getElements()))).isNull();
  }

  @Test
  void testUnnecessaryUpgradeOfReplacedEntry() throws Exception {
    adapter.initializeRepo("main");
    // Load a legacy entry into the adapter
    ContentKey key1 = ContentKey.of("test", "table", "11111");
    ContentId contentId1 = ContentId.of("test-content-id");
    commitLegacyData(key1, contentId1);

    // Replace the table using current Nessie format
    IcebergTable table = IcebergTable.of("metadata1", 1, 2, 3, 4, "id123");
    VersionStore versionStore = new PersistVersionStore(adapter);
    versionStore
        .commit(
            BranchName.of("main"),
            Optional.empty(),
            CommitMeta.fromMessage("test"),
            Collections.singletonList(
                ImmutablePut.builder().key(key1).valueSupplier(() -> table).build()))
        .getCommitHash();

    task.upgrade(storeProvider, 5);

    assertThat(unversionedStore.getValue(EmbeddedContentKey.of(key1.getElements())))
        .asInstanceOf(type(EmbeddedContent.class))
        .extracting(EmbeddedContent::location)
        .isEqualTo("metadata1");
  }
}
