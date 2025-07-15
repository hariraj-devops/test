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
package com.dremio.dac.explore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogUnsupportedOperationException;
import com.dremio.common.exceptions.UserException;
import com.dremio.dac.explore.join.JoinRecommender;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetUI;
import com.dremio.dac.proto.model.dataset.SourceVersionReference;
import com.dremio.dac.proto.model.dataset.VersionContext;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.SupportsMutatingViews;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRestApi;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.test.DremioTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.ws.rs.core.SecurityContext;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

public class TestCreateViewFromAPI extends DremioTest {

  private static final List<String> SOURCE_PATH = Arrays.asList("test", "view1");
  private static final DatasetPath SOURCE_DATASET_PATH = new DatasetPath(SOURCE_PATH);
  private static final String VIEW_NAME = "view1";
  private static final String BRANCH_NAME = "main";
  private static final String SQL = "select * from test.table1";
  private static final String OWNER = "dremio";

  private static final SourceVersionReference SOURCE_VERSION_REFERENCE =
      new SourceVersionReference("source", VersionContext.getDefaultInstance());
  private static final List<SourceVersionReference> SOURCE_VERSION_REFERENCE_LIST =
      Arrays.asList(SOURCE_VERSION_REFERENCE);

  @Rule public MockitoRule rule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);
  @Mock private DatasetTool tool;
  @Mock private QueryExecutor executor;
  @Mock private DatasetVersionMutator datasetService;
  @Mock private JobsService jobsService;
  @Mock private Transformer transformer;
  @Mock private Recommenders recommenders;
  @Mock private JoinRecommender joinRecommender;
  @Mock private SecurityContext securityContext;
  @Mock private DatasetPath datasetPath;
  @Mock private DatasetVersion version;
  @Mock private HistogramGenerator histograms;
  @Mock private BufferAllocator allocator;
  @Mock private Catalog catalog;
  @Mock private CatalogService catalogService;
  @Mock private DatasetUI datasetUI;

  private DatasetVersionResource datasetVersionResource;
  private VirtualDatasetUI virtualDatasetUI = new VirtualDatasetUI();

  @Test
  public void testCreateVersionedViewVerify()
      throws IOException,
          UserNotFoundException,
          NamespaceException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    setup();
    doReturn(true).when(datasetVersionResource).isVersionedPlugin(datasetPath, catalog);
    doReturn(virtualDatasetUI).when(datasetService).getVersion(datasetPath, version, true);
    StoragePlugin plugin = mock(StoragePlugin.class);
    doReturn(plugin).when(catalog).getSource(anyString());
    doReturn(false).when(plugin).isWrapperFor(SupportsMutatingViews.class);

    doNothing()
        .when(datasetService)
        .putWithVersionedSource(virtualDatasetUI, datasetPath, BRANCH_NAME, null);
    doNothing().when(datasetVersionResource).setReference(virtualDatasetUI, BRANCH_NAME);
    when(datasetPath.toPathList()).thenReturn(List.of("test", "view1"));
    when(datasetPath.getDataset()).thenReturn(SOURCE_DATASET_PATH.getDataset());
    doReturn(datasetUI).when(datasetVersionResource).newDataset(virtualDatasetUI, null);
    datasetVersionResource.saveAsDataSet(datasetPath, null, BRANCH_NAME);
    verify(datasetService, times(1))
        .putWithVersionedSource(virtualDatasetUI, datasetPath, BRANCH_NAME, null);
  }

  @Test
  public void testCreateNonVersionedViewWithBranchName()
      throws IOException, UserNotFoundException, NamespaceException {
    setup();
    doReturn(false).when(datasetVersionResource).isVersionedPlugin(SOURCE_DATASET_PATH, catalog);
    doReturn(virtualDatasetUI).when(datasetService).getVersion(datasetPath, version, false);
    assertThatThrownBy(
            () -> datasetVersionResource.saveAsDataSet(SOURCE_DATASET_PATH, null, BRANCH_NAME))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Tried to create a non-versioned view but branch name is not null");
  }

  @Test
  public void testCreateViewWithSupportsMutatingViewsVerify()
      throws IOException,
          UserNotFoundException,
          NamespaceException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    setup();
    StoragePlugin plugin = mock(StoragePlugin.class);
    doReturn(false).when(datasetVersionResource).isVersionedPlugin(datasetPath, catalog);
    doReturn(plugin).when(catalog).getSource(anyString());
    doReturn(true).when(plugin).isWrapperFor(SupportsMutatingViews.class);
    doReturn(true).when(plugin).isWrapperFor(SupportsIcebergRestApi.class);
    doReturn(virtualDatasetUI).when(datasetService).getVersion(datasetPath, version, false);
    doNothing()
        .when(datasetService)
        .putWithSupportsMutatingViews(virtualDatasetUI, SOURCE_DATASET_PATH, null);
    when(datasetPath.toPathList()).thenReturn(List.of("test", "view1"));
    when(datasetPath.getDataset()).thenReturn(SOURCE_DATASET_PATH.getDataset());
    doReturn(datasetUI).when(datasetVersionResource).newDataset(virtualDatasetUI, null);
    datasetVersionResource.saveAsDataSet(datasetPath, null, null);
    verify(datasetService, times(1))
        .putWithSupportsMutatingViews(virtualDatasetUI, datasetPath, null);
  }

  @Test
  public void testCreateVersionedViewWithoutBranchName()
      throws IOException, UserNotFoundException, NamespaceException {
    setup();
    doReturn(true).when(datasetVersionResource).isVersionedPlugin(SOURCE_DATASET_PATH, catalog);
    doReturn(virtualDatasetUI).when(datasetVersionResource).getDatasetConfig(true);
    assertThatThrownBy(() -> datasetVersionResource.saveAsDataSet(SOURCE_DATASET_PATH, null, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Tried to create a versioned view but branch name is null");
  }

  @Test
  public void testCreateNonVersionedViewVerify()
      throws IOException,
          UserNotFoundException,
          NamespaceException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    setup();
    doReturn(false).when(datasetVersionResource).isVersionedPlugin(SOURCE_DATASET_PATH, catalog);
    doReturn(virtualDatasetUI).when(datasetService).getVersion(datasetPath, version, false);
    doReturn(datasetUI)
        .when(datasetVersionResource)
        .save(virtualDatasetUI, SOURCE_DATASET_PATH, null, null, false);
    datasetVersionResource.saveAsDataSet(SOURCE_DATASET_PATH, null, null);
    verify(datasetService, times(0))
        .putWithVersionedSource(virtualDatasetUI, SOURCE_DATASET_PATH, BRANCH_NAME, null);
  }

  public void setup() throws IOException, UserNotFoundException, NamespaceException {
    datasetVersionResource =
        spy(
            new DatasetVersionResource(
                executor,
                datasetService,
                recommenders,
                transformer,
                joinRecommender,
                tool,
                histograms,
                securityContext,
                datasetPath,
                version,
                allocator));
    virtualDatasetUI.setFullPathList(SOURCE_PATH);
    virtualDatasetUI.setVersion(DatasetVersion.newVersion());
    virtualDatasetUI.setName(VIEW_NAME);
    virtualDatasetUI.setSql(SQL);
    virtualDatasetUI.setOwner(OWNER);
    virtualDatasetUI.setReferencesList(SOURCE_VERSION_REFERENCE_LIST);
    doReturn(catalog).when(datasetService).getCatalog();
  }
}
