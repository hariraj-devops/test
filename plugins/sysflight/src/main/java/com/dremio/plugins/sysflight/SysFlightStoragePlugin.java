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
package com.dremio.plugins.sysflight;

import com.dremio.common.AutoCloseables;
import com.dremio.common.utils.PathUtils;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CatalogUser;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.SupportsReadingViews;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.server.JobResultInfoProvider;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.exec.store.Views;
import com.dremio.exec.store.dfs.system.SystemIcebergTableMetadataFactory;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePlugin;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePluginConfig;
import com.dremio.exec.store.dfs.system.SystemIcebergViewMetadataFactory;
import com.dremio.exec.store.sys.SystemStoragePlugin;
import com.dremio.exec.store.sys.SystemTable;
import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.users.SystemUser;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Provider;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightGrpcUtils;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.memory.BufferAllocator;

/** Plugin for System tables using Flight protocol, also aware of tables in {@link SystemTable} */
public class SysFlightStoragePlugin
    implements StoragePlugin, SupportsListingDatasets, SupportsReadingViews {
  private final Map<EntityPath, SystemTable> legacyTableMap =
      Stream.of(SystemTable.values())
          .collect(
              Collectors.toMap(
                  systemTable -> canonicalize(systemTable.getDatasetPath()), Function.identity()));
  private volatile Set<EntityPath> flightTableList = new HashSet<>();

  private final PluginSabotContext context;
  private final String name;
  private final boolean useConduit;
  private final BufferAllocator allocator;
  private final JobResultInfoProvider jobResultInfoProvider;
  private final NodesHistoryViewResolver nodeHistoryViewResolver;

  private volatile FlightClient flightClient;
  private volatile ManagedChannel prevChannel;
  private Supplier<SystemIcebergTablesStoragePlugin> systemIcebergTablesStoragePlugin;

  public SysFlightStoragePlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider,
      boolean useConduit,
      List<SystemTable> excludeLegacyTablesList) {
    excludeLegacyTables(legacyTableMap, excludeLegacyTablesList);
    this.context = pluginSabotContext;
    this.jobResultInfoProvider = pluginSabotContext.getJobResultInfoProvider();
    this.name = name;
    this.useConduit = useConduit;
    allocator =
        pluginSabotContext
            .getAllocator()
            .newChildAllocator(SysFlightStoragePlugin.class.getName(), 0, Long.MAX_VALUE);

    if (pluginSabotContext
        .getOptionManager()
        .getOption(ExecConstants.ENABLE_SYSTEM_ICEBERG_TABLES_STORAGE)) {
      systemIcebergTablesStoragePlugin =
          Suppliers.memoize(
              () ->
                  pluginSabotContext
                      .getCatalogService()
                      .getSource(
                          SystemIcebergTablesStoragePluginConfig
                              .SYSTEM_ICEBERG_TABLES_PLUGIN_NAME));
    }
    this.nodeHistoryViewResolver =
        new NodesHistoryViewResolver(pluginSabotContext::getOptionManager);
  }

  Map<EntityPath, SystemTable> getLegacyTableMap() {
    return legacyTableMap;
  }

  public FlightClient getFlightClient() {
    final ManagedChannel curChannel;
    if (useConduit) {
      curChannel = context.getConduitProvider().getOrCreateChannelToMaster();
    } else {
      curChannel = context.getConduitProvider().getOrCreateChannel(context.getEndpoint());
    }

    if (prevChannel != curChannel) {
      synchronized (this) {
        if (prevChannel != curChannel) {
          AutoCloseables.closeNoChecked(flightClient);
          flightClient = FlightGrpcUtils.createFlightClient(allocator, curChannel);
          prevChannel = curChannel;
        }
      }
    }

    Preconditions.checkNotNull(flightClient, "FlightClient not instantiated");
    return flightClient;
  }

  BufferAllocator getAllocator() {
    return allocator;
  }

  public PluginSabotContext getSabotContext() {
    return context;
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    return true;
  }

  @Override
  public SourceState getState() {
    return SourceState.GOOD;
  }

  @Override
  public Optional<ViewTable> getView(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    if (tableSchemaPath.size() == 2
        && systemIcebergTablesStoragePlugin != null
        && systemIcebergTablesStoragePlugin.get().isSupportedTablePath(tableSchemaPath)) {
      return Optional.ofNullable(
          systemIcebergTablesStoragePlugin
              .get()
              .getViewTable(tableSchemaPath, schemaConfig.getUserName()));
    }

    if (nodeHistoryViewResolver.isSupportedView(tableSchemaPath)) {
      return Optional.ofNullable(nodeHistoryViewResolver.getViewTable());
    }

    if (!JobResultInfoProvider.isJobResultsTable(tableSchemaPath)) {
      return Optional.empty();
    }

    final String jobId = Iterables.getLast(tableSchemaPath);
    final Optional<JobResultInfoProvider.JobResultInfo> jobResultInfo =
        jobResultInfoProvider.getJobResultInfo(jobId, schemaConfig.getUserName());

    return Optional.ofNullable(
        jobResultInfo
            .map(
                info -> {
                  final View view =
                      Views.fieldTypesToView(
                          jobId,
                          getJobResultsQuery(info.getResultDatasetPath()),
                          ViewFieldsHelper.getBatchSchemaFields(info.getBatchSchema()),
                          null);

                  return new ViewTable(
                      new NamespaceKey(tableSchemaPath),
                      view,
                      CatalogUser.from(SystemUser.SYSTEM_USERNAME),
                      info.getBatchSchema());
                })
            .orElse(null));
  }

  private static String getJobResultsQuery(List<String> resultDatasetPath) {
    return String.format("SELECT * FROM %s", PathUtils.constructFullPath(resultDatasetPath));
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return SysFlightRulesFactory.class;
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    return SourceCapabilities.NONE;
  }

  @Override
  public void start() throws IOException {}

  @Override
  public void close() throws Exception {
    AutoCloseables.close(flightClient, allocator);
  }

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    final Map<EntityPath, DatasetHandle> tablesMap = new HashMap<>();

    getFlightTableList(true).forEach(e -> tablesMap.put(e, getDataset(e).get()));
    getLegacyTableMap()
        .forEach((k, v) -> tablesMap.computeIfAbsent(k, value -> getDataset(k).get()));

    return () -> (Iterator<? extends DatasetHandle>) tablesMap.values().iterator();
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) {
    return getDataset(datasetPath);
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle,
      PartitionChunkListing chunkListing,
      GetMetadataOption... options) {
    return datasetHandle.unwrap(SystemTableWrapper.class);
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) {
    return datasetHandle.unwrap(SystemTableWrapper.class);
  }

  /**
   * Retrieve system tables (such as the system iceberg tables) that cannot be listed using
   * FlightClient and are need to be gathered from elsewhere.
   *
   * @return A list of {@link EntityPath} objects representing the qualified paths of system tables.
   */
  public List<EntityPath> getUnlistedSystemTablesOrViews() {
    List<EntityPath> unlistedDatasets =
        Arrays.stream(SystemIcebergTableMetadataFactory.SupportedSystemIcebergTable.values())
            .map(
                table ->
                    new EntityPath(Arrays.asList(SystemStoragePlugin.NAME, table.getTableName())))
            .collect(Collectors.toList());
    unlistedDatasets.add(
        new EntityPath(
            Arrays.asList(
                SystemStoragePlugin.NAME,
                SystemIcebergViewMetadataFactory.SupportedSystemIcebergView.COPY_ERRORS_HISTORY
                    .getViewName())));
    return unlistedDatasets;
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    return false;
  }

  static EntityPath canonicalize(EntityPath entityPath) {
    return new EntityPath(
        entityPath.getComponents().stream().map(String::toLowerCase).collect(Collectors.toList()));
  }

  private Optional<DatasetHandle> getDataset(EntityPath entityPath) {
    entityPath = canonicalize(entityPath);

    if (getFlightTableList().contains(entityPath)) {
      return Optional.of(
          SystemTableWrapper.wrap(new SysFlightTable(entityPath, getFlightClient())));
    } else if (getLegacyTableMap().containsKey(entityPath)) {
      return Optional.of(SystemTableWrapper.wrap(getLegacyTableMap().get(entityPath)));
    }
    return Optional.empty();
  }

  private Set<EntityPath> getFlightTableList(boolean refresh) {
    if (refresh || flightTableList.isEmpty()) {
      Set<EntityPath> flightList = new HashSet<>();
      Iterable<FlightInfo> flightInfos = getFlightClient().listFlights(Criteria.ALL);
      flightInfos.forEach(e -> flightList.add(getEntityPath(e.getDescriptor().getPath())));
      flightTableList = ImmutableSet.copyOf(flightList);
    }
    return flightTableList;
  }

  private Set<EntityPath> getFlightTableList() {
    return getFlightTableList(false);
  }

  Optional<SystemTable> getLegacyDataset(EntityPath datasetPath) {
    if (!getFlightTableList().contains(datasetPath)) {
      return Optional.ofNullable(getLegacyTableMap().get(canonicalize(datasetPath)));
    } else {
      return Optional.empty();
    }
  }

  boolean isDistributed(EntityPath datasetPath) {
    Optional<SystemTable> datasetHandle =
        Optional.ofNullable(getLegacyTableMap().get(canonicalize(datasetPath)));
    return datasetHandle.map(SystemTable::isDistributed).orElse(false);
  }

  void excludeLegacyTables(
      Map<EntityPath, SystemTable> legacyTableMap, List<SystemTable> excludeLegacyTablesList) {
    if (excludeLegacyTablesList != null && !excludeLegacyTablesList.isEmpty()) {
      excludeLegacyTablesList.forEach(e -> legacyTableMap.remove(canonicalize(e.getDatasetPath())));
    }
  }

  // if any path component contains '.', then split it into separate components.
  private EntityPath getEntityPath(List<String> inputList) {
    List<String> components = new ArrayList<>();
    components.add(name);
    for (String input : inputList) {
      if (input.contains(".")) {
        components.addAll(Arrays.asList(input.split("\\.")));
      } else {
        components.add(input);
      }
    }
    return canonicalize(new EntityPath(components));
  }
}
