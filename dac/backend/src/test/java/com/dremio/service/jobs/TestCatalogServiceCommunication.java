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
package com.dremio.service.jobs;

import static org.junit.Assert.assertTrue;

import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.dac.server.BaseTestServer;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.TestCatalogServiceImpl;
import com.dremio.exec.catalog.TestCatalogServiceImpl.MockUpPlugin;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.inject.Provider;
import org.junit.Before;
import org.junit.Test;

/** Tests for catalog service communication. */
public class TestCatalogServiceCommunication extends BaseTestServer {

  private static MockUpPlugin mockUpPlugin;

  @Before
  public void setup() throws Exception {
    clearAllDataExceptUser();

    mockUpPlugin = new MockUpPlugin();
  }

  @Test
  public void changeConfigAndExpectMessage() throws Exception {
    if (!isMultinode()) {
      return;
    }

    Long lastModifiedTime = null;
    Catalog systemUserCatalog = getCatalogService().getSystemUserCatalog();
    {
      final SourceConfig mockUpConfig =
          new SourceConfig()
              .setName(MOCK_UP)
              .setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY)
              .setConnectionConf(new MockUpConfig());

      doMockDatasets(mockUpPlugin, ImmutableList.of());

      systemUserCatalog.createSource(mockUpConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);

      lastModifiedTime =
          getExecutorDaemon()
              .getInstance(CatalogService.class)
              .getManagedSource(MOCK_UP)
              .getConfig()
              .getLastModifiedAt();

      assertTrue(
          getExecutorDaemon()
              .getInstance(CatalogService.class)
              .getManagedSource(MOCK_UP)
              .matches(mockUpConfig));
    }

    {
      SourceConfig source = l(NamespaceService.class).getSource(new NamespaceKey(MOCK_UP));
      final SourceConfig mockUpConfig =
          new SourceConfig()
              .setName(MOCK_UP)
              .setId(source.getId())
              .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
              .setTag(source.getTag())
              .setConfigOrdinal(source.getConfigOrdinal())
              .setConnectionConf(new MockUpConfig());

      systemUserCatalog.updateSource(mockUpConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);

      Long newLastModifiedTime =
          getExecutorDaemon()
              .getInstance(CatalogService.class)
              .getManagedSource(MOCK_UP)
              .getConfig()
              .getLastModifiedAt();

      assertTrue(
          getExecutorDaemon()
              .getInstance(CatalogService.class)
              .getManagedSource(MOCK_UP)
              .matches(mockUpConfig));

      assertTrue(newLastModifiedTime > lastModifiedTime);

      systemUserCatalog.deleteSource(mockUpConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
  }

  public static final String MOCK_UP = "mockup2";

  /** Mock source config. */
  @SourceType(value = MOCK_UP, configurable = false)
  public static class MockUpConfig
      extends ConnectionConf<TestCatalogServiceImpl.MockUpConfig, MockUpPlugin> {

    @Override
    public MockUpPlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return mockUpPlugin;
    }
  }

  private void doMockDatasets(StoragePlugin plugin, final List<DatasetHandle> datasets)
      throws Exception {
    ((MockUpPlugin) plugin).setDatasets(datasets);
  }
}
