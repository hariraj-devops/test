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

import com.dremio.exec.catalog.ManagedStoragePlugin;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.google.common.io.Files;
import java.io.File;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Test when the file limit is hit for directories when using external file systems. */
public class TestMaxFileLimit extends TestSubPathFileSystemPlugin {

  @ClassRule public static TemporaryFolder testFolder = TestSubPathFileSystemPlugin.testFolder;

  @BeforeClass
  public static void setup() throws Exception {
    setDfsMaxFiles(1);
    TestSubPathFileSystemPlugin.generateTestData();
    generateManyExcelFiles();
    addSubPathDfsExternalPlugin();
  }

  private static void setDfsMaxFiles(int maxFiles) throws Exception {
    testNoResult(
        "alter system set \"%s\" = " + maxFiles, FileDatasetHandle.DFS_MAX_FILES.getOptionName());
  }

  private static void generateManyExcelFiles() throws Exception {
    File dstFolder = new File(storageBase, "largeExcel");
    Files.createParentDirs(dstFolder);

    String excelFilePath =
        System.getProperty("user.dir") + "/src/test/resources/test_folder_xlsx/simple.xlsx";
    File srcFile = new File(excelFilePath);
    int numFilesToCreate = 3;
    for (int i = 0; i < numFilesToCreate; i++) {
      File dstFile = new File(storageBase, "largeExcel/file" + i + ".xlsx");
      Files.createParentDirs(dstFile);
      Files.copy(srcFile, dstFile);
    }
  }

  private static void addSubPathDfsExternalPlugin() throws Exception {
    final CatalogService catalogService = getCatalogService();
    final ManagedStoragePlugin msp = catalogService.getManagedSource("dfs_test");
    StoragePluginId pluginId = msp.getId();
    InternalFileConf nasConf = pluginId.getConnectionConf();
    nasConf.path = storageBase.getPath();
    nasConf.mutability = SchemaMutability.ALL;

    // Add one configuration for testing when internal is true
    nasConf.isInternal = false;

    SourceConfig config = pluginId.getConfig();
    config.setId(null);
    config.setTag(null);
    config.setConfigOrdinal(null);
    config.setName("subPathDfs");
    config.setConfig(nasConf.toBytesString());
    catalogService
        .getSystemUserCatalog()
        .createSource(config, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }

  @Test
  public void testTooManyFiles() throws Exception {
    try (AutoCloseable changeMaxTextFiles =
        withSystemOption(FileDatasetHandle.DFS_MAX_TEXT_FILES, 1)) {
      errorMsgTestHelper(
          "SELECT * FROM subPathDfs.\"largeDir\"",
          "VALIDATION ERROR: Number of files in dataset 'largeDir' contained 2 files which exceeds the maximum number of files of 1");
    }
    test("SELECT * FROM subPathDfs.\"largeDir\"");
  }

  @Test
  public void testTooManyFilesAfterRefreshExternal() throws Exception {
    try (AutoCloseable changeMaxTextFiles =
        withSystemOption(FileDatasetHandle.DFS_MAX_TEXT_FILES, 1)) {
      test("SELECT * FROM subPathDfs.\"largeDir2\"");

      // Add a new file to the large data set.
      final File tempFile = new File(storageBase, "largeDir2/tbl2.csv");
      generateTestDataFile(tempFile, "%d,key_%d");

      // Should not be queryable due to refresh.
      try {
        errorMsgTestHelper(
            "ALTER PDS subPathDfs.\"largeDir2\" REFRESH METADATA",
            "SYSTEM ERROR: FileCountTooLargeException: Number of files in dataset 'largeDir2' contained 2 files which exceeds the maximum number of files of 1");
      } finally {
        tempFile.delete();
      }

      // Verify re-queryable after the delete.
      test("ALTER PDS subPathDfs.\"largeDir2\" REFRESH METADATA");
      test("SELECT * FROM subPathDfs.\"largeDir2\"");
    }
  }

  @Test
  public void testTooManyExcelFiles() throws Exception {
    try (AutoCloseable changeMaxExcelFiles =
        withSystemOption(FileDatasetHandle.DFS_MAX_EXCEL_FILES, 2)) {
      errorMsgTestHelper(
          "SELECT * FROM subPathDfs.\"largeExcel\"",
          "VALIDATION ERROR: Number of files in dataset 'largeExcel' contained 3 files which exceeds the maximum number of files of 2");

      // Set global Limit to 3 to see if that interferes with Excel-specific limit - it should not
      setDfsMaxFiles(1);
      errorMsgTestHelper(
          "SELECT * FROM subPathDfs.\"largeExcel\"",
          "VALIDATION ERROR: Number of files in dataset 'largeExcel' contained 3 files which exceeds the maximum number of files of 2");
    }

    test("SELECT * FROM subPathDfs.\"largeExcel\"");
  }

  @Test
  public void testTooManyJsonFiles() throws Exception {
    try (AutoCloseable changeMaxExcelFiles =
        withSystemOption(FileDatasetHandle.DFS_MAX_JSON_FILES, 3)) {
      errorMsgTestHelper(
          "SELECT * FROM subPathDfs.\"largeJsonDir\"",
          "VALIDATION ERROR: Number of files in dataset 'largeJsonDir' contained 4 files which exceeds the maximum number of files of 3");
    }

    test("SELECT * FROM subPathDfs.\"largeJsonDir\"");
  }

  @AfterClass
  public static void shutdown() throws Exception {
    TestSubPathFileSystemPlugin.shutdown();
  }
}
