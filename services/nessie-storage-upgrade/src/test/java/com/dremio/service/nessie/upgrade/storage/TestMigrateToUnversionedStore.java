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
import com.dremio.legacy.org.projectnessie.model.Content;
import com.dremio.legacy.org.projectnessie.model.ContentKey;
import com.dremio.legacy.org.projectnessie.model.IcebergTable;
import com.dremio.legacy.org.projectnessie.model.Namespace;
import com.dremio.legacy.org.projectnessie.versioned.BranchName;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceConflictException;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceNotFoundException;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.ContentId;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.ImmutableCommitParams;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyWithBytes;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.ImmutableAdjustableNonTransactionalDatabaseAdapterConfig;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapterConfig;
import com.dremio.legacy.org.projectnessie.versioned.store.DefaultStoreWorker;
import com.dremio.service.embedded.catalog.EmbeddedContent;
import com.dremio.service.embedded.catalog.EmbeddedContentKey;
import com.dremio.service.embedded.catalog.EmbeddedUnversionedStore;
import com.dremio.service.nessie.DatastoreDatabaseAdapterFactory;
import com.dremio.service.nessie.ImmutableDatastoreDbConfig;
import com.dremio.service.nessie.NessieDatastoreInstance;
import com.dremio.service.nessie.upgrade.kvstore.NessieCommitLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieGlobalLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieGlobalPointerStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieKeyListStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieNamedRefHeadsStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRefLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRefNamesStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRepoDescriptionStoreBuilder;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MigrateToUnversionedStore}. */
class TestMigrateToUnversionedStore {

  private MigrateToUnversionedStore task;
  private LocalKVStoreProvider storeProvider;
  private DatabaseAdapter adapter;
  private EmbeddedUnversionedStore store;

  @BeforeEach
  void createKVStore() throws Exception {
    task = new MigrateToUnversionedStore();
    storeProvider = new LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false); // in-memory
    storeProvider.start();

    NessieDatastoreInstance nessieDatastore = new NessieDatastoreInstance();
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
    adapter.initializeRepo("main");

    store = new EmbeddedUnversionedStore(() -> storeProvider);
  }

  @AfterEach
  void stopKVStore() throws Exception {
    if (storeProvider != null) {
      storeProvider.close();
    }
  }

  @Test
  void testEmptyUpgrade() throws Exception {
    task.upgrade(storeProvider, 10);
  }

  private void putTable(ContentKey key, String location)
      throws ReferenceNotFoundException, ReferenceConflictException {
    put(key, IcebergTable.of(location, 1, 2, 3, 4, UUID.randomUUID().toString()));
  }

  private void putNamespace(Namespace ns)
      throws ReferenceNotFoundException, ReferenceConflictException {
    put(ns.toContentKey(), ns.withId(UUID.randomUUID().toString()));
  }

  private void put(ContentKey key, Content content)
      throws ReferenceNotFoundException, ReferenceConflictException {
    ContentId contentId = ContentId.of(UUID.randomUUID().toString());
    ImmutableCommitParams.Builder commit = ImmutableCommitParams.builder();
    adapter.commit(
        commit
            .toBranch(BranchName.of("main"))
            .commitMetaSerialized(
                METADATA_SERIALIZER.toBytes(CommitMeta.fromMessage("test-" + key)))
            .addPuts(
                KeyWithBytes.of(
                    key,
                    contentId,
                    (byte) DefaultStoreWorker.payloadForContent(content),
                    DefaultStoreWorker.instance().toStoreOnReferenceState(content)))
            .build());
  }

  private static EmbeddedContentKey key(ContentKey key) {
    return EmbeddedContentKey.of(key.getElements());
  }

  @Test
  void testMigrateEntries() throws Exception {
    ContentKey key1 = ContentKey.of("dremio.internal", "table1");
    putTable(key1, "loc111");
    ContentKey key2 = ContentKey.of("dremio.internal", "table2");
    putTable(key2, "loc222");
    Namespace ns = Namespace.of("dremio.internal");
    putNamespace(ns);

    task.upgrade(storeProvider, 1);

    assertThat(store.getValue(key(key1)))
        .asInstanceOf(type(EmbeddedContent.class))
        .extracting(EmbeddedContent::location)
        .isEqualTo("loc111");
    assertThat(store.getValue(key(key2)))
        .asInstanceOf(type(EmbeddedContent.class))
        .extracting(EmbeddedContent::location)
        .isEqualTo("loc222");
    assertThat(store.getValue(key(ns.toContentKey())))
        .asInstanceOf(type(EmbeddedContent.class))
        .extracting(EmbeddedContent::key)
        .isEqualTo(key(ns.toContentKey()));
  }

  @Test
  void testEraseLegacyData() throws Exception {
    ContentKey key1 = ContentKey.of("dremio.internal", "table1");
    putTable(key1, "loc111");
    putTable(key1, "loc222");
    putTable(key1, "loc333");
    Namespace ns = Namespace.of("dremio.internal");
    putNamespace(ns);

    task.upgrade(storeProvider, 2);

    assertThat(storeProvider.getStore(NessieRefNamesStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieRefLogStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieRepoDescriptionStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieGlobalLogStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieGlobalPointerStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieNamedRefHeadsStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieKeyListStoreBuilder.class).find()).isEmpty();
    assertThat(storeProvider.getStore(NessieCommitLogStoreBuilder.class).find()).isEmpty();
  }
}
