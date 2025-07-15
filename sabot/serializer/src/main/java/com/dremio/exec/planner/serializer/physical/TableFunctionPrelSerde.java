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
package com.dremio.exec.planner.serializer.physical;

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.serializer.catalog.CatalogFromProto;
import com.dremio.exec.planner.serializer.catalog.CatalogToProto;
import com.dremio.exec.store.RelOptNamespaceTable;
import com.dremio.exec.store.TableMetadata;
import com.dremio.plan.serialization.PPhyscialRels.PTableFunctionPrel;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.plan.RelOptTable;

public class TableFunctionPrelSerde {

  public static PTableFunctionPrel toProto(TableFunctionPrel pojo, PrelToProto prelToProto) {
    CatalogToProto catalogToProto = prelToProto.getCatalogToProto();
    return PTableFunctionPrel.newBuilder()
        .setTraitSet(prelToProto.toProto(pojo.getTraitSet()))
        .setInput(prelToProto.toProto((Prel) pojo.getInput()))
        .setTableMetadata(catalogToProto.toProto(pojo.getTableMetadata()))
        .setFunctionConfigJson(catalogToProto.toBson(pojo.getTableFunctionConfig()))
        .addAllFieldTypes(prelToProto.toProto(pojo.getRowType().getFieldList()))
        .setSurvivingRecords(pojo.getSurvivingRecords())
        .addAllRuntimeFilterInfo(
            pojo.getRuntimeFilters().stream()
                .map(RuntimeFilterSerde::toProto)
                .collect(ImmutableList.toImmutableList()))
        .setUser(pojo.getUser())
        .build();
  }

  public static TableFunctionPrel fromProto(PTableFunctionPrel proto, ProtoToPrel protoToPrel) {
    CatalogFromProto catalogToProto = protoToPrel.getCatalogFromProto();
    TableMetadata tableMetadata = catalogToProto.fromProto(proto.getTableMetadata());
    RelOptTable table = new RelOptNamespaceTable(tableMetadata, protoToPrel.getCluster());
    return new TableFunctionPrel(
        protoToPrel.getCluster(),
        protoToPrel.fromProto(proto.getTraitSet()),
        table,
        protoToPrel.fromProto(proto.getInput()),
        catalogToProto.fromProto(proto.getTableMetadata()),
        catalogToProto.tableFunctionConfigFromJson(proto.getFunctionConfigJson()),
        protoToPrel.fromProto(proto.getFieldTypesList()),
        null,
        proto.getSurvivingRecords(),
        proto.getRuntimeFilterInfoList().stream()
            .map(RuntimeFilterSerde::fromProto)
            .collect(ImmutableList.toImmutableList()),
        proto.getUser());
  }
}
