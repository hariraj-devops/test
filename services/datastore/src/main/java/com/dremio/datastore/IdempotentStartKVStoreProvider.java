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
package com.dremio.datastore;

import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.StoreCreationFunction;
import java.util.Set;

/** Wrapper around underlying KVStoreProvider to make start idempotent. */
public class IdempotentStartKVStoreProvider implements KVStoreProvider {

  private final KVStoreProvider underlyingKVStoreProvider;

  private boolean started;
  private boolean closed;

  public IdempotentStartKVStoreProvider(KVStoreProvider underlyingKVStoreProvider) {
    this.underlyingKVStoreProvider = underlyingKVStoreProvider;
    this.started = false;
    this.closed = false;
  }

  @Override
  public void start() throws Exception {
    if (!started) {
      underlyingKVStoreProvider.start();
      started = true;
    }
  }

  @Override
  public Set<KVStore<?, ?>> stores() {
    return underlyingKVStoreProvider.stores();
  }

  @Override
  public <K, V, T extends KVStore<K, V>> T getStore(
      Class<? extends StoreCreationFunction<K, V, T>> creator) {
    return underlyingKVStoreProvider.getStore(creator);
  }

  @Override
  public <K, V> StoreBuilder<K, V> newStore() {
    return underlyingKVStoreProvider.newStore();
  }

  @Override
  public void close() throws Exception {
    if (started && !closed) {
      underlyingKVStoreProvider.close();
      closed = true;
    }
  }
}
