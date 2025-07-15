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
package org.apache.calcite.plan;

import com.dremio.exec.planner.serializer.core.RelTraitSerde;
import com.dremio.plan.serialization.PRelCompositeTrait;
import com.google.common.collect.ImmutableList;
import java.util.stream.IntStream;

/** {@link RelCompositeTrait} is package internal so this class is in org.apache.calcite.plan. */
public class RelCompositeTraitSerde {

  public static PRelCompositeTrait toProto(RelTrait pojo) {
    RelCompositeTrait<?> relCompositeTrait = (RelCompositeTrait<?>) pojo;

    PRelCompositeTrait.Builder builder = PRelCompositeTrait.newBuilder();
    assert 0 != relCompositeTrait.size();
    IntStream.range(0, relCompositeTrait.size())
        .mapToObj(relCompositeTrait::trait)
        .map(RelTraitSerde::toProto)
        .forEach(builder::addTraits);
    return builder.build();
  }

  public static RelTrait fromProto(PRelCompositeTrait proto) {
    ImmutableList<RelMultipleTrait> traits =
        proto.getTraitsList().stream()
            .map(RelTraitSerde::fromProto)
            .map(RelMultipleTrait.class::cast)
            .collect(ImmutableList.toImmutableList());

    return RelCompositeTrait.of(traits.get(0).getTraitDef(), traits);
  }
}
