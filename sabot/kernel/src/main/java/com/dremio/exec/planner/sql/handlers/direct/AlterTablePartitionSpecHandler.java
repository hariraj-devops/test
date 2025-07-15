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
package com.dremio.exec.planner.sql.handlers.direct;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.PartitionSpecAlterOption;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.PartitionTransform;
import com.dremio.exec.planner.sql.SqlValidatorImpl;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.planner.sql.handlers.query.DataAdditionCmdHandler;
import com.dremio.exec.planner.sql.parser.SqlAlterTablePartitionColumns;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlNode;

public class AlterTablePartitionSpecHandler extends SimpleDirectHandler {
  private final Catalog catalog;
  private final SqlHandlerConfig config;

  public AlterTablePartitionSpecHandler(Catalog catalog, SqlHandlerConfig config) {
    this.catalog = catalog;
    this.config = config;
  }

  protected void handleCommandOptionConflicts(DremioTable table, NamespaceKey path) {}

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    SqlAlterTablePartitionColumns sqlPartitionSpecChanges =
        SqlNodeUtil.unwrap(sqlNode, SqlAlterTablePartitionColumns.class);
    QueryContext context = Preconditions.checkNotNull(config.getContext());
    OptionManager optionManager = Preconditions.checkNotNull(context.getOptions());
    SqlValidatorImpl.checkForFeatureSpecificSyntax(sqlNode, optionManager);
    VersionContext statementSourceVersion =
        sqlPartitionSpecChanges
            .getSqlTableVersionSpec()
            .getTableVersionSpec()
            .getTableVersionContext()
            .asVersionContext();

    NamespaceKey path =
        CatalogUtil.getResolvePathForTableManagement(catalog, sqlPartitionSpecChanges.getTable());

    DremioTable table = catalog.getTableNoResolve(path);
    SimpleCommandResult result =
        SqlHandlerUtil.validateSupportForDDLOperations(catalog, config, path, table);

    if (!result.ok) {
      return Collections.singletonList(result);
    }

    handleCommandOptionConflicts(table, path);

    boolean isInternalIcebergTableOrJsonTableOrMongoTable =
        CatalogUtil.isFSInternalIcebergTableOrJsonTableOrMongo(
            catalog, path, table.getDatasetConfig());
    if (isInternalIcebergTableOrJsonTableOrMongoTable) {
      throw UserException.unsupportedError()
          .message(
              "Using \'ALTER TABLE\' command to change partition specification is supported only for ICEBERG table format type")
          .buildSilently();
    }

    PartitionTransform partitionTransform = sqlPartitionSpecChanges.getPartitionTransform();
    SqlAlterTablePartitionColumns.Mode mode = sqlPartitionSpecChanges.getMode();
    PartitionSpecAlterOption partitionSpecAlterOption =
        new PartitionSpecAlterOption(partitionTransform, mode);
    final String sourceName = path.getRoot();
    final VersionContext sessionVersion =
        config.getContext().getSession().getSessionVersionForSource(sourceName);
    VersionContext sourceVersion = statementSourceVersion.orElse(sessionVersion);
    ResolvedVersionContext resolvedVersionContext =
        CatalogUtil.resolveVersionContext(catalog, sourceName, sourceVersion);
    CatalogUtil.validateResolvedVersionIsBranch(resolvedVersionContext);
    TableMutationOptions tableMutationOptions =
        TableMutationOptions.newBuilder().setResolvedVersionContext(resolvedVersionContext).build();
    catalog.alterTable(
        path, table.getDatasetConfig(), partitionSpecAlterOption, tableMutationOptions);

    DataAdditionCmdHandler.refreshDataset(catalog, path, false);
    String message = null;
    switch (mode) {
      case ADD:
        message = String.format("Partition field [%s] added", partitionTransform.toString());
        break;
      case DROP:
        message = String.format("Partition field [%s] dropped", partitionTransform.toString());
        break;
    }
    return Collections.singletonList(SimpleCommandResult.successful(message));
  }
}
