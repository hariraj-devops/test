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
package com.dremio.datastore.transientstore;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.ImmutableDocument;
import com.dremio.datastore.api.KVStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;

/**
 * An in-memory store backed by {@link Cache}. The keys and values are serialized.
 *
 * @param <K> key by which values are retrieved.
 * @param <V> the value stored in the store.
 */
public class CaffeineTransientStore<K, V> implements TransientStore<K, V> {
  private final Cache<K, V> cache;

  public CaffeineTransientStore(Cache<K, V> cache) {
    this.cache = cache;
  }

  /**
   * Retrieves an entry for a key.
   *
   * @param key the key to use to look for the value.
   * @param options must be empty.
   * @return null if the value is not present
   */
  @Override
  public Document<K, V> get(K key, KVStore.GetOption... options) {
    V value = cache.getIfPresent(key);
    if (null != value) {
      return new ImmutableDocument.Builder<K, V>().setKey(key).setValue(value).build();
    } else {
      return null;
    }
  }

  /**
   * Retrieves a list of entries for a list of keys.
   *
   * @param keys a list of keys of which their values are to be retrieved.
   * @param options must be empty.
   * @return an iterable of retrieved keys and their values.
   */
  @Override
  public Iterable<Document<K, V>> get(List<K> keys, KVStore.GetOption... options) {
    return keys.stream()
        .map(k -> get(k, options))
        .filter(Objects::nonNull)
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Puts or replaces a value in the store.
   *
   * @param key the key to save the value.
   * @param value the value to save.
   * @param options options must be empty.
   * @return the entry retrieved.
   */
  @Override
  public Document<K, V> put(K key, V value, KVStore.PutOption... options) {

    cache.put(key, value);

    return new SimpleDocument<>(key, value);
  }

  @Override
  public void delete(K key, KVStore.DeleteOption... options) {
    cache.invalidate(key);
  }

  /**
   * Tests if the key is present in the store and not expired.
   *
   * @param key the key of the document to search for.
   * @return true if the key is contained in the store.
   */
  @Override
  public boolean contains(K key) {
    return cache.asMap().containsKey(key);
  }

  @Override
  public Iterable<Document<K, V>> find(String pattern, KVStore.GetOption... options) {
    throw new UnsupportedOperationException("find is not supported");
  }

  @Override
  public void deleteAll() {
    cache.invalidateAll();
  }
}
