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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.VERSIONED_SOURCE_UDF_ENABLED;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstructionWithAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.concurrent.bulk.BulkRequest;
import com.dremio.common.concurrent.bulk.BulkResponse;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.AttributeValue;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.api.ImmutableFindByCondition;
import com.dremio.exec.catalog.CatalogImpl.IdentityResolver;
import com.dremio.exec.catalog.CatalogServiceImpl.SourceModifier;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.store.AuthorizationContext;
import com.dremio.exec.store.ConnectionRefusedException;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.NamespaceAlreadyExistsException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.ReferenceTypeConflictException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.MetadataIOPool;
import com.dremio.options.OptionManager;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.DatasetIndexKeys;
import com.dremio.service.namespace.ImmutableEntityNamespaceFindOptions;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceIdentity;
import com.dremio.service.namespace.NamespaceIndexKeys;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEvents;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.test.UserExceptionAssert;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.projectnessie.error.NessieError;
import org.projectnessie.error.NessieForbiddenException;

public class TestCatalogImpl {

  private final MetadataRequestOptions options = mock(MetadataRequestOptions.class);
  private final PluginRetriever pluginRetriever = mock(PluginRetriever.class);
  private final SourceModifier sourceModifier = mock(SourceModifier.class);
  private final OptionManager optionManager = mock(OptionManager.class);
  private final NamespaceService systemNamespaceService = mock(NamespaceService.class);
  private final NamespaceService.Factory namespaceFactory = mock(NamespaceService.Factory.class);
  private final Orphanage orphanage = mock(Orphanage.class);
  private final DatasetListingService datasetListingService = mock(DatasetListingService.class);
  private final ViewCreatorFactory viewCreatorFactory = mock(ViewCreatorFactory.class);
  private final SchemaConfig schemaConfig = mock(SchemaConfig.class);
  private final NamespaceService userNamespaceService = mock(NamespaceService.class);
  private final IdentityResolver identityProvider = mock(IdentityResolver.class);
  private final NamespaceIdentity namespaceIdentity = mock(NamespaceIdentity.class);
  private final VersionContextResolverImpl versionContextResolver =
      mock(VersionContextResolverImpl.class);
  private final CatalogStatusEvents catalogStatusEvents = mock(CatalogStatusEvents.class);
  private final MetadataIOPool metadataIOPool = mock(MetadataIOPool.class);
  private final String userName = "gnarly";

  @Before
  public void setup() throws Exception {
    when(options.getSchemaConfig()).thenReturn(schemaConfig);
    when(schemaConfig.getUserName()).thenReturn(userName);

    AuthorizationContext authorizationContext =
        new AuthorizationContext(new CatalogUser(userName), false);
    when(schemaConfig.getAuthContext()).thenReturn(authorizationContext);

    when(identityProvider.toNamespaceIdentity(authorizationContext.getSubject()))
        .thenReturn(namespaceIdentity);

    when(namespaceFactory.get(namespaceIdentity)).thenReturn(userNamespaceService);
  }

  private CatalogImpl newCatalogImpl(VersionContextResolverImpl versionContextResolver) {
    return new CatalogImpl(
        options,
        pluginRetriever,
        sourceModifier,
        optionManager,
        systemNamespaceService,
        namespaceFactory,
        orphanage,
        datasetListingService,
        viewCreatorFactory,
        identityProvider,
        versionContextResolver,
        catalogStatusEvents,
        new VersionedDatasetAdapterFactory(),
        metadataIOPool);
  }

  @Test
  public void testUnknownSource() throws NamespaceException {
    NamespaceKey key = new NamespaceKey("unknown");
    Map<String, AttributeValue> attributes = new HashMap<>();

    CatalogImpl catalog = newCatalogImpl(null);

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(key.getRoot(), true)).thenReturn(null);

    UserExceptionAssert.assertThatThrownBy(
            () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Unknown source");
  }

  @Test
  public void testEntityExistsById() {
    CatalogEntityId catalogEntityId =
        CatalogEntityId.fromVersionedDatasetId(
            new VersionedDatasetId(
                ImmutableList.of("catalog", "table"),
                "contentId",
                TableVersionContext.NOT_SPECIFIED));
    StoragePlugin mockStoragePlugin = mock(StoragePlugin.class);
    VersionedPlugin mockVersionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    when(sourceModifier.getSource("catalog")).thenReturn(mockStoragePlugin);
    when(mockStoragePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(mockStoragePlugin.unwrap(VersionedPlugin.class)).thenReturn(mockVersionedPlugin);
    when(mockVersionedPlugin.resolveVersionContext(VersionContext.NOT_SPECIFIED))
        .thenReturn(resolvedVersionContext);
    when(mockVersionedPlugin.getContentId(ImmutableList.of("table"), resolvedVersionContext))
        .thenReturn("id");

    CatalogImpl catalogImpl = newCatalogImpl(null);

    assertTrue(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdDoesNotExist() {
    CatalogEntityId catalogEntityId =
        CatalogEntityId.fromVersionedDatasetId(
            new VersionedDatasetId(
                ImmutableList.of("catalog", "table"),
                "contentId",
                TableVersionContext.NOT_SPECIFIED));
    StoragePlugin mockStoragePlugin = mock(StoragePlugin.class);
    VersionedPlugin mockVersionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    when(sourceModifier.getSource("catalog")).thenReturn(mockStoragePlugin);
    when(mockStoragePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(mockStoragePlugin.unwrap(VersionedPlugin.class)).thenReturn(mockVersionedPlugin);
    when(mockVersionedPlugin.resolveVersionContext(VersionContext.NOT_SPECIFIED))
        .thenReturn(resolvedVersionContext);
    when(mockVersionedPlugin.getContentId(ImmutableList.of("table"), resolvedVersionContext))
        .thenReturn(null);

    CatalogImpl catalogImpl = newCatalogImpl(null);

    assertFalse(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdSourceDoesNotExist() {
    String mySourceName = "mySource";
    when(sourceModifier.getSource(mySourceName))
        .thenThrow(
            UserException.validationError(new NamespaceNotFoundException("")).buildSilently());

    assertFalse(
        newCatalogImpl(null)
            .existsById(
                CatalogEntityId.fromVersionedDatasetId(
                    new VersionedDatasetId(
                        ImmutableList.of(mySourceName, "table"),
                        "contentId",
                        TableVersionContext.NOT_SPECIFIED))));
  }

  @Test
  public void testEntityExistsByIdForNamespace() {
    CatalogEntityId catalogEntityId = CatalogEntityId.fromString("catalog.someTable");
    when(userNamespaceService.getEntityById(new EntityId("catalog.someTable")))
        .thenReturn(Optional.of(mock(NameSpaceContainer.class)));

    CatalogImpl catalogImpl = newCatalogImpl(null);
    assertTrue(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testEntityExistsByIdDoesNotExistForNamespace() {
    CatalogEntityId catalogEntityId = CatalogEntityId.fromString("catalog.someTable");
    when(userNamespaceService.getEntityById(new EntityId("catalog.someTable")))
        .thenReturn(Optional.empty());

    CatalogImpl catalogImpl = newCatalogImpl(null);
    assertFalse(catalogImpl.existsById(catalogEntityId));
  }

  @Test
  public void testAlterDataset_ConnectorException() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    doThrow(new ConnectorException())
        .when(plugin)
        .getDatasetHandle(key, null, datasetRetrievalOptions);

    CatalogImpl catalog = newCatalogImpl(null);

    UserExceptionAssert.assertThatThrownBy(
            () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Failure while retrieving dataset");
    verify(plugin).getDatasetHandle(any(), any(), any());
  }

  @Test
  public void testAlterDataset_KeyNotFoundInDefaultNamespace() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();
    EntityPath entityPath = mock(EntityPath.class);
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    Optional<DatasetHandle> handle = Optional.of(datasetHandle);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey namespaceKey = mock(NamespaceKey.class);

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(plugin.getDatasetHandle(key, null, datasetRetrievalOptions)).thenReturn(handle);
    when(datasetHandle.getDatasetPath()).thenReturn(entityPath);
    doThrow(new NamespaceNotFoundException(namespaceKey, "not found"))
        .when(systemNamespaceService)
        .getDataset(namespaceKey);

    CatalogImpl catalog = newCatalogImpl(null);

    try (MockedStatic<MetadataObjectsUtils> mocked = mockStatic(MetadataObjectsUtils.class)) {
      mocked.when(() -> MetadataObjectsUtils.toNamespaceKey(entityPath)).thenReturn(namespaceKey);

      UserExceptionAssert.assertThatThrownBy(
              () -> catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes))
          .hasErrorType(VALIDATION)
          .hasMessageContaining("Unable to find requested dataset");
    }
    verify(plugin).getDatasetHandle(key, null, datasetRetrievalOptions);
    verify(systemNamespaceService).getDataset(namespaceKey);
  }

  @Test
  public void testAlterDataset_ShortNamespaceKey() throws Exception {
    ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);
    NamespaceKey key = new NamespaceKey("hivestore.datatab");
    Map<String, AttributeValue> attributes = new HashMap<>();
    EntityPath entityPath = mock(EntityPath.class);
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    Optional<DatasetHandle> handle = Optional.of(datasetHandle);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    NamespaceKey namespaceKey = mock(NamespaceKey.class);
    DatasetConfig datasetConfig = new DatasetConfig();

    doThrow(new NamespaceNotFoundException(key, "not found"))
        .when(systemNamespaceService)
        .getDataset(key);
    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);
    when(plugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(plugin.getDatasetHandle(key, null, datasetRetrievalOptions)).thenReturn(handle);
    when(datasetHandle.getDatasetPath()).thenReturn(entityPath);
    when(systemNamespaceService.getDataset(namespaceKey)).thenReturn(datasetConfig);
    when(plugin.alterDataset(key, datasetConfig, attributes)).thenReturn(true);

    CatalogImpl catalog = newCatalogImpl(null);

    try (MockedStatic<MetadataObjectsUtils> mocked = mockStatic(MetadataObjectsUtils.class)) {
      mocked.when(() -> MetadataObjectsUtils.toNamespaceKey(entityPath)).thenReturn(namespaceKey);

      assertTrue(catalog.alterDataset(CatalogEntityKey.fromNamespaceKey(key), attributes));
    }
    verify(plugin).getDatasetHandle(key, null, datasetRetrievalOptions);
    verify(systemNamespaceService).getDataset(namespaceKey);
  }

  @Test
  public void testGetColumnExtendedProperties_nullReadDefinition() {
    final CatalogImpl catalog = newCatalogImpl(null);

    final DremioTable table = mock(DremioTable.class);
    when(table.getDatasetConfig()).thenReturn(DatasetConfig.getDefaultInstance());

    assertNull(catalog.getColumnExtendedProperties(table));
  }

  @Test
  public void testGetTable_ReturnsUpdatedTable() {
    final View outdatedView = mock(View.class);
    when(outdatedView.isFieldUpdated()).thenReturn(true);

    NamespaceKey viewPath = new NamespaceKey("@" + userName);

    final ViewTable tableToBeUpdated = mock(ViewTable.class);
    when(tableToBeUpdated.getView()).thenReturn(outdatedView);
    when(tableToBeUpdated.getPath()).thenReturn(viewPath);

    final ViewTable updatedTable = mock(ViewTable.class);

    ViewCreatorFactory.ViewCreator viewCreator = mock(ViewCreatorFactory.ViewCreator.class);
    when(viewCreatorFactory.get(userName)).thenReturn(viewCreator);

    NamespaceKey key = new NamespaceKey("test");

    final AtomicBoolean isFirstGetTableCall = new AtomicBoolean(true);
    try (MockedConstruction<DatasetManager> ignored =
        mockConstructionWithAnswer(
            DatasetManager.class,
            invocation -> {
              // generate answers for method invocations on the constructed object
              if ("getTable".equals(invocation.getMethod().getName())) {
                if (isFirstGetTableCall.getAndSet(false)) {
                  return tableToBeUpdated;
                }
                return updatedTable;
              }
              // this should never get called
              return invocation.callRealMethod();
            })) {
      CatalogImpl catalog = newCatalogImpl(versionContextResolver);

      DremioTable actual = catalog.getTable(key);
      assertEquals(updatedTable, actual);
    }
  }

  @Test
  public void testGetDatasetType_NullKey() {
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(null), DatasetType.OTHERS);
  }

  @Test
  public void testGetDatasetType_WrongPluginType() {
    final ManagedStoragePlugin plugin = mock(ManagedStoragePlugin.class);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(plugin);

    final List<String> tableKey = Arrays.asList("source", "table");
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(tableKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.OTHERS);
  }

  @Test
  public void testGetDatasetType_CheckReturnPhysicalDataset() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(fakeVersionedPlugin.unwrap(VersionedPlugin.class)).thenReturn(fakeVersionedPlugin);
    when(fakeVersionedPlugin.getType(any(), any()))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(tableKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.PHYSICAL_DATASET);
  }

  @Test
  public void testGetTableSnapshotWrongVersionContext() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    when(managedStoragePlugin.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    when(catalog.resolveVersionContext(sourceName, tableVersionContext.asVersionContext()))
        .thenThrow(new ReferenceNotFoundException());
    UserExceptionAssert.assertThatThrownBy(
            () ->
                catalog.getTableSnapshotForQuery(
                    CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                        namespaceKey, tableVersionContext)))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("Table " + "'" + namespaceKey + "'" + " not found");
  }

  @Test
  public void testGetTableSnapshotWrongVersionType() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext wrongVersionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);

    when(versionContextResolver.resolveVersionContext(sourceName, wrongVersionContext))
        .thenThrow(
            new ReferenceTypeConflictException(
                "Requested "
                    + wrongVersionContext
                    + " in source "
                    + sourceName
                    + " is not the requested type",
                null));
    final TableVersionContext tableVersionContext = TableVersionContext.of(wrongVersionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotForbiddenException() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "table";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    doThrow(new NessieForbiddenException(mock(NessieError.class)))
        .when(versionContextResolver)
        .resolveVersionContext(sourceName, versionContext);

    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotConnectionException() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");
    final TimeTravelOption.TimeTravelRequest timeTravelRequest =
        TimeTravelOption.newTimestampRequest(10L);
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "source";
    final String tableName = "versionedtable";
    final List<String> tableKey = Arrays.asList(sourceName, tableName);
    final NamespaceKey namespaceKey = new NamespaceKey(tableKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    doThrow(new ConnectionRefusedException())
        .when(versionContextResolver)
        .resolveVersionContext(sourceName, versionContext);

    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(() -> CatalogUtil.getIcebergTimeTravelRequest(namespaceKey, tableVersionContext))
          .thenReturn(timeTravelRequest);
    }
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    assertThrows(
        RuntimeException.class,
        () ->
            catalog.getTableSnapshot(
                CatalogEntityKey.namespaceKeyToCatalogEntityKey(
                    namespaceKey, tableVersionContext)));
  }

  @Test
  public void testGetTableSnapshotWhenContextSourceDown() throws ConnectorException {
    final ManagedStoragePlugin mspForGoodSource = mock(ManagedStoragePlugin.class);
    final ManagedStoragePlugin mspForSourceThatsDown = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final VersionContextResolverImpl versionContextResolver =
        mock(VersionContextResolverImpl.class);
    final ResolvedVersionContext resolvedVersionContext = mock(ResolvedVersionContext.class);
    final VersionContext versionContext = VersionContext.ofBranch("foo");

    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    final String sourceName = "goodSource";
    final String badSourceContext = "sourceThatsDown";
    final String versionedtable = "versionedTable";
    final List<String> versionedTablePath = Arrays.asList(sourceName, versionedtable);
    final CatalogEntityKey versionedTableKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(versionedTablePath)
            .tableVersionContext(TableVersionContext.of(versionContext))
            .build();
    when(pluginRetriever.getPlugin(badSourceContext, false)).thenReturn(mspForSourceThatsDown);
    when(mspForSourceThatsDown.getPlugin()).thenReturn(Optional.empty());
    when(pluginRetriever.getPlugin(sourceName, false)).thenReturn(mspForGoodSource);
    when(mspForGoodSource.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    when(fakeVersionedPlugin.getType(anyList(), any(ResolvedVersionContext.class)))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_TABLE);
    when(versionContextResolver.resolveVersionContext(sourceName, versionContext))
        .thenReturn(resolvedVersionContext);
    // Set the context to the sourceThatsDown so the getTableSnapshot call resolves first to
    // "sourceThatsDown.goodSource.versionedTable"
    when(options.getSchemaConfig().getDefaultSchema())
        .thenReturn(new NamespaceKey(badSourceContext));
    when(datasetRetrievalOptions.toBuilder())
        .thenReturn(DatasetRetrievalOptions.DEFAULT.toBuilder());
    when(mspForGoodSource.getDefaultRetrievalOptions()).thenReturn(datasetRetrievalOptions);
    final TableVersionContext tableVersionContext = TableVersionContext.of(versionContext);
    try (MockedStatic<CatalogUtil> mockedCatalogUtil = mockStatic(CatalogUtil.class)) {
      mockedCatalogUtil
          .when(
              () ->
                  CatalogUtil.getIcebergTimeTravelRequest(
                      versionedTableKey.toNamespaceKey(), tableVersionContext))
          .thenReturn(null);
    }

    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);
    // Assert
    // Expected : getTableSnapshot() will try looking up the key resolved with sourceThatsDown, not
    // find it , and then try to lookup the versionedTableKey as is .
    assertThatThrownBy(() -> catalog.getTableSnapshot(versionedTableKey))
        .hasMessageContaining(
            "Table "
                + "'"
                + PathUtils.constructFullPath(versionedTableKey.getKeyComponents())
                + "'"
                + " not found");
    verify(mspForSourceThatsDown).getPlugin();
  }

  @Test
  public void testGetDatasetType_CheckReturnVirtualDataset() {
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);
    final String sourceName = "source";
    final String viewName = "view";
    final List<String> viewKey = Arrays.asList(sourceName, viewName);
    final NamespaceKey namespaceKey = new NamespaceKey(viewKey);

    when(pluginRetriever.getPlugin(anyString(), anyBoolean())).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getName()).thenReturn(namespaceKey);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.getType(any(), any()))
        .thenReturn(VersionedPlugin.EntityType.ICEBERG_VIEW);
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(fakeVersionedPlugin.unwrap(VersionedPlugin.class)).thenReturn(fakeVersionedPlugin);
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(viewKey)
            .tableVersionContext(versionContext)
            .build();
    final CatalogImpl catalog = newCatalogImpl(versionContextResolver);

    assertEquals(catalog.getDatasetType(key), DatasetType.VIRTUAL_DATASET);
  }

  @Test
  public void testForgetTable_View() throws NamespaceException {
    NamespaceKey key = new NamespaceKey(Arrays.asList("space1", "view1"));
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setName("view1");
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(key.getPathComponents());

    when(systemNamespaceService.getDataset(key)).thenReturn(datasetConfig);
    when(systemNamespaceService.getEntities(List.of(new NamespaceKey(key.getRoot()))))
        .thenReturn(List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.SPACE)));

    assertThatThrownBy(() -> newCatalogImpl(versionContextResolver).forgetTable(key))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("does not exist or is not a table");
  }

  @Test
  public void testRefreshDataset_View() throws NamespaceException {
    NamespaceKey key = new NamespaceKey(Arrays.asList("space1", "view1"));
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setName("view1");
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(key.getPathComponents());
    DatasetRetrievalOptions datasetRetrievalOptions = mock(DatasetRetrievalOptions.class);

    when(pluginRetriever.getPlugin("space1", true)).thenThrow(UserException.class);
    when(userNamespaceService.getDataset(key)).thenReturn(datasetConfig);

    assertThatThrownBy(
            () ->
                newCatalogImpl(versionContextResolver).refreshDataset(key, datasetRetrievalOptions))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Only tables can be refreshed");
  }

  @Test
  public void testContainerExists_sortDisabled() {
    CatalogImpl catalog = newCatalogImpl(null);

    NamespaceKey key = new NamespaceKey(ImmutableList.of("source", "table"));

    // Mock source.
    ArrayList<NameSpaceContainer> rootEntities = new ArrayList<>();
    rootEntities.add(new NameSpaceContainer().setType(NameSpaceContainer.Type.SOURCE));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(rootEntities);

    // Mock not found table.
    ArrayList<NameSpaceContainer> entities = new ArrayList<>();
    entities.add(null);
    when(userNamespaceService.getEntities(eq(ImmutableList.of(key)))).thenReturn(entities);

    CatalogEntityKey catalogKey = CatalogEntityKey.fromNamespaceKey(key);
    catalog.containerExists(catalogKey);

    // Verify that find was called w/o sort.
    SearchTypes.SearchQuery searchQuery =
        SearchQueryUtils.and(
            SearchQueryUtils.newTermQuery(
                NamespaceIndexKeys.ENTITY_TYPE.getIndexFieldName(),
                NameSpaceContainer.Type.DATASET.getNumber()),
            SearchQueryUtils.or(
                SearchQueryUtils.newTermQuery(
                    DatasetIndexKeys.UNQUOTED_LC_SCHEMA,
                    catalogKey.asLowerCase().toUnescapedString()),
                SearchQueryUtils.newPrefixQuery(
                    DatasetIndexKeys.UNQUOTED_LC_SCHEMA.getIndexFieldName(),
                    catalogKey.asLowerCase().toUnescapedString() + ".")));
    verify(userNamespaceService, times(1))
        .find(
            eq(
                new ImmutableFindByCondition.Builder()
                    .setCondition(searchQuery)
                    .setLimit(1)
                    .build()),
            eq(new ImmutableEntityNamespaceFindOptions.Builder().setDisableKeySort(true).build()));
  }

  @Test
  public void testContainerExists_invalid_container_type() {
    NamespaceKey key = new NamespaceKey(ImmutableList.of("myFunction"));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(
            new ArrayList<>() {
              {
                add(new NameSpaceContainer().setType(NameSpaceContainer.Type.FUNCTION));
              }
            });

    assertThat(newCatalogImpl(null).containerExists(CatalogEntityKey.fromNamespaceKey(key)))
        .isFalse();
  }

  @Test
  public void testExists_null() {
    assertThat(newCatalogImpl(null).exists((CatalogEntityKey) null)).isFalse();
  }

  @Test
  public void testExists_source() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("my_source")).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    List<NameSpaceContainer> rootEntities =
        List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.SOURCE));
    when(systemNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    when(userNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);

    assertThat(catalog.exists(catalogEntityKey)).isTrue();
  }

  @Test
  public void testExists_source_missing() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("my_source")).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    when(systemNamespaceService.getEntities(eq(namespaceKeys)))
        .thenReturn(
            new ArrayList<>() {
              {
                add(null);
              }
            });

    assertThat(catalog.exists(catalogEntityKey)).isFalse();
  }

  private void testExists_home(boolean isOwner) {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(List.of("@" + (isOwner ? "" : "not_") + userName))
            .build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    List<NameSpaceContainer> rootEntities =
        List.of(new NameSpaceContainer().setType(NameSpaceContainer.Type.HOME));
    when(userNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    if (!isOwner) {
      when(systemNamespaceService.getEntities(eq(namespaceKeys))).thenReturn(rootEntities);
    }

    assertThat(catalog.exists(catalogEntityKey)).isEqualTo(isOwner);
  }

  @Test
  public void testExists_home() {
    testExists_home(true);
  }

  @Test
  public void testExists_home_wrong_user() {
    testExists_home(false);
  }

  @Test
  public void testExists_home_wrong_user_with_exception() {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder().keyComponents(List.of("@not_" + userName)).build();

    ImmutableList<NamespaceKey> namespaceKeys = ImmutableList.of(catalogEntityKey.toNamespaceKey());
    when(systemNamespaceService.getEntities(eq(namespaceKeys)))
        .thenThrow(UserException.connectionError().buildSilently());

    assertThrows(UserException.class, () -> catalog.exists(catalogEntityKey));
  }

  private void testExists_table_namespace(boolean entityExists) {
    CatalogImpl catalog = newCatalogImpl(null);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(List.of("my_source", "my_table"))
            .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
            .build();

    when(userNamespaceService.exists(catalogEntityKey.toNamespaceKey())).thenReturn(entityExists);

    assertThat(catalog.exists(catalogEntityKey)).isEqualTo(entityExists);
  }

  @Test
  public void testExists_table_namespace() {
    testExists_table_namespace(true);
  }

  @Test
  public void testExists_table_namespace_missing() {
    testExists_table_namespace(false);
  }

  private void testExists_table_versioned_entity(boolean entityExists) {
    CatalogImpl catalog = newCatalogImpl(null);

    VersionedDatasetId versionedDatasetId =
        new VersionedDatasetId(
            ImmutableList.of("my_source", "my_table"),
            "4c04aa93-a1c2-40e3-a3cd-63680617dcfb",
            TableVersionContext.of(VersionContext.ofBranch("main")));

    VersionedPlugin versionedPlugin = mock(VersionedPlugin.class);
    ResolvedVersionContext resolvedVersionContext =
        ResolvedVersionContext.ofBranch("main", UUID.randomUUID().toString());
    when(versionedPlugin.resolveVersionContext(
            eq(versionedDatasetId.getVersionContext().asVersionContext())))
        .thenReturn(resolvedVersionContext);
    when(versionedPlugin.getContentId(
            eq(
                versionedDatasetId
                    .getTableKey()
                    .subList(1, versionedDatasetId.getTableKey().size())),
            eq(resolvedVersionContext)))
        .thenReturn(entityExists ? UUID.randomUUID().toString() : null);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(eq(VersionedPlugin.class))).thenReturn(true);
    when(storagePlugin.unwrap(eq(VersionedPlugin.class))).thenReturn(versionedPlugin);

    when(sourceModifier.getSource(eq(versionedDatasetId.getTableKey().get(0))))
        .thenReturn(storagePlugin);

    assertThat(catalog.exists(CatalogEntityKey.fromVersionedDatasetId(versionedDatasetId)))
        .isEqualTo(entityExists);
  }

  @Test
  public void testExists_table_versioned_entity() {
    testExists_table_versioned_entity(true);
  }

  @Test
  public void testExists_table_versioned_entity_missing() {
    testExists_table_versioned_entity(false);
  }

  private void testExists_function_at_root(boolean exists) {
    NamespaceKey key = new NamespaceKey(ImmutableList.of("myFunction"));
    when(systemNamespaceService.getEntities(eq(ImmutableList.of(new NamespaceKey(key.getRoot())))))
        .thenReturn(
            new ArrayList<>() {
              {
                add(new NameSpaceContainer().setType(NameSpaceContainer.Type.FUNCTION));
              }
            });

    when(userNamespaceService.exists(eq(key))).thenReturn(exists);

    assertThat(newCatalogImpl(null).exists(CatalogEntityKey.fromNamespaceKey(key)))
        .isEqualTo(exists);
  }

  @Test
  public void testExists_function_at_root() {
    testExists_function_at_root(true);
  }

  @Test
  public void testExists_function_at_root_missing() {
    testExists_function_at_root(false);
  }

  @Test
  public void testNoRedundantLookupForEmptySchema() {
    NamespaceKey emptyDefaultSchema = new NamespaceKey(ImmutableList.of());
    when(schemaConfig.getDefaultSchema()).thenReturn(emptyDefaultSchema);
    BulkRequest<NamespaceKey> request =
        BulkRequest.<NamespaceKey>builder().add(new NamespaceKey("k")).build();
    BulkResponse<NamespaceKey, Optional<DremioTable>> response =
        BulkResponse.<NamespaceKey, Optional<DremioTable>>builder()
            .add(new NamespaceKey("k"), Optional.empty())
            .build();

    MutableInt datasetManagerInvocations = new MutableInt();

    try (MockedConstruction<DatasetManager> ignored =
        mockConstructionWithAnswer(
            DatasetManager.class,
            invocation -> {
              if ("bulkGetTables".equals(invocation.getMethod().getName())) {
                datasetManagerInvocations.increment();
              }
              return response;
            })) {

      CatalogImpl catalog = newCatalogImpl(null);
      catalog.bulkGetTables(request);

      // We should only invoke DatasetManager#bulkGetTables once if default schema is empty
      assertEquals(1, datasetManagerInvocations.intValue());
    }
  }

  @Test
  public void testGetFunctionsVersionedSourceUdfEnabledIsFalse() {
    OptionManager optionManager = mock(OptionManager.class);
    String sourceName = "source";
    String functionName = "udf";
    final ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);
    final FakeVersionedPlugin fakeVersionedPlugin = mock(FakeVersionedPlugin.class);

    when(optionManager.getOption(VERSIONED_SOURCE_UDF_ENABLED)).thenReturn(false);

    CatalogImpl catalogImpl = newCatalogImpl(null);

    CatalogImpl spyCatalogImpl = Mockito.spy(catalogImpl);

    final List<String> udfKey = Arrays.asList(sourceName, functionName);
    final TableVersionContext versionContext = TableVersionContext.NOT_SPECIFIED;
    final CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(udfKey)
            .tableVersionContext(versionContext)
            .build();
    when(pluginRetriever.getPlugin(sourceName, false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.getPlugin()).thenReturn(Optional.of(fakeVersionedPlugin));
    when(fakeVersionedPlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);

    spyCatalogImpl.getFunctions(catalogEntityKey, SimpleCatalog.FunctionType.SCALAR);

    // Verify getUserDefinedFunctionImplementationFromNessie is not called
    verify(spyCatalogImpl, never())
        .getUserDefinedFunctionImplementationFromNessie(
            any(CatalogEntityKey.class), eq(fakeVersionedPlugin));
  }

  @Test
  public void testCreateFolderSuccessForVersionedPlugin() throws CatalogException {
    final String sourceName = "source";
    final String folderName = "folder";
    final VersionedDatasetId versionedDatasetId =
        new VersionedDatasetId(
            ImmutableList.of(sourceName, folderName),
            "contentId",
            TableVersionContext.NOT_SPECIFIED);
    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    final ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(Arrays.asList(sourceName, folderName))
            .tableVersionContext(tableVersionContext)
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setStorageUri(storageUri)
            .build();
    CatalogFolder returnedCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setId(versionedDatasetId.asString())
            .setResolvedVersionContext(resolvedVersionContext)
            .setStorageUri(storageUri)
            .build();
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    VersionedPlugin plugin = mock(VersionedPlugin.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(storagePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(storagePlugin.unwrap(VersionedPlugin.class)).thenReturn(plugin);
    when(plugin.createFolder(folderKey, storageUri)).thenReturn(Optional.of(returnedCatalogFolder));

    CatalogImpl catalog = newCatalogImpl(null);
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().storageUri(),
        returnedCatalogFolder.storageUri());
    assertEquals(catalog.createFolder(inputCatalogFolder).get().id(), returnedCatalogFolder.id());
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().resolvedVersionContext(),
        resolvedVersionContext);
  }

  @Test
  public void testCreateFolderSuccessForNonVersionedPlugin()
      throws CatalogException, NamespaceException {
    final String sourceName = "source";
    final String folderName = "folder";
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder().keyComponents(Arrays.asList(sourceName, folderName)).build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setStorageUri(storageUri)
            .build();
    CatalogFolder returnedCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setStorageUri(storageUri)
            .build();
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders plugin = mock(SupportsMutatingFolders.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(storagePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(false);
    when(plugin.createFolder(folderKey, storageUri)).thenReturn(Optional.of(returnedCatalogFolder));
    when(userNamespaceService.getFolder(any())).thenReturn(convertToNS(returnedCatalogFolder));

    CatalogImpl catalog = newCatalogImpl(null);
    assertEquals(
        catalog.createFolder(inputCatalogFolder).get().storageUri(),
        returnedCatalogFolder.storageUri());
  }

  @Test
  public void testCreateFolderAlreadyExists() throws CatalogException {
    final String sourceName = "source";
    final String folderName = "folder";
    final TableVersionContext tableVersionContext = TableVersionContext.NOT_SPECIFIED;
    final ResolvedVersionContext resolvedVersionContext = ResolvedVersionContext.ofCommit("abc123");
    String storageUri = "s3://bucket/folder";
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(Arrays.asList(sourceName, folderName))
            .tableVersionContext(tableVersionContext)
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(tableVersionContext.asVersionContext())
            .setStorageUri(storageUri)
            .build();

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders plugin = mock(SupportsMutatingFolders.class);
    when(sourceModifier.getSource(sourceName)).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(plugin);
    when(plugin.createFolder(folderKey, storageUri))
        .thenThrow(NamespaceAlreadyExistsException.class);

    CatalogImpl catalog = newCatalogImpl(null);
    assertThatThrownBy(() -> catalog.createFolder(inputCatalogFolder))
        .isInstanceOf(CatalogEntityAlreadyExistsException.class)
        .hasMessageContaining(
            String.format(
                "Unable to create folder %s on source %s. An object already exists with that name.",
                folderName, sourceName));
  }

  @Test
  public void testCreateViewInRestCatalogSourceCallsRefreshDataset() throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer sourceContainer = new NameSpaceContainer();
    sourceContainer.setType(NameSpaceContainer.Type.SOURCE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    // View view = new View("testView", "SELECT * FROM testTable", null, null, null);
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();

    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was called
    verify(spyCatalog, times(1)).refreshDataset(viewKey, DatasetRetrievalOptions.DEFAULT);
  }

  @Test
  public void testCreateViewDoesNotCallRefreshDatasetWhenRestCatalogViewsSupportedIsFalse()
      throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer sourceContainer = new NameSpaceContainer();
    sourceContainer.setType(NameSpaceContainer.Type.SOURCE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(sourceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();

    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was not called
    verify(spyCatalog, never()).refreshDataset(viewKey, DatasetRetrievalOptions.DEFAULT);
  }

  @Test
  public void testCreateViewDoesNotCallRefreshDatasetWhenContainerIsSpace() throws Exception {
    SupportsRefreshViews supportsRefreshViews = mock(SupportsRefreshViews.class);
    ManagedStoragePlugin managedStoragePlugin = mock(ManagedStoragePlugin.class);

    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(storagePlugin.isWrapperFor(SupportsMutatingViews.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingViews.class))
        .thenReturn(mock(SupportsMutatingViews.class));
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(sourceModifier.getSource("testSource")).thenReturn(storagePlugin);

    // Set up CatalogImpl
    NameSpaceContainer spaceContainer = new NameSpaceContainer();
    spaceContainer.setType(NameSpaceContainer.Type.SPACE);
    when(pluginRetriever.getPlugin("testSource", false)).thenReturn(managedStoragePlugin);
    when(managedStoragePlugin.refreshDataset(any(), any()))
        .thenReturn(DatasetCatalog.UpdateStatus.CHANGED);
    when(userNamespaceService.getEntities(any())).thenReturn(List.of(spaceContainer));
    when(systemNamespaceService.getEntities(any())).thenReturn(List.of(spaceContainer));

    CatalogImpl catalog = newCatalogImpl(null);
    // Mock option and plugin behavior
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(pluginRetriever.getPlugin("testSource", true)).thenReturn(managedStoragePlugin);
    when(storagePlugin.unwrap(SupportsRefreshViews.class)).thenReturn(supportsRefreshViews);
    when(storagePlugin.isWrapperFor(SupportsRefreshViews.class)).thenReturn(true);

    CatalogImpl spyCatalog = Mockito.spy(catalog);
    RelDataType rowType = mock(RelDataType.class);
    doReturn(storagePlugin).when(spyCatalog).getSource("testSource");
    ViewCreatorFactory.ViewCreator viewCreator = mock(ViewCreatorFactory.ViewCreator.class);
    when(viewCreatorFactory.get(userName)).thenReturn(viewCreator);

    NamespaceKey viewKey = new NamespaceKey(List.of("testSource", "testView"));
    View view = new View("testView", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    ViewOptions viewOptions = new ViewOptions.ViewOptionsBuilder().build();
    spyCatalog.createView(viewKey, view, viewOptions);

    // Verify that refreshDataset was not called
    verify(spyCatalog, never()).refreshDataset(viewKey, DatasetRetrievalOptions.DEFAULT);
  }

  @Test
  public void testDeleteFolder() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey(Arrays.asList("source", "folder"));
    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    SupportsMutatingFolders supportsMutatingFolders = mock(SupportsMutatingFolders.class);
    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(namespaceKey.getPathComponents())
            .tableVersionContext(TableVersionContext.of(VersionContext.NOT_SPECIFIED))
            .build();
    when(sourceModifier.getSource("source")).thenReturn(storagePlugin);
    when(storagePlugin.isWrapperFor(SupportsMutatingFolders.class)).thenReturn(true);
    when(storagePlugin.unwrap(SupportsMutatingFolders.class)).thenReturn(supportsMutatingFolders);
    doNothing().when(supportsMutatingFolders).deleteFolder(any(CatalogEntityKey.class));
    when(userNamespaceService.exists(namespaceKey)).thenReturn(true);
    doNothing().when(userNamespaceService).deleteEntity(namespaceKey);
    CatalogImpl catalog = newCatalogImpl(null);
    catalog.deleteFolder(catalogEntityKey, null);
    verify(userNamespaceService).exists(namespaceKey);
    verify(userNamespaceService).deleteFolder(namespaceKey, null);
  }

  private static FolderConfig convertToNS(CatalogFolder catalogFolder) {
    FolderConfig folderConfig = new FolderConfig();
    if (catalogFolder.id() != null) {
      folderConfig.setId(new EntityId(catalogFolder.id()));
    } else {
      folderConfig.setId(new EntityId(UUID.randomUUID().toString()));
    }
    folderConfig.setFullPathList(catalogFolder.fullPath());
    folderConfig.setName(catalogFolder.fullPath().get(catalogFolder.fullPath().size() - 1));
    folderConfig.setTag(catalogFolder.tag());
    folderConfig.setStorageUri(catalogFolder.storageUri());
    return folderConfig;
  }

  private interface FakeVersionedPlugin extends VersionedPlugin, StoragePlugin {}
}
