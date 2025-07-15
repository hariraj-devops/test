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
package com.dremio.exec.planner.sql.parser;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.calcite.logical.VacuumCatalogCrel;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.ops.DremioCatalogReader;
import com.dremio.exec.planner.VacuumOutputSchema;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.planner.sql.handlers.query.SqlToPlanHandler;
import com.dremio.exec.planner.sql.handlers.query.SupportsSqlToRelConversion;
import com.dremio.exec.planner.sql.handlers.query.VacuumCatalogHandler;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;

public class SqlVacuumCatalog extends SqlVacuum
    implements SqlToPlanHandler.Creator, SupportsSqlToRelConversion {

  public static final SqlSpecialOperator VACUUM_CATALOG_OPERATOR =
      new SqlSpecialOperator("VACUUM_CATALOG_OPERATOR", SqlKind.OTHER) {
        @Override
        public SqlCall createCall(
            SqlLiteral functionQualifier, SqlParserPos pos, SqlNode... operands) {
          Preconditions.checkArgument(
              operands.length == 2, "SqlVacuumCatalog.createCall() " + "has 1 operand!");
          return new SqlVacuumCatalog(pos, (SqlIdentifier) operands[0], (SqlNodeList) operands[1]);
        }

        @Override
        public RelDataType deriveType(
            SqlValidator validator, SqlValidatorScope scope, SqlCall call) {
          final RelDataTypeFactory typeFactory = validator.getTypeFactory();
          return VacuumOutputSchema.getCatalogOutputRelDataType(typeFactory);
        }
      };

  private final SqlIdentifier catalogSource;
  private final SqlNodeList excludeTableList;
  private SqlSelect sourceSelect;

  /** Creates a SqlVacuum. */
  public SqlVacuumCatalog(
      SqlParserPos pos, SqlIdentifier catalogSource, SqlNodeList excludeTableList) {
    super(
        pos,
        SqlLiteral.createBoolean(true, pos),
        SqlLiteral.createBoolean(true, pos),
        SqlNodeList.EMPTY,
        SqlNodeList.EMPTY);
    this.catalogSource = catalogSource;
    this.excludeTableList = excludeTableList;
  }

  @Override
  public SqlOperator getOperator() {
    return VACUUM_CATALOG_OPERATOR;
  }

  public SqlIdentifier getCatalogSource() {
    return catalogSource;
  }

  public SqlNodeList getExcludeTableList() {
    return excludeTableList;
  }

  public void setSourceSelect(SqlSelect select) {
    this.sourceSelect = select;
  }

  @Override
  public List<SqlNode> getOperandList() {
    return ImmutableList.of(catalogSource, excludeTableList);
  }

  @Override
  public NamespaceKey getPath() {
    return new NamespaceKey(catalogSource.getSimple());
  }

  @Override
  protected void populateOptions(SqlNodeList optionsList, SqlNodeList optionsValueList) {
    // Nothing to do
  }

  @Override
  public Optional<Consumer<SqlNode>> getOptionsConsumer(String optionName) {
    return Optional.empty();
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("VACUUM");
    writer.keyword("CATALOG");
    catalogSource.unparse(writer, leftPrec, rightPrec);

    if (excludeTableList.size() > 0) {
      writer.keyword("EXCLUDE");
      SqlHandlerUtil.unparseSqlNodeList(writer, leftPrec, rightPrec, excludeTableList);
    }
  }

  @Override
  public SqlToPlanHandler toPlanHandler() {
    return new VacuumCatalogHandler();
  }

  @Override
  public void validate(SqlValidator validator, SqlValidatorScope scope) {
    if (sourceSelect != null) {
      validator.validate(sourceSelect);
    }
  }

  @Override
  public RelNode convertToRel(
      RelOptCluster cluster,
      Prepare.CatalogReader catalogReader,
      RelNode inputRel,
      RelOptTable.ToRelContext relContext) {
    List<String> excludedContentIDs = new ArrayList<>();
    for (SqlNode tableNode : excludeTableList) {
      final CatalogEntityKey catalogEntityKey =
          CatalogEntityKey.newBuilder()
              .keyComponents(DmlUtils.getPath(tableNode).getPathComponents())
              .tableVersionContext(getTableVersionContext(tableNode))
              .build();
      DremioCatalogReader dremioCatalogReader = catalogReader.unwrap(DremioCatalogReader.class);
      DremioTable dremioTable = dremioCatalogReader.getTable(catalogEntityKey);
      excludedContentIDs.add(
          VersionedDatasetId.tryParse(dremioTable.getDatasetConfig().getId().getId())
              .getContentId());
    }
    return new VacuumCatalogCrel(
        cluster,
        cluster.traitSetOf(Convention.NONE),
        null,
        null,
        getCatalogSource().getSimple(),
        null,
        null,
        null,
        null,
        Collections.unmodifiableList(excludedContentIDs));
  }

  private TableVersionContext getTableVersionContext(SqlNode table) {
    if (table instanceof SqlVersionedTableCollectionCall) {
      TableVersionSpec tableVersionSpec =
          ((SqlVersionedTableCollectionCall) table)
              .getVersionedTableMacroCall()
              .getSqlTableVersionSpec()
              .getTableVersionSpec();
      if (tableVersionSpec != null) {
        return tableVersionSpec.getTableVersionContext();
      }
    }
    return TableVersionContext.NOT_SPECIFIED;
  }
}
