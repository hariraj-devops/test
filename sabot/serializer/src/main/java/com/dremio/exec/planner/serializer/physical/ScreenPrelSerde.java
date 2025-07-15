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
import com.dremio.exec.planner.physical.ScreenPrel;
import com.dremio.exec.planner.serializer.TypeSerde;
import com.dremio.exec.planner.serializer.core.RelTraitSetSerde;
import com.dremio.plan.serialization.PPhyscialRels;

public final class ScreenPrelSerde {

  private ScreenPrelSerde() {}

  public static PPhyscialRels.PScreenPrel toProto(ScreenPrel pojo, PrelToProto prelToProto) {
    TypeSerde typeSerde = prelToProto.getTypeSerde();
    return PPhyscialRels.PScreenPrel.newBuilder()
        .setTraitSet(RelTraitSetSerde.toProto(pojo.getTraitSet()))
        .setInput(prelToProto.toProto((Prel) (pojo.getInput())))
        .build();
  }

  public static ScreenPrel fromProto(PPhyscialRels.PScreenPrel proto, ProtoToPrel protoToPrel) {
    TypeSerde typeSerde = protoToPrel.getTypeSerde();
    return new ScreenPrel(
        protoToPrel.getCluster(),
        RelTraitSetSerde.fromProto(proto.getTraitSet()),
        protoToPrel.fromProto(proto.getInput()));
  }
}
