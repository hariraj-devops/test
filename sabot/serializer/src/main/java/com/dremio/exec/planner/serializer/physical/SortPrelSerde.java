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
import com.dremio.exec.planner.physical.SortPrel;
import com.dremio.exec.planner.serializer.core.RelFieldCollationSerde;
import com.dremio.plan.serialization.PPhyscialRels;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;

public final class SortPrelSerde {

  private SortPrelSerde() {}

  public static PPhyscialRels.PSortPrel toProto(SortPrel pojo, PrelToProto prelToProto) {
    PPhyscialRels.PSortPrel.Builder builder =
        PPhyscialRels.PSortPrel.newBuilder()
            .setTraitSet(prelToProto.toProto(pojo.getTraitSet()))
            .setInput(prelToProto.toProto((Prel) (pojo.getInput())))
            .addAllCollation(
                prelToProto.toProtoRelFieldCollation(pojo.collation.getFieldCollations()));

    return builder.build();
  }

  public static SortPrel fromProto(PPhyscialRels.PSortPrel proto, ProtoToPrel s) {
    RelNode input = s.fromProto(proto.getInput());
    RelCollation fieldCollations =
        RelCollations.of(
            proto.getCollationList().stream()
                .map(RelFieldCollationSerde::fromProto)
                .collect(Collectors.toList()));
    RelTraitSet traitSet = s.fromProto(proto.getTraitSet());
    return SortPrel.create(
        s.getCluster(),
        traitSet,
        input,
        (RelCollation)
            traitSet.getTrait(
                fieldCollations.getTraitDef()) // this dependence on reference equality
        );
  }
}
