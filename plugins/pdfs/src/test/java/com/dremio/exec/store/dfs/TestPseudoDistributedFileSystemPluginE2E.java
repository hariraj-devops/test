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
package com.dremio.exec.store.dfs;

import com.dremio.BaseTestQuery;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.parquet.ParquetFormatConfig;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.services.fabric.api.FabricService;
import com.dremio.test.DremioTest;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** An end-to-end test for PDFS */
@Ignore("DX-5178")
public class TestPseudoDistributedFileSystemPluginE2E extends BaseTestQuery {

  @ClassRule public static final TemporaryFolder TEMPORARY_FOLDER = new TemporaryFolder();

  private static PDFSService service;

  @BeforeClass
  public static void setupClass() throws Exception {
    Map<String, FormatPluginConfig> formats =
        ImmutableMap.of("parquet", (FormatPluginConfig) new ParquetFormatConfig());
    WorkspaceConfig workspace =
        new WorkspaceConfig(TEMPORARY_FOLDER.newFolder().getAbsolutePath(), true, "parquet");
    String path = TEMPORARY_FOLDER.newFolder().getAbsolutePath();

    service =
        new PDFSService(
            getProvider(FabricService.class),
            getProvider(NodeEndpoint.class),
            () -> getInstance(ClusterCoordinator.class).getExecutorEndpoints(),
            DremioTest.DEFAULT_SABOT_CONFIG,
            getDremioRootAllocator());
    service.start();

    SourceConfig c = new SourceConfig();
    PDFSConf conf = new PDFSConf();
    conf.path = path;
    c.setType(conf.getType());
    c.setName("pdfs");
    c.setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY);
    c.setConfig(conf.toBytesString());
    getCatalogService()
        .getSystemUserCatalog()
        .createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }

  @AfterClass
  public static void teardownClass() throws Exception {
    service.close();
  }

  @Test
  public void test() throws Exception {
    testNoResult("CREATE TABLE pdfs.test_table AS SELECT * FROM cp.\"employees.json\"");

    testBuilder()
        .sqlQuery("SELECT * FROM pdfs.test_table")
        .ordered()
        .jsonBaselineFile("employees.json")
        .build()
        .run();

    testNoResult("DROP TABLE pdfs.test_table");
  }
}
