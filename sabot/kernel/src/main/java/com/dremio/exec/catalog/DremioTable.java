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
package com.dremio.exec.catalog;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.TableMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.util.List;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ExtensibleTable;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

/**
 * A TranslatableTable (View or Table) that also knows its canonical name and exposes schema
 * information.
 */
public interface DremioTable extends DremioTranslatableTable, ExtensibleTable {

  /**
   * Provide the version of the dataset, if available. Otherwise, return -1.
   *
   * @return
   */
  String getVersion();

  /**
   * The BatchSchema for the dataset. For the exception of old dot file views, this returns correct
   * schema according to sampling/metadata of the underlying system.
   *
   * @return BatchSchema for the dataset.
   */
  BatchSchema getSchema();

  DatasetConfig getDatasetConfig();

  @Override
  default boolean isRolledUp(String column) {
    return false;
  }

  @Override
  default boolean rolledUpColumnValidInsideAgg(
      String column, SqlCall call, SqlNode parent, CalciteConnectionConfig config) {
    return true;
  }

  default TableMetadata getDataset() {
    throw new UnsupportedOperationException();
  }

  /**
   * Override `extend` and `getExtendedColumnOffset` if you want the implementing class to be able
   * to extend its schema.
   */
  String UNSUPPORTED_EXTENDED_TABLE = "The extended table of type '%s' is unsupported.";

  @Override
  default Table extend(List<RelDataTypeField> fields) {
    throw new UnsupportedOperationException();
  }

  @Override
  default int getExtendedColumnOffset() {
    throw new UnsupportedOperationException();
  }

  /**
   * For DML (i.e., DELETE, MERGE, UPDATE (not INSERT)) operations, the table gets extended with
   * system columns (defined in ColumnUtils). For those cases, the table needs to get extended via
   * the `EXTEND` sql statement. This will return the sql required to extend the table with the
   * fields.
   */
  default String getExtendTableSql() {
    throw new UnsupportedExtendTableException();
  }

  default TableVersionContext getVersionContext() {
    return getDataset().getVersionContext();
  }

  default boolean hasAtSpecifier() {
    return false;
  }

  default DatasetMetadataState getDatasetMetadataState() {
    return DatasetMetadataState.builder().setIsComplete(true).setIsExpired(false).build();
  }
}
