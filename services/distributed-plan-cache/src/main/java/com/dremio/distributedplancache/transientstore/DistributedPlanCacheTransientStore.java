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
package com.dremio.distributedplancache.transientstore;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStore.DeleteOption;
import com.dremio.datastore.api.KVStore.GetOption;
import com.dremio.datastore.api.KVStore.PutOption;
import com.dremio.datastore.transientstore.Marshaller;
import com.dremio.datastore.transientstore.SimpleDocument;
import com.dremio.datastore.transientstore.TransientStore;
import com.dremio.proto.model.TransientStore.ByteArrayKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Objects;

/*
 Transient Store for DPC. It has a delegate transient store which stores encrypted cache entries.
 It marshals key and value before storing it into cache. and unmarshalls value before returning it.
*/
public class DistributedPlanCacheTransientStore<K, V> implements TransientStore<K, V> {
  private final TransientStore<ByteArrayKey, byte[]> cache;
  private final Marshaller<K> keyMarshaller;
  private final Marshaller<V> valueMarshaller;

  public DistributedPlanCacheTransientStore(
      TransientStore<ByteArrayKey, byte[]> cache,
      Marshaller<K> keyMarshaller,
      Marshaller<V> valueMarshaller) {
    this.cache = Preconditions.checkNotNull(cache, "cache");
    this.keyMarshaller = Preconditions.checkNotNull(keyMarshaller, "keyMarshaller");
    this.valueMarshaller = Preconditions.checkNotNull(valueMarshaller, "valueMarshaller");
  }

  @Override
  public SimpleDocument<K, V> get(K key, GetOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");
    ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(keyMarshaller.marshal(key))).build();
    Document<ByteArrayKey, byte[]> bytesEntry = cache.get(byteArrayKey, options);
    if (null != bytesEntry) {
      try {
        return new SimpleDocument<>(key, valueMarshaller.unmarshal(bytesEntry.getValue()));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
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
        .map(k -> (Document<K, V>) get(k, options))
        .filter(Objects::nonNull)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Document<K, V> put(K key, V value, PutOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");
    ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(keyMarshaller.marshal(key))).build();
    byte[] bytesForValue = valueMarshaller.marshal(value);

    cache.put(byteArrayKey, bytesForValue, options);

    return new SimpleDocument<>(key, value);
  }

  @Override
  public void delete(K key, DeleteOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");

    ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(keyMarshaller.marshal(key))).build();

    cache.delete(byteArrayKey, options);
  }

  @Override
  public boolean contains(K key) {
    ByteArrayKey byteArrayKey =
        ByteArrayKey.newBuilder().setKey(ByteString.copyFrom(keyMarshaller.marshal(key))).build();

    return cache.contains(byteArrayKey);
  }

  @Override
  public Iterable<Document<K, V>> find(String pattern, GetOption... options) {
    Preconditions.checkArgument(0 == options.length, "options not empty");

    throw new UnsupportedOperationException("find is not supported");
  }

  /*
   deletes all the entries in plan cache.
  */
  @Override
  public void deleteAll() {
    this.cache.deleteAll();
  }
}
