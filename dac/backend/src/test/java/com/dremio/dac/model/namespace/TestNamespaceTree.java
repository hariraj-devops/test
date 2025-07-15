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
package com.dremio.dac.model.namespace;

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.dac.model.folder.SourceFolderPath;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import com.dremio.service.namespace.space.proto.FolderConfig;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TestNamespaceTree {

  @Test
  public void testAddFolderCheckFileSystemFolder() throws Exception {
    NamespaceTree namespaceTree = new NamespaceTree();
    namespaceTree.setIsFileSystemSource(true);
    namespaceTree.addFolder(
        new SourceFolderPath("abc.cde"), new FolderConfig(), null, Type.SOURCE, false, false);
    assertTrue(
        "Folder should be file system folder if source is file system source",
        namespaceTree.getFolders().get(0).isFileSystemFolder());

    namespaceTree = new NamespaceTree();
    namespaceTree.setIsFileSystemSource(false);
    namespaceTree.addFolder(
        new SourceFolderPath("abc.bcd"), new FolderConfig(), null, Type.SOURCE, false, false);
    assertFalse(
        "Folder should not be file system folder if source is not file system source",
        namespaceTree.getFolders().get(0).isFileSystemFolder());
  }

  @Test
  public void testVirtualDatasetListingWhenRestCatalogViewsSupportedIsFalse()
      throws NamespaceException {
    // Initialize
    DatasetVersionMutator datasetService = mock(DatasetVersionMutator.class);
    OptionManager optionManager = mock(OptionManager.class);

    // Arrange
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);

    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(List.of("root", "path"));
    datasetConfig.setName("testDataset");

    NameSpaceContainer container = new NameSpaceContainer();
    container.setType(Type.DATASET);
    container.setDataset(datasetConfig);
    container.setFullPathList(List.of("root", "path", "testDataset"));

    List<NameSpaceContainer> children = Collections.singletonList(container);

    // Act & Assert
    // Should not add anyu entries to namespace tree from source
    NamespaceTree namespaceTree =
        NamespaceTree.newInstance(
            datasetService, children, Type.SOURCE, null, null, null, optionManager);
    assertTrue(namespaceTree.totalCount() == 0);
  }
}
