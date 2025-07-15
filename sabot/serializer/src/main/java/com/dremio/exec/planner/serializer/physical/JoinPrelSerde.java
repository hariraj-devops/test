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

import com.dremio.exec.planner.physical.HashJoinPrel;
import com.dremio.exec.planner.physical.MergeJoinPrel;
import com.dremio.exec.planner.physical.NestedLoopJoinPrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.serializer.core.RelTraitSetSerde;
import com.dremio.plan.serialization.PPhyscialRels;

public final class JoinPrelSerde {

  private JoinPrelSerde() {}

  public static PPhyscialRels.PHashJoinPrel toProto(HashJoinPrel pojo, PrelToProto prelToProto) {
    PPhyscialRels.PHashJoinPrel.Builder builder =
        PPhyscialRels.PHashJoinPrel.newBuilder()
            .setTraitSet(RelTraitSetSerde.toProto(pojo.getTraitSet()))
            .setRight(prelToProto.toProto((Prel) pojo.getRight()))
            .setLeft(prelToProto.toProto((Prel) pojo.getLeft()))
            .setCondition(prelToProto.toProto(pojo.getCondition()))
            .setJoinType(prelToProto.toProto(pojo.getJoinType()))
            .setSwapped(pojo.isSwapped())
            .setIgnoreForJoinAnalysis(pojo.getIgnoreForJoinAnalysis());
    if (null != pojo.getRuntimeFilterId()) {
      builder.setRuntimeFilterId(RuntimeFilterSerde.toProto(pojo.getRuntimeFilterId()));
    }
    if (null != pojo.getExtraCondition()) {
      builder.setExtraCondition(prelToProto.toProto(pojo.getExtraCondition()));
    }
    return builder.build();
  }

  public static HashJoinPrel fromProto(PPhyscialRels.PHashJoinPrel proto, ProtoToPrel protoToPrel) {
    return new HashJoinPrel(
        protoToPrel.getCluster(),
        protoToPrel.fromProto(proto.getTraitSet()),
        protoToPrel.fromProto(proto.getLeft()),
        protoToPrel.fromProto(proto.getRight()),
        protoToPrel.fromProto(proto.getCondition()),
        protoToPrel.fromProto(proto.getExtraCondition()),
        protoToPrel.fromProto(proto.getJoinType()),
        proto.getSwapped(),
        RuntimeFilterSerde.fromProto(proto.getRuntimeFilterId(), proto.hasRuntimeFilterId()),
        proto.getIgnoreForJoinAnalysis());
  }

  public static PPhyscialRels.PMergeJoinPrel toProto(MergeJoinPrel pojo, PrelToProto prelToProto) {
    return PPhyscialRels.PMergeJoinPrel.newBuilder()
        .setTraitSet(RelTraitSetSerde.toProto(pojo.getTraitSet()))
        .setRight(prelToProto.toProto((Prel) pojo.getRight()))
        .setLeft(prelToProto.toProto((Prel) pojo.getLeft()))
        .setJoinType(prelToProto.toProto(pojo.getJoinType()))
        .setCondition(prelToProto.toProto(pojo.getCondition()))
        .build();
  }

  public static MergeJoinPrel fromProto(
      PPhyscialRels.PMergeJoinPrel proto, ProtoToPrel protoToPrel) {
    return new MergeJoinPrel(
        protoToPrel.getCluster(),
        protoToPrel.fromProto(proto.getTraitSet()),
        protoToPrel.fromProto(proto.getLeft()),
        protoToPrel.fromProto(proto.getRight()),
        protoToPrel.fromProto(proto.getCondition()),
        protoToPrel.fromProto(proto.getJoinType()));
  }

  public static PPhyscialRels.PNestedLoopJoinPrel toProto(
      NestedLoopJoinPrel pojo, PrelToProto prelToProto) {
    return PPhyscialRels.PNestedLoopJoinPrel.newBuilder()
        .setTraitSet(RelTraitSetSerde.toProto(pojo.getTraitSet()))
        .setRight(prelToProto.toProto((Prel) pojo.getRight()))
        .setLeft(prelToProto.toProto((Prel) pojo.getLeft()))
        .setJoinType(prelToProto.toProto(pojo.getJoinType()))
        .setCondition(prelToProto.toProto(pojo.getCondition()))
        .mergeVectorExpression(prelToProto.toProto(pojo.getVectorExpression()))
        .build();
  }

  public static NestedLoopJoinPrel fromProto(
      PPhyscialRels.PNestedLoopJoinPrel proto, ProtoToPrel protoToPrel) {
    return new NestedLoopJoinPrel(
        protoToPrel.getCluster(),
        protoToPrel.fromProto(proto.getTraitSet()),
        protoToPrel.fromProto(proto.getLeft()),
        protoToPrel.fromProto(proto.getRight()),
        protoToPrel.fromProto(proto.getJoinType()),
        protoToPrel.fromProto(proto.getCondition()),
        protoToPrel.fromProto(proto.getVectorExpression()));
  }
}
