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

import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.serializer.SqlOperatorSerde;
import com.dremio.exec.planner.serializer.TypeSerde;
import com.dremio.exec.planner.serializer.catalog.CatalogFromProto;
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
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

/** A facade for converting Proto Prels to Pojos. */
public interface ProtoToPrel {
  TypeSerde getTypeSerde();

  RelOptCluster getCluster();

  SqlOperatorSerde sqlOperatorSerde();

  RelNode fromProto(PPhysicalRelNode rel);

  RexLiteral toRex(PRexLiteral literal);

  RelTraitSet fromProto(PRelTraitSet traitSet);

  RelDataType fromProto(List<PRelDataTypeField> fieldsList);

  RexNode fromProto(PRexNode pRexNode);

  default JoinRelType fromProto(PJoinType joinType) {
    return CommonRelSerde.fromProto(joinType);
  }

  default ImmutableBitSet fromProto(PGroupSet pGroupSet) {
    return CommonRelSerde.fromProtoGroupSet(pGroupSet);
  }

  default List<AggregateCall> fromProto(
      List<PAggregateCall> aggregateCall, PGroupSet pGroupSet, RelNode input) {
    return AggregateCallSerde.fromProto(aggregateCall, pGroupSet, input, sqlOperatorSerde());
  }

  default List<DistributionTrait.DistributionField> fromProtoDistributionField(
      List<PDistributionField> pDistributionFieldList) {
    return DistributionTraitSerde.fromProto(pDistributionFieldList);
  }

  default List<RelFieldCollation> fromProtoRelFieldCollation(
      List<PRelFieldCollation> pRelFieldCollationList) {
    return pRelFieldCollationList.stream()
        .map(RelFieldCollationSerde::fromProto)
        .collect(ImmutableList.toImmutableList());
  }

  CatalogFromProto getCatalogFromProto();
}
