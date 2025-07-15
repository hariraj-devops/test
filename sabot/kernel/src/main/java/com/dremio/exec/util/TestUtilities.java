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
package com.dremio.exec.util;

import static com.dremio.exec.ExecConstants.ICEBERG_NAMESPACE_KEY;
import static com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePluginConfig.SYSTEM_ICEBERG_TABLES_PLUGIN_NAME;
import static com.dremio.exec.store.iceberg.IcebergModelCreator.DREMIO_NESSIE_DEFAULT_NAMESPACE;
import static com.dremio.exec.store.metadatarefresh.MetadataRefreshExecConstants.METADATA_STORAGE_PLUGIN_NAME;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogConstants;
import com.dremio.exec.catalog.CatalogServiceImpl;
import com.dremio.exec.catalog.ManagedStoragePlugin;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.InternalFileConf;
import com.dremio.exec.store.dfs.MetadataStoragePluginConfig;
import com.dremio.exec.store.dfs.SchemaMutability;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePluginConfig;
import com.dremio.plugins.nodeshistory.NodeHistorySourceConfigFactory;
import com.dremio.plugins.nodeshistory.NodesHistoryStoreConfig;
import com.dremio.service.coordinator.proto.DataCredentials;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.NamespaceStore;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;

/**
 * This class contains utility methods to speed up tests. Some of the production code currently
 * calls this method when the production code is executed as part of the test runs. That's the
 * reason why this code has to be in production module.
 */
public class TestUtilities {

  public static final String DFS_TEST_PLUGIN_NAME = "dfs_test";
  public static final String ICEBERG_TEST_TABLES_ROOT_PATH = "/tmp/iceberg-test-tables";

  /**
   * Create and removes a temporary folder
   *
   * @return absolute path to temporary folder
   */
  public static String createTempDir() {
    final File tmpDir = Files.createTempDir();
    tmpDir.deleteOnExit();
    return tmpDir.getAbsolutePath();
  }

  public static void addDefaultTestPlugins(CatalogService catalog, final String tmpDirPath) {
    addDefaultTestPlugins(catalog, tmpDirPath, true);
  }

  public static void addDefaultTestPlugins(
      CatalogService catalogService, final String tmpDirPath, boolean addHadoopDataLakes) {
    if (addHadoopDataLakes) {
      addIcebergHadoopTables(catalogService, tmpDirPath);
    }
    // add dfs.
    Catalog systemUserCatalog = catalogService.getSystemUserCatalog();
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/";
      conf.propertyList =
          Arrays.asList(
              new Property(ExecConstants.ICEBERG_CATALOG_TYPE_KEY, "nessie"),
              new Property(ICEBERG_NAMESPACE_KEY, DREMIO_NESSIE_DEFAULT_NAMESPACE));
      c.setConnectionConf(conf);
      c.setName("dfs");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
    // add dfs_partition_inference.
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/";
      conf.propertyList =
          Arrays.asList(
              new Property(ExecConstants.ICEBERG_CATALOG_TYPE_KEY, "nessie"),
              new Property(ICEBERG_NAMESPACE_KEY, DREMIO_NESSIE_DEFAULT_NAMESPACE));
      conf.isPartitionInferenceEnabled = true;
      c.setConnectionConf(conf);
      c.setName("dfs_partition_inference");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
    // add dfs_test
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = tmpDirPath;
      conf.mutability = SchemaMutability.ALL;
      conf.propertyList =
          Arrays.asList(
              new Property(ExecConstants.ICEBERG_CATALOG_TYPE_KEY, "nessie"),
              new Property(ICEBERG_NAMESPACE_KEY, DREMIO_NESSIE_DEFAULT_NAMESPACE));
      c.setConnectionConf(conf);
      c.setName("dfs_test");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
    addClasspathSource(catalogService);
    // add metadataSink.
    // add dfs_root
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/";
      c.setConnectionConf(conf);
      c.setName("dfs_root");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    // add dacfs
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/";
      c.setConnectionConf(conf);
      c.setName("dacfs");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    if (!pluginExists(catalogService, METADATA_STORAGE_PLUGIN_NAME)) {
      SourceConfig c = new SourceConfig();
      MetadataStoragePluginConfig conf = new MetadataStoragePluginConfig();
      conf.connection = "file:///";
      conf.path = tmpDirPath;
      conf.propertyList =
          Collections.singletonList(
              new Property(ICEBERG_NAMESPACE_KEY, DREMIO_NESSIE_DEFAULT_NAMESPACE));
      c.setConnectionConf(conf);
      c.setName(METADATA_STORAGE_PLUGIN_NAME);
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    if (!pluginExists(catalogService, SYSTEM_ICEBERG_TABLES_PLUGIN_NAME)) {
      SourceConfig c = new SourceConfig();
      SystemIcebergTablesStoragePluginConfig conf = new SystemIcebergTablesStoragePluginConfig();
      conf.connection = "file:///";
      conf.path = tmpDirPath;
      conf.propertyList =
          Collections.singletonList(
              new Property(ICEBERG_NAMESPACE_KEY, DREMIO_NESSIE_DEFAULT_NAMESPACE));
      c.setConnectionConf(conf);
      c.setName(SYSTEM_ICEBERG_TABLES_PLUGIN_NAME);
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    if (!pluginExists(catalogService, NodesHistoryStoreConfig.STORAGE_PLUGIN_NAME)) {
      SourceConfig c =
          NodeHistorySourceConfigFactory.newSourceConfig(
              new File(tmpDirPath).toURI(), DataCredentials.newBuilder().build());
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
  }

  private static void addIcebergHadoopTables(CatalogService catalog, final String tmpDirPath) {
    // add dfs_hadoop.
    Catalog systemUserCatalog = catalog.getSystemUserCatalog();
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/";
      conf.defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;
      c.setConnectionConf(conf);
      c.setName("dfs_hadoop");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
    // dfs_hadoop_mutable
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = "/tmp";
      conf.mutability = SchemaMutability.ALL;
      conf.defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;
      c.setConnectionConf(conf);
      c.setName("dfs_hadoop_mutable");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    // add dfs_test
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = tmpDirPath;
      conf.mutability = SchemaMutability.ALL;
      conf.defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;
      c.setConnectionConf(conf);
      c.setName("dfs_test_hadoop");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    // Need to create a new source `dfs_static_test_hadoop` rooted at a known location because:
    //  1. dfs_hadoop is immutable.
    //  2. dfs_test_hadoop is mutable BUT is rooted at a tmpDirPath which won't work with statically
    // created Iceberg tables.
    {
      SourceConfig c = new SourceConfig();
      InternalFileConf conf = new InternalFileConf();
      conf.connection = "file:///";
      conf.path = ICEBERG_TEST_TABLES_ROOT_PATH;
      conf.mutability = SchemaMutability.ALL;
      conf.defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;
      c.setConnectionConf(conf);
      c.setName("dfs_static_test_hadoop");
      c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
      systemUserCatalog.createSource(c, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }
  }

  private static boolean pluginExists(CatalogService catalogService, String pluginName) {
    try {
      catalogService.getSystemUserCatalog().getSource(pluginName);
      return true;
    } catch (UserException ex) {
      if (!ex.getMessage().contains("Tried to access non-existent source")) {
        throw ex;
      }
      return false;
    }
  }

  public static void addClasspathSource(CatalogService catalogService) {
    // add cp.
    catalogService
        .getSystemUserCatalog()
        .createSource(cp(), SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }

  private static SourceConfig cp() {
    SourceConfig c = new SourceConfig();
    InternalFileConf conf = new InternalFileConf();
    conf.connection = "classpath:///";
    conf.path = "/";
    conf.isInternal = false;
    c.setName("cp");
    c.setConnectionConf(conf);
    c.setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY_WITH_AUTO_PROMOTE);
    return c;
  }

  public static void addClasspathSourceIf(CatalogService catalog) {
    try {
      catalog.createSourceIfMissingWithThrow(cp());
    } catch (ConcurrentModificationException e) {
      // no-op since signature was change to throw
    }
  }

  /**
   * Clear all data except the identified values. Note that this delete all stores except those
   * identified and the namespace stores (namespace/splits). For namespace, it does selective delete
   * based on the list of items that should be maintained in savedPaths.
   *
   * @param catalogService CatalogService
   * @param legacyKVStoreProvider Legacy KV store provider
   * @param kvStoreProvider KV store provider
   * @param savedStores List of kvstores that should be maintained (in addition to namespace).
   * @param savedPaths List of root entities in namespace that should be maintained in addition to a
   *     standard set of internal entities.
   * @throws NamespaceException
   * @throws IOException
   */
  public static void clear(
      CatalogService catalogService,
      LegacyKVStoreProvider legacyKVStoreProvider,
      KVStoreProvider kvStoreProvider,
      List<String> savedStores,
      List<String> savedPaths)
      throws NamespaceException, IOException {
    {
      List<String> list = new ArrayList<>();
      list.add(NamespaceStore.DAC_NAMESPACE);
      list.add(NamespaceServiceImpl.PARTITION_CHUNKS);
      list.add(CatalogConstants.CATALOG_SOURCE_DATA_NAMESPACE);
      list.add("wlmqueue");
      list.add("rulesmanager");
      list.add("wlmqueuecontainerversion");
      list.add("configuration");
      list.add("node_collections");
      list.add("roles_store");
      list.add("sys.options");
      list.add("catalogevent");
      if (savedStores != null) {
        list.addAll(savedStores);
      }
      legacyKVStoreProvider
          .unwrap(LocalKVStoreProvider.class)
          .deleteEverything(list.toArray(new String[0]));
    }

    final NamespaceService namespace =
        new NamespaceServiceImpl(
            kvStoreProvider,
            new CatalogStatusEventsImpl(),
            CatalogEventMessagePublisherProvider.NO_OP);

    List<String> list = new ArrayList<>();
    list.add("__jobResultsStore");
    list.add("__home");
    list.add("__accelerator");
    list.add("__datasetDownload");
    list.add("__support");
    list.add("__metadata");
    list.add(SYSTEM_ICEBERG_TABLES_PLUGIN_NAME);
    list.add(NodesHistoryStoreConfig.STORAGE_PLUGIN_NAME);
    list.add("$scratch");
    list.add("sys");
    list.add("INFORMATION_SCHEMA");
    if (savedPaths != null) {
      list.addAll(savedPaths);
    }

    final Set<String> rootsToSaveSet = ImmutableSet.copyOf(list);

    for (HomeConfig home : namespace.getHomeSpaces()) {
      String name = "@" + home.getOwner();
      if (rootsToSaveSet.contains(name)) {
        continue;
      }

      namespace.deleteHome(new NamespaceKey("@" + home.getOwner()), home.getTag());
    }

    for (SpaceConfig space : namespace.getSpaces()) {
      if (rootsToSaveSet.contains(space.getName())) {
        continue;
      }

      namespace.deleteSpace(new NamespaceKey(space.getName()), space.getTag());
    }

    ((CatalogServiceImpl) catalogService).deleteExcept(rootsToSaveSet);
  }

  public static void updateDfsTestTmpSchemaLocation(
      final CatalogService catalog, final String tmpDirPath) throws ExecutionSetupException {
    final ManagedStoragePlugin msp = catalog.getManagedSource(DFS_TEST_PLUGIN_NAME);
    final FileSystemPlugin plugin = (FileSystemPlugin) catalog.getSource(DFS_TEST_PLUGIN_NAME);
    SourceConfig newConfig = msp.getId().getClonedConfig();
    InternalFileConf conf = (InternalFileConf) plugin.getConfig();
    conf.path = tmpDirPath;
    conf.mutability = SchemaMutability.ALL;
    newConfig.setConfig(conf.toBytesString());
    catalog
        .getSystemUserCatalog()
        .updateSource(newConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }

  public static boolean isAArch64() {
    return "aarch64".equals(System.getProperty("os.arch"));
  }
}
