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
package com.dremio.exec.catalog;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.sample.SampleSourceMetadata;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.test.DremioTest;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Test for CatalogUtil */
public class TestCatalogUtil {
  private LegacyKVStoreProvider kvStoreProvider;
  private NamespaceService namespaceService;
  private DatasetMetadataSaver metadataSaver;

  @Before
  public void setup() throws Exception {
    LocalKVStoreProvider storeProvider =
        new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    storeProvider.start();
    kvStoreProvider = storeProvider.asLegacy();
    kvStoreProvider.start();
    namespaceService =
        new NamespaceServiceImpl(
            storeProvider,
            new CatalogStatusEventsImpl(),
            CatalogEventMessagePublisherProvider.NO_OP);
  }

  @After
  public void cleanup() throws Exception {
    kvStoreProvider.close();
  }

  @Test
  public void testSavePartitionChunks() throws ConnectorException {
    final int numPartitionChunks = 10;
    final int numSplitsPerChunk = 20;
    long recordCountFromSplits = 0;
    final SampleSourceMetadata s1 = new SampleSourceMetadata();
    final NamespaceKey dsPath = new NamespaceKey(asList("dataset_10_20"));
    s1.addNDatasets(1, numPartitionChunks, numSplitsPerChunk);
    final List<DatasetHandle> dsHandles = ImmutableList.copyOf(s1.listDatasetHandles().iterator());
    assertEquals(1, dsHandles.size());
    final DatasetHandle ds = dsHandles.get(0);

    final DatasetConfig datasetConfig = new DatasetConfig();

    datasetConfig.setId(new EntityId().setId(UUID.randomUUID().toString()));
    datasetConfig.setName(ds.getDatasetPath().getName());
    datasetConfig.setFullPathList(ds.getDatasetPath().getComponents());
    datasetConfig.setType(DatasetType.PHYSICAL_DATASET);

    ReadDefinition readDefinition = new ReadDefinition();
    readDefinition.setSplitVersion((long) 1);
    datasetConfig.setReadDefinition(readDefinition);
    List<List<DatasetSplit>> splits = s1.getDatasetSplitsForDataset(ds);
    AtomicInteger recordCountFromSource = new AtomicInteger();
    splits.stream()
        .flatMap(List::stream)
        .forEach(x -> recordCountFromSource.addAndGet((int) x.getRecordCount()));

    try {
      DatasetMetadataSaver metadataSaver =
          namespaceService.newDatasetMetadataSaver(
              dsPath,
              datasetConfig.getId(),
              NamespaceService.SplitCompression.SNAPPY,
              Long.MAX_VALUE,
              false);
      final PartitionChunkListing chunkListing = s1.listPartitionChunks(ds);
      recordCountFromSplits =
          CatalogUtil.savePartitionChunksInSplitsStores(metadataSaver, chunkListing);
    } catch (IOException e) {
    }
    assertEquals(recordCountFromSource.get(), recordCountFromSplits);
  }
}
