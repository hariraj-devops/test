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
package com.dremio.exec.store.mfunctions;

import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.store.MFunctionCatalogMetadata;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.type.RelDataType;

/** Drel implementation of metadata functions scan. */
final class MFunctionQueryScanDrel extends MFunctionQueryRelBase implements Rel {

  public MFunctionQueryScanDrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      MFunctionCatalogMetadata tableMetadata,
      String user,
      String metadataLocation) {
    super(cluster, traitSet, rowType, tableMetadata, user, metadataLocation);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new MFunctionQueryScanDrel(
        getCluster(), getTraitSet(), getRowType(), tableMetadata, user, metadataLocation);
  }

  @Override
  public final RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("source", tableMetadata.getStoragePluginId().getName())
        .item("mFunction", tableMetadata.getMetadataFunctionName().getName())
        .item("table", tableMetadata.getNamespaceKey().getPathComponents());
  }
}
