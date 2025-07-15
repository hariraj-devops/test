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

import static com.dremio.exec.store.iceberg.IcebergSerDe.serializedSchemaAsJson;
import static com.dremio.exec.store.iceberg.IcebergUtils.DEFAULT_TABLE_PROPERTIES;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.ColumnCountTooLargeException;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.base.IcebergWriterOptions;
import com.dremio.exec.physical.base.ImmutableIcebergWriterOptions;
import com.dremio.exec.physical.base.ImmutableTableFormatWriterOptions;
import com.dremio.exec.physical.base.TableFormatWriterOptions;
import com.dremio.exec.physical.base.TableFormatWriterOptions.TableFormatOperation;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.SqlValidatorImpl;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.planner.sql.handlers.query.DataAdditionCmdHandler;
import com.dremio.exec.planner.sql.parser.DremioSqlColumnDeclaration;
import com.dremio.exec.planner.sql.parser.ReferenceTypeUtils;
import com.dremio.exec.planner.sql.parser.SqlCreateEmptyTable;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.IcebergSerDe;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.protostuff.ByteString;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.SqlNode;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.SortOrder;

public class CreateEmptyTableHandler extends SimpleDirectHandlerWithValidator {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CreateEmptyTableHandler.class);

  private final Catalog catalog;
  private final SqlHandlerConfig config;
  private final OptionManager optionManager;
  private final UserSession userSession;
  private final boolean ifNotExists;
  private Map<String, String> tableProperties = new HashMap<>(DEFAULT_TABLE_PROPERTIES);

  public CreateEmptyTableHandler(
      Catalog catalog, SqlHandlerConfig config, UserSession userSession, boolean ifNotExists) {
    this.catalog = Preconditions.checkNotNull(catalog);
    this.config = Preconditions.checkNotNull(config);
    QueryContext context = Preconditions.checkNotNull(config.getContext());
    this.optionManager = Preconditions.checkNotNull(context.getOptions());
    this.userSession = Preconditions.checkNotNull(userSession);
    this.ifNotExists = ifNotExists;
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    SqlCreateEmptyTable sqlCreateEmptyTable =
        SqlNodeUtil.unwrap(sqlNode, SqlCreateEmptyTable.class);

    NamespaceKey tableKey = catalog.resolveSingle(sqlCreateEmptyTable.getPath());

    final String sourceName = tableKey.getRoot();
    VersionContext statementSourceVersion =
        ReferenceTypeUtils.map(
            sqlCreateEmptyTable.getRefType(), sqlCreateEmptyTable.getRefValue(), null);
    final VersionContext sessionVersion = userSession.getSessionVersionForSource(sourceName);
    VersionContext sourceVersion = statementSourceVersion.orElse(sessionVersion);

    // TODO: DX-94683: Should use CAC::canPerformOperation
    catalog.validatePrivilege(tableKey, SqlGrant.Privilege.CREATE_TABLE);

    CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(tableKey.getPathComponents())
            .tableVersionContext(TableVersionContext.of(sourceVersion))
            .build();

    List<SimpleCommandResult> result = createEmptyTable(catalogEntityKey, sql, sqlCreateEmptyTable);
    return handlePolicy(result, tableKey, sql, sqlCreateEmptyTable);
  }

  protected List<SimpleCommandResult> handlePolicy(
      List<SimpleCommandResult> createTableResult,
      NamespaceKey key,
      String sql,
      SqlCreateEmptyTable sqlCreateEmptyTable)
      throws Exception {
    return createTableResult;
  }

  protected SortOrder getSortOrder(
      PartitionSpec partitionSpec,
      BatchSchema batchSchema,
      Map<String, String> tableProperties,
      SqlHandlerConfig config,
      String sql,
      SqlCreateEmptyTable sqlCreateEmptyTable)
      throws Exception {
    return IcebergUtils.getIcebergSortOrder(
        batchSchema,
        sqlCreateEmptyTable.getSortColumns(),
        partitionSpec.schema(),
        config.getContext().getOptions());
  }

  protected SqlHandlerConfig getConfig() {
    return config;
  }

  protected Catalog getCatalog() {
    return catalog;
  }

  protected boolean isPolicyAllowed() {
    return false;
  }

  protected boolean isAutoClusteringAllowed() {
    return false;
  }

  @VisibleForTesting
  public void callCatalogCreateEmptyTableWithCleanup(
      CatalogEntityKey key, BatchSchema batchSchema, WriterOptions options) {
    ResolvedVersionContext resolvedVersionContext =
        getResolvedVersionContextIfVersioned(key, catalog);
    try {
      options.setResolvedVersionContext(resolvedVersionContext);
      catalog.createEmptyTable(key.toNamespaceKey(), batchSchema, options);
    } catch (Exception ex) {
      cleanUpFromCatalogAndMetaStore(key.toNamespaceKey(), resolvedVersionContext);
      throw UserException.validationError(ex).message(ex.getMessage()).buildSilently();
    }
  }

  protected List<SimpleCommandResult> createEmptyTable(
      CatalogEntityKey key, String sql, SqlCreateEmptyTable sqlCreateEmptyTable) throws Exception {
    validateInSource(key, catalog);
    validateCreateTableOptions(
        sqlCreateEmptyTable, sql, key, getResolvedVersionContextIfVersioned(key, catalog));
    if (!(sqlCreateEmptyTable.getTablePropertyNameList() == null
        || sqlCreateEmptyTable.getTablePropertyNameList().isEmpty())) {
      IcebergUtils.validateTablePropertiesRequest(optionManager);
      tableProperties =
          IcebergUtils.convertTableProperties(
              sqlCreateEmptyTable.getTablePropertyNameList(),
              sqlCreateEmptyTable.getTablePropertyValueList(),
              false);
    }
    List<DremioSqlColumnDeclaration> columnDeclarations =
        SqlHandlerUtil.columnDeclarationsFromSqlNodes(sqlCreateEmptyTable.getFieldList(), sql);
    final long ringCount = optionManager.getOption(PlannerSettings.RING_COUNT);
    if (!isPolicyAllowed()
        && columnDeclarations.stream().anyMatch(col -> col.getPolicy() != null)) {
      throw UserException.unsupportedError()
          .message("This Dremio edition doesn't support SET COLUMN MASKING")
          .buildSilently();
    }
    long maxColumnCount = optionManager.getOption(CatalogOptions.METADATA_LEAF_COLUMN_MAX);
    SqlHandlerUtil.checkForDuplicateColumns(columnDeclarations, BatchSchema.of(), sql);
    if (columnDeclarations.size() > maxColumnCount) {
      throw new ColumnCountTooLargeException((int) maxColumnCount);
    }

    BatchSchema batchSchema = SqlHandlerUtil.batchSchemaFromSqlSchemaSpec(columnDeclarations, sql);
    PartitionSpec partitionSpec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(
            batchSchema, sqlCreateEmptyTable.getPartitionTransforms(null), null);
    if (!isAutoClusteringAllowed()
        && sqlCreateEmptyTable.getClusterKeys() != null
        && !sqlCreateEmptyTable.getClusterKeys().isEmpty()) {
      throw UserException.unsupportedError()
          .message("This Dremio edition doesn't support CLUSTER BY option")
          .buildSilently();
    }
    SortOrder sortOrder =
        getSortOrder(partitionSpec, batchSchema, tableProperties, config, sql, sqlCreateEmptyTable);
    IcebergTableProps icebergTableProps =
        new IcebergTableProps(
            ByteString.copyFrom(IcebergSerDe.serializePartitionSpec(partitionSpec)),
            serializedSchemaAsJson(
                SchemaConverter.getBuilder().build().toIcebergSchema(batchSchema)),
            IcebergSerDe.serializeSortOrderAsJson(sortOrder),
            tableProperties);
    IcebergWriterOptions icebergWriterOptions =
        new ImmutableIcebergWriterOptions.Builder().setIcebergTableProps(icebergTableProps).build();
    TableFormatWriterOptions tableFormatOptions =
        new ImmutableTableFormatWriterOptions.Builder()
            .setIcebergSpecificOptions(icebergWriterOptions)
            .setOperation(TableFormatOperation.CREATE)
            .build();

    final WriterOptions options =
        new WriterOptions(
            (int) ringCount,
            sqlCreateEmptyTable.getPartitionColumns(null),
            sqlCreateEmptyTable.getSortColumns(),
            sqlCreateEmptyTable.getDistributionColumns(),
            sqlCreateEmptyTable.getPartitionDistributionStrategy(config, null, null),
            sqlCreateEmptyTable.getLocation(),
            sqlCreateEmptyTable.isSingleWriter(),
            Long.MAX_VALUE,
            tableFormatOptions,
            null,
            tableProperties,
            WriterOptions.DEFAULT.isMergeOnReadRowSplitterMode());

    DremioTable table = null;
    try {
      table = catalog.getTableNoResolve(key);
    } catch (UserException ex) {
      if (ex.getErrorType() == UserBitShared.DremioPBError.ErrorType.PERMISSION) {
        throw UserException.permissionError(ex.getCause())
            .message(
                "You do not have [CREATE] privilege on [%s]; the table may already exist.",
                key.toNamespaceKey())
            .build(logger);
      }
      throw ex;
    }

    if (table != null) {
      if (ifNotExists) {
        return Collections.singletonList(
            new SimpleCommandResult(
                true, String.format("Table [%s] already exists.", key.toNamespaceKey())));
      } else {
        throw UserException.validationError()
            .message("A table or view with given name [%s] already exists.", key.toNamespaceKey())
            .buildSilently();
      }
    }

    callCatalogCreateEmptyTableWithCleanup(key, batchSchema, options);

    // do a refresh on the dataset to populate the kvstore.
    // Will the orphanage cleanup logic remove files and folders if query fails during
    // refreshDataset() function?
    DataAdditionCmdHandler.refreshDataset(catalog, key.toNamespaceKey(), true);

    return Collections.singletonList(SimpleCommandResult.successful("Table created"));
  }

  private static void validateInSource(CatalogEntityKey catalogEntityKey, Catalog catalog)
      throws NamespaceException {
    final String name = catalogEntityKey.getRootEntity();
    try {
      final NameSpaceContainer entity = catalog.getEntityByPath(new NamespaceKey(name));
      if (entity != null && entity.getType().equals(NameSpaceContainer.Type.SPACE)) {
        throw UserException.validationError()
            .message("You cannot create a table in a space (name: %s).", name)
            .build(logger);
      }
    } catch (NamespaceNotFoundException ex) {
      throw UserException.validationError(ex)
          .message("Tried to access non-existent source [%s].", name)
          .build(logger);
    }
  }

  @VisibleForTesting
  public void validateCreateTableOptions(
      SqlCreateEmptyTable sqlCreateEmptyTable,
      String sql,
      CatalogEntityKey catalogEntityKey,
      ResolvedVersionContext resolvedVersionContext) {
    SqlValidatorImpl.checkForFeatureSpecificSyntax(sqlCreateEmptyTable, optionManager);

    // path is not valid
    if (!DataAdditionCmdHandler.validatePath(this.catalog, catalogEntityKey.toNamespaceKey())) {
      throw UserException.unsupportedError()
          .message(
              String.format(
                  "Invalid path. Given path, [%s] is not valid.",
                  catalogEntityKey.toNamespaceKey()))
          .buildSilently();
    }

    // path is valid but source is not valid
    if (!IcebergUtils.validatePluginSupportForIceberg(
        this.catalog, catalogEntityKey.toNamespaceKey())) {
      throw UserException.unsupportedError()
          .message(
              String.format(
                  "Source [%s] does not support CREATE TABLE.", catalogEntityKey.getRootEntity()))
          .buildSilently();
    }

    // row access policy is not allowed
    if (!isPolicyAllowed() && sqlCreateEmptyTable.getPolicy() != null) {
      throw UserException.unsupportedError()
          .message("This Dremio edition doesn't support ADD ROW ACCESS POLICY")
          .buildSilently();
    }

    // auto-clustering is not allowed
    if (!isAutoClusteringAllowed() && !sqlCreateEmptyTable.getClusterKeys().isEmpty()) {
      throw UserException.unsupportedError()
          .message("This Dremio edition doesn't support CLUSTER BY option")
          .buildSilently();
    }

    IcebergUtils.validateIcebergLocalSortIfDeclared(sql, config.getContext().getOptions());

    if (!(sqlCreateEmptyTable.getTablePropertyNameList() == null
        || sqlCreateEmptyTable.getTablePropertyNameList().isEmpty())) {
      IcebergUtils.validateTablePropertiesRequest(optionManager);
      tableProperties =
          IcebergUtils.convertTableProperties(
              sqlCreateEmptyTable.getTablePropertyNameList(),
              sqlCreateEmptyTable.getTablePropertyValueList(),
              false);
    }

    // validate if source supports providing table location
    DataAdditionCmdHandler.validateCreateTableLocation(
        this.catalog, catalogEntityKey.toNamespaceKey(), sqlCreateEmptyTable);

    CatalogUtil.validateResolvedVersionIsBranch(resolvedVersionContext);
  }

  public static CreateEmptyTableHandler create(
      Catalog catalog, SqlHandlerConfig config, UserSession userSession, boolean ifNotExists) {
    try {
      final Class<?> cl =
          Class.forName("com.dremio.exec.planner.sql.handlers.CreateEmptyTableHandler");
      final Constructor<?> ctor =
          cl.getConstructor(
              Catalog.class, SqlHandlerConfig.class, UserSession.class, boolean.class);
      return (CreateEmptyTableHandler) ctor.newInstance(catalog, config, userSession, ifNotExists);
    } catch (ClassNotFoundException e) {
      return new CreateEmptyTableHandler(catalog, config, userSession, ifNotExists);
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e2) {
      throw Throwables.propagate(e2);
    }
  }

  private void cleanUpFromCatalogAndMetaStore(
      NamespaceKey key, ResolvedVersionContext resolvedVersionContext) {
    TableMutationOptions tableMutationOptions =
        TableMutationOptions.newBuilder().setResolvedVersionContext(resolvedVersionContext).build();
    try {
      if (catalog.getSource(key.getRoot()) instanceof FileSystemPlugin) {
        catalog.forgetTable(key);
      } else {
        catalog.dropTable(key, tableMutationOptions);
      }
    } catch (Exception i) {
      logger.warn("Failure during removing table from catalog and metastore. " + i.getMessage());
    }
  }

  private ResolvedVersionContext getResolvedVersionContextIfVersioned(
      CatalogEntityKey key, Catalog catalog) {
    if (CatalogUtil.requestedPluginSupportsVersionedTables(key.getRootEntity(), catalog)) {
      return CatalogUtil.resolveVersionContext(
          catalog, key.getRootEntity(), key.getTableVersionContext().asVersionContext());
    }
    return null;
  }
}
