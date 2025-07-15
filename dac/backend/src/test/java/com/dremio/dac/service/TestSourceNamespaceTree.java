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
package com.dremio.dac.service;

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dremio.dac.model.folder.SourceFolderPath;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.sources.SourceName;
import com.dremio.dac.service.collaboration.CollaborationHelper;
import com.dremio.dac.service.collaboration.TagsSearchResult;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.PhysicalDatasetNotFoundException;
import com.dremio.dac.service.reflection.ReflectionServiceHelper;
import com.dremio.dac.service.source.SourceService;
import com.dremio.exec.catalog.ConnectionReader;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.file.proto.ParquetFileConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import javax.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TestSourceNamespaceTree {

  @Mock private NamespaceService namespaceService;
  @Mock private DatasetVersionMutator datasetService;
  @Mock private CatalogService catalogService;
  @Mock private ReflectionServiceHelper reflectionServiceHelper;
  @Mock private ConnectionReader connectionReader;
  @Mock private SecurityContext security;
  @Mock private CollaborationHelper collaborationService;
  @Mock private OptionManager optionManager;

  private SourceService sourceService;

  @BeforeEach
  public void setup() {
    MockitoAnnotations.openMocks(this);
    sourceService =
        new SourceService(
            Clock.systemUTC(),
            optionManager,
            namespaceService,
            datasetService,
            catalogService,
            reflectionServiceHelper,
            collaborationService,
            connectionReader,
            security);
  }

  @Test
  public void testListFolderWithVirtualAndPhysicalDatasetsInSource()
      throws NamespaceException, PhysicalDatasetNotFoundException {
    SourceName sourceName = new SourceName("testSource");
    SourceFolderPath folderPath = new SourceFolderPath("testSource.folder");
    String userName = "testUser";
    String refType = "BRANCH";
    String refValue = "main";

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(catalogService.getSource("testSource")).thenReturn(storagePlugin);

    TagsSearchResult tagSearchResult = new TagsSearchResult(new HashMap<>(), false);
    when(collaborationService.getTagsForIds(any())).thenReturn(tagSearchResult);

    // Mock the namespace service to return a list of entities
    DatasetConfig datasetConfig1 = new DatasetConfig();
    datasetConfig1.setId(new EntityId(UUID.randomUUID().toString()));
    datasetConfig1.setFullPathList(Arrays.asList("testSource", "folder", "virtualDataset"));
    datasetConfig1.setType(DatasetType.VIRTUAL_DATASET);
    NameSpaceContainer virtualDatasetContainer = new NameSpaceContainer();
    virtualDatasetContainer.setType(Type.DATASET);
    virtualDatasetContainer.setDataset(datasetConfig1);
    virtualDatasetContainer.setFullPathList(
        Arrays.asList("testSource", "folder", "virtualDataset"));

    DatasetConfig datasetConfig2 = new DatasetConfig();
    datasetConfig2.setType(DatasetType.PHYSICAL_DATASET);
    datasetConfig2.setId(new EntityId(UUID.randomUUID().toString()));
    datasetConfig2.setFullPathList(Arrays.asList("testSource", "folder", "physicalDataset"));
    PhysicalDataset physicalDataset = new PhysicalDataset();
    physicalDataset.setFormatSettings(new ParquetFileConfig().asFileConfig());
    datasetConfig2.setPhysicalDataset(physicalDataset);
    NameSpaceContainer physicalDatasetContainer = new NameSpaceContainer();
    physicalDatasetContainer.setType(Type.DATASET);
    physicalDatasetContainer.setDataset(datasetConfig2);
    physicalDatasetContainer.setFullPathList(
        Arrays.asList("testSource", "folder", "physicalDataset"));

    List<NameSpaceContainer> entities =
        Arrays.asList(virtualDatasetContainer, physicalDatasetContainer);
    when(namespaceService.list(any(NamespaceKey.class), any(), anyInt())).thenReturn(entities);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);

    // Call the method under test
    NamespaceTree namespaceTree =
        sourceService.listFolder(
            sourceName, folderPath, userName, refType, refValue, null, Integer.MAX_VALUE, false);

    // Verify the results
    assertThat(namespaceTree.totalCount() == 2).isTrue();
    assertThat(namespaceTree.getDatasets().size() == 1).isTrue();
    assertThat(namespaceTree.getPhysicalDatasets().size() == 1).isTrue();
  }

  @Test
  public void testListFolderWithVirtualAndPhysicalDatasetsInSourceWithoutViewOptionSet()
      throws NamespaceException, PhysicalDatasetNotFoundException {
    SourceName sourceName = new SourceName("testSource");
    SourceFolderPath folderPath = new SourceFolderPath("testSource.folder");
    String userName = "testUser";
    String refType = "BRANCH";
    String refValue = "main";

    StoragePlugin storagePlugin = mock(StoragePlugin.class);
    when(catalogService.getSource("testSource")).thenReturn(storagePlugin);

    TagsSearchResult tagSearchResult = new TagsSearchResult(new HashMap<>(), false);
    when(collaborationService.getTagsForIds(any())).thenReturn(tagSearchResult);

    // Mock the namespace service to return a list of entities
    DatasetConfig datasetConfig1 = new DatasetConfig();
    datasetConfig1.setId(new EntityId(UUID.randomUUID().toString()));
    datasetConfig1.setFullPathList(Arrays.asList("testSource", "folder", "virtualDataset"));
    datasetConfig1.setType(DatasetType.VIRTUAL_DATASET);
    NameSpaceContainer virtualDatasetContainer = new NameSpaceContainer();
    virtualDatasetContainer.setType(Type.DATASET);
    virtualDatasetContainer.setDataset(datasetConfig1);
    virtualDatasetContainer.setFullPathList(
        Arrays.asList("testSource", "folder", "virtualDataset"));

    DatasetConfig datasetConfig2 = new DatasetConfig();
    datasetConfig2.setType(DatasetType.PHYSICAL_DATASET);
    datasetConfig2.setId(new EntityId(UUID.randomUUID().toString()));
    datasetConfig2.setFullPathList(Arrays.asList("testSource", "folder", "physicalDataset"));
    PhysicalDataset physicalDataset = new PhysicalDataset();
    physicalDataset.setFormatSettings(new ParquetFileConfig().asFileConfig());
    datasetConfig2.setPhysicalDataset(physicalDataset);
    NameSpaceContainer physicalDatasetContainer = new NameSpaceContainer();
    physicalDatasetContainer.setType(Type.DATASET);
    physicalDatasetContainer.setDataset(datasetConfig2);
    physicalDatasetContainer.setFullPathList(
        Arrays.asList("testSource", "folder", "physicalDataset"));

    List<NameSpaceContainer> entities =
        Arrays.asList(virtualDatasetContainer, physicalDatasetContainer);
    when(namespaceService.list(any(NamespaceKey.class), any(), anyInt())).thenReturn(entities);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);

    // Call the method under test
    NamespaceTree namespaceTree =
        sourceService.listFolder(
            sourceName, folderPath, userName, refType, refValue, null, Integer.MAX_VALUE, false);

    // Verify the results
    assertThat(namespaceTree.totalCount() == 1).isTrue();
    assertThat(namespaceTree.getDatasets().size() == 0).isTrue();
    assertThat(namespaceTree.getPhysicalDatasets().size() == 1).isTrue();
  }
}
