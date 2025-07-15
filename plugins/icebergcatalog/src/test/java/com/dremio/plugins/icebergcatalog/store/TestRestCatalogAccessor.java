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
package com.dremio.plugins.icebergcatalog.store;

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS;
import static com.dremio.plugins.icebergcatalog.store.AbstractRestCatalogAccessor.DEFAULT_BASE_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.iceberg.dremioudf.core.udf.InMemoryCatalog;
import com.dremio.options.OptionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewOperations;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestRestCatalogAccessor {

  @Mock private RESTCatalog mockRestCatalog;
  @Mock private BaseView mockBaseView;
  @Mock private ViewOperations viewOperations;
  @Mock private ViewMetadata mockViewMetadata;
  @Mock private OptionManager mockOptionManager;
  @Mock private Supplier<Catalog> invalidSupplierCatalog;
  @Mock private Supplier<Catalog> validSupplierCatalog;

  private CatalogAccessor restCatalogAccessor;
  private CatalogAccessor restCatalogAccessorWithViewSupport;
  private static final long CATALOG_EXPIRY_SECONDS = 2L;

  @Before
  public void setup() {
    when(validSupplierCatalog.get()).thenReturn(mockRestCatalog);
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS)).thenReturn(10L);
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS))
        .thenReturn(10L);
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS))
        .thenReturn(CATALOG_EXPIRY_SECONDS);

    when(mockBaseView.operations()).thenReturn(viewOperations);
    when(viewOperations.current()).thenReturn(mockViewMetadata);

    restCatalogAccessor =
        new IcebergRestCatalogAccessor(validSupplierCatalog, mockOptionManager, null, false);
    restCatalogAccessorWithViewSupport =
        new IcebergRestCatalogAccessor(validSupplierCatalog, mockOptionManager, null, false);
  }

  @After
  public void teardown() throws Exception {
    restCatalogAccessor = null;
  }

  @Test
  public void testDatasetExists() {
    when(mockRestCatalog.tableExists(TableIdentifier.of("b", "c"))).thenReturn(true);
    assertTrue(restCatalogAccessor.datasetExists(Arrays.asList("a", "b", "c")));
  }

  @Test
  public void testDatasetDoesNotExists() {
    when(mockRestCatalog.tableExists(any())).thenReturn(false);
    assertFalse(restCatalogAccessor.datasetExists(Arrays.asList("a", "b", "c")));
  }

  @Test
  public void testDatasetExistsThrowsNullPointerExceptionForNullList() {
    assertThatThrownBy(() -> restCatalogAccessor.datasetExists(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  public void testClose() throws Exception {
    restCatalogAccessor.datasetExists(Arrays.asList("a", "b", "c"));
    restCatalogAccessor.close();
    verify(((Closeable) mockRestCatalog), times(1)).close();
  }

  @Test
  public void testCatalogSupplierCalledAfterExpiry() throws Exception {
    when(mockRestCatalog.tableExists(any())).thenReturn(true);

    assertTrue(restCatalogAccessor.datasetExists(Arrays.asList("a", "b", "c")));

    Thread.sleep(TimeUnit.SECONDS.toMillis(CATALOG_EXPIRY_SECONDS + 1));
    assertTrue(restCatalogAccessor.datasetExists(Arrays.asList("a", "b", "c")));
  }

  private void testNSAllowList(
      Set<Namespace> allowedNamespaces,
      boolean isRecursiveAllowedNamespaces,
      List<Integer> expectedTableNameNums) {
    RESTCatalog catalog = mock(RESTCatalog.class);
    // -A
    //  |-tab0
    //  |-tab1
    //  |-a
    //  | |-tab2
    //  | |-alpha
    //  | |   |-tab3
    //  | |   |-tab4
    //  | |
    //  | |-beta
    //  |     |-tab5
    //  |
    //  |-b
    //    |-gamma
    //        |-tab6
    // -B
    //  |-c
    //  | |-delta
    //  |     |-tab7
    //  |-d
    //    |-tab8
    //    |-epsilon
    //        |-tab9

    when(catalog.listNamespaces(any(Namespace.class)))
        .then(
            invocationOnMock -> {
              Namespace argNs = invocationOnMock.getArgument(0);
              if (argNs.equals(Namespace.empty())) {
                return Lists.newArrayList(Namespace.of("A"), Namespace.of("B"));
              } else if (argNs.equals(Namespace.of("A"))) {
                return Lists.newArrayList(Namespace.of("A", "a"), Namespace.of("A", "b"));
              } else if (argNs.equals(Namespace.of("B"))) {
                return Lists.newArrayList(Namespace.of("B", "c"), Namespace.of("B", "d"));
              } else if (argNs.equals(Namespace.of("A", "a"))) {
                return Lists.newArrayList(
                    Namespace.of("A", "a", "alpha"), Namespace.of("A", "a", "beta"));
              } else if (argNs.equals(Namespace.of("A", "b"))) {
                return Lists.newArrayList(Namespace.of("A", "b", "gamma"));
              } else if (argNs.equals(Namespace.of("B", "c"))) {
                return Lists.newArrayList(Namespace.of("B", "c", "delta"));
              } else if (argNs.equals(Namespace.of("B", "d"))) {
                return Lists.newArrayList(Namespace.of("B", "d", "epsilon"));
              } else {
                return Lists.newArrayList();
              }
            });

    when(catalog.listTables(any(Namespace.class)))
        .then(
            invocationOnMock -> {
              Namespace argNs = invocationOnMock.getArgument(0);
              List<String> tables = Lists.newArrayList();
              if (argNs.equals(Namespace.of("A"))) {
                tables.add("tab0");
                tables.add("tab1");
              } else if (argNs.equals(Namespace.of("A", "a"))) {
                tables.add("tab2");
              } else if (argNs.equals(Namespace.of("A", "a", "alpha"))) {
                tables.add("tab3");
                tables.add("tab4");
              } else if (argNs.equals(Namespace.of("A", "a", "beta"))) {
                tables.add("tab5");
              } else if (argNs.equals(Namespace.of("A", "b", "gamma"))) {
                tables.add("tab6");
              } else if (argNs.equals(Namespace.of("B", "c", "delta"))) {
                tables.add("tab7");
              } else if (argNs.equals(Namespace.of("B", "d"))) {
                tables.add("tab8");
              } else if (argNs.equals(Namespace.of("B", "d", "epsilon"))) {
                tables.add("tab9");
              } else {
                return Lists.newArrayList();
              }
              return tables.stream()
                  .map(t -> TableIdentifier.of(argNs, t))
                  .collect(Collectors.toList());
            });

    List<TableIdentifier> tables =
        ((IcebergRestCatalogAccessor) restCatalogAccessor)
            .streamTables(catalog, allowedNamespaces, isRecursiveAllowedNamespaces)
            .collect(Collectors.toList());
    assertEquals(expectedTableNameNums.size(), tables.size());
    List<Integer> tableNumList = tableIdentifiersToTableNameNumbers(tables);
    tableNumList.removeAll(expectedTableNameNums);
    assertTrue(tableNumList.isEmpty());
  }

  @Test
  public void testNSAllowListFullRecursive() {
    testNSAllowList(
        Sets.newHashSet(Namespace.empty()), true, Lists.newArrayList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
  }

  @Test
  public void testNSAllowListPartialRecursion() {
    testNSAllowList(
        Sets.newHashSet(Namespace.of("A", "a", "alpha"), Namespace.of("B", "d")),
        true,
        Lists.newArrayList(3, 4, 8, 9));
  }

  @Test
  public void testNSAllowListNoRecursion() {
    testNSAllowList(
        Sets.newHashSet(Namespace.of("A", "a", "alpha"), Namespace.of("B", "d")),
        false,
        Lists.newArrayList(3, 4, 8));
  }

  @Test
  public void testNSAllowListHigherLevelRecursion() {
    testNSAllowList(
        Sets.newHashSet(Namespace.of("A", "a"), Namespace.of("B")),
        true,
        Lists.newArrayList(2, 3, 4, 5, 7, 8, 9));
  }

  private static List<Integer> tableIdentifiersToTableNameNumbers(
      List<TableIdentifier> tableIdentifiers) {
    return tableIdentifiers.stream()
        .map(ti -> Character.getNumericValue(ti.name().charAt(3)))
        .collect(Collectors.toList());
  }

  @Test
  public void testGetViewMetadata() {
    when(mockRestCatalog.loadView(any())).thenReturn(mockBaseView);
    List<String> dataset = List.of("source", "db", "view");
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    ViewMetadata viewMetadata = restCatalogAccessorWithViewSupport.getViewMetadata(dataset);
    assertEquals(mockViewMetadata, viewMetadata);
  }

  @Test
  public void testInvalidCatalog() {
    InMemoryCatalog mockInMemoryCatalog = mock(InMemoryCatalog.class);
    when(invalidSupplierCatalog.get()).thenReturn(mockInMemoryCatalog);
    CatalogAccessor restCatalogAccessorWithInvalidCatalog =
        new IcebergRestCatalogAccessor(invalidSupplierCatalog, mockOptionManager, null, false);
    assertThatThrownBy(
            () -> restCatalogAccessorWithInvalidCatalog.datasetExists(Arrays.asList("a", "b", "c")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RESTCatalog instance expected");
  }

  @Test
  public void testDatasetExistsWithView() {
    when(mockRestCatalog.viewExists(TableIdentifier.of("b", "c"))).thenReturn(true);
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    assertTrue(restCatalogAccessorWithViewSupport.datasetExists(Arrays.asList("a", "b", "c")));
  }

  @Test
  public void testDatasetDoesNotExistsWithView() {
    when(mockRestCatalog.viewExists(any())).thenReturn(false);
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    assertFalse(restCatalogAccessorWithViewSupport.datasetExists(Arrays.asList("a", "b", "c")));
  }

  @Test
  public void testListDatasetHandles() throws ConnectorException {
    TableIdentifier mockTableIdentifier = mock(TableIdentifier.class);
    TableIdentifier mockViewIdentifier = mock(TableIdentifier.class);
    SupportsIcebergRootPointer mockPlugin = mock(SupportsIcebergRootPointer.class);

    when(mockRestCatalog.listTables(any())).thenReturn(List.of(mockTableIdentifier));
    when(mockRestCatalog.listViews(any())).thenReturn(List.of(mockViewIdentifier));
    when(mockTableIdentifier.namespace()).thenReturn(Namespace.of("root"));
    when(mockViewIdentifier.namespace()).thenReturn(Namespace.of("root"));
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    DatasetHandleListing datasetHandleListing =
        restCatalogAccessorWithViewSupport.listDatasetHandles("root", mockPlugin);
    List<DatasetHandle> datasetHandles = ImmutableList.copyOf(datasetHandleListing.iterator());

    assertNotNull(datasetHandles);
    assertEquals(2, datasetHandles.size());
  }

  @Test
  public void testListDatasetHandlesViewsDisabled() throws ConnectorException {
    TableIdentifier mockTableIdentifier = mock(TableIdentifier.class);
    SupportsIcebergRootPointer mockPlugin = mock(SupportsIcebergRootPointer.class);

    when(mockRestCatalog.listTables(any())).thenReturn(List.of(mockTableIdentifier));
    when(mockTableIdentifier.namespace()).thenReturn(Namespace.of("root"));

    DatasetHandleListing datasetHandleListing =
        restCatalogAccessor.listDatasetHandles("root", mockPlugin);
    List<DatasetHandle> datasetHandles = ImmutableList.copyOf(datasetHandleListing.iterator());

    assertNotNull(datasetHandles);
    assertEquals(1, datasetHandles.size());
    verify(mockRestCatalog, never()).listViews(any());
  }

  @Test
  public void testListDatasetHandlesOnlyViews() throws ConnectorException {
    TableIdentifier mockViewIdentifier = mock(TableIdentifier.class);
    SupportsIcebergRootPointer mockPlugin = mock(SupportsIcebergRootPointer.class);

    when(mockRestCatalog.listTables(any())).thenReturn(List.of());
    when(mockRestCatalog.listViews(any())).thenReturn(List.of(mockViewIdentifier));
    when(mockViewIdentifier.namespace()).thenReturn(Namespace.of("root"));
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);

    DatasetHandleListing datasetHandleListing =
        restCatalogAccessorWithViewSupport.listDatasetHandles("root", mockPlugin);
    List<DatasetHandle> datasetHandles = ImmutableList.copyOf(datasetHandleListing.iterator());

    assertNotNull(datasetHandles);
    assertEquals(1, datasetHandles.size());
    assertDoesNotThrow(() -> datasetHandles.get(0).unwrap(ViewDatasetHandle.class));
  }

  @Test
  public void testListDatasetHandlesNoTablesOrViews() throws ConnectorException {
    SupportsIcebergRootPointer mockPlugin = mock(SupportsIcebergRootPointer.class);
    when(mockOptionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(mockRestCatalog.listTables(any())).thenReturn(List.of());
    when(mockRestCatalog.listViews(any())).thenReturn(List.of());

    DatasetHandleListing datasetHandleListing =
        restCatalogAccessorWithViewSupport.listDatasetHandles("root", mockPlugin);
    List<DatasetHandle> datasetHandles = ImmutableList.copyOf(datasetHandleListing.iterator());

    assertNotNull(datasetHandles);
    assertTrue(datasetHandles.isEmpty());
  }

  @Test
  public void testListDatasetHandlesReturnsEmptyOnException() throws ConnectorException {
    SupportsIcebergRootPointer mockPlugin = mock(SupportsIcebergRootPointer.class);
    assertThat(restCatalogAccessorWithViewSupport.listDatasetHandles("root", mockPlugin).iterator())
        .toIterable()
        .isEmpty();
  }

  @Test
  public void testGetViewMetadataDataset() {
    // Mock the behavior of the viewHandle
    DatasetHandle mockViewHandle = mock(DatasetHandle.class);
    IcebergCatalogViewProvider mockViewProvider = mock(IcebergCatalogViewProvider.class);
    when(mockViewHandle.unwrap(IcebergCatalogViewProvider.class)).thenReturn(mockViewProvider);

    // Call the method
    DatasetMetadata result = restCatalogAccessor.getViewMetadata(mockViewHandle);

    // Verify the result
    assertNotNull(result);
    assertEquals(mockViewProvider, result);
  }

  @Test
  public void testGetDefaultBaseLocation() {
    String defaultBaseLocationInCatalog = "s3://bucket/path";
    when(mockRestCatalog.properties())
        .thenReturn(ImmutableMap.of(DEFAULT_BASE_LOCATION, defaultBaseLocationInCatalog));
    String expectedDefaultBaseLocation = restCatalogAccessor.getDefaultBaseLocation();
    assertThat(expectedDefaultBaseLocation).isEqualTo(defaultBaseLocationInCatalog);
  }
}
