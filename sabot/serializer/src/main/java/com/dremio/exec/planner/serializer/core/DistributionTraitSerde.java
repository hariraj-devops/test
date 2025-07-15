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

import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.DistributionTrait.DistributionField;
import com.dremio.plan.serialization.PDistributionField;
import com.dremio.plan.serialization.PDistributionTrait;
import com.dremio.plan.serialization.PDistributionType;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.commons.lang3.NotImplementedException;

public final class DistributionTraitSerde {

  private DistributionTraitSerde() {}

  public static PDistributionTrait toProto(DistributionTrait distributionTrait) {
    PDistributionType pDistributionType = toProto(distributionTrait.getType());
    return PDistributionTrait.newBuilder()
        .setType(pDistributionType)
        .addAllFields(toProto(distributionTrait.getFields()))
        .build();
  }

  public static DistributionTrait fromProto(PDistributionTrait distributionTrait) {
    DistributionTrait.DistributionType distributionType = fromProto(distributionTrait.getType());
    switch (distributionType) {
      case SINGLETON:
        return DistributionTrait.SINGLETON;
      case ANY:
        return DistributionTrait.ANY;
      case ROUND_ROBIN_DISTRIBUTED:
        return DistributionTrait.ROUND_ROBIN;
      case BROADCAST_DISTRIBUTED:
        return DistributionTrait.BROADCAST;
      default:
        return new DistributionTrait(
            distributionType, fromProto(distributionTrait.getFieldsList()));
    }
  }

  public static List<PDistributionField> toProto(List<DistributionField> pojoList) {
    return pojoList.stream()
        .map(DistributionTraitSerde::toProto)
        .collect(ImmutableList.toImmutableList());
  }

  public static ImmutableList<DistributionField> fromProto(List<PDistributionField> protoList) {
    return protoList.stream()
        .map(DistributionTraitSerde::fromProto)
        .collect(ImmutableList.toImmutableList());
  }

  public static PDistributionField toProto(DistributionField pojo) {
    return PDistributionField.newBuilder()
        .setFieldId(pojo.getFieldId())
        .setNotNull(pojo.notNull())
        .build();
  }

  public static DistributionField fromProto(PDistributionField proto) {
    return new DistributionField(proto.getFieldId(), proto.getNotNull());
  }

  private static PDistributionType toProto(DistributionTrait.DistributionType type) {
    switch (type) {
      case SINGLETON:
        return PDistributionType.SINGLETON;
      case ANY:
        return PDistributionType.ANY_;
      case HASH_DISTRIBUTED:
        return PDistributionType.HASH_DISTRIBUTED;
      case BROADCAST_DISTRIBUTED:
        return PDistributionType.BROADCAST_DISTRIBUTED;
      case RANGE_DISTRIBUTED:
        return PDistributionType.RANGE_DISTRIBUTED;
      default:
        throw new NotImplementedException(type.name());
    }
  }

  private static DistributionTrait.DistributionType fromProto(PDistributionType type) {
    switch (type) {
      case SINGLETON:
        return DistributionTrait.DistributionType.SINGLETON;
      case ANY_:
        return DistributionTrait.DistributionType.ANY;
      case HASH_DISTRIBUTED:
        return DistributionTrait.DistributionType.HASH_DISTRIBUTED;
      case BROADCAST_DISTRIBUTED:
        return DistributionTrait.DistributionType.BROADCAST_DISTRIBUTED;
      case RANGE_DISTRIBUTED:
        return DistributionTrait.DistributionType.RANGE_DISTRIBUTED;
      default:
        throw new NotImplementedException(type.name());
    }
  }
}
