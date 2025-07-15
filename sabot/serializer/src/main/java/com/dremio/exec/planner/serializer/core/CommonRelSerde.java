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

import com.dremio.plan.serialization.PGroupSet;
import com.dremio.plan.serialization.PJoinType;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.util.ImmutableBitSet;

public final class CommonRelSerde {
  public static PGroupSet toProtoGroupSet(ImmutableBitSet set) {
    return PGroupSet.newBuilder().addAllGroup(set.asList()).build();
  }

  public static ImmutableBitSet fromProtoGroupSet(PGroupSet pGroupSet) {
    return ImmutableBitSet.of(pGroupSet.getGroupList());
  }

  public static JoinRelType fromProto(PJoinType type) {
    switch (type) {
      case FULL:
        return JoinRelType.FULL;
      case INNER:
        return JoinRelType.INNER;
      case LEFT:
        return JoinRelType.LEFT;
      case RIGHT:
        return JoinRelType.RIGHT;
      default:
        throw new UnsupportedOperationException(String.format("Unknown type %s.", type));
    }
  }

  public static PJoinType toProto(JoinRelType type) {
    switch (type) {
      case FULL:
        return PJoinType.FULL;
      case INNER:
        return PJoinType.INNER;
      case LEFT:
        return PJoinType.LEFT;
      case RIGHT:
        return PJoinType.RIGHT;
      default:
        throw new UnsupportedOperationException(String.format("Unknown type %s.", type));
    }
  }
}
