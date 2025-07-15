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
package com.dremio.exec.store.iceberg;

import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.TableFunctionUtil;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

public class ManifestFileDuplicateRemovePrel extends TableFunctionPrel {
  public ManifestFileDuplicateRemovePrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      BatchSchema schema,
      Long survivingRowCount) {
    this(
        cluster,
        traitSet,
        child,
        TableFunctionUtil.getManifestFileDuplicateRemoveTableFunctionConfig(schema),
        CalciteArrowHelper.wrap(schema).toCalciteRecordType(cluster.getTypeFactory(), true),
        survivingRowCount);
  }

  private ManifestFileDuplicateRemovePrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      TableFunctionConfig functionConfig,
      RelDataType rowType,
      Long survivingRowCount) {
    super(cluster, traitSet, null, child, null, functionConfig, rowType, survivingRowCount);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new ManifestFileDuplicateRemovePrel(
        getCluster(),
        getTraitSet(),
        sole(inputs),
        getTableFunctionConfig(),
        getRowType(),
        getSurvivingRecords());
  }

  @Override
  protected double defaultEstimateRowCount(
      TableFunctionConfig functionConfig, RelMetadataQuery mq) {

    return (double) getSurvivingRecords();
  }
}
