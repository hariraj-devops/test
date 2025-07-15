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

import com.dremio.exec.planner.physical.AggregatePrel;
import com.dremio.exec.planner.physical.HashAggPrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.StreamAggPrel;
import com.dremio.plan.serialization.PPhyscialRels;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;

public final class AggPrelSerde {
  private AggPrelSerde() {}

  public static PPhyscialRels.PHashAggPrel toProto(HashAggPrel pojo, PrelToProto prelToProto) {
    return PPhyscialRels.PHashAggPrel.newBuilder()
        .setTraitSet(prelToProto.toProto(pojo.getTraitSet()))
        .setInput(prelToProto.toProto((Prel) pojo.getInput()))
        .addAllAggregateCall(prelToProto.toProtoAggregateCall(pojo))
        .setGroupSet(prelToProto.toProtoGroupSet(pojo.getGroupSet()))
        .setOperatorPhase(toProto(pojo.getOperatorPhase()))
        .build();
  }

  public static HashAggPrel fromProto(PPhyscialRels.PHashAggPrel proto, ProtoToPrel protoToPrel) {
    RelNode input = protoToPrel.fromProto(proto.getInput());
    try {
      return new HashAggPrel(
          protoToPrel.getCluster(),
          protoToPrel.fromProto(proto.getTraitSet()),
          input,
          protoToPrel.fromProto(proto.getGroupSet()),
          protoToPrel.fromProto(proto.getAggregateCallList(), proto.getGroupSet(), input),
          fromProto(proto.getOperatorPhase()));
    } catch (InvalidRelException invalidRelException) {
      // Maybe this should be a different exception
      throw new RuntimeException(invalidRelException);
    }
  }

  public static PPhyscialRels.PStreamAggPrel toProto(StreamAggPrel pojo, PrelToProto prelToProto) {
    return PPhyscialRels.PStreamAggPrel.newBuilder()
        .setTraitSet(prelToProto.toProto(pojo.getTraitSet()))
        .setInput(prelToProto.toProto((Prel) pojo.getInput()))
        .addAllAggregateCall(prelToProto.toProtoAggregateCall(pojo))
        .setGroupSet(prelToProto.toProtoGroupSet(pojo.getGroupSet()))
        .setOperatorPhase(toProto(pojo.getOperatorPhase()))
        .build();
  }

  public static StreamAggPrel fromProto(
      PPhyscialRels.PStreamAggPrel proto, ProtoToPrel protoToPrel) {
    RelNode input = protoToPrel.fromProto(proto.getInput());
    try {
      return new StreamAggPrel(
          protoToPrel.getCluster(),
          protoToPrel.fromProto(proto.getTraitSet()),
          input,
          protoToPrel.fromProto(proto.getGroupSet()),
          protoToPrel.fromProto(proto.getAggregateCallList(), proto.getGroupSet(), input),
          fromProto(proto.getOperatorPhase()));
    } catch (InvalidRelException invalidRelException) {
      // Maybe this should be a different exception
      throw new RuntimeException(invalidRelException);
    }
  }

  public static PPhyscialRels.POperatorPhase toProto(AggregatePrel.OperatorPhase operatorPhase) {
    switch (operatorPhase) {
      case PHASE_1of1:
        return PPhyscialRels.POperatorPhase.PHASE_1of1;
      case PHASE_1of2:
        return PPhyscialRels.POperatorPhase.PHASE_1of2;
      case PHASE_2of2:
        return PPhyscialRels.POperatorPhase.PHASE_2of2;
      default:
        throw new UnsupportedOperationException(operatorPhase.name());
    }
  }

  public static AggregatePrel.OperatorPhase fromProto(PPhyscialRels.POperatorPhase operatorPhase) {
    switch (operatorPhase) {
      case PHASE_1of1:
        return AggregatePrel.OperatorPhase.PHASE_1of1;
      case PHASE_1of2:
        return AggregatePrel.OperatorPhase.PHASE_1of2;
      case PHASE_2of2:
        return AggregatePrel.OperatorPhase.PHASE_2of2;
      default:
        throw new UnsupportedOperationException(operatorPhase.name());
    }
  }
}
