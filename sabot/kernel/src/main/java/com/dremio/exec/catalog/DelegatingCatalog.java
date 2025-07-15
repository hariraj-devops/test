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

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.exception.CatalogUnsupportedOperationException;
import com.dremio.catalog.exception.UnsupportedForgetTableException;
import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.concurrent.bulk.BulkRequest;
import com.dremio.common.concurrent.bulk.BulkResponse;
import com.dremio.common.expression.CompleteType;
import com.dremio.connector.metadata.AttributeValue;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.FindByCondition;
import com.dremio.datastore.api.FindByRange;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.ColumnExtendedProperty;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.NoDefaultBranchException;
import com.dremio.exec.store.PartitionNotFoundException;
import com.dremio.exec.store.ReferenceConflictException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.service.catalog.Schema;
import com.dremio.service.catalog.SearchQuery;
import com.dremio.service.catalog.Table;
import com.dremio.service.catalog.TableSchema;
import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.DatasetConfigAndEntitiesOnPath;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.EntityNamespaceFindOption;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceFindByRange;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceType;
import com.dremio.service.namespace.PartitionChunkId;
import com.dremio.service.namespace.PartitionChunkMetadata;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.dataset.DatasetMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.SourceMetadata;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.schema.Function;

/** Delegating implementation of {@link Catalog} */
public abstract class DelegatingCatalog implements Catalog {

  private final Catalog delegate;

  public DelegatingCatalog(Catalog delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate catalog required");
  }

  @Override
  public DremioTable getTableNoResolve(NamespaceKey key) {
    return delegate.getTableNoResolve(key);
  }

  @Override
  public DremioTable getTableNoResolve(CatalogEntityKey catalogEntityKey) {
    return delegate.getTableNoResolve(catalogEntityKey);
  }

  @Override
  public DremioTable getTableNoColumnCount(NamespaceKey key) {
    return delegate.getTableNoColumnCount(key);
  }

  @Override
  public void addOrUpdateDataset(NamespaceKey key, DatasetConfig dataset)
      throws NamespaceException {
    delegate.addOrUpdateDataset(key, dataset);
  }

  @Override
  public void addPrimaryKey(
      NamespaceKey namespaceKey, List<String> columns, VersionContext statementSourceVersion) {
    delegate.addPrimaryKey(namespaceKey, columns, statementSourceVersion);
  }

  @Override
  public void dropPrimaryKey(NamespaceKey namespaceKey, VersionContext statementSourceVersion) {
    delegate.dropPrimaryKey(namespaceKey, statementSourceVersion);
  }

  @Override
  public DremioTable getTable(NamespaceKey key) {
    return delegate.getTable(key);
  }

  @Override
  public DremioTable getTable(CatalogEntityKey key) {
    return delegate.getTable(key);
  }

  @Override
  public DremioTable getTableForQuery(NamespaceKey key) {
    return delegate.getTableForQuery(key);
  }

  @Override
  public String getDatasetId(NamespaceKey key) {
    return delegate.getDatasetId(key);
  }

  @Override
  public DremioTable getTableSnapshotForQuery(CatalogEntityKey catalogEntityKey) {
    return delegate.getTableSnapshotForQuery(catalogEntityKey);
  }

  @Override
  public DatasetType getDatasetType(CatalogEntityKey key) {
    return delegate.getDatasetType(key);
  }

  @Override
  public DremioTable getTableSnapshot(CatalogEntityKey catalogEntityKey) {
    return delegate.getTableSnapshot(catalogEntityKey);
  }

  @Override
  public DremioTable getTable(String datasetId) {
    return delegate.getTable(datasetId);
  }

  @Override
  public BulkResponse<NamespaceKey, Optional<DremioTable>> bulkGetTables(
      BulkRequest<NamespaceKey> keys) {
    return delegate.bulkGetTables(keys);
  }

  @Override
  public BulkResponse<NamespaceKey, Optional<DremioTable>> bulkGetTablesForQuery(
      BulkRequest<NamespaceKey> keys) {
    return delegate.bulkGetTablesForQuery(keys);
  }

  @Override
  public Map<String, List<ColumnExtendedProperty>> getColumnExtendedProperties(DremioTable table) {
    return delegate.getColumnExtendedProperties(table);
  }

  @Override
  public Iterable<DremioTable> getAllRequestedTables() {
    return delegate.getAllRequestedTables();
  }

  @Override
  public NamespaceKey resolveSingle(NamespaceKey key) {
    return delegate.resolveSingle(key);
  }

  @Override
  public boolean containerExists(CatalogEntityKey path) {
    return delegate.containerExists(path);
  }

  @Override
  public boolean exists(CatalogEntityKey path) {
    return delegate.exists(path);
  }

  @Override
  public Iterable<String> listSchemas(NamespaceKey path) {
    return delegate.listSchemas(path);
  }

  @Override
  public Iterable<Table> listDatasets(NamespaceKey path) {
    return delegate.listDatasets(path);
  }

  @Override
  public Collection<Function> getFunctions(CatalogEntityKey path, FunctionType functionType) {
    return delegate.getFunctions(path, functionType);
  }

  @Override
  public NamespaceKey getDefaultSchema() {
    return delegate.getDefaultSchema();
  }

  @Override
  public NamespaceKey resolveToDefault(NamespaceKey key) {
    return delegate.resolveToDefault(key);
  }

  @Override
  public Catalog resolveCatalog(CatalogIdentity subject) {
    return delegate.resolveCatalog(subject);
  }

  @Override
  public Catalog resolveCatalog(NamespaceKey newDefaultSchema) {
    return delegate.resolveCatalog(newDefaultSchema);
  }

  @Override
  public Catalog resolveCatalog(Map<String, VersionContext> sourceVersionMapping) {
    return delegate.resolveCatalog(sourceVersionMapping);
  }

  @Override
  public Catalog resolveCatalogResetContext(String sourceName, VersionContext versionContext) {
    return delegate.resolveCatalogResetContext(sourceName, versionContext);
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey key,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions) {
    return delegate.createNewTable(key, icebergTableProps, writerOptions, storageOptions);
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey key,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions,
      CreateTableOptions createTableOptions) {
    return delegate.createNewTable(
        key, icebergTableProps, writerOptions, storageOptions, createTableOptions);
  }

  @Override
  public void createEmptyTable(
      NamespaceKey key, BatchSchema batchSchema, WriterOptions writerOptions) {
    delegate.createEmptyTable(key, batchSchema, writerOptions);
  }

  @Override
  public void createView(
      NamespaceKey key, View view, ViewOptions viewOptions, NamespaceAttribute... attributes)
      throws IOException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    delegate.createView(key, view, viewOptions, attributes);
  }

  @Override
  public void updateView(
      NamespaceKey key, View view, ViewOptions viewOptions, NamespaceAttribute... attributes)
      throws IOException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    delegate.updateView(key, view, viewOptions, attributes);
  }

  @Override
  public void dropView(NamespaceKey key, ViewOptions viewOptions) throws IOException {
    delegate.dropView(key, viewOptions);
  }

  @Override
  public void dropTable(NamespaceKey key, TableMutationOptions tableMutationOptions) {
    delegate.dropTable(key, tableMutationOptions);
  }

  @Override
  public void forgetTable(NamespaceKey key) throws UnsupportedForgetTableException {
    delegate.forgetTable(key);
  }

  @Override
  public void alterTable(
      NamespaceKey key,
      DatasetConfig datasetConfig,
      AlterTableOption alterTableOption,
      TableMutationOptions tableMutationOptions) {
    delegate.alterTable(key, datasetConfig, alterTableOption, tableMutationOptions);
  }

  @Override
  public void truncateTable(NamespaceKey key, TableMutationOptions tableMutationOptions) {
    delegate.truncateTable(key, tableMutationOptions);
  }

  @Override
  public void rollbackTable(
      NamespaceKey path,
      DatasetConfig datasetConfig,
      RollbackOption rollbackOption,
      TableMutationOptions tableMutationOptions) {
    delegate.rollbackTable(path, datasetConfig, rollbackOption, tableMutationOptions);
  }

  @Override
  public void addColumns(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      List<Field> colsToAdd,
      TableMutationOptions tableMutationOptions) {
    delegate.addColumns(table, datasetConfig, colsToAdd, tableMutationOptions);
  }

  @Override
  public void dropColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      String columnToDrop,
      TableMutationOptions tableMutationOptions) {
    delegate.dropColumn(table, datasetConfig, columnToDrop, tableMutationOptions);
  }

  @Override
  public void changeColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      String columnToChange,
      Field fieldFromSqlColDeclaration,
      TableMutationOptions tableMutationOptions) {
    delegate.changeColumn(
        table, datasetConfig, columnToChange, fieldFromSqlColDeclaration, tableMutationOptions);
  }

  @Override
  public boolean toggleSchemaLearning(NamespaceKey table, boolean enableSchemaLearning) {
    return delegate.toggleSchemaLearning(table, enableSchemaLearning);
  }

  @Override
  public void updateTableProperties(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      Map<String, String> tableProperties,
      TableMutationOptions tableMutationOptions,
      boolean isRemove) {
    delegate.updateTableProperties(
        table, datasetConfig, schema, tableProperties, tableMutationOptions, isRemove);
  }

  @Override
  public void alterSortOrder(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      List<String> sortOrderColumns,
      TableMutationOptions tableMutationOptions) {
    delegate.alterSortOrder(table, datasetConfig, schema, sortOrderColumns, tableMutationOptions);
  }

  @Override
  public void createDataset(
      NamespaceKey key,
      com.google.common.base.Function<DatasetConfig, DatasetConfig> datasetMutator) {
    delegate.createDataset(key, datasetMutator);
  }

  @Override
  public UpdateStatus refreshDataset(NamespaceKey key, DatasetRetrievalOptions retrievalOptions) {
    return delegate.refreshDataset(key, retrievalOptions);
  }

  @Override
  public UpdateStatus refreshDataset(
      NamespaceKey key,
      DatasetRetrievalOptions retrievalOptions,
      boolean isPrivilegeValidationNeeded) {
    return delegate.refreshDataset(key, retrievalOptions, isPrivilegeValidationNeeded);
  }

  @Override
  public SourceState refreshSourceStatus(NamespaceKey key) throws Exception {
    return delegate.refreshSourceStatus(key);
  }

  @Override
  public Optional<CatalogFolder> createFolder(CatalogFolder catalogFolder) throws CatalogException {
    return delegate.createFolder(catalogFolder);
  }

  @Override
  public Optional<CatalogFolder> updateFolder(CatalogFolder catalogFolder) throws CatalogException {
    return delegate.updateFolder(catalogFolder);
  }

  @Override
  public void deleteFolder(CatalogEntityKey catalogEntityKey, @Nullable String version)
      throws CatalogException {
    delegate.deleteFolder(catalogEntityKey, version);
  }

  @Override
  public Optional<CatalogFolder> getFolder(CatalogEntityKey catalogEntityKey) {
    return delegate.getFolder(catalogEntityKey);
  }

  @Override
  public Iterable<String> getSubPartitions(
      NamespaceKey key, List<String> partitionColumns, List<String> partitionValues)
      throws PartitionNotFoundException {
    return delegate.getSubPartitions(key, partitionColumns, partitionValues);
  }

  @Override
  public boolean createOrUpdateDataset(
      NamespaceKey source,
      NamespaceKey datasetPath,
      DatasetConfig datasetConfig,
      NamespaceAttribute... attributes)
      throws NamespaceException {
    return delegate.createOrUpdateDataset(source, datasetPath, datasetConfig, attributes);
  }

  @Override
  public void updateDatasetSchema(NamespaceKey datasetKey, BatchSchema newSchema) {
    delegate.updateDatasetSchema(datasetKey, newSchema);
  }

  @Override
  public void updateDatasetField(
      NamespaceKey datasetKey, String originField, CompleteType fieldSchema) {
    delegate.updateDatasetField(datasetKey, originField, fieldSchema);
  }

  @Override
  public <T extends StoragePlugin> T getSource(String name) {
    return delegate.getSource(name);
  }

  @Override
  public <T extends StoragePlugin> T getSource(String name, boolean skipStateCheck) {
    return delegate.getSource(name, skipStateCheck);
  }

  @Override
  public void createSource(
      SourceConfig config,
      SourceRefreshOption sourceRefreshOption,
      NamespaceAttribute... attributes) {
    delegate.createSource(config, sourceRefreshOption, attributes);
  }

  @Override
  public void updateSource(
      SourceConfig config,
      SourceRefreshOption sourceRefreshOption,
      NamespaceAttribute... attributes) {
    delegate.updateSource(config, sourceRefreshOption, attributes);
  }

  @Override
  public void deleteSource(SourceConfig config, SourceRefreshOption sourceRefreshOption) {
    delegate.deleteSource(config, sourceRefreshOption);
  }

  @Override
  public boolean alterDataset(
      final CatalogEntityKey catalogEntityKey, final Map<String, AttributeValue> attributes)
      throws CatalogUnsupportedOperationException {
    return delegate.alterDataset(catalogEntityKey, attributes);
  }

  @Override
  public boolean alterColumnOption(
      final NamespaceKey key,
      String columnToChange,
      final String attributeName,
      final AttributeValue attributeValue) {
    return delegate.alterColumnOption(key, columnToChange, attributeName, attributeValue);
  }

  @Override
  public Iterator<com.dremio.service.catalog.Catalog> listCatalogs(SearchQuery searchQuery) {
    return delegate.listCatalogs(searchQuery);
  }

  @Override
  public Iterator<Schema> listSchemata(SearchQuery searchQuery) {
    return delegate.listSchemata(searchQuery);
  }

  @Override
  public Iterator<Table> listTables(SearchQuery searchQuery) {
    return delegate.listTables(searchQuery);
  }

  @Override
  public Iterator<com.dremio.service.catalog.View> listViews(SearchQuery searchQuery) {
    return delegate.listViews(searchQuery);
  }

  @Override
  public Iterator<TableSchema> listTableSchemata(SearchQuery searchQuery) {
    return delegate.listTableSchemata(searchQuery);
  }

  @Override
  public ResolvedVersionContext resolveVersionContext(
      String sourceName, VersionContext versionContext)
      throws ReferenceNotFoundException, NoDefaultBranchException, ReferenceConflictException {
    return delegate.resolveVersionContext(sourceName, versionContext);
  }

  @Override
  public void validatePrivilege(NamespaceKey key, SqlGrant.Privilege privilege) {
    delegate.validatePrivilege(key, privilege);
  }

  @Override
  public void validateOwnership(CatalogEntityKey key) {
    delegate.validateOwnership(key);
  }

  @Override
  public void invalidateNamespaceCache(final NamespaceKey key) {
    delegate.invalidateNamespaceCache(key);
  }

  @Override
  public MetadataRequestOptions getMetadataRequestOptions() {
    return delegate.getMetadataRequestOptions();
  }

  @Override
  public void clearDatasetCache(NamespaceKey dataset, final TableVersionContext context) {
    delegate.clearDatasetCache(dataset, context);
  }

  @Nonnull
  @Override
  public Optional<TableMetadataVerifyResult> verifyTableMetadata(
      CatalogEntityKey key, TableMetadataVerifyRequest metadataVerifyRequest) {
    return delegate.verifyTableMetadata(key, metadataVerifyRequest);
  }

  public Catalog getDelegate() {
    return delegate;
  }

  @Override
  public void clearPermissionCache(String sourceName) {
    delegate.clearPermissionCache(sourceName);
  }

  //// Begin: NamespacePassthrough Methods
  @Override
  public boolean existsById(CatalogEntityId id) {
    return delegate.existsById(id);
  }

  @Override
  public List<NameSpaceContainer> getEntities(List<NamespaceKey> lookupKeys) {
    return delegate.getEntities(lookupKeys);
  }

  @Override
  public void addOrUpdateDataset(
      NamespaceKey datasetPath, DatasetConfig dataset, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.addOrUpdateDataset(datasetPath, dataset, attributes);
  }

  @Override
  public DatasetConfig renameDataset(NamespaceKey oldDatasetPath, NamespaceKey newDatasetPath)
      throws NamespaceException {
    return delegate.renameDataset(oldDatasetPath, newDatasetPath);
  }

  @Override
  public String getEntityIdByPath(NamespaceKey entityPath) throws NamespaceNotFoundException {
    return delegate.getEntityIdByPath(entityPath);
  }

  @Override
  public NameSpaceContainer getEntityByPath(NamespaceKey entityPath) throws NamespaceException {
    return delegate.getEntityByPath(entityPath);
  }

  @Override
  public DatasetConfig getDataset(NamespaceKey datasetPath) throws NamespaceNotFoundException {
    return delegate.getDataset(datasetPath);
  }

  @Override
  public Iterable<NamespaceKey> getAllDatasets(final NamespaceKey parent) {
    return delegate.getAllDatasets(parent);
  }

  @Override
  public void deleteDataset(
      NamespaceKey datasetPath, String version, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.deleteDataset(datasetPath, version, attributes);
  }

  @Override
  public List<Integer> getCounts(SearchTypes.SearchQuery... queries) throws NamespaceException {
    return delegate.getCounts(queries);
  }

  @Override
  public Iterable<Document<NamespaceKey, NameSpaceContainer>> find(FindByCondition condition) {
    return delegate.find(condition);
  }

  @Override
  public List<NameSpaceContainer> getEntitiesByIds(List<EntityId> ids) {
    return delegate.getEntitiesByIds(ids);
  }

  @Override
  public List<SourceConfig> getSourceConfigs() {
    return delegate.getSourceConfigs();
  }

  @Override
  public boolean exists(NamespaceKey key, NameSpaceContainer.Type type) {
    return delegate.exists(key, type);
  }

  @Override
  public boolean exists(NamespaceKey key) {
    return delegate.exists(key);
  }

  @Override
  public boolean hasChildren(NamespaceKey key) {
    return delegate.hasChildren(key);
  }

  @Override
  public Optional<NameSpaceContainer> getEntityById(EntityId id) {
    return delegate.getEntityById(id);
  }

  @Override
  public List<NameSpaceContainer> list(
      NamespaceKey entityPath, String startChildName, int maxResults) throws NamespaceException {
    return delegate.list(entityPath, startChildName, maxResults);
  }

  @Override
  public Iterable<NameSpaceContainer> getAllDescendants(NamespaceKey root) {
    return delegate.getAllDescendants(root);
  }

  @Override
  public Iterable<Document<NamespaceKey, NameSpaceContainer>> find(
      FindByCondition condition, EntityNamespaceFindOption... options) {
    return delegate.find(condition, options);
  }

  @Override
  public Iterable<Document<NamespaceKey, NameSpaceContainer>> findByRange(
      NamespaceFindByRange findByRange) {
    return delegate.findByRange(findByRange);
  }

  @Override
  public void deleteEntity(NamespaceKey entityPath) throws NamespaceException {
    delegate.deleteEntity(entityPath);
  }

  @Override
  public boolean tryCreatePhysicalDataset(
      NamespaceKey datasetPath, DatasetConfig config, NamespaceAttribute... attributes)
      throws NamespaceException {
    return delegate.tryCreatePhysicalDataset(datasetPath, config, attributes);
  }

  @Override
  public DatasetMetadataSaver newDatasetMetadataSaver(
      NamespaceKey datasetPath,
      EntityId datasetId,
      SplitCompression splitCompression,
      long maxSinglePartitionChunks,
      boolean datasetMetadataConsistencyValidate) {
    return delegate.newDatasetMetadataSaver(
        datasetPath,
        datasetId,
        splitCompression,
        maxSinglePartitionChunks,
        datasetMetadataConsistencyValidate);
  }

  @Override
  public DatasetMetadata getDatasetMetadata(NamespaceKey datasetPath) throws NamespaceException {
    return delegate.getDatasetMetadata(datasetPath);
  }

  @Override
  public DatasetConfigAndEntitiesOnPath getDatasetAndEntitiesOnPath(NamespaceKey datasetPath)
      throws NamespaceException {
    return delegate.getDatasetAndEntitiesOnPath(datasetPath);
  }

  @Override
  public int getAllDatasetsCount(NamespaceKey path) throws NamespaceException {
    return delegate.getAllDatasetsCount(path);
  }

  @Override
  public BoundedDatasetCount getDatasetCount(
      NamespaceKey root, long searchTimeLimitMillis, int countLimitToStopSearch)
      throws NamespaceException {
    return delegate.getDatasetCount(root, searchTimeLimitMillis, countLimitToStopSearch);
  }

  @Override
  public Optional<DatasetConfig> getDatasetById(EntityId entityId) {
    return delegate.getDatasetById(entityId);
  }

  @Override
  public Map<NamespaceKey, NamespaceType> getDatasetNamespaceTypes(NamespaceKey... datasetPaths) {
    return delegate.getDatasetNamespaceTypes(datasetPaths);
  }

  @Override
  public void addOrUpdateFolder(
      NamespaceKey folderPath, FolderConfig folderConfig, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.addOrUpdateFolder(folderPath, folderConfig, attributes);
  }

  @Override
  public FolderConfig getFolder(NamespaceKey folderPath) throws NamespaceException {
    return delegate.getFolder(folderPath);
  }

  @Override
  public void deleteFolder(NamespaceKey folderPath, @Nullable String version)
      throws NamespaceException {
    delegate.deleteFolder(folderPath, version);
  }

  @Override
  public List<FolderConfig> getFolders(NamespaceKey rootPath) throws NamespaceException {
    return delegate.getFolders(rootPath);
  }

  @Override
  public void addOrUpdateFunction(
      NamespaceKey functionPath, FunctionConfig functionConfig, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.addOrUpdateFunction(functionPath, functionConfig, attributes);
  }

  @Override
  public FunctionConfig getFunction(NamespaceKey functionPath) throws NamespaceException {
    return delegate.getFunction(functionPath);
  }

  @Override
  public List<FunctionConfig> getFunctions() {
    return delegate.getFunctions();
  }

  @Override
  public List<FunctionConfig> getTopLevelFunctions() {
    return delegate.getTopLevelFunctions();
  }

  @Override
  public void deleteFunction(NamespaceKey functionPath) throws NamespaceException {
    delegate.deleteFunction(functionPath);
  }

  @Override
  public void addOrUpdateHome(NamespaceKey homePath, HomeConfig homeConfig)
      throws NamespaceException {
    delegate.addOrUpdateHome(homePath, homeConfig);
  }

  @Override
  public HomeConfig getHome(NamespaceKey homePath) throws NamespaceException {
    return delegate.getHome(homePath);
  }

  @Override
  public List<HomeConfig> getHomeSpaces() {
    return delegate.getHomeSpaces();
  }

  @Override
  public void deleteHome(NamespaceKey homePath, String version) throws NamespaceException {
    delegate.deleteHome(homePath, version);
  }

  @Override
  public void addOrUpdateSource(
      NamespaceKey sourcePath, SourceConfig sourceConfig, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.addOrUpdateSource(sourcePath, sourceConfig, attributes);
  }

  @Override
  public SourceConfig getSource(NamespaceKey sourcePath) throws NamespaceException {
    return delegate.getSource(sourcePath);
  }

  @Override
  public SourceMetadata getSourceMetadata(NamespaceKey sourcePath) throws NamespaceException {
    return delegate.getSourceMetadata(sourcePath);
  }

  @Override
  public SourceConfig getSourceById(EntityId id) throws NamespaceException {
    return delegate.getSourceById(id);
  }

  @Override
  public List<SourceConfig> getSources() {
    return delegate.getSources();
  }

  @Override
  public void deleteSourceChildren(NamespaceKey sourcePath, String version, DeleteCallback callback)
      throws NamespaceException {
    delegate.deleteSourceChildren(sourcePath, version, callback);
  }

  @Override
  public void deleteSourceChild(
      NamespaceKey path, String version, boolean deleteRoot, DeleteCallback callback)
      throws NamespaceNotFoundException {
    delegate.deleteSourceChild(path, version, deleteRoot, callback);
  }

  @Override
  public void deleteSourceChildIfExists(
      NamespaceKey path, String version, boolean deleteRoot, DeleteCallback callback) {
    delegate.deleteSourceChildIfExists(path, version, deleteRoot, callback);
  }

  @Override
  public void deleteSourceWithCallBack(
      NamespaceKey sourcePath, String version, DeleteCallback callback) throws NamespaceException {
    delegate.deleteSourceWithCallBack(sourcePath, version, callback);
  }

  @Override
  public void deleteSource(NamespaceKey sourcePath, String version) throws NamespaceException {
    delegate.deleteSource(sourcePath, version);
  }

  @Override
  public void canSourceConfigBeSaved(
      SourceConfig newConfig, SourceConfig existingConfig, NamespaceAttribute... attributes)
      throws ConcurrentModificationException, NamespaceException {
    delegate.canSourceConfigBeSaved(newConfig, existingConfig, attributes);
  }

  @Override
  public void addOrUpdateSpace(
      NamespaceKey spacePath, SpaceConfig spaceConfig, NamespaceAttribute... attributes)
      throws NamespaceException {
    delegate.addOrUpdateSpace(spacePath, spaceConfig, attributes);
  }

  @Override
  public SpaceConfig getSpace(NamespaceKey spacePath) throws NamespaceException {
    return delegate.getSpace(spacePath);
  }

  @Override
  public List<SpaceConfig> getSpaces() {
    return delegate.getSpaces();
  }

  @Override
  public void deleteSpace(NamespaceKey spacePath, String version) throws NamespaceException {
    delegate.deleteSpace(spacePath, version);
  }

  @Override
  public Iterable<PartitionChunkMetadata> findSplits(FindByCondition condition) {
    return delegate.findSplits(condition);
  }

  @Override
  public Iterable<PartitionChunkMetadata> findSplits(FindByRange<PartitionChunkId> range) {
    return delegate.findSplits(range);
  }

  @Override
  public int getPartitionChunkCount(FindByCondition condition) {
    return delegate.getPartitionChunkCount(condition);
  }

  @Override
  public int deleteSplitOrphans(
      PartitionChunkId.SplitOrphansRetentionPolicy policy,
      boolean datasetMetadataConsistencyValidate) {
    return delegate.deleteSplitOrphans(policy, datasetMetadataConsistencyValidate);
  }

  @Override
  public void deleteSplits(Iterable<PartitionChunkId> datasetSplits) {
    delegate.deleteSplits(datasetSplits);
  }

  @Override
  public int getDownstreamsCount(NamespaceKey path) {
    return delegate.getDownstreamsCount(path);
  }

  @Override
  public List<DatasetConfig> getAllDownstreams(NamespaceKey path) throws NamespaceException {
    return delegate.getAllDownstreams(path);
  }

  @Override
  public List<NamespaceKey> getUpstreamPhysicalDatasets(NamespaceKey path) {
    return delegate.getUpstreamPhysicalDatasets(path);
  }

  @Override
  public List<String> getUpstreamSources(NamespaceKey path) {
    return delegate.getUpstreamSources(path);
  }
  //// End: NamespacePassthrough Methods
}
