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
package com.dremio.plugins.icebergcatalog.store;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.dremio.BaseTestQuery;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.Property;
import java.util.ArrayList;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class TestRestIcebergCatalogPluginConfig extends BaseTestQuery {

  private RestIcebergCatalogPlugin restIcebergCatalogPlugin;
  private StoragePluginId storagePluginId;

  @Before
  public void setup() {
    RestIcebergCatalogPluginConfig pluginConfig = new RestIcebergCatalogPluginConfig();
    pluginConfig.propertyList = new ArrayList<>();
    pluginConfig.propertyList.add(new Property("testPropertyName", "testPropertyValue"));
    pluginConfig.secretPropertyList = new ArrayList<>();
    pluginConfig.secretPropertyList.add(
        new Property("testSecretPropertyName", "testSecretPropertyValue"));

    storagePluginId = mock(StoragePluginId.class);
    restIcebergCatalogPlugin =
        new RestIcebergCatalogPlugin(
            pluginConfig, getSabotContext(), "test", () -> storagePluginId);
  }

  @Test
  public void testCreateRestCatalog() throws Exception {
    Configuration conf = new Configuration();
    try (MockedStatic<CatalogUtil> mockCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockCatalogUtil
          .when(() -> CatalogUtil.loadCatalog(any(), any(), any(), any()))
          .thenReturn(mock(RESTCatalog.class));
      CatalogAccessor catalogAccessor = restIcebergCatalogPlugin.createCatalog(conf);
      try {
        catalogAccessor.checkState();
      } catch (Exception e) {
        if (e instanceof IllegalArgumentException) {
          // Ignore it, createRestCatalog() is evaluated lazily through catalogSupplier
          // this checkState() call is to trigger the createRestCatalog(). So later loadCatalog()
          // arguments can be asserted.
        } else {
          throw e;
        }
      }
      ArgumentCaptor<Map<String, String>> argument = ArgumentCaptor.forClass(Map.class);
      mockCatalogUtil.verify(
          () -> CatalogUtil.loadCatalog(any(), any(), argument.capture(), any()));
      Map<String, String> properties = argument.getValue();
      assertTrue(properties.containsKey("testPropertyName"));
      assertTrue(properties.containsKey("testSecretPropertyName"));
    }
  }
}
