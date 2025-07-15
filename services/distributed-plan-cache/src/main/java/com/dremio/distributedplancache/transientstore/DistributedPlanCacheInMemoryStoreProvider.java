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

import com.dremio.datastore.transientstore.CaffeineTransientStore;
import com.dremio.datastore.transientstore.InMemoryTransientStoreProvider;
import com.dremio.datastore.transientstore.TransientStore;
import com.dremio.proto.model.TransientStore.ByteArrayKey;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;

/*
 In memory implementation for DPC store provider, it uses caffeine cache.
*/
public class DistributedPlanCacheInMemoryStoreProvider extends InMemoryTransientStoreProvider
    implements DistributedPlanCacheStoreProvider {

  @Override
  public <T extends TransientStore<ByteArrayKey, byte[]>> T provideStore(
      long maxByteSize, long expiresAfterMinute) {
    return (T)
        new CaffeineTransientStore<ByteArrayKey, byte[]>(
            Caffeine.newBuilder()
                .maximumWeight(maxByteSize)
                .weigher((key, cachedPlan) -> ((byte[]) cachedPlan).length)
                .softValues()
                .expireAfterAccess(expiresAfterMinute, TimeUnit.MINUTES)
                .build());
  }
}
