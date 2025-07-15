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

import com.dremio.exec.planner.physical.DistributionTrait.DistributionField;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.serializer.SqlOperatorSerde;
import com.dremio.exec.planner.serializer.TypeSerde;
import com.dremio.exec.planner.serializer.catalog.CatalogToProto;
import com.dremio.exec.planner.serializer.core.AggregateCallSerde;
import com.dremio.exec.planner.serializer.core.CommonRelSerde;
import com.dremio.exec.planner.serializer.core.DistributionTraitSerde;
import com.dremio.exec.planner.serializer.core.RelFieldCollationSerde;
import com.dremio.plan.serialization.PAggregateCall;
import com.dremio.plan.serialization.PDistributionField;
import com.dremio.plan.serialization.PGroupSet;
import com.dremio.plan.serialization.PJoinType;
import com.dremio.plan.serialization.PPhyscialRels.PPhysicalRelNode;
import com.dremio.plan.serialization.PRelDataTypeField;
import com.dremio.plan.serialization.PRelFieldCollation;
import com.dremio.plan.serialization.PRelTraitSet;
import com.dremio.plan.serialization.PRexLiteral;
import com.dremio.plan.serialization.PRexNode;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

/** A facade for converting Pojo Prels to Protos. */
public interface PrelToProto {

  PRexNode toProto(RexNode node);

  PRexLiteral toProto(RexLiteral rexLiteral);

  PPhysicalRelNode toProto(Prel prel);

  Iterable<PRelDataTypeField> toProto(List<RelDataTypeField> fieldList);

  PRelTraitSet toProto(RelTraitSet traitSet);

  default List<PDistributionField> toProtoDistributionFields(
      List<DistributionField> distributionFieldList) {
    return DistributionTraitSerde.toProto(distributionFieldList);
  }

  default List<PAggregateCall> toProtoAggregateCall(Aggregate aggregate) {
    return AggregateCallSerde.toProtoAggregateCall(aggregate, sqlOperatorSerde());
  }

  default PJoinType toProto(JoinRelType joinRelType) {
    return CommonRelSerde.toProto(joinRelType);
  }

  default PGroupSet toProtoGroupSet(ImmutableBitSet immutableBitSet) {
    return CommonRelSerde.toProtoGroupSet(immutableBitSet);
  }

  default List<PRelFieldCollation> toProtoRelFieldCollation(
      List<RelFieldCollation> relFieldCollationList) {
    return relFieldCollationList.stream()
        .map(RelFieldCollationSerde::toProto)
        .collect(ImmutableList.toImmutableList());
  }

  SqlOperatorSerde sqlOperatorSerde();

  TypeSerde getTypeSerde();

  CatalogToProto getCatalogToProto();
}
