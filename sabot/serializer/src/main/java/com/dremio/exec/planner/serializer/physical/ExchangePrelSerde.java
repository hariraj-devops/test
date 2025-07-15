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

import com.dremio.exec.planner.physical.AdaptiveHashExchangePrel;
import com.dremio.exec.planner.physical.BridgeExchangePrel;
import com.dremio.exec.planner.physical.BroadcastExchangePrel;
import com.dremio.exec.planner.physical.HashToMergeExchangePrel;
import com.dremio.exec.planner.physical.HashToRandomExchangePrel;
import com.dremio.exec.planner.physical.OrderedPartitionExchangePrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.RoundRobinExchangePrel;
import com.dremio.exec.planner.physical.SingleMergeExchangePrel;
import com.dremio.exec.planner.physical.UnionExchangePrel;
import com.dremio.exec.planner.physical.UnorderedDeMuxExchangePrel;
import com.dremio.exec.planner.physical.UnorderedMuxExchangePrel;
import com.dremio.exec.planner.serializer.RelCollationSerde;
import com.dremio.plan.serialization.PPhyscialRels.PAdaptiveHashExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PBridgeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PBroadcastExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashToMergeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PHashToRandomExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.POrderedPartitionExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PRoundRobinExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PSingleMergeExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnionExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnorderedDeMuxExchangePrel;
import com.dremio.plan.serialization.PPhyscialRels.PUnorderedMuxExchangePrel;
import com.google.common.base.Preconditions;

public final class ExchangePrelSerde {
  private ExchangePrelSerde() {}

  public static PAdaptiveHashExchangePrel toProto(AdaptiveHashExchangePrel pojo, PrelToProto p) {
    Preconditions.checkArgument(pojo.getTableFunctionCreator() == null);
    return PAdaptiveHashExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .addAllFields(p.toProtoDistributionFields(pojo.getFields()))
        .setHashFunctionName(pojo.getHashFunctionName())
        .setWindowPushedDown(pojo.isWindowPushedDown())
        .build();
  }

  public static AdaptiveHashExchangePrel fromProto(PAdaptiveHashExchangePrel proto, ProtoToPrel p) {
    return new AdaptiveHashExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        p.fromProtoDistributionField(proto.getFieldsList()),
        proto.getHashFunctionName(),
        null,
        proto.getWindowPushedDown());
  }

  public static PBridgeExchangePrel toProto(BridgeExchangePrel pojo, PrelToProto p) {
    return PBridgeExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .setBridgeSetId(pojo.getBridgeSetId())
        .build();
  }

  public static BridgeExchangePrel fromProto(PBridgeExchangePrel proto, ProtoToPrel p) {
    return new BridgeExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        proto.getBridgeSetId());
  }

  public static PBroadcastExchangePrel toProto(BroadcastExchangePrel pojo, PrelToProto p) {
    return PBroadcastExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .build();
  }

  public static BroadcastExchangePrel fromProto(PBroadcastExchangePrel proto, ProtoToPrel p) {
    return new BroadcastExchangePrel(
        p.getCluster(), p.fromProto(proto.getTraitSet()), p.fromProto(proto.getInput()));
  }

  public static PHashToMergeExchangePrel toProto(HashToMergeExchangePrel pojo, PrelToProto p) {
    return PHashToMergeExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .addAllFields(p.toProtoDistributionFields(pojo.getDistFields()))
        .setCollation(RelCollationSerde.toProto(pojo.getCollation()))
        .setNumEndPoints(pojo.getNumEndPoints())
        .build();
  }

  public static HashToMergeExchangePrel fromProto(PHashToMergeExchangePrel proto, ProtoToPrel p) {
    return new HashToMergeExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        p.fromProtoDistributionField(proto.getFieldsList()),
        RelCollationSerde.fromProto(proto.getCollation()),
        proto.getNumEndPoints());
  }

  public static PHashToRandomExchangePrel toProto(HashToRandomExchangePrel pojo, PrelToProto p) {
    return PHashToRandomExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .addAllFields(p.toProtoDistributionFields(pojo.getFields()))
        .setHashFunctionName(pojo.getHashFunctionName())
        .setWindowPushedDown(pojo.isWindowPushedDown())
        .build();
  }

  public static HashToRandomExchangePrel fromProto(PHashToRandomExchangePrel proto, ProtoToPrel p) {
    return new HashToRandomExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        p.fromProtoDistributionField(proto.getFieldsList()),
        proto.getHashFunctionName(),
        null,
        proto.getWindowPushedDown());
  }

  public static POrderedPartitionExchangePrel toProto(
      OrderedPartitionExchangePrel pojo, PrelToProto p) {
    return POrderedPartitionExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .build();
  }

  public static OrderedPartitionExchangePrel fromProto(
      POrderedPartitionExchangePrel proto, ProtoToPrel p) {
    return new OrderedPartitionExchangePrel(
        p.getCluster(), p.fromProto(proto.getTraitSet()), p.fromProto(proto.getInput()));
  }

  public static PRoundRobinExchangePrel toProto(RoundRobinExchangePrel pojo, PrelToProto p) {
    return PRoundRobinExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .build();
  }

  public static RoundRobinExchangePrel fromProto(PRoundRobinExchangePrel proto, ProtoToPrel p) {
    return new RoundRobinExchangePrel(
        p.getCluster(), p.fromProto(proto.getTraitSet()), p.fromProto(proto.getInput()));
  }

  public static PSingleMergeExchangePrel toProto(SingleMergeExchangePrel pojo, PrelToProto p) {
    return PSingleMergeExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .setCollation(RelCollationSerde.toProto(pojo.getCollation()))
        .build();
  }

  public static SingleMergeExchangePrel fromProto(PSingleMergeExchangePrel proto, ProtoToPrel p) {
    return new SingleMergeExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        RelCollationSerde.fromProto(proto.getCollation()));
  }

  public static PUnionExchangePrel toProto(UnionExchangePrel pojo, PrelToProto p) {
    return PUnionExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .build();
  }

  public static UnionExchangePrel fromProto(PUnionExchangePrel proto, ProtoToPrel p) {
    return new UnionExchangePrel(
        p.getCluster(), p.fromProto(proto.getTraitSet()), p.fromProto(proto.getInput()));
  }

  public static PUnorderedDeMuxExchangePrel toProto(
      UnorderedDeMuxExchangePrel pojo, PrelToProto p) {
    return PUnorderedDeMuxExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .addAllFields(p.toProtoDistributionFields(pojo.getFields()))
        .build();
  }

  public static UnorderedDeMuxExchangePrel fromProto(
      PUnorderedDeMuxExchangePrel proto, ProtoToPrel p) {
    return new UnorderedDeMuxExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        p.fromProtoDistributionField(proto.getFieldsList()));
  }

  public static PUnorderedMuxExchangePrel toProto(UnorderedMuxExchangePrel pojo, PrelToProto p) {
    return PUnorderedMuxExchangePrel.newBuilder()
        .setTraitSet(p.toProto(pojo.getTraitSet()))
        .setInput(p.toProto((Prel) pojo.getInput()))
        .setFragmentPerNode(pojo.getFragmentsPerNode())
        .build();
  }

  public static UnorderedMuxExchangePrel fromProto(PUnorderedMuxExchangePrel proto, ProtoToPrel p) {
    return new UnorderedMuxExchangePrel(
        p.getCluster(),
        p.fromProto(proto.getTraitSet()),
        p.fromProto(proto.getInput()),
        proto.getFragmentPerNode());
  }
}
