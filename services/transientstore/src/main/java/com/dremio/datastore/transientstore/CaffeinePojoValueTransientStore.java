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

import static com.google.common.base.Preconditions.checkNotNull;

import com.dremio.datastore.ByteSerializerFactory;
import com.dremio.datastore.Serializer;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.format.Format;
import com.dremio.proto.model.TransientStore.ByteArrayKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Objects;

/**
 * An in-memory store backed by {@link Cache}. The keys are serialized but not the values.
 *
 * @param <K> key by which values are retrieved.
 * @param <V> the value stored in the store.
 */
public class CaffeinePojoValueTransientStore<K, V> implements TransientStore<K, V> {
  private final Cache<ByteArrayKey, V> cache;
  private final Format<K> keyFormat;

  public CaffeinePojoValueTransientStore(Cache<ByteArrayKey, V> cache, Format<K> keyFormat) {
    this.cache = checkNotNull(cache, "cache");
    this.keyFormat = checkNotNull(keyFormat, "keyFormat");
  }

  /**
   * Retrieves an entry for a key.
   *
   * @param key the key to use to look for the value.
   * @param options must be empty.
   * @return null if the value is not present
   */
  @Override
  public SimpleDocument<K, V> get(K key, KVStore.GetOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");
    final ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(serializeKey(key))).build();
    V value = cache.getIfPresent(byteArrayKey);
    if (null != value) {
      return new SimpleDocument<>(key, value);
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
    Preconditions.checkArgument(0 == options.length, "options not empty");
    return keys.stream()
        .map(
            k -> {
              Document<K, V> d = get(k, options);
              return d;
            })
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
    Preconditions.checkArgument(0 == options.length, "options not empty");

    final ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(serializeKey(key))).build();

    cache.put(byteArrayKey, value);

    return new SimpleDocument<>(key, value);
  }

  @Override
  public void delete(K key, KVStore.DeleteOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");

    final ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(serializeKey(key))).build();

    cache.invalidate(byteArrayKey);
  }

  /**
   * Tests if the key is present in the store and not expired.
   *
   * @param key the key of the document to search for.
   * @return true if the key is contained in the store.
   */
  @Override
  public boolean contains(K key) {
    final ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(serializeKey(key))).build();

    return cache.asMap().containsKey(byteArrayKey);
  }

  @Override
  public Iterable<Document<K, V>> find(String pattern, KVStore.GetOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");

    throw new UnsupportedOperationException("find is not supported");
  }

  @Override
  public void deleteAll() {
    cache.invalidateAll();
  }

  private byte[] serializeKey(K key) {
    Serializer<K, byte[]> keyFormatX =
        (Serializer<K, byte[]>) keyFormat.apply(ByteSerializerFactory.INSTANCE);
    return keyFormatX.serialize(key);
  }
}
