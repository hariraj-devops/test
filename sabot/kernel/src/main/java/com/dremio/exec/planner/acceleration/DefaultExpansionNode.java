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
package com.dremio.exec.planner.acceleration;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.service.namespace.NamespaceKey;
import java.util.List;
import org.apache.calcite.plan.CopyWithCluster;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

/** Represents a location where the query was expanded from a VDS to a default reflection */
public class DefaultExpansionNode extends ExpansionNode {
  protected DefaultExpansionNode(
      NamespaceKey path,
      RelDataType rowType,
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input,
      TableVersionContext versionContext,
      ViewTable viewTable) {
    super(path, rowType, cluster, traits, input, versionContext, viewTable);
  }

  private DefaultExpansionNode(
      NamespaceKey path,
      RelDataType rowType,
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input,
      TableVersionContext versionContext,
      ViewTable viewTable,
      List<RexNode> pushedDownFilters,
      boolean considerForPullUpPredicate) {
    super(
        path,
        rowType,
        cluster,
        traits,
        input,
        versionContext,
        viewTable,
        pushedDownFilters,
        considerForPullUpPredicate);
  }

  public static DefaultExpansionNode wrap(
      NamespaceKey path,
      RelNode node,
      RelDataType rowType,
      TableVersionContext versionContext,
      ViewTable viewTable) {
    return new DefaultExpansionNode(
        path, rowType, node.getCluster(), node.getTraitSet(), node, versionContext, viewTable);
  }

  @Override
  public boolean isDefault() {
    return true;
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new DefaultExpansionNode(
        getPath(),
        rowType,
        this.getCluster(),
        traitSet,
        inputs.get(0),
        getVersionContext(),
        getViewTable(),
        getPushedDownFilters(),
        considerForPullUpPredicate());
  }

  @Override
  public RelNode copyWith(CopyWithCluster copier) {
    return new DefaultExpansionNode(
        getPath(),
        rowType,
        copier.getCluster(),
        copier.copyOf(getTraitSet()),
        getInput().accept(copier),
        getVersionContext(),
        getViewTable(),
        getPushedDownFilters(),
        considerForPullUpPredicate());
  }

  @Override
  public DefaultExpansionNode copy(
      RelTraitSet traitSet, RelNode input, List<RexNode> pushedDownFilters) {
    return new DefaultExpansionNode(
        getPath(),
        rowType,
        this.getCluster(),
        traitSet,
        input,
        getVersionContext(),
        getViewTable(),
        pushedDownFilters,
        considerForPullUpPredicate());
  }

  @Override
  public DefaultExpansionNode considerForPullUpPredicate(boolean considerForPullUpPredicate) {
    return new DefaultExpansionNode(
        getPath(),
        rowType,
        this.getCluster(),
        traitSet,
        input,
        getVersionContext(),
        getViewTable(),
        this.getPushedDownFilters(),
        considerForPullUpPredicate);
  }
}
