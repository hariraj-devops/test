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
package com.dremio.exec.tablefunctions;

import com.dremio.exec.store.MFunctionCatalogMetadata;
import com.dremio.exec.store.mfunctions.MFunctionQueryScanCrel;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;

/**
 * Translatable table impl for Iceberg metadata functions as table_history, table_snapshot,
 * table_manifests
 */
public final class IcebergMFunctionTranslatableTableImpl extends MFunctionTranslatableTable {

  private final String metadataLocation;

  public IcebergMFunctionTranslatableTableImpl(
      MFunctionCatalogMetadata catalogMetadata, String user, String metadataLocation) {
    super(catalogMetadata, user, true);
    this.metadataLocation = metadataLocation;
  }

  @Override
  public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
    return new MFunctionQueryScanCrel(
        context.getCluster(),
        context.getCluster().traitSetOf(Convention.NONE),
        getRowType(context.getCluster().getTypeFactory()),
        catalogMetadata,
        user,
        metadataLocation);
  }
}
