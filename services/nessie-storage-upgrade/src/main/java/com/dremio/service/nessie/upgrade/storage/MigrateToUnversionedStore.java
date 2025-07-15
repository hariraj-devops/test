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

import com.dremio.dac.cmd.AdminLogger;
import com.dremio.dac.cmd.upgrade.UpgradeContext;
import com.dremio.dac.cmd.upgrade.UpgradeTask;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.legacy.org.projectnessie.model.ContentKey;
import com.dremio.legacy.org.projectnessie.server.store.proto.ObjectTypes;
import com.dremio.legacy.org.projectnessie.versioned.GetNamedRefsParams;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceInfo;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceNotFoundException;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.ContentAndState;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyFilterPredicate;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyListEntry;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.ImmutableAdjustableNonTransactionalDatabaseAdapterConfig;
import com.dremio.service.embedded.catalog.EmbeddedContent;
import com.dremio.service.embedded.catalog.EmbeddedContentKey;
import com.dremio.service.embedded.catalog.EmbeddedPointerStore;
import com.dremio.service.nessie.DatastoreDatabaseAdapter;
import com.dremio.service.nessie.DatastoreDatabaseAdapterFactory;
import com.dremio.service.nessie.ImmutableDatastoreDbConfig;
import com.dremio.service.nessie.NessieDatastoreInstance;
import com.dremio.service.nessie.upgrade.kvstore.AbstractNessieStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieCommitLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieGlobalLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieGlobalPointerStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieKeyListStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieNamedRefHeadsStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRefLogStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRefNamesStoreBuilder;
import com.dremio.service.nessie.upgrade.kvstore.NessieRepoDescriptionStoreBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Migrates Nessie data stored in the "old" OSS Nessie data model ({@link DatabaseAdapter}) to the
 * unversion KVStore-based model based on {@code EmbeddedUnversionedStore}.
 */
public class MigrateToUnversionedStore extends UpgradeTask {

  public static final String TASK_ID = "d45484da-cc7c-4165-9139-b693a92e982a";

  private static final int PROGRESS_CYCLE =
      Integer.getInteger("nessie.upgrade.migrate_to_unversioned.progress", 10_000);
  private static final int BATCH_SIZE =
      Integer.getInteger("nessie.upgrade.migrate_to_unversioned.batch_size", 1000);

  public MigrateToUnversionedStore() {
    super(
        "Migrate Nessie Data from database adapter to non-versioned KVStore",
        Collections.singletonList(MigrateOldKVStoreToUnversionedStore.TASK_ID));
  }

  @Override
  public String getTaskUUID() {
    return TASK_ID;
  }

  @Override
  public void upgrade(UpgradeContext context) throws Exception {
    upgrade(context.getKvStoreProvider(), PROGRESS_CYCLE);
  }

  @VisibleForTesting
  void upgrade(KVStoreProvider storeProvider, int progressCycle) throws Exception {
    EmbeddedPointerStore unversionedStore = new EmbeddedPointerStore(storeProvider);

    AtomicInteger count = new AtomicInteger();
    try (NessieDatastoreInstance store = new NessieDatastoreInstance()) {
      store.configure(
          new ImmutableDatastoreDbConfig.Builder().setStoreProvider(() -> storeProvider).build());
      store.initialize();
      DatastoreDatabaseAdapter adapter =
          new DatastoreDatabaseAdapterFactory()
              .newBuilder()
              .withConnector(store)
              .withConfig(
                  ImmutableAdjustableNonTransactionalDatabaseAdapterConfig.builder()
                      .validateNamespaces(false)
                      .build())
              .build();

      // Only process main. Other branches in the Embedded Nessie are not utilized during runtime.
      ReferenceInfo<?> main;
      try {
        main = adapter.namedRef("main", GetNamedRefsParams.DEFAULT);
      } catch (ReferenceNotFoundException e) {
        AdminLogger.log("Reference 'main' does not exist. Nothing to migrate.");
        return;
      }

      try (Stream<KeyListEntry> keys = adapter.keys(main.getHash(), KeyFilterPredicate.ALLOW_ALL)) {
        Iterator<List<KeyListEntry>> batch = Iterators.partition(keys.iterator(), BATCH_SIZE);
        while (batch.hasNext()) {
          List<KeyListEntry> entries = batch.next();
          List<ContentKey> contentKeys =
              entries.stream().map(KeyListEntry::getKey).collect(Collectors.toList());
          Map<ContentKey, ContentAndState> values =
              adapter.values(main.getHash(), contentKeys, KeyFilterPredicate.ALLOW_ALL);
          for (Map.Entry<ContentKey, ContentAndState> entry : values.entrySet()) {
            ContentAndState value = entry.getValue();
            if (value == null) {
              throw new IllegalStateException(
                  "Unable to load content for key: "
                      + entry.getKey()
                      + ", hash: "
                      + main.getHash());
            }

            EmbeddedContent content = parseContent(entry.getKey(), value);
            EmbeddedContentKey key = EmbeddedContentKey.of(entry.getKey().getElements());
            unversionedStore.put(key, content);

            if (count.incrementAndGet() % progressCycle == 0) {
              AdminLogger.log("Migrated {} embedded catalog entries.", count.get());
            }
          }
        }
      }
    }

    removeObsoleteEntries(storeProvider, progressCycle);
  }

  private void removeObsoleteEntries(KVStoreProvider storeProvider, int progressCycle) {
    removeObsoleteEntries(storeProvider, NessieRefNamesStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieRefLogStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieRepoDescriptionStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieGlobalLogStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieGlobalPointerStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieNamedRefHeadsStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieKeyListStoreBuilder.class, progressCycle);
    removeObsoleteEntries(storeProvider, NessieCommitLogStoreBuilder.class, progressCycle);
  }

  private EmbeddedContent parseContent(ContentKey key, ContentAndState contentAndState) {
    try {
      ObjectTypes.Content refState = ObjectTypes.Content.parseFrom(contentAndState.getRefState());
      ObjectTypes.Content.ObjectTypeCase type = refState.getObjectTypeCase();

      if (type == ObjectTypes.Content.ObjectTypeCase.ICEBERG_METADATA_POINTER) {
        ObjectTypes.IcebergMetadataPointer pointer = refState.getIcebergMetadataPointer();
        String metadataLocation = pointer.getMetadataLocation();
        return EmbeddedContent.table(metadataLocation, UUID.randomUUID().toString());
      } else if (type == ObjectTypes.Content.ObjectTypeCase.ICEBERG_REF_STATE) {
        ObjectTypes.IcebergRefState icebergRefState = refState.getIcebergRefState();
        String metadataLocation = icebergRefState.getMetadataLocation();
        if (icebergRefState.hasMetadataLocation()) {
          metadataLocation = icebergRefState.getMetadataLocation();
        } else {
          if (contentAndState.getGlobalState() == null) {
            throw new IllegalStateException("No metadata location set for " + key);
          }

          ObjectTypes.Content globalState =
              ObjectTypes.Content.parseFrom(contentAndState.getGlobalState());
          if (!globalState.hasIcebergMetadataPointer()) {
            throw new IllegalStateException("No metadata location set for " + key);
          }

          ObjectTypes.IcebergMetadataPointer pointer = globalState.getIcebergMetadataPointer();
          metadataLocation = pointer.getMetadataLocation();
        }
        return EmbeddedContent.table(metadataLocation, UUID.randomUUID().toString());
      } else if (type == ObjectTypes.Content.ObjectTypeCase.NAMESPACE) {
        return EmbeddedContent.namespace(
            EmbeddedContentKey.of(key.getElements()), UUID.randomUUID().toString());
      } else {
        throw new IllegalStateException(
            "Unable to parse object type: " + type + " for key: " + key);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T extends AbstractNessieStoreBuilder> void removeObsoleteEntries(
      KVStoreProvider storeProvider, Class<T> storeClass, int progressCycle) {
    KVStore<String, byte[]> store = storeProvider.getStore(storeClass);

    AtomicInteger count = new AtomicInteger();
    store
        .find()
        .forEach(
            entry -> {
              store.delete(entry.getKey(), KVStore.DeleteOption.NO_META);
              if (count.incrementAndGet() % progressCycle == 0) {
                AdminLogger.log("Deleted {} {} entries.", count.get(), store.getName());
              }
            });
  }
}
