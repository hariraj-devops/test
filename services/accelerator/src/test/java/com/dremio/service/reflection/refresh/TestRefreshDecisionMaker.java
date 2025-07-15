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
package com.dremio.service.reflection.refresh;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.reflection.DependencyEntry;
import com.dremio.service.reflection.DependencyEntry.DatasetDependency;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.store.MaterializationStore;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TestRefreshDecisionMaker {
  /**
   * Tests that hasNewSnapshotsForRefresh does not throw an NPE for an unavailable dataset
   * dependency
   */
  @Test
  public void testUnavailableDatasetDependency() {
    ReflectionEntry entry = new ReflectionEntry();
    EntityExplorer catalog = mock(EntityExplorer.class);
    List<String> datasetPath = Arrays.asList("path", "to", "dataset");
    NamespaceKey key = new NamespaceKey(datasetPath);
    DatasetDependency dependency = DependencyEntry.of("dataset", datasetPath, 0L, null);
    List<DependencyEntry> dependencies = Arrays.asList(dependency);
    MaterializationStore store = mock(MaterializationStore.class);

    String result =
        RefreshDecisionMaker.hasNewSnapshotsForRefresh(entry, catalog, dependencies, store);
    assertEquals(
        String.format(
            "Refresh couldn't be skipped because dataset dependency %s was not found", key),
        result);
  }

  /**
   * Tests that hasNewSnapshotsForRefresh immediately returns true if there is a table function
   * dependency
   */
  @Test
  public void testTableFunctionDependency() {
    ReflectionEntry entry = new ReflectionEntry();
    EntityExplorer catalog = mock(EntityExplorer.class);
    List<String> datasetPath = Arrays.asList("path", "to", "dataset");
    NamespaceKey key = new NamespaceKey(datasetPath);
    DatasetDependency datasetDependency = DependencyEntry.of("dataset", datasetPath, 0L, null);
    DependencyEntry tableFunctionDependency =
        DependencyEntry.of("tableFunction", "dummySource", "SELECT 1");
    List<DependencyEntry> dependencies = Arrays.asList(tableFunctionDependency, datasetDependency);
    MaterializationStore store = mock(MaterializationStore.class);

    String result =
        RefreshDecisionMaker.hasNewSnapshotsForRefresh(entry, catalog, dependencies, store);
    assertEquals(
        String.format(
            "Refresh couldn't be skipped because of table function dependency %s",
            tableFunctionDependency.getId()),
        result);
  }

  @Test
  public void testDatasetDependencySnapshotIdRetrievalFailed() {
    ReflectionEntry entry = new ReflectionEntry();
    EntityExplorer catalog = mock(EntityExplorer.class);
    List<String> datasetPath = Arrays.asList("path", "to", "dataset");
    DatasetDependency dependency = DependencyEntry.of("dataset", datasetPath, 0L, null);
    List<DependencyEntry> dependencies = Arrays.asList(dependency);
    MaterializationStore store = mock(MaterializationStore.class);
    DremioTable table = mock(DremioTable.class);
    DatasetConfig datasetConfig = mock(DatasetConfig.class);
    PhysicalDataset physicalDataset = mock(PhysicalDataset.class);
    IcebergMetadata metadata = mock(IcebergMetadata.class);
    when(catalog.getTable(any(CatalogEntityKey.class))).thenReturn(table);
    when(table.getDatasetConfig()).thenReturn(datasetConfig);
    when(datasetConfig.getPhysicalDataset()).thenReturn(physicalDataset);
    when(physicalDataset.getIcebergMetadata()).thenReturn(metadata);
    when(metadata.getFileType()).thenReturn(FileType.ICEBERG);
    when(metadata.getSnapshotId()).thenReturn(0L);

    String result =
        RefreshDecisionMaker.hasNewSnapshotsForRefresh(entry, catalog, dependencies, store);
    assertEquals(
        String.format(
            "Refresh couldn't be skipped because retrieving current snapshot ID for dataset dependency %s failed.",
            dependency.getNamespaceKey()),
        result);
  }
}
