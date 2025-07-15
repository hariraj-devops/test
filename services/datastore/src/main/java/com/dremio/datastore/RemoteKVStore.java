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

import static com.dremio.telemetry.api.metrics.MeterProviders.newTimerResourceSampleSupplier;
import static com.dremio.telemetry.api.metrics.TimerUtils.timedOperation;
import static java.lang.String.format;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.FindByRange;
import com.dremio.datastore.api.ImmutableDocument;
import com.dremio.datastore.api.IncrementCounter;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.options.KVStoreOptionUtility;
import com.dremio.datastore.api.options.MaxResultsOption;
import com.dremio.datastore.api.options.VersionOption;
import com.dremio.datastore.indexed.PutRequestDocumentWriter;
import com.dremio.exec.rpc.RpcException;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Timer.ResourceSample;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Remote KVStore. Caches store id received from master. */
public class RemoteKVStore<K, V> implements KVStore<K, V> {
  private static final Logger logger = LoggerFactory.getLogger(RemoteKVStore.class);

  private static final String METRIC_PREFIX = "kvstore.remote";

  private enum Stats {
    GET,
    GET_LIST,
    PUT,
    CHECK_AND_PUT,
    CONTAINS,
    DELETE,
    CHECK_AND_DELETE,
    DELETE_VERSION,
    FIND_BY_RANGE,
    FIND_ALL
  }

  private final String storeId;
  private final DatastoreRpcClient client;
  private final Converter<K, byte[]> keyConverter;
  private final Converter<V, byte[]> valueConverter;
  private final StoreBuilderHelper<K, V> helper;

  private final Map<Stats, Supplier<ResourceSample>> metrics;

  @SuppressWarnings("unchecked")
  public RemoteKVStore(DatastoreRpcClient client, String storeId, StoreBuilderHelper<K, V> helper) {
    this.client = client;
    this.storeId = storeId;
    this.helper = helper;
    this.keyConverter =
        (Converter<K, byte[]>) helper.getKeyFormat().apply(ByteSerializerFactory.INSTANCE);
    this.valueConverter =
        (Converter<V, byte[]>) helper.getValueFormat().apply(ByteSerializerFactory.INSTANCE);
    metrics = registerMetrics();
  }

  private Map<Stats, Supplier<ResourceSample>> registerMetrics() {
    final ImmutableMap.Builder<Stats, Supplier<ResourceSample>> builder = ImmutableMap.builder();
    for (Stats stat : Stats.values()) {
      final Supplier<ResourceSample> timer =
          newTimerResourceSampleSupplier(
              METRIC_PREFIX + "." + stat.name().toLowerCase(),
              format("Time taken for %s operation", stat.name()));
      builder.put(stat, timer);
    }
    return builder.build();
  }

  private ResourceSample time(Stats stat) {
    return metrics.get(stat).get();
  }

  private K revertKey(ByteString key) {
    return keyConverter.revert(key.toByteArray());
  }

  private V revertValue(ByteString value) {
    return valueConverter.revert(value.toByteArray());
  }

  private ByteString convertKey(K key) {
    Preconditions.checkNotNull(key);
    return ByteString.copyFrom(keyConverter.convert(key));
  }

  private ByteString convertValue(V value) {
    Preconditions.checkNotNull(value);
    return ByteString.copyFrom(valueConverter.convert(value));
  }

  @Override
  public Document<K, V> get(K key, GetOption... options) {
    return loggedTimedOperation(
        Stats.GET,
        () -> {
          try {
            final Document<ByteString, ByteString> document = client.get(storeId, convertKey(key));
            return convertDocument(document);
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to get from store id: %s", getStoreId()), e);
          }
        });
  }

  public String getStoreId() {
    return storeId;
  }

  @Override
  public KVAdmin getAdmin() {
    throw new UnsupportedOperationException("KV administration can only be done on master node.");
  }

  public DatastoreRpcClient getClient() {
    return client;
  }

  public Converter<K, byte[]> getKeyConverter() {
    return keyConverter;
  }

  public Converter<V, byte[]> getValueConverter() {
    return valueConverter;
  }

  @Override
  public Iterable<Document<K, V>> get(List<K> keys, GetOption... options) {
    return loggedTimedOperation(
        Stats.GET_LIST,
        () -> {
          try {
            List<ByteString> keyLists = Lists.newArrayList();
            for (K key : keys) {
              keyLists.add(convertKey(key));
            }
            return Lists.transform(client.get(storeId, keyLists), this::convertDocument);
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to get multiple values from store id: %s", getStoreId()), e);
          }
        });
  }

  @Override
  public Document<K, V> put(K key, V value, PutOption... options) {
    KVStoreOptionUtility.checkIndexPutOptionIsNotUsed(options);

    final PutRequestDocumentWriter putRequestDocumentWriter = new PutRequestDocumentWriter();
    if (helper.hasDocumentConverter()) {
      helper.getDocumentConverter().doConvert(putRequestDocumentWriter, key, value);
    }

    return loggedTimedOperation(
        Stats.PUT,
        () -> {
          final String tag;
          try {
            final Optional<PutOption> option =
                KVStoreOptionUtility.getCreateOrVersionOption(options);
            if (option.isPresent()) {
              tag =
                  client.put(
                      storeId,
                      convertKey(key),
                      convertValue(value),
                      putRequestDocumentWriter,
                      option.get());
            } else {
              tag =
                  client.put(
                      storeId, convertKey(key), convertValue(value), putRequestDocumentWriter);
            }
          } catch (RpcException e) {
            throw new DatastoreException(format("Failed to put in store id: %s", getStoreId()), e);
          }
          return createDocument(key, value, tag);
        });
  }

  @Override
  public boolean contains(K key, ContainsOption... options) {
    return loggedTimedOperation(
        Stats.CONTAINS,
        () -> {
          try {
            return client.contains(storeId, convertKey(key));
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to check contains for store id: %s", getStoreId()), e);
          }
        });
  }

  @Override
  public void delete(K key, DeleteOption... options) {
    loggedTimedOperation(
        Stats.DELETE,
        () -> {
          try {
            final String deleteOptionTag = VersionOption.getTagInfo(options).getTag();
            client.delete(storeId, convertKey(key), deleteOptionTag);
            return (Void) null;
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to delete from store id: %s", getStoreId()), e);
          }
        });
  }

  @Override
  public Iterable<Document<K, V>> find(FindByRange<K> find, FindOption... options) {
    final RemoteDataStoreProtobuf.FindRequest.Builder request =
        RemoteDataStoreProtobuf.FindRequest.newBuilder().setStoreId(storeId);

    if (find.getStart() != null) {
      request.setStart(convertKey(find.getStart())).setIncludeStart(find.isStartInclusive());
    }
    if (find.getEnd() != null) {
      request.setEnd(convertKey(find.getEnd())).setIncludeEnd(find.isEndInclusive());
    }

    if (options.length > 0) {
      Optional<MaxResultsOption> optionalMaxResults =
          Arrays.stream(options)
              .filter(option -> option instanceof MaxResultsOption)
              .map(option -> (MaxResultsOption) option)
              .findFirst();
      optionalMaxResults.ifPresent(
          maxResultsOption -> request.setMaxResults(maxResultsOption.maxResults()));
    }

    return loggedTimedOperation(
        Stats.FIND_BY_RANGE,
        () -> {
          try {
            return Iterables.transform(client.find(request.build()), this::convertDocument);
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to find by range for store id: %s", getStoreId()), e);
          }
        });
  }

  @Override
  public void bulkIncrement(
      Map<K, List<IncrementCounter>> keysToIncrement, IncrementOption option) {
    throw new UnsupportedOperationException("Bulk increment operation is not supported.");
  }

  @Override
  public void bulkDelete(List<K> keysToDelete, DeleteOption... deleteOptions) {
    for (K key : keysToDelete) {
      delete(key, deleteOptions);
    }
  }

  @Override
  public Iterable<Document<K, V>> find(FindOption... options) {
    return loggedTimedOperation(
        Stats.FIND_ALL,
        () -> {
          try {
            return Iterables.transform(client.find(storeId), this::convertDocument);
          } catch (RpcException e) {
            throw new DatastoreException(
                format("Failed to find all for store id: %s", getStoreId()), e);
          }
        });
  }

  @Override
  public String getName() {
    return helper.getName();
  }

  protected Document<K, V> createDocument(K key, V value, String tag) {
    ImmutableDocument.Builder<K, V> builder = new ImmutableDocument.Builder<>();
    builder.setKey(key);
    builder.setValue(value);
    if (!Strings.isNullOrEmpty(tag)) {
      builder.setTag(tag);
    }
    return builder.build();
  }

  protected Document<K, V> createDocumentFromBytes(ByteString key, ByteString value, String tag) {
    return createDocument(revertKey(key), revertValue(value), tag);
  }

  protected Document<K, V> convertDocument(Document<ByteString, ByteString> document) {
    return (document != null)
        ? createDocumentFromBytes(document.getKey(), document.getValue(), document.getTag())
        : null;
  }

  private <VALUE> VALUE loggedTimedOperation(Stats stat, Supplier<VALUE> operation) {
    try {
      logger.debug("Starting {}", stat);
      return timedOperation(time(stat), operation);
    } finally {
      logger.debug("Finished {}", stat);
    }
  }
}
