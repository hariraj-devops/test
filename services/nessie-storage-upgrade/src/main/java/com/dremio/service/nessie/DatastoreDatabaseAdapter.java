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
package com.dremio.service.nessie;

import static com.dremio.legacy.org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.protoToCommitLogEntry;
import static com.dremio.legacy.org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.protoToKeyList;
import static com.dremio.legacy.org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.protoToRepoDescription;
import static com.dremio.legacy.org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization.toProto;
import static com.dremio.legacy.org.projectnessie.versioned.persist.adapter.spi.DatabaseAdapterUtil.hashCollisionDetected;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVStore;
import com.dremio.legacy.org.projectnessie.nessie.relocated.protobuf.InvalidProtocolBufferException;
import com.dremio.legacy.org.projectnessie.versioned.CommitResult;
import com.dremio.legacy.org.projectnessie.versioned.Hash;
import com.dremio.legacy.org.projectnessie.versioned.NamedRef;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceConflictException;
import com.dremio.legacy.org.projectnessie.versioned.ReferenceNotFoundException;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.CommitLogEntry;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.CommitParams;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.DatabaseAdapter;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyListEntity;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.KeyListEntry;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.RepoDescription;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.events.AdapterEventConsumer;
import com.dremio.legacy.org.projectnessie.versioned.persist.adapter.serialize.ProtoSerialization;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapter;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.NonTransactionalDatabaseAdapterConfig;
import com.dremio.legacy.org.projectnessie.versioned.persist.nontx.NonTransactionalOperationContext;
import com.dremio.legacy.org.projectnessie.versioned.persist.serialize.AdapterTypes;
import com.dremio.legacy.org.projectnessie.versioned.persist.serialize.AdapterTypes.GlobalStateLogEntry;
import com.dremio.legacy.org.projectnessie.versioned.persist.serialize.AdapterTypes.GlobalStatePointer;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Datastore Database Adapter for Embedded Nessie. */
public class DatastoreDatabaseAdapter
    extends NonTransactionalDatabaseAdapter<NonTransactionalDatabaseAdapterConfig> {

  private final NessieDatastoreInstance db;
  private final String keyPrefix;
  private final String globalPointerKey;

  public DatastoreDatabaseAdapter(
      NonTransactionalDatabaseAdapterConfig config,
      NessieDatastoreInstance dbInstance,
      AdapterEventConsumer eventConsumer) {
    super(config, eventConsumer);
    Objects.requireNonNull(dbInstance);
    this.db = dbInstance;
    this.keyPrefix = config.getRepositoryId();
    this.globalPointerKey = keyPrefix;
  }

  @Override
  public CommitResult<CommitLogEntry> commit(CommitParams commitParams)
      throws ReferenceConflictException, ReferenceNotFoundException {
    // Note: Embedded Nessie servers run only on the master coordinator node (and have exclusive
    // access to storage).
    Semaphore sem = db.getCommitSemaphore();
    sem.acquireUninterruptibly(); // Note: R/W locks in other methods are also not interruptible
    try {
      return super.commit(commitParams);
    } finally {
      sem.release();
    }
  }

  public String dbKey(Hash hash) {
    return keyPrefix.concat(hash.asString());
  }

  public String dbKey(String name) {
    return keyPrefix.concat(name);
  }

  public String dbKey(int segment) {
    return keyPrefix.concat(Integer.toString(segment));
  }

  /**
   * Calculate the expected size of the given {@link CommitLogEntry} in the database.
   *
   * @param entry
   */
  @Override
  protected int entitySize(CommitLogEntry entry) {
    return toProto(entry).getSerializedSize();
  }

  /**
   * Calculate the expected size of the given {@link KeyListEntry} in the database.
   *
   * @param entry
   */
  @Override
  protected int entitySize(KeyListEntry entry) {
    return toProto(entry).getSerializedSize();
  }

  /**
   * Load the current global-state-pointer.
   *
   * @param ctx
   * @return the current global points if set, or {@code null} if not set.
   */
  @Override
  protected GlobalStatePointer doFetchGlobalPointer(NonTransactionalOperationContext ctx) {
    try {
      Document<String, byte[]> serialized = db.getGlobalPointer().get(globalPointerKey);
      return serialized != null ? GlobalStatePointer.parseFrom(serialized.getValue()) : null;
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Write a new commit-entry, the given commit entry is to be persisted as is. All values of the
   * given {@link CommitLogEntry} can be considered valid and consistent.
   *
   * <p>Implementations however can enforce strict consistency checks/guarantees, like a best-effort
   * approach to prevent hash-collisions but without any other consistency checks/guarantees.
   *
   * @param ctx
   * @param entry
   */
  @Override
  protected void doWriteIndividualCommit(NonTransactionalOperationContext ctx, CommitLogEntry entry)
      throws ReferenceConflictException {
    Lock lock = db.getLock().writeLock();
    lock.lock();

    try {
      String key = dbKey(entry.getHash());
      Document<String, byte[]> commitEntry = db.getCommitLog().get(key);
      if (commitEntry != null) {
        throw hashCollisionDetected();
      } else {
        db.getCommitLog().put(key, toProto(entry).toByteArray(), KVStore.PutOption.CREATE);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void doUpdateMultipleCommits(
      NonTransactionalOperationContext ctx, List<CommitLogEntry> entries) {
    persistMultipleCommits(ctx, entries);
  }

  /**
   * Write multiple new commit-entries, the given commit entries are to be persisted as is. All
   * values of the * given {@link CommitLogEntry} can be considered valid and consistent.
   *
   * <p>Implementations however can enforce strict consistency checks/guarantees, like a best-effort
   * approach to prevent hash-collisions but without any other consistency checks/guarantees.
   *
   * @param ctx
   * @param entries
   */
  @Override
  protected void doWriteMultipleCommits(
      NonTransactionalOperationContext ctx, List<CommitLogEntry> entries) {
    persistMultipleCommits(ctx, entries);
  }

  protected void persistMultipleCommits(
      NonTransactionalOperationContext ctx, List<CommitLogEntry> entries) {
    Lock lock = db.getLock().writeLock();
    lock.lock();

    try {
      for (CommitLogEntry e : entries) {
        db.getCommitLog().put(dbKey(e.getHash()), toProto(e).toByteArray());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Unsafe operation to initialize a repository: unconditionally writes the global-state-pointer.
   *
   * @param ctx
   * @param pointer
   */
  @Override
  protected void unsafeWriteGlobalPointer(
      NonTransactionalOperationContext ctx, GlobalStatePointer pointer) {
    try {
      db.getGlobalPointer().put(globalPointerKey, pointer.toByteArray());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Atomically update the global-commit-pointer to the given new-global-head, if the value in the
   * database is the given expected-global-head.
   *
   * @param ctx
   * @param expected
   * @param newPointer
   */
  @Override
  protected boolean doGlobalPointerCas(
      NonTransactionalOperationContext ctx,
      GlobalStatePointer expected,
      GlobalStatePointer newPointer) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      Document<String, byte[]> bytes = db.getGlobalPointer().get(globalPointerKey);
      GlobalStatePointer oldPointer =
          bytes == null ? null : GlobalStatePointer.parseFrom(bytes.getValue());
      if (oldPointer == null || !oldPointer.getGlobalId().equals(expected.getGlobalId())) {
        return false;
      }
      db.getGlobalPointer().put(globalPointerKey, newPointer.toByteArray());
      return true;
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * If a {@link #globalPointerCas(NonTransactionalOperationContext, GlobalStatePointer,
   * GlobalStatePointer)} failed, {@link DatabaseAdapter#commit(CommitParams)} calls this function
   * to remove the optimistically written data.
   *
   * <p>Implementation notes: non-transactional implementations <em>must</em> delete entries for the
   * given keys, no-op for transactional implementations.
   */
  @Override
  protected void doCleanUpCommitCas(
      NonTransactionalOperationContext ctx, Set<Hash> branchCommits, Set<Hash> newKeyLists) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      for (Hash h : branchCommits) {
        db.getCommitLog().delete(dbKey(h));
      }
      for (Hash h : newKeyLists) {
        db.getKeyList().delete(dbKey(h));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Load the global-log entry with the given id.
   *
   * @param ctx
   * @param id
   * @return the loaded entry if it is available, {@code null} if it does not exist.
   */
  @Override
  protected GlobalStateLogEntry doFetchFromGlobalLog(
      NonTransactionalOperationContext ctx, Hash id) {
    try {
      Document<String, byte[]> entry = db.getGlobalLog().get(dbKey(id));
      return entry != null ? GlobalStateLogEntry.parseFrom(entry.getValue()) : null;
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected Stream<CommitLogEntry> doScanAllCommitLogEntries(NonTransactionalOperationContext c) {
    try {
      return Streams.stream(db.getCommitLog().find())
          .filter(e -> e.getKey().startsWith(keyPrefix))
          .filter(e -> e.getValue() != null)
          .map(e -> protoToCommitLogEntry(e.getValue()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Load the commit-log entry for the given hash, return {@code null}, if not found. */
  @Override
  protected CommitLogEntry doFetchFromCommitLog(NonTransactionalOperationContext ctx, Hash hash) {
    try {
      Document<String, byte[]> entry = db.getCommitLog().get(dbKey(hash));
      return protoToCommitLogEntry(entry != null ? entry.getValue() : null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Fetch multiple {@link CommitLogEntry commit-log-entries} from the commit-log. The returned list
   * must have exactly as many elements as in the parameter {@code hashes}. Non-existing hashes are
   * returned as {@code null}.
   *
   * @param ctx
   * @param hashes
   */
  @Override
  protected List<CommitLogEntry> doFetchMultipleFromCommitLog(
      NonTransactionalOperationContext ctx, List<Hash> hashes) {
    return fetchPage(db.getCommitLog(), hashes, ProtoSerialization::protoToCommitLogEntry);
  }

  @Override
  protected List<GlobalStateLogEntry> doFetchPageFromGlobalLog(
      NonTransactionalOperationContext ctx, List<Hash> hashes) {
    return fetchPage(
        db.getGlobalLog(),
        hashes,
        v -> {
          try {
            return v != null ? GlobalStateLogEntry.parseFrom(v) : null;
          } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private <T> List<T> fetchPage(
      KVStore<String, byte[]> store, List<Hash> hashes, Function<byte[], T> deserializer) {
    try {
      List<String> keys = hashes.stream().map(this::dbKey).collect(Collectors.toList());
      Iterable<Document<String, byte[]>> iterable = store.get(keys);
      List<byte[]> result = new ArrayList<>();
      for (Document<String, byte[]> doc : iterable) {
        result.add(doc != null ? doc.getValue() : null);
      }
      return result.stream().map(deserializer).collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void doWriteKeyListEntities(
      NonTransactionalOperationContext ctx, List<KeyListEntity> newKeyListEntities) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      for (KeyListEntity keyListEntity : newKeyListEntities) {
        db.getKeyList()
            .put(dbKey(keyListEntity.getId()), toProto(keyListEntity.getKeys()).toByteArray());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected Stream<KeyListEntity> doFetchKeyLists(
      NonTransactionalOperationContext ctx, List<Hash> keyListsIds) {
    try {
      KVStore<String, byte[]> store = db.getKeyList();
      List<String> keys = keyListsIds.stream().map(this::dbKey).collect(Collectors.toList());
      Iterable<Document<String, byte[]>> entries = store.get(keys);
      Iterator<Document<String, byte[]>> iterator = entries.iterator();
      return IntStream.range(0, keyListsIds.size())
          .mapToObj(
              i -> {
                Document<String, byte[]> doc = iterator.next();
                return KeyListEntity.of(
                    keyListsIds.get(i), protoToKeyList(doc != null ? doc.getValue() : null));
              });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void doEraseRepo() {
    try {
      Stream.of(
              db.getGlobalPointer(),
              db.getGlobalLog(),
              db.getCommitLog(),
              db.getNamedRefHeads(),
              db.getRefNames(),
              db.getRepoDescription(),
              db.getKeyList())
          .forEach(
              cf -> {
                List<String> deletes = new ArrayList<>();
                for (Document<String, byte[]> doc : cf.find()) {
                  if (doc.getKey().startsWith(keyPrefix)) {
                    deletes.add(doc.getKey());
                  }
                }
                deletes.forEach(cf::delete);
              });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected RepoDescription doFetchRepositoryDescription(NonTransactionalOperationContext ctx) {
    try {
      Document<String, byte[]> entry = db.getRepoDescription().get(globalPointerKey);
      return entry != null ? protoToRepoDescription(entry.getValue()) : null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected boolean doTryUpdateRepositoryDescription(
      NonTransactionalOperationContext ctx, RepoDescription expected, RepoDescription updateTo) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      Document<String, byte[]> entry = db.getRepoDescription().get(globalPointerKey);
      byte[] bytes = entry != null ? entry.getValue() : null;
      byte[] updatedBytes = toProto(updateTo).toByteArray();
      if ((bytes == null && expected == null)
          || (bytes != null && Arrays.equals(bytes, toProto(expected).toByteArray()))) {
        db.getRepoDescription().put(globalPointerKey, updatedBytes);
        return true;
      }
      return false;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected List<AdapterTypes.NamedReference> doFetchNamedReference(
      NonTransactionalOperationContext ctx, List<String> refNames) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      return refNames.stream()
          .map(name -> db.getNamedRefHeads().get(dbKey(name)))
          .filter(Objects::nonNull)
          .map(
              entry -> {
                try {
                  return AdapterTypes.NamedReference.parseFrom(entry.getValue());
                } catch (InvalidProtocolBufferException e) {
                  throw new RuntimeException(e);
                }
              })
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void doAddToNamedReferences(
      NonTransactionalOperationContext ctx, Stream<NamedRef> refStream, int addToSegment) {
    Set<String> refNamesToAdd = refStream.map(NamedRef::getName).collect(Collectors.toSet());
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      String key = dbKey(addToSegment);
      Document<String, byte[]> segment = db.getRefNames().get(key);

      AdapterTypes.ReferenceNames referenceNames;
      try {
        referenceNames =
            segment == null
                ? AdapterTypes.ReferenceNames.getDefaultInstance()
                : AdapterTypes.ReferenceNames.parseFrom(segment.getValue());
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }

      byte[] newRefNameBytes =
          referenceNames.toBuilder().addAllRefNames(refNamesToAdd).build().toByteArray();

      db.getRefNames().put(key, newRefNameBytes);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void doRemoveFromNamedReferences(
      NonTransactionalOperationContext ctx, NamedRef ref, int removeFromSegment) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      String key = dbKey(removeFromSegment);
      Document<String, byte[]> segment = db.getRefNames().get(key);
      if (segment == null) {
        return;
      }

      AdapterTypes.ReferenceNames referenceNames;
      try {
        referenceNames = AdapterTypes.ReferenceNames.parseFrom(segment.getValue());
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }

      AdapterTypes.ReferenceNames.Builder newRefNames = referenceNames.toBuilder().clearRefNames();
      referenceNames.getRefNamesList().stream()
          .filter(n -> !n.equals(ref.getName()))
          .forEach(newRefNames::addRefNames);
      byte[] newRefNameBytes = newRefNames.build().toByteArray();

      db.getRefNames().put(key, newRefNameBytes);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected boolean doCreateNamedReference(
      NonTransactionalOperationContext ctx, AdapterTypes.NamedReference namedReference) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      String key = dbKey(namedReference.getName());
      Document<String, byte[]> existing = db.getNamedRefHeads().get(key);
      if (existing != null) {
        return false;
      }

      db.getNamedRefHeads().put(key, namedReference.toByteArray());
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected boolean doDeleteNamedReference(
      NonTransactionalOperationContext ctx, NamedRef ref, AdapterTypes.RefPointer refHead) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      String key = dbKey(ref.getName());
      Document<String, byte[]> existing = db.getNamedRefHeads().get(key);
      if (existing == null) {
        return false;
      }

      AdapterTypes.NamedReference expected =
          AdapterTypes.NamedReference.newBuilder().setName(ref.getName()).setRef(refHead).build();

      if (!Arrays.equals(existing.getValue(), expected.toByteArray())) {
        return false;
      }

      db.getNamedRefHeads().delete(key);
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected boolean doUpdateNamedReference(
      NonTransactionalOperationContext ctx,
      NamedRef ref,
      AdapterTypes.RefPointer refHead,
      Hash newHead) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      String key = dbKey(ref.getName());
      Document<String, byte[]> existing = db.getNamedRefHeads().get(key);
      if (existing == null) {
        return false;
      }

      AdapterTypes.NamedReference namedReference;
      try {
        namedReference = AdapterTypes.NamedReference.parseFrom(existing.getValue());
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }

      if (!namedReference.getRef().equals(refHead)) {
        return false;
      }

      AdapterTypes.NamedReference newNamedReference =
          namedReference.toBuilder()
              .setRef(namedReference.getRef().toBuilder().setHash(newHead.asBytes()))
              .build();

      db.getNamedRefHeads().put(key, newNamedReference.toByteArray());
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected List<AdapterTypes.ReferenceNames> doFetchReferenceNames(
      NonTransactionalOperationContext ctx, int segment, int prefetchSegments) {
    Lock lock = db.getLock().writeLock();
    lock.lock();
    try {
      return IntStream.rangeClosed(segment, segment + prefetchSegments)
          .mapToObj(seg -> db.getRefNames().get(dbKey(seg)))
          .map(
              s -> {
                try {
                  return s != null ? AdapterTypes.ReferenceNames.parseFrom(s.getValue()) : null;
                } catch (InvalidProtocolBufferException e) {
                  throw new RuntimeException(e);
                }
              })
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      lock.unlock();
    }
  }
}
