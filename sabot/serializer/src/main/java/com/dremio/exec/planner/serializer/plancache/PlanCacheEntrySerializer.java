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
package com.dremio.exec.planner.serializer.plancache;

import com.dremio.exec.planner.plancache.distributable.DistributedPlanCacheEntry;
import com.dremio.exec.planner.serializer.physical.PrelSerializer;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.plan.serialization.PPlanCache.PDistributedPlanCacheEntry;
import com.google.protobuf.InvalidProtocolBufferException;

public class PlanCacheEntrySerializer {

  private final PrelSerializer physicalRelSerde;

  public PlanCacheEntrySerializer(PrelSerializer physicalRelSerde) {
    this.physicalRelSerde = physicalRelSerde;
  }

  public DistributedPlanCacheEntry fromProto(PDistributedPlanCacheEntry pDistributedPlanCacheEntry)
      throws InvalidProtocolBufferException {
    return new DistributedPlanCacheEntry(
        physicalRelSerde.fromProto(pDistributedPlanCacheEntry.getPrel()),
        pDistributedPlanCacheEntry.getConsideredReflectionHash(),
        pDistributedPlanCacheEntry.getCreationTime(),
        UserBitShared.AccelerationProfile.parseFrom(
            pDistributedPlanCacheEntry.getAccelerationProfile()));
  }

  public PDistributedPlanCacheEntry toProto(DistributedPlanCacheEntry distributedPlanCacheEntry) {
    return PDistributedPlanCacheEntry.newBuilder()
        .setPrel(physicalRelSerde.toProto(distributedPlanCacheEntry.getPrel()))
        .setConsideredReflectionHash(distributedPlanCacheEntry.getConsideredReflectionsHash())
        .setCreationTime(distributedPlanCacheEntry.getCreationTime())
        .setAccelerationProfile(distributedPlanCacheEntry.getAccelerationProfile().toByteString())
        .build();
  }
}
