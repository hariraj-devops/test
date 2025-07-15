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
package com.dremio.exec.planner.sql.handlers;

import com.dremio.exec.calcite.logical.JdbcCrel;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.PlannerType;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.common.JdbcRelImpl;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.fun.SqlQuantifyOperator;

public final class RexSubQueryUtils {

  private RexSubQueryUtils() {}

  public static boolean containsSubQuery(RelNode relNode) {
    RexSubQueryFinder rexSubQueryFinder = new RexSubQueryFinder();
    relNode.accept(rexSubQueryFinder);
    if (rexSubQueryFinder.foundRexSubQuery) {
      return true;
    }
    for (RelNode sub : relNode.getInputs()) {
      if (containsSubQuery(sub)) {
        return true;
      }
    }
    return false;
  }

  public static boolean containsSubQuery(RexNode rexNode) {
    RexSubQueryFinder rexSubQueryFinder = new RexSubQueryFinder();
    rexNode.accept(rexSubQueryFinder);

    return rexSubQueryFinder.foundRexSubQuery;
  }

  public static Map<RelNode, Set<RexFieldAccess>> mapCorrelateVariables(RelNode relNode) {
    Map<RelNode, Set<RexFieldAccess>> mapping = new HashMap<>();
    fillCorrelateVariableMap(mapping, relNode);
    return mapping;
  }

  // TODO: add RexSubQuery.clone(CorrelationId) so we don't need this
  public static RexSubQuery clone(RexSubQuery rexSubQuery, CorrelationId correlationId) {
    switch (rexSubQuery.op.kind) {
      case SCALAR_QUERY:
        return RexSubQuery.scalar(rexSubQuery.rel, correlationId);

      case EXISTS:
        return RexSubQuery.exists(rexSubQuery.rel, correlationId);

      case IN:
        return RexSubQuery.in(rexSubQuery.rel, rexSubQuery.operands, correlationId);

      case SOME:
        return RexSubQuery.some(
            rexSubQuery.rel,
            rexSubQuery.operands,
            (SqlQuantifyOperator) rexSubQuery.op,
            correlationId);

      case ARRAY_QUERY_CONSTRUCTOR:
        return RexSubQuery.array(rexSubQuery.rel, correlationId);

      case MAP_QUERY_CONSTRUCTOR:
        return RexSubQuery.map(rexSubQuery.rel, correlationId);

      case MULTISET_QUERY_CONSTRUCTOR:
        return RexSubQuery.multiset(rexSubQuery.rel, correlationId);

      default:
        throw new UnsupportedOperationException("Unexpected op: " + rexSubQuery.op);
    }
  }

  private static void fillCorrelateVariableMap(
      Map<RelNode, Set<RexFieldAccess>> mapping, RelNode node) {
    Set<RexFieldAccess> correlatedVariables = new HashSet<>();
    for (RelNode child : node.getInputs()) {
      fillCorrelateVariableMap(mapping, child);
      Set<RexFieldAccess> childCorrelatedVariables = mapping.get(child);
      correlatedVariables.addAll(childCorrelatedVariables);
    }

    Set<RexFieldAccess> parentCorrelatedVariables = CorrelateVariableFinder.find(node);
    correlatedVariables.addAll(parentCorrelatedVariables);
    mapping.put(node, correlatedVariables);
  }

  private static final class CorrelateVariableFinder extends RexShuttle {
    private final Set<RexFieldAccess> correlatedVariables;

    private CorrelateVariableFinder() {
      this.correlatedVariables = new HashSet<>();
    }

    public static Set<RexFieldAccess> find(RelNode node) {
      CorrelateVariableFinder finder = new CorrelateVariableFinder();
      node.accept(finder);
      return finder.correlatedVariables;
    }

    @Override
    public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      RexNode referenceExpr = fieldAccess.getReferenceExpr();
      if (referenceExpr instanceof RexCorrelVariable) {
        correlatedVariables.add(fieldAccess);
      }

      return super.visitFieldAccess(fieldAccess);
    }
  }

  /*
   * Finds RexSubQuery/FieldAccess/CorrelatedVariable in RelNode's expressions.
   */
  public static class RexSubQueryFinder extends RexShuttle {
    private boolean foundRexSubQuery = false;
    private boolean foundFieldAccess = false;
    private boolean foundCorrelVariable = false;

    public boolean getFoundSubQuery() {
      return foundRexSubQuery || foundFieldAccess || foundCorrelVariable;
    }

    @Override
    public RexNode visitSubQuery(RexSubQuery subQuery) {
      foundRexSubQuery = true;
      return super.visitSubQuery(subQuery);
    }

    @Override
    public RexNode visitCorrelVariable(RexCorrelVariable variable) {
      foundCorrelVariable = true;
      return super.visitCorrelVariable(variable);
    }

    @Override
    public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      foundFieldAccess = true;
      return super.visitFieldAccess(fieldAccess);
    }
  }

  /** Calls the DefaultSqlHandler's transform() on each RexSubQuery's subtree. */
  public static class RexSubQueryTransformer extends RexShuttle {

    private final SqlHandlerConfig config;
    private RelTraitSet traitSet;
    private boolean failed = false;

    public boolean isFailed() {
      return failed;
    }

    public RexSubQueryTransformer(SqlHandlerConfig config) {
      this.config = config;
    }

    public void setTraitSet(RelTraitSet traitSet) {
      this.traitSet = traitSet;
    }

    @Override
    public RexNode visitSubQuery(RexSubQuery subQuery) {
      RelNode transformed;
      try {
        transformed =
            PlannerUtil.transform(
                config,
                PlannerType.HEP_AC,
                PlannerPhase.JDBC_PUSHDOWN,
                subQuery.rel,
                traitSet,
                false);

        // We may need to run the planner again on the sub-queries in the sub-tree this produced.
        final RelsWithRexSubQueryTransformer nestedSubqueryTransformer =
            new RelsWithRexSubQueryTransformer(config);
        transformed = transformed.accept(nestedSubqueryTransformer);
        if (!(transformed instanceof JdbcCrel) || nestedSubqueryTransformer.failed()) {
          failed = true;
          return subQuery;
        }
      } catch (Throwable t) {
        failed = true;
        return subQuery;
      }
      return subQuery.clone(((JdbcCrel) transformed).getInput());
    }
  }

  /**
   * Transforms a RelNode with RexSubQuery into JDBC convention. Does so by using {@link
   * RexSubQueryTransformer}.
   */
  public static class RelsWithRexSubQueryTransformer extends StatelessRelShuttleImpl {

    private final RexSubQueryTransformer transformer;

    public RelsWithRexSubQueryTransformer(SqlHandlerConfig config) {
      this.transformer = new RexSubQueryTransformer(config);
    }

    public boolean failed() {
      return transformer.isFailed();
    }

    @Override
    protected RelNode visitChild(RelNode parent, int i, RelNode child) {
      RelNode newParent = parent;
      if (parent instanceof JdbcRelImpl) {
        transformer.setTraitSet(
            parent.getTraitSet().plus(DistributionTrait.ANY).plus(RelCollations.EMPTY));
        newParent = parent.accept(transformer);
      }
      return super.visitChild(newParent, i, newParent.getInput(i));
    }
  }

  /** Finds relnodes that are not of JDBC convention that have RexSubQuery rexnodes */
  public static class FindNonJdbcConventionRexSubQuery {

    public boolean visit(final RelNode node) {
      if (node instanceof JdbcCrel) {
        return false;
      }

      for (RelNode input : node.getInputs()) {
        if (visit(input)) {
          return true;
        }
      }

      final RexSubQueryFinder subQueryFinder = new RexSubQueryFinder();
      node.accept(subQueryFinder);
      if (subQueryFinder.getFoundSubQuery()) {
        return true;
      }

      return false;
    }
  }

  /** Checks that the subquery is of JDBC convention. */
  public static class RexSubQueryPluginIdChecker extends RexShuttle {
    private StoragePluginId pluginId;
    private boolean canPushdownRexSubQuery = true;

    public RexSubQueryPluginIdChecker(StoragePluginId pluginId) {
      this.pluginId = pluginId;
    }

    public boolean canPushdownRexSubQuery() {
      return canPushdownRexSubQuery;
    }

    public StoragePluginId getPluginId() {
      return pluginId;
    }

    @Override
    public RexNode visitSubQuery(RexSubQuery subQuery) {
      RexSubQueryUtils.RexSubQueryPushdownChecker checker =
          new RexSubQueryUtils.RexSubQueryPushdownChecker(pluginId);
      checker.visit(subQuery.rel);

      if (!checker.canPushdownRexSubQuery()) {
        canPushdownRexSubQuery = false;
      }

      // Begin validating against the plugin ID identified by the pushdown checker.
      if (checker.getPluginId() != null) {
        pluginId = checker.getPluginId();
      }
      return super.visitSubQuery(subQuery);
    }
  }

  /**
   * Checks for RexSubQuery rexnodes in the tree, and if there are any, ensures that the underlying
   * table scans for these RexSubQuery nodes all either share one pluginId or have a null pluginId,
   * and that the single unique Plugin ID is from a JDBC data source.
   *
   * <p>Two different non-null pluginIds indicate that the table scans are from different databases
   * and thus the subquery cannot be pushed down.
   */
  public static class RexSubQueryPushdownChecker {

    public RexSubQueryPushdownChecker(StoragePluginId pluginId) {
      this.pluginId = pluginId;
    }

    private StoragePluginId pluginId;
    private boolean canPushdownRexSubQuery = true;
    private boolean foundRexSubQuery = false;

    public boolean canPushdownRexSubQuery() {
      return canPushdownRexSubQuery;
    }

    public boolean foundRexSubQuery() {
      return foundRexSubQuery;
    }

    public StoragePluginId getPluginId() {
      return pluginId;
    }

    /**
     * Inspect the sub-tree starting at this node to see if there is at most one non-null pluginId,
     * and that pluginId supports subquery pushdown.
     *
     * @param node The node to start visiting from.
     * @return True if the given node can be pushed down if it's in a sub-query.
     */
    public boolean visit(final RelNode node) {
      if (node instanceof ScanRelBase) {
        ScanRelBase scan = ((ScanRelBase) node);
        if (!verifyAndUpdatePluginId(scan.getPluginId())) {
          return false;
        }
      }

      for (RelNode input : node.getInputs()) {
        canPushdownRexSubQuery = visit(input) && canPushdownRexSubQuery;
      }

      final RexSubQueryFinder subQueryFinder = new RexSubQueryFinder();
      node.accept(subQueryFinder);
      if (!subQueryFinder.getFoundSubQuery()) {
        return canPushdownRexSubQuery;
      }

      if (subQueryFinder.foundCorrelVariable
          && !pluginId
              .getCapabilities()
              .getCapability(SourceCapabilities.CORRELATED_SUBQUERY_PUSHDOWN)) {
        return false;
      }

      foundRexSubQuery = true;

      // Check that the subquery has the same pluginId as well!
      final RexSubQueryPluginIdChecker subQueryConventionChecker =
          new RexSubQueryPluginIdChecker(pluginId);
      node.accept(subQueryConventionChecker);
      if (!subQueryConventionChecker.canPushdownRexSubQuery()) {
        canPushdownRexSubQuery = false;
        return false;
      }

      return true;
    }

    /**
     * Checks if the given pluginId would allow for sub-query pushdown within the context of this
     * tree.
     *
     * @param pluginId The pluginId to verify.
     * @return True if the pluginId would allow for sub-query pushdown.
     */
    private boolean verifyAndUpdatePluginId(StoragePluginId pluginId) {
      if (!canPushdownRexSubQuery) {
        return false;
      }

      if (this.pluginId == null) {
        this.pluginId = pluginId;
        if (pluginId.getCapabilities().getCapability(SourceCapabilities.SUBQUERY_PUSHDOWNABLE)) {
          return true;
        } else {
          canPushdownRexSubQuery = false;
          return false;
        }
      }

      if (pluginId == null || pluginId.equals(this.pluginId)) {
        return true;
      }

      canPushdownRexSubQuery = false;
      return false;
    }
  }

  public static Set<RexCorrelVariable> collectCorrelatedVariables(RelNode relNode) {
    return collectCorrelatedVariables(relNode, null);
  }

  public static Set<RexCorrelVariable> collectCorrelatedVariables(
      RelNode relNode, CorrelationId correlationId) {
    Set<RexCorrelVariable> rexCorrelVariables = new HashSet<>();
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof HepRelVertex) {
              other = ((HepRelVertex) other).getCurrentRel();
            }

            RelNode visitedRelNode = super.visit(other);
            rexCorrelVariables.addAll(
                collectCorrelateVariablesInRelNode(visitedRelNode, correlationId));
            return visitedRelNode;
          }
        });

    return rexCorrelVariables;
  }

  public static Set<RexCorrelVariable> collectCorrelateVariablesInRelNode(
      RelNode relNode, CorrelationId correlationId) {
    Set<RexCorrelVariable> rexCorrelVariables = new HashSet<>();
    relNode.accept(
        new RexShuttle() {
          @Override
          public RexNode visitCorrelVariable(RexCorrelVariable variable) {
            if (correlationId == null || (variable.id.getId() == correlationId.getId())) {
              rexCorrelVariables.add(variable);
            }

            return super.visitCorrelVariable(variable);
          }

          @Override
          public RexNode visitSubQuery(RexSubQuery subQuery) {
            subQuery.rel.accept(this);
            return super.visitSubQuery(subQuery);
          }
        });

    return rexCorrelVariables;
  }
}
