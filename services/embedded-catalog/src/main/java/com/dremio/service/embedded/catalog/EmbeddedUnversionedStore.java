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

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.services.nessie.grpc.api.CommitOperation;
import com.google.common.base.Suppliers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.projectnessie.error.NessieReferenceConflictException;
import org.projectnessie.error.ReferenceConflicts;
import org.projectnessie.model.Conflict;

public class EmbeddedUnversionedStore {

  private final Semaphore commitSemaphore = new Semaphore(1, true);
  private final Supplier<EmbeddedPointerStore> store;

  public EmbeddedUnversionedStore(Provider<KVStoreProvider> kvStoreProvider) {
    store = Suppliers.memoize(() -> new EmbeddedPointerStore(kvStoreProvider.get()));
  }

  public void commit(List<CommitOperation> operations) throws NessieReferenceConflictException {
    try {
      commitSemaphore.acquire();
      try {
        checkOperationsForConflicts(operations);
        commitUnversioned(operations);
      } finally {
        commitSemaphore.release();
      }
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private static org.projectnessie.model.ContentKey asModelKey(EmbeddedContentKey key) {
    return org.projectnessie.model.ContentKey.of(key.getElements());
  }

  private void checkOperationsForConflicts(List<CommitOperation> operations)
      throws NessieReferenceConflictException {
    List<Conflict> conflicts = new ArrayList<>();
    for (CommitOperation op : operations) {
      if (op.hasPut()) {
        EmbeddedContentKey key = EmbeddedContentKey.of(op.getPut().getKey().getElementsList());
        String msgPrefix = "Key '" + key + "' ";
        com.dremio.services.nessie.grpc.api.Content value = op.getPut().getContent();
        if (value.hasIceberg()) {
          // detect and fail concurrent changes
          EmbeddedContent previous = store.get().get(key);
          String id = value.getIceberg().getId();
          if (previous == null) {
            if (!id.isEmpty()) {
              conflicts.add(
                  Conflict.conflict(
                      Conflict.ConflictType.KEY_DOES_NOT_EXIST,
                      asModelKey(key),
                      msgPrefix + "Cannot update a non-existing table"));
            }
          } else {
            if (id.isEmpty()) {
              conflicts.add(
                  Conflict.conflict(
                      Conflict.ConflictType.KEY_EXISTS,
                      asModelKey(key),
                      msgPrefix + "Table already exists"));
            } else {
              String oldId = previous.id();
              if (oldId != null && !oldId.equals(id)) {
                conflicts.add(
                    Conflict.conflict(
                        Conflict.ConflictType.CONTENT_ID_DIFFERS,
                        asModelKey(key),
                        msgPrefix
                            + "Table content ID mismatch - expected: "
                            + id
                            + ", found: "
                            + oldId));
              }
            }
          }
        }
      }
    }

    if (!conflicts.isEmpty()) {
      String msg = conflicts.stream().map(Conflict::message).collect(Collectors.joining(", "));

      // Note: use unrelocated Nessie Model exception classes to make sure gRPC exception
      // mappers handle them properly.
      throw new NessieReferenceConflictException(
          ReferenceConflicts.referenceConflicts(conflicts),
          msg,
          new IllegalStateException("Conflicts: " + msg));
    }
  }

  private void commitUnversioned(List<CommitOperation> operations) {
    for (CommitOperation op : operations) {
      if (op.hasPut()) {
        EmbeddedContentKey key = EmbeddedContentKey.of(op.getPut().getKey().getElementsList());
        com.dremio.services.nessie.grpc.api.Content content = op.getPut().getContent();
        EmbeddedContent value;
        if (content.hasIceberg()) {
          value =
              EmbeddedContent.table(
                  content.getIceberg().getMetadataLocation(), content.getIceberg().getId());
        } else if (content.hasNamespace()) {
          value = EmbeddedContent.namespace(key, content.getNamespace().getId());
        } else {
          throw new IllegalArgumentException("Unsupported content type: " + content);
        }
        store.get().put(key, value);
      } else if (op.hasDelete()) {
        EmbeddedContentKey key = EmbeddedContentKey.of(op.getDelete().getKey().getElementsList());
        store.get().delete(key);
      }
    }
  }

  public EmbeddedContent getValue(EmbeddedContentKey key) {
    return store.get().get(key);
  }

  public Map<EmbeddedContentKey, EmbeddedContent> getEntries(int maxEntries) {
    return store
        .get()
        .findAll()
        .limit(maxEntries)
        .collect(Collectors.toMap(Document::getKey, Document::getValue));
  }
}
