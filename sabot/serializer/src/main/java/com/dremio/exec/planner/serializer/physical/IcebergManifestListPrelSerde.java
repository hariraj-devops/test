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
import com.dremio.exec.planner.serializer.catalog.CatalogFromProto;
import com.dremio.exec.planner.serializer.catalog.CatalogToProto;
import com.dremio.exec.store.iceberg.IcebergManifestListPrel;
import com.dremio.exec.store.iceberg.ManifestContentType;
import com.dremio.plan.serialization.PPhyscialRels.PIcebergManifestListPrel;
import com.dremio.plan.serialization.PPhyscialRels.PIcebergManifestListPrel.PManifestContentType;
import com.google.common.collect.ImmutableList;

public class IcebergManifestListPrelSerde {
  public static PIcebergManifestListPrel toProto(
      IcebergManifestListPrel prel, PrelToProto prelSerializer) {
    CatalogToProto catalogToProto = prelSerializer.getCatalogToProto();
    return PIcebergManifestListPrel.newBuilder()
        .setTraitSet(prelSerializer.toProto(prel.getTraitSet()))
        .setTableMetadata(catalogToProto.toProto(prel.getTableMetadata()))
        .setBatchSchemaBytes(catalogToProto.toProto(prel.getSchema()))
        .addAllProjectedColumns(
            prel.getProjectedColumns().stream()
                .map(catalogToProto::toBson)
                .collect(ImmutableList.toImmutableList()))
        .addAllFieldTypes(prelSerializer.toProto(prel.getRowType().getFieldList()))
        .setExpression(catalogToProto.toBson(prel.getIcebergExpression()))
        .setManifestContentType(toProto(prel.getManifestContent()))
        .build();
  }

  public static Prel fromProto(
      PIcebergManifestListPrel icebergManifestList, PrelSerializer prelSerializer) {
    CatalogFromProto catalogFromProto = prelSerializer.getCatalogFromProto();
    return new IcebergManifestListPrel(
        prelSerializer.getCluster(),
        prelSerializer.fromProto(icebergManifestList.getTraitSet()),
        catalogFromProto.fromProto(icebergManifestList.getTableMetadata()),
        catalogFromProto.batchSchemaFromJson(icebergManifestList.getBatchSchemaBytes()),
        icebergManifestList.getProjectedColumnsList().stream()
            .map(catalogFromProto::schemaPathFromBson)
            .collect(ImmutableList.toImmutableList()),
        prelSerializer.fromProto(icebergManifestList.getFieldTypesList()),
        catalogFromProto.expressionFromBson(icebergManifestList.getExpression()),
        fromProto(icebergManifestList.getManifestContentType()));
  }

  private static PManifestContentType toProto(ManifestContentType manifestContentType) {
    switch (manifestContentType) {
      case ALL:
        return PManifestContentType.ALL;
      case DATA:
        return PManifestContentType.DATA;
      case DELETES:
        return PManifestContentType.DELETES;
      default:
        throw new IllegalArgumentException("Unknown manifest content type: " + manifestContentType);
    }
  }

  private static ManifestContentType fromProto(PManifestContentType proto) {
    switch (proto) {
      case ALL:
        return ManifestContentType.ALL;
      case DATA:
        return ManifestContentType.DATA;
      case DELETES:
        return ManifestContentType.DELETES;
      default:
        throw new IllegalArgumentException("Unknown manifest content type: " + proto);
    }
  }
}
