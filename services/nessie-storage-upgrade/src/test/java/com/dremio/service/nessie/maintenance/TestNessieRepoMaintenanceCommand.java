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
package com.dremio.service.nessie.maintenance;

import static com.dremio.test.DremioTest.CLASSPATH_SCAN_RESULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.service.embedded.catalog.EmbeddedUnversionedStore;
import com.dremio.service.namespace.NamespaceStore;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.nessie.maintenance.NessieRepoMaintenanceCommand.Options;
import com.dremio.services.nessie.grpc.api.CommitOperation;
import com.dremio.services.nessie.grpc.api.Content;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.projectnessie.model.ContentKey;
import org.slf4j.helpers.MessageFormatter;

class TestNessieRepoMaintenanceCommand {

  private LocalKVStoreProvider storeProvider;
  private EmbeddedUnversionedStore store;

  @BeforeEach
  void createKVStore() throws Exception {
    storeProvider = new LocalKVStoreProvider(CLASSPATH_SCAN_RESULT, null, true, false); // in-memory
    storeProvider.start();

    store = new EmbeddedUnversionedStore(() -> storeProvider);
  }

  @AfterEach
  void stopKVStore() throws Exception {
    if (storeProvider != null) {
      storeProvider.close();
    }
  }

  @Test
  void testListKeys() throws Exception {
    // Just a smoke test. This option is not meant for production use.
    NessieRepoMaintenanceCommand.execute(
        storeProvider, Options.parse(new String[] {"--list-keys"}));
  }

  private CommitOperation putTable(ContentKey key, String loc) {
    return CommitOperation.newBuilder()
        .setPut(
            com.dremio.services.nessie.grpc.api.Put.newBuilder()
                .setKey(
                    com.dremio.services.nessie.grpc.api.ContentKey.newBuilder()
                        .addAllElements(key.getElements())
                        .build())
                .setContent(
                    Content.newBuilder()
                        .setIceberg(
                            com.dremio.services.nessie.grpc.api.IcebergTable.newBuilder()
                                .setMetadataLocation(loc)
                                .build())
                        .build())
                .build())
        .build();
  }

  @Test
  void testListObsoleteInternalKeys() throws Exception {
    String tableId2 = "8ec5373f-d2a6-4b1a-a870-e18046bbd6ae";
    String tableId3 = "34dadc3a-ae44-4e61-b78e-0295c089df70";
    store.commit(
        ImmutableList.of(
            putTable(ContentKey.of("test1"), "t1"),
            putTable(ContentKey.of("dremio.internal", "test2/" + tableId2), "t2"),
            putTable(ContentKey.of("dremio.internal", "test3/" + tableId3), "t3")));

    NamespaceStore namespace = new NamespaceStore(() -> storeProvider);
    IcebergMetadata metadata = new IcebergMetadata();
    metadata.setTableUuid(tableId3);
    metadata.setMetadataFileLocation("test-location");
    namespace.put(
        "dataset3",
        new NameSpaceContainer()
            .setFullPathList(ImmutableList.of("ns", "dataset"))
            .setType(NameSpaceContainer.Type.DATASET)
            .setDataset(
                new DatasetConfig()
                    .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FILE)
                    .setId(new EntityId("ds-id3"))
                    .setPhysicalDataset(new PhysicalDataset().setIcebergMetadata(metadata))));

    // Note: This DataSet has null TableUuid in IcebergMetadata, which represents a first-class
    // Iceberg table,
    // not a DataSet promoted from plain Parquet files.
    String dataset4Tag =
        namespace
            .put(
                "dataset4",
                new NameSpaceContainer()
                    .setFullPathList(ImmutableList.of("ns", "dataset4"))
                    .setType(NameSpaceContainer.Type.DATASET)
                    .setDataset(
                        new DatasetConfig()
                            .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FILE)
                            .setId(new EntityId("ds-id4"))
                            .setPhysicalDataset(
                                new PhysicalDataset().setIcebergMetadata(new IcebergMetadata()))))
            .getTag();

    List<String> log = new ArrayList<>();
    NessieRepoMaintenanceCommand.execute(
        storeProvider,
        Options.parse(new String[] {"--list-obsolete-internal-keys"}),
        (msg, args) -> log.add(MessageFormatter.arrayFormat(msg, args).getMessage()));

    // Note: "test1" is not an "internal" key, so it is not reported
    assertThat(log).containsExactly("dremio.internal|test2/8ec5373f-d2a6-4b1a-a870-e18046bbd6ae");

    String tableId4 = "e14e4ecb-d39c-4311-84fc-2127cc11f195";
    IcebergMetadata metadata4 = new IcebergMetadata();
    metadata4.setTableUuid(tableId4);
    metadata4.setMetadataFileLocation("test-location");
    namespace.put(
        "dataset4",
        new NameSpaceContainer()
            .setFullPathList(ImmutableList.of("ns", "dataset4"))
            .setType(NameSpaceContainer.Type.DATASET)
            .setDataset(
                new DatasetConfig()
                    .setType(DatasetType.PHYSICAL_DATASET_SOURCE_FILE)
                    .setId(new EntityId("ds-id4"))
                    .setTag(dataset4Tag)
                    .setPhysicalDataset(new PhysicalDataset().setIcebergMetadata(metadata4))));

    log.clear();
    assertThatThrownBy(
            () ->
                NessieRepoMaintenanceCommand.execute(
                    storeProvider,
                    Options.parse(new String[] {"--list-obsolete-internal-keys"}),
                    (msg, args) -> log.add(MessageFormatter.arrayFormat(msg, args).getMessage())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Keys for some table IDs were not found");

    assertThat(log)
        .containsExactly(
            "dremio.internal|test2/8ec5373f-d2a6-4b1a-a870-e18046bbd6ae",
            "Live metadata table ID: e14e4ecb-d39c-4311-84fc-2127cc11f195 does not have a corresponding Nessie key");
  }
}
