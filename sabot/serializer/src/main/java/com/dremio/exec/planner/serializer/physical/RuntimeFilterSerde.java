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

import com.dremio.exec.planner.physical.filter.RuntimeFilterId;
import com.dremio.exec.planner.physical.filter.RuntimeFilteredRel;
import com.dremio.exec.planner.physical.filter.RuntimeFilteredRel.ColumnType;
import com.dremio.plan.serialization.PPhyscialRels;
import com.dremio.plan.serialization.PPhyscialRels.PRuntimeFilteredRelInfo.PColumnType;

public class RuntimeFilterSerde {

  public static PPhyscialRels.PRuntimeFilteredRelInfo toProto(
      RuntimeFilteredRel.Info runtimeFilteredRelInfo) {
    if (null == runtimeFilteredRelInfo) {
      return null;
    }
    return PPhyscialRels.PRuntimeFilteredRelInfo.newBuilder()
        .setRuntimeFilterId(toProto(runtimeFilteredRelInfo.getRuntimeFilterId()))
        .setColumnType(toProto(runtimeFilteredRelInfo.getColumnType()))
        .setFilteredColumnName(runtimeFilteredRelInfo.getFilteredColumnName())
        .setFilteringColumnName(runtimeFilteredRelInfo.getFilteringColumnName())
        .build();
  }

  public static RuntimeFilteredRel.Info fromProto(
      PPhyscialRels.PRuntimeFilteredRelInfo pRuntimeFilteredRelInfo) {
    return new RuntimeFilteredRel.Info(
        fromProto(pRuntimeFilteredRelInfo.getRuntimeFilterId(), true),
        fromProto(pRuntimeFilteredRelInfo.getColumnType()),
        pRuntimeFilteredRelInfo.getFilteredColumnName(),
        pRuntimeFilteredRelInfo.getFilteringColumnName());
  }

  private static PColumnType toProto(ColumnType columnType) {
    switch (columnType) {
      case PARTITION:
        return PColumnType.PARTITION;
      case RANDOM:
        return PColumnType.RANDOM;
      default:
        throw new IllegalArgumentException("Unknown column type: " + columnType);
    }
  }

  private static ColumnType fromProto(PColumnType pColumnType) {
    switch (pColumnType) {
      case PARTITION:
        return ColumnType.PARTITION;
      case RANDOM:
        return ColumnType.RANDOM;
      default:
        throw new IllegalArgumentException("Unknown column type: " + pColumnType);
    }
  }

  public static PPhyscialRels.PRuntimeFilterId toProto(RuntimeFilterId runtimeFilterId) {
    if (null == runtimeFilterId) {
      return null;
    }
    return PPhyscialRels.PRuntimeFilterId.newBuilder()
        .setValue(runtimeFilterId.getValue())
        .setIsBroadcastJoin(runtimeFilterId.isBroadcastJoin())
        .build();
  }

  public static RuntimeFilterId fromProto(
      PPhyscialRels.PRuntimeFilterId pRuntimeFilterId, boolean isNotNull) {
    if (isNotNull) {
      return new RuntimeFilterId(
          pRuntimeFilterId.getValue(), pRuntimeFilterId.getIsBroadcastJoin());
    } else {
      return null;
    }
  }
}
