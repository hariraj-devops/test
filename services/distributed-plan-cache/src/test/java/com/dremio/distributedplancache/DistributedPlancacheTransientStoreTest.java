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
package com.dremio.distributedplancache;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.transientstore.CaffeineTransientStore;
import com.dremio.datastore.transientstore.Marshaller;
import com.dremio.datastore.transientstore.SimpleDocument;
import com.dremio.datastore.transientstore.TransientStore;
import com.dremio.distributedplancache.transientstore.DistributedPlanCacheTransientStore;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DistributedPlancacheTransientStoreTest {

  private static final byte[] ba1 = new byte[] {1};
  private static final byte[] ba2 = new byte[] {2};
  private static final byte[] ba3 = new byte[] {3};
  private static final byte[] ba4 = new byte[] {4};

  @ParameterizedTest(name = "{0}")
  @MethodSource("cachesToTest")
  public void test(TransientStore<byte[], byte[]> store) {

    store.put(ba1, ba1);
    store.put(ba2, ba2);
    store.put(ba3, ba3);
    // TEST put and get

    Assertions.assertEquals(
        new SimpleDocument<>(ba1, ba1), store.get(ba1), "Value for byte array 1 is retrievable");
    Assertions.assertEquals(
        new SimpleDocument<>(ba2, ba2), store.get(ba2), "Value for byte array 2 is retrievable");
    Assertions.assertEquals(
        new SimpleDocument<>(ba3, ba3), store.get(ba3), "Value for byte array 3 is retrievable");
    Assertions.assertNull(store.get(new byte[4]), "Value for byte array 4 is not retrievable");

    // TEST Replace
    store.put(ba1, ba4);

    Assertions.assertEquals(
        new SimpleDocument<>(ba1, ba4), store.get(ba1), "Value for byte array 1 is replace");

    // TEST non reference keys
    SimpleDocument<byte[], byte[]> result =
        (SimpleDocument<byte[], byte[]>) store.get(new byte[] {1});

    Assertions.assertArrayEquals(
        new byte[] {1}, result.getKey(), "No reference equality works for arrays");
    Assertions.assertArrayEquals(
        new byte[] {4}, result.getValue(), "No reference equality works for arrays");

    // TEST bulk get
    Iterable<Document<byte[], byte[]>> bulkResult = store.get(ImmutableList.of(ba3, ba2));
    ImmutableList<Document<byte[], byte[]>> expectedBulk =
        ImmutableList.of(new SimpleDocument<>(ba3, ba3), new SimpleDocument<>(ba2, ba2));

    Assertions.assertEquals(expectedBulk, bulkResult, "Bulk get returns correct values");

    // TEST contains
    Assertions.assertTrue(store.contains(ba1), "Contains positive");
    Assertions.assertFalse(store.contains(new byte[4]), "Contains negative");

    // TEST delete
    try {
      store.delete(new byte[4]);
    } catch (Throwable t) {
      // Assert No exception is thrown
      Assertions.fail(t.getMessage());
    }
    store.delete(ba1);
    Assertions.assertNull(store.get(ba1), "byte array 1 is deleted");
  }

  private static Stream<Arguments> cachesToTest() {

    return Stream.of(
        Arguments.arguments(
            new DistributedPlanCacheTransientStore<>(
                new CaffeineTransientStore<>(Caffeine.newBuilder().build()),
                Marshaller.BYTE_MARSHALLER,
                Marshaller.BYTE_MARSHALLER)));
  }
}
