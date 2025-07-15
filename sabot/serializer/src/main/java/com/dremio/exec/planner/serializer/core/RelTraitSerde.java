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
package com.dremio.exec.planner.serializer.core;

import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.serializer.RelCollationSerde;
import com.dremio.plan.serialization.PConvention;
import com.dremio.plan.serialization.PDistributionTrait;
import com.dremio.plan.serialization.PRelCompositeTrait;
import com.dremio.plan.serialization.PRelTrait;
import com.google.common.base.Preconditions;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelCompositeTraitSerde;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.rel.RelCollation;
import org.apache.commons.lang3.NotImplementedException;

/** Serde for RelTrait. */
public final class RelTraitSerde {
  private RelTraitSerde() {}

  public static RelTrait fromProto(PRelTrait pRelTrait) {
    Preconditions.checkNotNull(pRelTrait);
    switch (pRelTrait.getImplementationCase()) {
      case CONVENTION:
        return fromProtoConventionType(pRelTrait.getConvention());
      case COLLATION:
        return RelCollationSerde.fromProto(pRelTrait.getCollation());
      case DISTRIBUTION_TRAIT:
        return DistributionTraitSerde.fromProto(pRelTrait.getDistributionTrait());
      case COMPOSITE_TRAIT:
        return RelCompositeTraitSerde.fromProto(pRelTrait.getCompositeTrait());
      case IMPLEMENTATION_NOT_SET:
      default:
        throw new NotImplementedException("Don't know how to handle this PRelTrait.");
    }
  }

  public static PRelTrait toProto(RelTrait relTrait) {
    Preconditions.checkNotNull(relTrait);
    if (relTrait instanceof RelCollation) {
      return PRelTrait.newBuilder()
          .setCollation(RelCollationSerde.toProto((RelCollation) relTrait))
          .build();
    } else if (relTrait instanceof Convention) {
      return PRelTrait.newBuilder().setConvention(toProtoConventionType(relTrait)).build();
    } else if (relTrait instanceof DistributionTrait) {
      PDistributionTrait pDistributionTrait =
          DistributionTraitSerde.toProto((DistributionTrait) relTrait);
      return PRelTrait.newBuilder().setDistributionTrait(pDistributionTrait).build();
    } else if (relTrait.getClass().getSimpleName().equals("RelCompositeTrait")) {
      PRelCompositeTrait compTrait = RelCompositeTraitSerde.toProto(relTrait);
      return PRelTrait.newBuilder().setCompositeTrait(compTrait).build();
    } else {
      throw new NotImplementedException("Don't know how to handle this RelTrait: " + relTrait);
    }
  }

  private static PConvention toProtoConventionType(RelTrait relTrait) {
    if (relTrait == Prel.PHYSICAL) {
      return PConvention.PHYSICAL;
    } else if (relTrait == Rel.LOGICAL) {
      return PConvention.LOGICAL;
    } else {
      throw new NotImplementedException("Don't know how to handle this RelTrait: " + relTrait);
    }
  }

  private static RelTrait fromProtoConventionType(PConvention pConvention) {
    switch (pConvention) {
      case PHYSICAL:
        return Prel.PHYSICAL;
      case LOGICAL:
        return Rel.LOGICAL;
      default:
        throw new NotImplementedException("Don't know how to handle this PRelTrait.");
    }
  }
}
