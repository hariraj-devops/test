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
package com.dremio.dac.service.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityForbiddenException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.ProtostuffUtil;
import com.dremio.dac.explore.QueryExecutor;
import com.dremio.dac.explore.model.Dataset;
import com.dremio.dac.explore.model.VersionContextReq;
import com.dremio.dac.homefiles.HomeFileConf;
import com.dremio.dac.model.common.Function;
import com.dremio.dac.model.folder.FolderBody;
import com.dremio.dac.model.folder.FolderModel;
import com.dremio.dac.model.folder.SourceFolderPath;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.sources.FormatTools;
import com.dremio.dac.model.sources.PhysicalDataset;
import com.dremio.dac.model.sources.SourceName;
import com.dremio.dac.resource.SourceResource;
import com.dremio.dac.server.BufferAllocatorFactory;
import com.dremio.dac.service.collaboration.CollaborationHelper;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.ResourceExistsException;
import com.dremio.dac.service.errors.ResourceForbiddenException;
import com.dremio.dac.service.reflection.ReflectionServiceHelper;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.ConnectionReader;
import com.dremio.exec.catalog.ImmutableVersionedListOptions;
import com.dremio.exec.catalog.ImmutableVersionedListResponsePage;
import com.dremio.exec.catalog.MetadataRequestOptions;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.SourceCatalog;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.InternalFileConf;
import com.dremio.exec.store.dfs.PDFSConf;
import com.dremio.exec.store.sys.SystemPluginConf;
import com.dremio.file.File;
import com.dremio.options.OptionManager;
import com.dremio.plugins.ExternalNamespaceEntry;
import com.dremio.plugins.ExternalNamespaceEntry.Type;
import com.dremio.plugins.dataplane.store.DataplanePlugin;
import com.dremio.service.namespace.BoundedDatasetCount;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.reflection.ReflectionAdministrationService;
import com.dremio.service.reflection.ReflectionSettings;
import com.google.common.collect.ImmutableList;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.SecurityContext;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TestSourceService {
  private static final String SOURCE_NAME = "sourceName";
  private static final String DEFAULT_REF_TYPE =
      VersionContextReq.VersionContextType.BRANCH.toString();
  private static final String DEFAULT_BRANCH_NAME = "somebranch";
  private static final VersionContext DEFAULT_VERSION_CONTEXT =
      VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
  private static final ResolvedVersionContext DEFAULT_RESOLVED_VERSION_CONTEXT =
      ResolvedVersionContext.ofBranch(
          DEFAULT_BRANCH_NAME, DigestUtils.sha256Hex(UUID.randomUUID().toString()));
  private static final String FOLDER_NAME_1 = "folder1";
  private static final String FOLDER_NAME_2 = "folder2";
  private static final String TABLE_NAME_1 = "table1";
  private static final String CONTENT_ID = "b1f470a9-14fa-4dc5-aa2f-90a36e8c3a87";
  private static final List<ExternalNamespaceEntry> DEFAULT_ENTRIES =
      Arrays.asList(
          ExternalNamespaceEntry.of(Type.FOLDER, Collections.singletonList(FOLDER_NAME_1)),
          ExternalNamespaceEntry.of(Type.FOLDER, Collections.singletonList(FOLDER_NAME_2)),
          ExternalNamespaceEntry.of(Type.ICEBERG_TABLE, Collections.singletonList(TABLE_NAME_1)));
  private static final SourceConfig SOURCE_CONFIG =
      new SourceConfig()
          .setId(new EntityId().setId("id"))
          .setName("test")
          .setType("s3")
          .setTag("tag")
          .setMetadataPolicy(
              new MetadataPolicy()
                  .setAuthTtlMs(100_000L)
                  .setDatasetDefinitionExpireAfterMs(300_000L)
                  .setDatasetDefinitionRefreshAfterMs(300_000L)
                  .setDatasetUpdateMode(UpdateMode.INLINE)
                  .setNamesRefreshMs(300_000L))
          .setCtime(System.currentTimeMillis());
  private static final List<ConnectionConf<?, ?>> validConnectionConfs =
      ImmutableList.of(new NonInternalConf());
  private static final List<ConnectionConf<?, ?>> invalidConnectionConfs =
      ImmutableList.of(
          new SystemPluginConf(), new HomeFileConf(), new PDFSConf(), new InternalFileConf());

  @Mock private NamespaceService namespaceService;
  @Mock private DataplanePlugin dataplanePlugin;
  @Mock private ConnectionReader connectionReader;
  @Mock private ReflectionAdministrationService.Factory reflectionService;
  @Mock private SecurityContext securityContext;
  @Mock private CatalogService catalogService;
  @Mock private Catalog catalog;
  @Mock private ReflectionServiceHelper reflectionServiceHelper;

  private SourceService getSourceService() {
    return getSourceService(Clock.systemUTC());
  }

  private SourceService getSourceService(Clock clock) {
    return new SourceService(
        clock,
        mock(OptionManager.class),
        namespaceService,
        mock(DatasetVersionMutator.class),
        catalogService,
        reflectionServiceHelper,
        mock(CollaborationHelper.class),
        connectionReader,
        securityContext);
  }

  private void testConnectionConfs(
      List<ConnectionConf<?, ?>> validConnectionConfs, boolean isValid) {
    SourceService sourceService = getSourceService();
    for (ConnectionConf<?, ?> connectionConf : validConnectionConfs) {
      try {
        sourceService.validateConnectionConf(connectionConf);
      } catch (UserException e) {
        assertFalse(isValid);
      }
    }
  }

  @Test
  public void testCreateSource() throws Exception {
    Clock clock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
    SourceService sourceService = getSourceService(clock);

    final Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("username");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    final Catalog catalog = mock(Catalog.class);
    when(catalogService.getCatalog(any())).thenReturn(catalog);

    final ReflectionSettings reflectionSettings = mock(ReflectionSettings.class);
    when(reflectionServiceHelper.getReflectionSettings()).thenReturn(reflectionSettings);

    SourceConfig sourceConfig =
        ProtostuffUtil.copy(SOURCE_CONFIG).setCtime(null).setId(null).setTag(null);
    when(connectionReader.getConnectionConf(sourceConfig)).thenReturn(mock(ConnectionConf.class));

    assertThat(sourceConfig.getCtime()).isNull();

    sourceService.createSource(sourceConfig, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);

    verify(catalog, times(1))
        .createSource(eq(sourceConfig), eq(SourceRefreshOption.WAIT_FOR_DATASETS_CREATION));
  }

  @Test
  public void testUpdateSource_invalidCtime() throws Exception {
    SourceService sourceService = getSourceService();

    // Mock currently stored source.
    SourceConfig currentSourceConfig = ProtostuffUtil.copy(SOURCE_CONFIG);
    when(namespaceService.getSourceById(eq(currentSourceConfig.getId())))
        .thenReturn(currentSourceConfig);

    // Mock updated one.
    SourceConfig updatedSourceConfig =
        ProtostuffUtil.copy(currentSourceConfig)
            .setAccelerationNeverRefresh(true)
            .setCtime(System.currentTimeMillis() + 1000);

    when(connectionReader.getConnectionConf(updatedSourceConfig))
        .thenReturn(mock(ConnectionConf.class));

    // Expect an exception.
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                sourceService.updateSource(
                    updatedSourceConfig.getId().getId(), updatedSourceConfig));
    assertThat(e.getMessage()).contains("Creation time is immutable.");
  }

  @Test
  public void testGetSource() throws Exception {
    // Arrange
    complexMockSetup();
    final Principal principal = mock(Principal.class);
    when(dataplanePlugin.resolveVersionContext(DEFAULT_VERSION_CONTEXT))
        .thenReturn(DEFAULT_RESOLVED_VERSION_CONTEXT);
    when(dataplanePlugin.listEntries(
            any(),
            eq(DEFAULT_RESOLVED_VERSION_CONTEXT),
            eq(VersionedPlugin.NestingMode.IMMEDIATE_CHILDREN_ONLY),
            eq(VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)))
        .thenReturn(DEFAULT_ENTRIES.stream());
    SourceResource sourceResource = makeSourceResource();
    when(dataplanePlugin.getState()).thenReturn(SourceState.GOOD);
    when(catalogService.getSource(anyString())).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(dataplanePlugin.unwrap(VersionedPlugin.class)).thenReturn(dataplanePlugin);
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Act
    NamespaceTree contents =
        sourceResource.getSource(true, false, DEFAULT_REF_TYPE, DEFAULT_BRANCH_NAME).getContents();

    // Assert
    assertMatchesDefaultEntries(contents);
  }

  @Test
  public void testGetFolder() throws Exception {
    final Principal principal = mock(Principal.class);
    // Arrange
    when(namespaceService.getDataset(any())).thenThrow(NamespaceNotFoundException.class);
    when(catalogService.getSource(anyString())).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(dataplanePlugin.unwrap(VersionedPlugin.class)).thenReturn(dataplanePlugin);
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    when(dataplanePlugin.resolveVersionContext(DEFAULT_VERSION_CONTEXT))
        .thenReturn(DEFAULT_RESOLVED_VERSION_CONTEXT);
    when(dataplanePlugin.listEntries(
            any(),
            eq(DEFAULT_RESOLVED_VERSION_CONTEXT),
            eq(VersionedPlugin.NestingMode.IMMEDIATE_CHILDREN_ONLY),
            eq(VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)))
        .thenReturn(DEFAULT_ENTRIES.stream());

    SourceResource sourceResource = makeSourceResource();
    when(dataplanePlugin.getState()).thenReturn(SourceState.GOOD);
    when(dataplanePlugin.getFolder(any(CatalogEntityKey.class)))
        .thenReturn(Optional.of(mock(FolderConfig.class)));

    // Act
    NamespaceTree contents =
        sourceResource
            .getFolder("folder", true, false, DEFAULT_REF_TYPE, DEFAULT_BRANCH_NAME)
            .getContents();

    // Assert
    assertMatchesDefaultEntries(contents);
  }

  @Test
  public void testCreateFolder() throws CatalogException {
    SourceResource sourceResource = makeSourceResource();
    final Principal principal = mock(Principal.class);
    CatalogFolder catalogFolder = mock(CatalogFolder.class);

    final NamespaceKey key = new NamespaceKey(List.of(SOURCE_NAME, FOLDER_NAME_1));

    final VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(key.getPathComponents())
            .setContentId("b1f470a9-14fa-4dc5-aa2f-90a36e8c3a87")
            .setTableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
            .build();

    when(catalogFolder.id()).thenReturn(versionedDatasetId.asString());
    when(catalogFolder.fullPath()).thenReturn(key.getPathComponents());
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    when(catalog.createFolder(any())).thenReturn(Optional.of(catalogFolder));
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    FolderModel folder =
        sourceResource.createFolder(
            null, DEFAULT_REF_TYPE, DEFAULT_BRANCH_NAME, new FolderBody(FOLDER_NAME_1, null));

    assertThat(folder.getName()).isEqualTo(FOLDER_NAME_1);
    assertThat(folder.getIsPhysicalDataset()).isFalse();
    assertThat(VersionedDatasetId.tryParse(folder.getId())).isEqualTo(versionedDatasetId);
  }

  @Test
  public void testCreateFolderAlreadyExistsException() throws CatalogException {
    SourceName sourceName = new SourceName("source");
    final Principal principal = mock(Principal.class);
    makeSourceResource();
    SourceFolderPath folderPath = new SourceFolderPath("source.folder");
    String userName = "user";
    String storageUri = "uri";
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    doThrow(CatalogEntityAlreadyExistsException.class).when(catalog).createFolder(any());

    assertThatThrownBy(
            () ->
                getSourceService()
                    .createFolder(sourceName, folderPath, userName, null, null, storageUri))
        .isInstanceOf(ResourceExistsException.class)
        .hasMessageContaining("Folder source.folder already exists.");
  }

  @Test
  public void testCreateFolderCatalogException() throws CatalogException {
    SourceName sourceName = new SourceName("source");
    final Principal principal = mock(Principal.class);
    makeSourceResource();
    SourceFolderPath folderPath = new SourceFolderPath("source.folder");
    String userName = "user";
    String storageUri = "uri";
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    doThrow(new CatalogEntityNotFoundException("That thang ain't found."))
        .when(catalog)
        .createFolder(any());

    assertThatThrownBy(
            () ->
                getSourceService()
                    .createFolder(sourceName, folderPath, userName, null, null, storageUri))
        .isInstanceOf(WebApplicationException.class)
        .hasMessageContaining("That thang ain't found.");
  }

  @Test
  public void testCreateFolderThrowsResourceForbiddenException() throws CatalogException {
    SourceName sourceName = new SourceName("source");
    final Principal principal = mock(Principal.class);
    makeSourceResource();
    SourceFolderPath folderPath = new SourceFolderPath("source.folder");
    String userName = "user";
    String storageUri = "uri";
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    doThrow(CatalogEntityForbiddenException.class).when(catalog).createFolder(any());

    assertThatThrownBy(
            () ->
                getSourceService()
                    .createFolder(sourceName, folderPath, userName, null, null, storageUri))
        .isInstanceOf(ResourceForbiddenException.class);
  }

  @Test
  public void testDeleteFolderThrowsCatalogException() throws CatalogException {
    SourceFolderPath folderPath = new SourceFolderPath("source.testFolder");
    final Principal principal = mock(Principal.class);
    makeSourceResource();
    when(securityContext.getUserPrincipal()).thenReturn(principal);
    VersionContext versionContext = VersionContext.NOT_SPECIFIED;
    CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath.toPathList())
            .tableVersionContext(TableVersionContext.of(versionContext))
            .build();
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);

    doThrow(new CatalogEntityAlreadyExistsException("Catalog error"))
        .when(catalog)
        .deleteFolder(folderKey, null);

    assertThatThrownBy(() -> getSourceService().deleteFolder(folderPath, null, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Catalog error");
  }

  @Test
  public void testUpdateFolderThrowsCatalogEntityNotFoundException() throws CatalogException {
    SourceName sourceName = new SourceName("testSource");
    final Principal principal = mock(Principal.class);
    SourceFolderPath folderPath = new SourceFolderPath("testSource.testFolder");
    String storageUri = "testUri";
    when(securityContext.getUserPrincipal()).thenReturn(principal);
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    VersionContext versionContext = VersionContext.NOT_SPECIFIED;
    CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath.toPathList())
            .tableVersionContext(TableVersionContext.of(versionContext))
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(versionContext)
            .setStorageUri(storageUri)
            .build();

    doThrow(
            new CatalogEntityNotFoundException(
                "Folder not found", new ReferenceNotFoundException()))
        .when(catalog)
        .updateFolder(inputCatalogFolder);

    assertThatThrownBy(
            () -> getSourceService().updateFolder(sourceName, folderPath, null, null, storageUri))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Folder testSource.testFolder does not exist.");
  }

  @Test
  public void testUpdateFolderThrowsResourceForbiddenException() throws CatalogException {
    SourceName sourceName = new SourceName("testSource");
    final Principal principal = mock(Principal.class);
    SourceFolderPath folderPath = new SourceFolderPath("testSource.testFolder");
    String storageUri = "testUri";
    when(securityContext.getUserPrincipal()).thenReturn(principal);
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    VersionContext versionContext = VersionContext.NOT_SPECIFIED;
    CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath.toPathList())
            .tableVersionContext(TableVersionContext.of(versionContext))
            .build();
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(folderKey.getKeyComponents())
            .setVersionContext(versionContext)
            .setStorageUri(storageUri)
            .build();

    doThrow(new CatalogEntityForbiddenException("Forbidden"))
        .when(catalog)
        .updateFolder(inputCatalogFolder);

    assertThatThrownBy(
            () -> getSourceService().updateFolder(sourceName, folderPath, null, null, storageUri))
        .isInstanceOf(ResourceForbiddenException.class);
  }

  @Test
  public void testFolderIdForVersionedSources() throws CatalogException {
    String sourceName = "nessieSource";
    CatalogFolder catalogFolder = mock(CatalogFolder.class);
    String folderId =
        "{\"tableKey\":[\"nessie_demo\",\"test\"],"
            + "\"contentId\":\"4c04aa93-a1c2-40e3-a3cd-63680617dcfb\","
            + "\"versionContext\":{\"type\":\"BRANCH\",\"value\":\"main\"}}";
    SourceConfig sourceConfig = SourceConfig.getDefaultInstance();
    sourceConfig.setId(new EntityId());
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    when(catalogFolder.id()).thenReturn(folderId);
    when(catalogFolder.fullPath()).thenReturn(Arrays.asList("nessie_demo", "test"));
    when(catalog.createFolder(any())).thenReturn(Optional.of(catalogFolder));
    final Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("username");
    when(securityContext.getUserPrincipal()).thenReturn(principal);
    SourceFolderPath sourceFolderPath = new SourceFolderPath("foo.bar");
    Optional<FolderModel> folder =
        getSourceService()
            .createFolder(
                new SourceName(sourceName), sourceFolderPath, "bar", "BRANCH", "main", null);
    assertThat(folder.get().getId()).isEqualTo(folderId);
  }

  @Test
  public void testCreateFolderException() throws CatalogException {
    SourceResource sourceResource = makeSourceResource();
    String sourceName = "nessieSource";
    SourceFolderPath sourceFolderPath = new SourceFolderPath("foo.bar");
    final Principal principal = mock(Principal.class);
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);

    doThrow(CatalogEntityAlreadyExistsException.class).when(catalog).createFolder(any());

    assertThatThrownBy(
            () ->
                getSourceService()
                    .createFolder(
                        new SourceName(sourceName),
                        sourceFolderPath,
                        "user",
                        "BRANCH",
                        "main",
                        null))
        .isInstanceOf(ResourceExistsException.class);
  }

  @Test
  public void testCreateFolderWithSpaceInFolderName() throws CatalogException {
    final String folderNameWithSpace = "folder with space";
    SourceFolderPath sourceFolderPath =
        SourceFolderPath.fromURLPath(new SourceName(SOURCE_NAME), folderNameWithSpace);
    makeSourceResource();
    CatalogFolder returnedCatalogFolder = mock(CatalogFolder.class);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);
    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(Arrays.asList(SOURCE_NAME, folderNameWithSpace))
            .tableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
            .build();
    final VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(key.getKeyComponents())
            .setContentId(CONTENT_ID)
            .setTableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
            .build();
    when(returnedCatalogFolder.id()).thenReturn(versionedDatasetId.asString());
    when(returnedCatalogFolder.fullPath())
        .thenReturn(Arrays.asList(SOURCE_NAME, folderNameWithSpace));
    when(catalog.createFolder(any())).thenReturn(Optional.of(returnedCatalogFolder));
    final Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("username");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    Optional<FolderModel> folder =
        getSourceService()
            .createFolder(
                new SourceName(SOURCE_NAME),
                sourceFolderPath,
                "user",
                DEFAULT_REF_TYPE,
                DEFAULT_BRANCH_NAME,
                null);

    verify(catalog)
        .createFolder(
            new ImmutableCatalogFolder.Builder()
                .setFullPath(key.getKeyComponents())
                .setVersionContext(DEFAULT_VERSION_CONTEXT)
                .build());
    assertThat(folder.get().getName()).isEqualTo(folderNameWithSpace);
    assertThat(folder.get().getIsPhysicalDataset()).isFalse();
    assertThat(VersionedDatasetId.tryParse(folder.get().getId())).isEqualTo(versionedDatasetId);
  }

  @Test
  public void testCreateFolderWithSpaceInFolderNameWithinNestedFolder() throws CatalogException {
    final String rootFolderNameWithSpace = "folder with space";
    final String leafFolderNameWithSpace = "folder with another space";
    SourceFolderPath sourceFolderPath =
        SourceFolderPath.fromURLPath(
            new SourceName(SOURCE_NAME), rootFolderNameWithSpace + "/" + leafFolderNameWithSpace);
    final Principal principal = mock(Principal.class);
    makeSourceResource();
    CatalogFolder returnedCatalogFolder = mock(CatalogFolder.class);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);

    final CatalogEntityKey key =
        CatalogEntityKey.newBuilder()
            .keyComponents(
                Arrays.asList(SOURCE_NAME, rootFolderNameWithSpace, leafFolderNameWithSpace))
            .tableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
            .build();
    final VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(key.getKeyComponents())
            .setContentId(CONTENT_ID)
            .setTableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
            .build();

    when(securityContext.getUserPrincipal()).thenReturn(principal);
    when(returnedCatalogFolder.id()).thenReturn(versionedDatasetId.asString());
    when(returnedCatalogFolder.fullPath())
        .thenReturn(Arrays.asList(SOURCE_NAME, rootFolderNameWithSpace, leafFolderNameWithSpace));
    when(catalog.createFolder(any())).thenReturn(Optional.of(returnedCatalogFolder));
    when(principal.getName()).thenReturn("username");
    Optional<FolderModel> folder =
        getSourceService()
            .createFolder(
                new SourceName(SOURCE_NAME),
                sourceFolderPath,
                "user",
                DEFAULT_REF_TYPE,
                DEFAULT_BRANCH_NAME,
                null);

    verify(catalog)
        .createFolder(
            new ImmutableCatalogFolder.Builder()
                .setFullPath(key.getKeyComponents())
                .setVersionContext(DEFAULT_VERSION_CONTEXT)
                .build());
    assertThat(folder.get().getName()).isEqualTo(leafFolderNameWithSpace);
    assertThat(folder.get().getIsPhysicalDataset()).isFalse();
    assertThat(VersionedDatasetId.tryParse(folder.get().getId())).isEqualTo(versionedDatasetId);
  }

  @Test
  public void testDeleteFolder() throws CatalogException {
    final String rootFolder = "rootFolder";
    SourceFolderPath sourceFolderPath =
        new SourceFolderPath(Arrays.asList(SOURCE_NAME, rootFolder));
    SourceResource sourceResource = makeSourceResource();
    final Principal principal = mock(Principal.class);
    CatalogFolder catalogFolder = mock(CatalogFolder.class);

    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(catalog);

    when(catalogFolder.id()).thenReturn("id");
    when(catalogFolder.fullPath()).thenReturn(Arrays.asList(SOURCE_NAME, rootFolder));
    when(catalog.createFolder(any())).thenReturn(Optional.of(catalogFolder));
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    getSourceService()
        .createFolder(
            new SourceName(SOURCE_NAME),
            sourceFolderPath,
            "user",
            DEFAULT_REF_TYPE,
            DEFAULT_BRANCH_NAME,
            null);

    getSourceService().deleteFolder(sourceFolderPath, DEFAULT_REF_TYPE, DEFAULT_BRANCH_NAME);
    verify(catalog)
        .deleteFolder(
            CatalogEntityKey.newBuilder()
                .keyComponents(Arrays.asList(SOURCE_NAME, rootFolder))
                .tableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
                .build(),
            null);
  }

  @Test
  public void testDeletePhysicalDatasetForVersionedSource() {
    when(catalogService.getSource("nessie")).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    SourceService sourceService = getSourceService();

    assertThatThrownBy(
            () -> sourceService.deletePhysicalDataset(new SourceName("nessie"), null, "0001", null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("not allowed for Versioned source");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testListSource_versioned(boolean folderOrSource) throws Exception {
    when(catalogService.getSource("source")).thenReturn(dataplanePlugin);
    when(dataplanePlugin.isWrapperFor(VersionedPlugin.class)).thenReturn(true);
    when(dataplanePlugin.unwrap(VersionedPlugin.class)).thenReturn(dataplanePlugin);
    if (!folderOrSource) {
      when(dataplanePlugin.getState()).thenReturn(SourceState.GOOD);
    }

    // Verify that page token is passed to versioned plugin and the result is passed back.
    String pageToken = "page-token";
    String nextPageToken = "next-page-token";
    int maxResults = 3;
    when(dataplanePlugin.listEntriesPage(
            folderOrSource ? ImmutableList.of("folder") : ImmutableList.of(),
            dataplanePlugin.resolveVersionContext(VersionContext.NOT_SPECIFIED),
            VersionedPlugin.NestingMode.IMMEDIATE_CHILDREN_ONLY,
            VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY,
            new ImmutableVersionedListOptions.Builder()
                .setPageToken(pageToken)
                .setMaxResultsPerPage(maxResults)
                .build()))
        .thenReturn(
            new ImmutableVersionedListResponsePage.Builder()
                .setEntries(ImmutableList.of())
                .setPageToken(nextPageToken)
                .build());
    NamespaceTree tree =
        folderOrSource
            ? getSourceService()
                .listFolder(
                    new SourceName("source"),
                    new SourceFolderPath(ImmutableList.of("source", "folder")),
                    null,
                    null,
                    null,
                    pageToken,
                    maxResults,
                    false)
            : getSourceService()
                .listSource(
                    new SourceName("source"), null, null, null, null, pageToken, maxResults, false);
    assertThat(tree.getNextPageToken()).isEqualTo(nextPageToken);

    verify(dataplanePlugin, times(1))
        .listEntriesPage(
            any(), any(), any(), eq(VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY), any());
  }

  @Test
  public void testValidConnectionConfs() {
    testConnectionConfs(validConnectionConfs, true);
  }

  @Test
  public void testInvalidConnectionConfs() {
    testConnectionConfs(invalidConnectionConfs, false);
  }

  @Test
  public void testUnhealthySourceStateCheck() {
    String name = "mysource";
    String userName = "testUser";
    SourceName versionedSource = new SourceName(name);
    when(catalogService.getSource(name)).thenReturn(dataplanePlugin);
    when(dataplanePlugin.getState()).thenReturn(SourceState.DELETED);
    assertThatThrownBy(
            () ->
                getSourceService()
                    .listSource(versionedSource, null, userName, "BRANCH", "main", null, 10, false))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Cannot connect to");
  }

  private void assertMatchesDefaultEntries(NamespaceTree contents) {

    List<FolderModel> folders = contents.getFolders();
    List<PhysicalDataset> physicalDatasets = contents.getPhysicalDatasets();
    List<File> files = contents.getFiles();
    List<Dataset> virtualDatasets = contents.getDatasets();
    List<Function> udfs = contents.getFunctions();

    assertThat(folders).hasSize(2);
    assertThat(folders.get(0).getName()).isEqualTo(FOLDER_NAME_1);
    assertThat(folders.get(0).getIsPhysicalDataset()).isFalse();

    assertThat(physicalDatasets).hasSize(1);
    assertThat(physicalDatasets.get(0).getDatasetName().getName()).isEqualTo(TABLE_NAME_1);

    assertThat(files).isEmpty();
    assertThat(virtualDatasets).isEmpty();
    assertThat(udfs).isEmpty();

    assertThat(contents.totalCount()).isEqualTo(3);
  }

  private SourceResource makeSourceResource() {
    final SourceService sourceService = getSourceService();

    return new SourceResource(
        namespaceService,
        reflectionService,
        sourceService,
        new SourceName(SOURCE_NAME),
        mock(QueryExecutor.class),
        securityContext,
        connectionReader,
        mock(SourceCatalog.class),
        mock(FormatTools.class),
        mock(BufferAllocatorFactory.class),
        mock(OptionManager.class),
        () -> mock(Orphanage.Factory.class),
        catalogService);
  }

  private void complexMockSetup() throws NamespaceException {
    final SourceConfig sourceConfig =
        new SourceConfig()
            .setName(SOURCE_NAME)
            .setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY)
            .setCtime(100L)
            .setId(new EntityId().setId("1"));
    when(connectionReader.getConnectionConf(sourceConfig)).thenReturn(mock(ConnectionConf.class));
    when(namespaceService.getSource(any())).thenReturn(sourceConfig);
    when(namespaceService.getDatasetCount(any(), anyLong(), anyInt()))
        .thenReturn(new BoundedDatasetCount(0, false, false));
    when(catalogService.getSourceState(SOURCE_NAME)).thenReturn(SourceState.GOOD);

    ReflectionSettings reflectionSettings = mock(ReflectionSettings.class);
    when(reflectionSettings.getReflectionSettings((NamespaceKey) any())).thenReturn(null);

    ReflectionAdministrationService reflectionAdministrationService =
        mock(ReflectionAdministrationService.class);
    when(reflectionAdministrationService.getReflectionSettings()).thenReturn(reflectionSettings);

    when(reflectionService.get(any())).thenReturn(reflectionAdministrationService);
  }

  private static final class NonInternalConf
      extends ConnectionConf<NonInternalConf, StoragePlugin> {

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }

    @Override
    // Don't need to override, but making it explicit.
    public boolean isInternal() {
      return false;
    }
  }
}
