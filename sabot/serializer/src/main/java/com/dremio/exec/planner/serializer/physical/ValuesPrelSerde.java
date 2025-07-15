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

import com.dremio.exec.planner.physical.ValuesPrel;
import com.dremio.plan.serialization.PPhyscialRels.PValuesPrel;
import com.dremio.plan.serialization.PPhyscialRels.PValuesPrel.PValuesPrelTuple;
import com.google.common.collect.ImmutableList;
import java.util.stream.Collectors;
import org.apache.calcite.rex.RexLiteral;

public final class ValuesPrelSerde {

  private ValuesPrelSerde() {}

  public static PValuesPrel toProto(ValuesPrel values, PrelToProto prelToProto) {
    return PValuesPrel.newBuilder()
        .setTraitSet(prelToProto.toProto(values.getTraitSet()))
        .addAllFields(prelToProto.toProto(values.getRowType().getFieldList()))
        .addAllTuples(
            values.getTuples().stream()
                .map(
                    c ->
                        PValuesPrelTuple.newBuilder()
                            .addAllLiteral(
                                c.stream().map(prelToProto::toProto).collect(Collectors.toList()))
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  public static ValuesPrel fromProto(PValuesPrel proto, ProtoToPrel protoToPrel) {
    return new ValuesPrel(
        protoToPrel.getCluster(),
        protoToPrel.fromProto(proto.getTraitSet()),
        protoToPrel.fromProto(proto.getFieldsList()),
        proto.getTuplesList().stream()
            .map(
                rec ->
                    rec.getLiteralList().stream()
                        .map(protoToPrel::toRex)
                        .map(RexLiteral.class::cast)
                        .collect(ImmutableList.toImmutableList()))
            .collect(ImmutableList.toImmutableList()));
  }
}
