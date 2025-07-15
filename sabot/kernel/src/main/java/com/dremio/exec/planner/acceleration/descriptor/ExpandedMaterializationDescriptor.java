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

package com.dremio.exec.planner.acceleration.descriptor;

import com.dremio.exec.planner.RoutingShuttle;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionUtils;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.proto.UserBitShared.LayoutMaterializedViewProfile;
import com.google.common.base.Preconditions;
import java.util.Set;
import org.apache.calcite.plan.CopyWithCluster;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;

/** {@link MaterializationDescriptor} that caches the expanded {@link DremioMaterialization} */
public class ExpandedMaterializationDescriptor extends BaseMaterializationDescriptor {

  private final DremioMaterialization materialization;

  public ExpandedMaterializationDescriptor(
      MaterializationDescriptor descriptor, DremioMaterialization materialization) {
    super(
        descriptor.getLayoutInfo(),
        descriptor.getMaterializationId(),
        descriptor.getVersion(),
        descriptor.getExpirationTimestamp(),
        descriptor.getPath(),
        descriptor.getOriginalCost(),
        descriptor.getJobStart(),
        descriptor.getIncrementalUpdateSettings(),
        descriptor.isStale(),
        descriptor.getMatchingHash());
    this.materialization =
        Preconditions.checkNotNull(materialization, "materialization is required");
  }

  public ExpandedMaterializationDescriptor(
      ExpandedMaterializationDescriptor cachedDescriptor, boolean isStale, String tag) {
    super(
        cachedDescriptor.getLayoutInfo(),
        cachedDescriptor.getMaterializationId(),
        tag,
        cachedDescriptor.getExpirationTimestamp(),
        cachedDescriptor.getPath(),
        cachedDescriptor.getOriginalCost(),
        cachedDescriptor.getJobStart(),
        cachedDescriptor.getIncrementalUpdateSettings(),
        isStale,
        cachedDescriptor.getMatchingHash());
    this.materialization =
        Preconditions.checkNotNull(
            cachedDescriptor.getMaterialization(), "materialization is required");
  }

  @Override
  public DremioMaterialization getMaterializationFor(SqlConverter converter) {
    final CopyWithCluster copier = new CopyWithCluster(converter.getCluster());
    final DremioMaterialization copied =
        materialization.accept(new FixCharTypeShuttle(converter.getCluster())).accept(copier);
    copier.validate();
    return copied;
  }

  public static class FixCharTypeRexShuttle extends RexShuttle {
    private final RelOptCluster cluster;

    FixCharTypeRexShuttle(RelOptCluster cluster) {
      this.cluster = cluster;
    }

    @Override
    public RexNode visitLiteral(RexLiteral literal) {
      if (literal.getTypeName() == SqlTypeName.VARCHAR) {
        return cluster.getRexBuilder().makeCharLiteral(literal.getValueAs(NlsString.class));
      }
      return literal;
    }
  }

  public static class FixCharTypeShuttle extends RoutingShuttle {
    RelOptCluster cluster;

    FixCharTypeShuttle(RelOptCluster cluster) {
      this.cluster = cluster;
    }

    @Override
    public RelNode visit(RelNode relNode) {
      RelNode rel = super.visit(relNode);
      return rel.accept(new FixCharTypeRexShuttle(cluster));
    }
  }

  public DremioMaterialization getMaterialization() {
    return materialization;
  }

  /**
   * Returns true only if there is overlap between this materialization and the input tables, views
   * and external queries.
   *
   * @param queryTablesUsed
   * @param queryVdsUsed
   * @param externalQueries
   * @return
   */
  @Override
  public boolean isApplicable(
      Set<SubstitutionUtils.VersionedPath> queryTablesUsed,
      Set<SubstitutionUtils.VersionedPath> queryVdsUsed,
      Set<SubstitutionUtils.ExternalQueryDescriptor> externalQueries) {
    return SubstitutionUtils.usesTableOrVds(
        queryTablesUsed, queryVdsUsed, externalQueries, materialization.getQueryRel());
  }

  @Override
  public LayoutMaterializedViewProfile getLayoutMaterializedViewProfile(boolean verbose) {
    return LayoutMaterializedViewProfile.newBuilder(
            materialization.getLayoutMaterializedViewProfile(verbose))
        .setIsStale(isStale())
        .build();
  }
}
