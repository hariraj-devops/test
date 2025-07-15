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
package com.dremio.exec.catalog.dataplane.test;

import static com.dremio.catalog.model.VersionContext.NOT_SPECIFIED;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DATAPLANE_PLUGIN_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_COUNT_COLUMN;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.describeUdfQueryWithAt;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.fullyQualifiedTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.selectCountAtBranchQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.selectCountDataFilesQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.selectCountQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.selectCountSnapshotQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.selectCountTablePartitionQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.showBranchesQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.showTagQuery;
import static com.dremio.plugins.NessieClient.Properties.NAMESPACE_URI_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.BaseTestQueryJunit5;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.record.RecordBatchLoader;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.plugins.dataplane.store.DataplanePlugin;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.arrow.vector.ValueVector;
import org.apache.iceberg.io.FileIO;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.IcebergView;
import org.projectnessie.model.Namespace;

/** Dataplane test common helper class */
public abstract class DataplaneTestHelper extends BaseTestQueryJunit5
    implements DataplaneTestHelperInterface {

  public void assertTableHasExpectedNumRows(List<String> tablePath, long expectedNumRows)
      throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountQuery(tablePath, DEFAULT_COUNT_COLUMN), DEFAULT_COUNT_COLUMN, expectedNumRows);
  }

  public void assertTableAtBranchHasExpectedNumRows(
      List<String> tablePath, String atBranch, long expectedNumRows) throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountAtBranchQuery(tablePath, atBranch, DEFAULT_COUNT_COLUMN),
        DEFAULT_COUNT_COLUMN,
        expectedNumRows);
  }

  public void assertTableHasExpectedNumOfSnapshots(List<String> tablePath, long expectedNumRows)
      throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountSnapshotQuery(tablePath, DEFAULT_COUNT_COLUMN),
        DEFAULT_COUNT_COLUMN,
        expectedNumRows);
  }

  public void assertTableHasExpectedNumOfDataFiles(List<String> tablePath, long expectedNumRows)
      throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountDataFilesQuery(tablePath, DEFAULT_COUNT_COLUMN),
        DEFAULT_COUNT_COLUMN,
        expectedNumRows);
  }

  public void assertTableHasExpectedNumOfTablePartitionFiles(
      List<String> tablePath, long expectedNumRows) throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountTablePartitionQuery(tablePath, DEFAULT_COUNT_COLUMN),
        DEFAULT_COUNT_COLUMN,
        expectedNumRows);
  }

  public void assertViewHasExpectedNumRows(List<String> viewPath, long expectedNumRows)
      throws Exception {
    assertSQLReturnsExpectedNumRows(
        selectCountQuery(viewPath, DEFAULT_COUNT_COLUMN), DEFAULT_COUNT_COLUMN, expectedNumRows);
  }

  public void assertSQLReturnsExpectedNumRows(
      String sqlQuery, String columnName, long expectedNumRows) throws Exception {
    testBuilder()
        .sqlQuery(sqlQuery)
        .unOrdered()
        .baselineColumns(columnName)
        .baselineValues(expectedNumRows)
        .build()
        .run();
  }

  public void assertQueryThrowsExpectedError(String query, String expectedError) {
    errorMsgTestHelper(query, expectedError);
  }

  public List<List<String>> runSqlWithResults(String sql) throws Exception {
    return getResultsFromBatches(testSqlWithResults(sql));
  }

  private List<List<String>> getResultsFromBatches(List<QueryDataBatch> batches) {
    List<List<String>> output = new ArrayList<>();
    RecordBatchLoader loader = new RecordBatchLoader(getTestAllocator());
    int last = 0;
    for (QueryDataBatch batch : batches) {
      int rows = batch.getHeader().getRowCount();
      if (batch.getData() != null) {
        loader.load(batch.getHeader().getDef(), batch.getData());
        for (int i = 0; i < rows; ++i) {
          output.add(new ArrayList<>());
          for (VectorWrapper<?> vw : loader) {
            ValueVector vv = vw.getValueVector();
            Object o = vv.getObject(i);
            output.get(last).add(o == null ? null : o.toString());
          }
          ++last;
        }
      }
      loader.clear();
      batch.release();
    }
    return output;
  }

  public String getCommitHashForBranch(String branchName) throws Exception {
    List<List<String>> rows = runSqlWithResults(showBranchesQuery());
    for (List<String> row : rows) {
      if (branchName.equals(row.get(1))) { // Column 1 is branchName
        return row.get(2); // Column 2 is commitHash
      }
    }
    return null;
  }

  public String getCommitHashForTag(String tagName) throws Exception {
    List<List<String>> rows = runSqlWithResults(showTagQuery());
    for (List<String> row : rows) {
      if (tagName.equals(row.get(1))) { // Column 1 is branchName
        return row.get(2); // Column 2 is commitHash
      }
    }
    return null;
  }

  public @Nullable String getContentId(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    String datatsetId = getVersionedDatatsetId(tableKey, tableVersionContext, base);
    VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datatsetId);
    return (versionedDatasetId == null ? null : versionedDatasetId.getContentId());
  }

  public String getVersionedDatatsetId(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    Preconditions.checkState(
        tableVersionContext.asVersionContext() != NOT_SPECIFIED,
        "tableVersionContext must specify a BRANCH, TAG or COMMIT");
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog = null;
    contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    try {
      return contextualizedCatalog
          .getTableForQuery(
              new NamespaceKey(
                  PathUtils.parseFullPath(
                      fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey))))
          .getDatasetConfig()
          .getId()
          .getId();
    } catch (Exception r) {
      return null;
    }
  }

  public String getVersionedDatatsetIdForTimeTravel(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    Preconditions.checkState(
        tableVersionContext.isTimeTravelType(),
        "tableVersionContext needs to be a timetravel type");
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog = null;
    contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return contextualizedCatalog
        .getTableSnapshotForQuery(
            CatalogEntityKey.newBuilder()
                .keyComponents(
                    PathUtils.parseFullPath(
                        fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey)))
                .tableVersionContext(tableVersionContext)
                .build())
        .getDatasetConfig()
        .getId()
        .getId();
  }

  public DremioTable getTableFromId(String id, ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    return contextualizedCatalog.getTable(id);
  }

  public String getUniqueIdForTable(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return String.valueOf(
        contextualizedCatalog
            .getTableForQuery(
                new NamespaceKey(
                    PathUtils.parseFullPath(
                        fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey))))
            .getDatasetConfig()
            .getPhysicalDataset()
            .getIcebergMetadata()
            .getTableUuid());
  }

  public String getMetadataLocationForViewKey(List<String> viewKey) throws NessieNotFoundException {
    ContentKey key = ContentKey.of(viewKey);
    return ((IcebergView)
            getNessieApi()
                .getContent()
                .key(key)
                .reference(getNessieApi().getDefaultBranch())
                .get()
                .get(key))
        .getMetadataLocation();
  }

  public String getStorageUriForFolder(List<String> folderKey, ITDataplanePluginTestSetup base)
      throws NessieNotFoundException {
    ContentKey key = ContentKey.of(folderKey);
    Namespace namespace =
        (Namespace)
            getNessieApi()
                .getContent()
                .key(key)
                .reference(getNessieApi().getDefaultBranch())
                .get()
                .get(key);

    if (namespace != null) {
      return namespace.getProperties().get(NAMESPACE_URI_LOCATION);
    }
    return null;
  }

  public FileIO getFileIO(DataplanePlugin dataplanePlugin) throws IOException {
    return dataplanePlugin.createIcebergFileIO(
        dataplanePlugin.getSystemUserFS(), null, null, null, null);
  }

  public @Nullable String getContentIdForTableAtRef(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    String datasetId = getVersionedDatasetIdForTableAtRef(tableKey, tableVersionContext, base);
    VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datasetId);
    return (versionedDatasetId == null ? null : versionedDatasetId.getContentId());
  }

  public String getVersionedDatasetIdForTableAtRef(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return contextualizedCatalog
        .getTableSnapshotForQuery(
            CatalogEntityKey.newBuilder()
                .keyComponents(
                    PathUtils.parseFullPath(
                        fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey)))
                .tableVersionContext(tableVersionContext)
                .build())
        .getDatasetConfig()
        .getId()
        .getId();
  }

  public String getUniqueIdForView(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return String.valueOf(
        contextualizedCatalog
            .getTableForQuery(
                new NamespaceKey(
                    PathUtils.parseFullPath(
                        fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey))))
            .getDatasetConfig()
            .getTag());
  }

  public String getUniqueIdForTableAtRef(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return String.valueOf(
        contextualizedCatalog
            .getTableSnapshotForQuery(
                CatalogEntityKey.newBuilder()
                    .keyComponents(
                        PathUtils.parseFullPath(
                            fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey)))
                    .tableVersionContext(tableVersionContext)
                    .build())
            .getDatasetConfig()
            .getPhysicalDataset()
            .getIcebergMetadata()
            .getTableUuid());
  }

  public long getMtimeForTable(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return contextualizedCatalog
        .getTableForQuery(
            new NamespaceKey(
                PathUtils.parseFullPath(fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey))))
        .getDatasetConfig()
        .getLastModified();
  }

  public long getMtimeForTableAtRef(
      List<String> tableKey,
      TableVersionContext tableVersionContext,
      ITDataplanePluginTestSetup base) {
    // Get a fresh instance of the Catalog and clear cache( i.e source, version mapping)
    Catalog contextualizedCatalog =
        base.getContextualizedCatalog(
            DATAPLANE_PLUGIN_NAME, tableVersionContext.asVersionContext());
    return contextualizedCatalog
        .getTableSnapshotForQuery(
            CatalogEntityKey.newBuilder()
                .keyComponents(
                    PathUtils.parseFullPath(
                        fullyQualifiedTableName(DATAPLANE_PLUGIN_NAME, tableKey)))
                .tableVersionContext(tableVersionContext)
                .build())
        .getDatasetConfig()
        .getLastModified();
  }

  public void assertCommitLogTail(String... expectedCommitMessages) throws Exception {
    assertCommitLogTail(VersionContext.ofBranch(DEFAULT_BRANCH_NAME), expectedCommitMessages);
  }

  public void assertCommitLogTail(VersionContext versionContext, String... expectedCommitMessages)
      throws Exception {
    // TODO: NPE without "AT xyz" part
    // TODO: does not respect previous "USE BRANCH xyz" cmd
    StringBuilder query = new StringBuilder("SHOW LOGS");
    if (versionContext != null) {
      query.append(" AT ");
      switch (versionContext.getType()) {
        case COMMIT:
          query.append("COMMIT ");
          break;
        case TAG:
          query.append("TAG ");
          break;
        case REF:
          query.append("REF ");
          break;
        case BRANCH:
          query.append("BRANCH ");
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported version type: " + versionContext.getType());
      }
      query.append(versionContext.getValue());
    }
    query.append(" IN ");
    query.append(DATAPLANE_PLUGIN_NAME);
    List<List<String>> commitLogRows = runSqlWithResults(query.toString());

    List<String> commitLogMessages =
        commitLogRows.stream()
            // hash, author, time, msg
            .map(row -> row.get(3))
            .collect(Collectors.toList());
    Collections.reverse(commitLogMessages);
    assertThat(commitLogMessages).endsWith(expectedCommitMessages);
  }

  protected void assertUdfContent(
      final List<String> functionKey,
      final String branchName,
      String functionType,
      final String function)
      throws Exception {
    assertUdfContentWithSql(
        describeUdfQueryWithAt(functionKey, branchName), functionType, function);
  }

  protected void assertUdfContentWithSql(String sql, String functionType, final String function)
      throws Exception {
    List<String> result = runSqlWithResults(sql).get(0);
    assertThat(result).hasSize(7);
    assertThat(result.get(2)).isEqualTo(functionType);
    assertThat(result.get(3)).contains(function);
  }
}
