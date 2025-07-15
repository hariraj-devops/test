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
package com.dremio.service.jobs.metadata;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.lineage.FieldOriginExtractor;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.common.ContainerRel;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.common.VacuumCatalogRelBase;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.logical.TableModifyRel;
import com.dremio.exec.planner.logical.TableOptimizeRel;
import com.dremio.exec.planner.logical.WriterRel;
import com.dremio.exec.planner.sql.TableIdentifierCollector;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.tablefunctions.ExternalQueryRelBase;
import com.dremio.exec.tablefunctions.ExternalQueryScanDrel;
import com.dremio.service.job.proto.JoinInfo;
import com.dremio.service.job.proto.ParentDatasetInfo;
import com.dremio.service.job.proto.ScanPath;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.tools.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A description of information we use to better understand a query. */
public class QueryMetadata {

  private static final Logger logger = LoggerFactory.getLogger(QueryMetadata.class);

  private static final Set<String> RESERVED_PARENT_NAMES =
      ImmutableSet.of("dremio_limited_preview");

  private final Optional<List<SqlIdentifier>> ancestors;
  private final Optional<List<FieldOrigin>> fieldOrigins;
  @Deprecated private final Optional<List<JoinInfo>> joins;
  private final Optional<List<ParentDatasetInfo>> parents;
  private final Optional<SqlNode> sqlNode;
  private final RelDataType rowType;
  private final Optional<List<ParentDataset>> grandParents;
  private final Optional<RelOptCost> cost;
  private final Optional<PlanningSet> planningSet;
  private final Optional<BatchSchema> batchSchema;
  private final List<ScanPath> scanPaths;
  private final String querySql;
  private final List<String> queryContext;
  private final List<String> sourceNames;
  private final List<String> sinkPath;

  QueryMetadata(
      List<SqlIdentifier> ancestors,
      List<FieldOrigin> fieldOrigins,
      List<JoinInfo> joins,
      List<ParentDatasetInfo> parents,
      SqlNode sqlNode,
      RelDataType rowType,
      List<ParentDataset> grandParents,
      final RelOptCost cost,
      final PlanningSet planningSet,
      BatchSchema batchSchema,
      List<ScanPath> scanPaths,
      String querySql,
      List<String> queryContext,
      List<String> sourceNames,
      List<String> sinkPath) {
    this.ancestors = Optional.ofNullable(ancestors);
    this.fieldOrigins = Optional.ofNullable(fieldOrigins);
    this.joins = Optional.ofNullable(joins);
    this.parents = Optional.ofNullable(parents);
    this.sqlNode = Optional.ofNullable(sqlNode);
    this.rowType = rowType;
    this.grandParents = Optional.ofNullable(grandParents);
    this.cost = Optional.ofNullable(cost);
    this.planningSet = Optional.ofNullable(planningSet);
    this.batchSchema = Optional.ofNullable(batchSchema);
    this.scanPaths = scanPaths;
    this.querySql = querySql;
    this.queryContext = queryContext;
    this.sourceNames = sourceNames;
    this.sinkPath = sinkPath;
  }

  @VisibleForTesting
  public Optional<List<String>> getReferredTables() {
    if (!ancestors.isPresent()) {
      return Optional.empty();
    }
    Set<String> tableNames = new HashSet<>();
    for (SqlIdentifier id : ancestors.get()) {
      if (id.names.size() > 0) {
        tableNames.add(id.names.get(id.names.size() - 1));
      }
    }
    return Optional.<List<String>>of(new ArrayList<>(tableNames));
  }

  public Optional<List<ParentDataset>> getGrandParents() {
    return grandParents;
  }

  @VisibleForTesting
  public Optional<SqlNode> getSqlNode() {
    return sqlNode;
  }

  @VisibleForTesting
  public Optional<List<SqlIdentifier>> getAncestors() {
    return ancestors;
  }

  public Optional<List<FieldOrigin>> getFieldOrigins() {
    return fieldOrigins;
  }

  public Optional<List<JoinInfo>> getJoins() {
    return joins;
  }

  public RelDataType getRowType() {
    return rowType;
  }

  public Optional<List<ParentDatasetInfo>> getParents() {
    return parents;
  }

  public Optional<BatchSchema> getBatchSchema() {
    return batchSchema;
  }

  public List<ScanPath> getScanPaths() {
    return scanPaths;
  }

  /** Returns original cost of query past logical planning. */
  public Optional<RelOptCost> getCost() {
    return cost;
  }

  public Optional<PlanningSet> getPlanningSet() {
    return planningSet;
  }

  public String getQuerySql() {
    return querySql;
  }

  public List<String> getQueryContext() {
    return queryContext;
  }

  public List<String> getSourceNames() {
    return sourceNames;
  }

  public List<String> getSinkPath() {
    return sinkPath;
  }

  /**
   * Create a builder for QueryMetadata.
   *
   * @return The builder.
   */
  public static Builder builder(Catalog userCatalog, Catalog systemCatalog) {
    return new Builder(userCatalog, systemCatalog, null);
  }

  public static Builder builder(
      Catalog userCatalog, Catalog systemCatalog, String jobResultsSourceName) {
    return new Builder(userCatalog, systemCatalog, jobResultsSourceName);
  }

  /** A builder to construct query metadata. */
  public static class Builder {

    private static final Logger logger = LoggerFactory.getLogger(Builder.class);

    private final Catalog userCatalog;
    private final Catalog systemCatalog;
    private final String jobResultsSourceName;

    private RelDataType rowType;
    private RelNode logicalBefore;
    private RelNode logicalAfter;
    private RelNode physicalBefore;
    private RelNode prejoin;
    private RelNode expanded;
    private SqlNode sql;
    private RelOptCost cost;
    private PlanningSet planningSet;
    private BatchSchema batchSchema;
    private String querySql;
    private List<String> queryContext;
    private List<String> externalQuerySourceInfo;

    Builder(Catalog userCatalog, Catalog systemCatalog, String jobResultsSourceName) {
      this.userCatalog = userCatalog;
      this.systemCatalog = systemCatalog;
      this.jobResultsSourceName = jobResultsSourceName;
    }

    public Builder addQuerySql(String sql) {
      this.querySql = sql;
      return this;
    }

    public Builder addQueryContext(List<String> context) {
      this.queryContext = context;
      return this;
    }

    public Builder addRowType(RelDataType rowType) {
      this.rowType = rowType;
      return this;
    }

    public Builder addLogicalPlan(RelNode before, RelNode after) {
      this.logicalBefore = before;
      this.logicalAfter = after;
      return this;
    }

    public Builder addPhysicalPlan(RelNode before) {
      this.physicalBefore = before;
      return this;
    }

    public Builder addBatchSchema(BatchSchema schema) {
      this.batchSchema = schema;
      return this;
    }

    public Builder addPreJoinPlan(RelNode rel) {
      this.prejoin = rel;
      return this;
    }

    /**
     * Expanded plan is needed to capture the query's dataset lineage. The lineage needs to be the
     * plan after expansion of views and UDFs but before any reflections.
     */
    public Builder addExpandedPlan(RelNode rel) {
      this.expanded = rel;
      return this;
    }

    public Builder addParsedSql(SqlNode sql) {
      this.sql = sql;
      return this;
    }

    public Builder addCost(final RelOptCost cost) {
      this.cost = cost;
      return this;
    }

    public Builder addSourceNames(final List<String> sourceNames) {
      this.externalQuerySourceInfo = sourceNames;
      return this;
    }

    /** Sets parallelized query plan. */
    public Builder setPlanningSet(final PlanningSet planningSet) {
      this.planningSet = planningSet;
      return this;
    }

    public QueryMetadata build() throws ValidationException {
      Preconditions.checkNotNull(
          rowType, "The validated row type must be observed before reporting metadata.");

      final List<SqlIdentifier> ancestors = new ArrayList<>();
      final List<TableVersionContext> versionContexts = new ArrayList<>();

      if (expanded != null) {
        expanded.accept(
            new RelShuttleImpl() {
              @Override
              public RelNode visit(RelNode other) {
                List<String> path = null;
                if (other instanceof ExpansionNode) {
                  path = ((ExpansionNode) other).getPath().getPathComponents();
                } else if (other instanceof ExternalQueryRelBase) {
                  path = ((ExternalQueryRelBase) other).getPath().getPathComponents();
                }

                if (path != null) {
                  ancestors.add(new SqlIdentifier(path, SqlParserPos.ZERO));
                  versionContexts.add(
                      (other instanceof ExpansionNode)
                          ? ((ExpansionNode) other).getVersionContext()
                          : null);

                  return other;
                }

                return super.visit(other);
              }

              @Override
              public RelNode visit(TableScan scan) {
                ancestors.add(
                    new SqlIdentifier(scan.getTable().getQualifiedName(), SqlParserPos.ZERO));
                versionContexts.add(
                    (scan instanceof ScanRelBase)
                        ? ((ScanRelBase) scan).getTableMetadata().getVersionContext()
                        : null);

                return scan;
              }
            });
      } else if (sql != null) {
        ancestors.addAll(
            TableIdentifierCollector.collect(sql).stream()
                .filter(input -> !RESERVED_PARENT_NAMES.contains(input.toString()))
                .collect(Collectors.toList()));
      }

      List<FieldOrigin> fieldOrigins = null;
      if (expanded != null && rowType != null) {
        try {
          fieldOrigins =
              ImmutableList.copyOf(FieldOriginExtractor.getFieldOrigins(expanded, rowType));
        } catch (Exception e) {
          // If we fail to extract the column origins, don't fail the query
          logger.debug("Failed to extract column origins for query: " + sql);
        }
      }

      // Make sure there are no duplicate column names
      SqlHandlerUtil.validateRowType(true, Lists.<String>newArrayList(), rowType);

      List<ScanPath> scanPaths = null;
      if (logicalAfter != null) {
        scanPaths = getScans(logicalAfter);
        externalQuerySourceInfo = getExternalQuerySources(logicalAfter);
      }

      List<String> sinkPath = null;
      if (physicalBefore != null && jobResultsSourceName != null) {
        sinkPath = getSinkPath(physicalBefore, jobResultsSourceName);
      }

      return new QueryMetadata(
          ancestors, // list of parents
          fieldOrigins,
          null,
          getParentsFromSql(ancestors, versionContexts), // convert parent to ParentDatasetInfo
          sql,
          rowType,
          getGrandParents(ancestors), // list of all parents to be stored with dataset
          cost, // query cost past logical
          planningSet,
          batchSchema,
          scanPaths,
          querySql,
          queryContext,
          externalQuerySourceInfo,
          sinkPath);
    }

    /**
     * Return list of all parents for given dataset
     *
     * @param parents parents of dataset from sql.
     * @throws NamespaceException
     */
    private List<ParentDataset> getGrandParents(List<SqlIdentifier> parents) {
      if (parents == null) {
        return null;
      }

      final Map<NamespaceKey, Integer> parentsToLevelMap = Maps.newHashMap();
      final List<NamespaceKey> parentKeys = Lists.newArrayList();
      final List<ParentDataset> grandParents = Lists.newArrayList();

      for (SqlIdentifier parent : parents) {
        final NamespaceKey parentKey = new NamespaceKey(parent.names);
        parentsToLevelMap.put(parentKey, 1);
        parentKeys.add(parentKey);
      }

      // add parents of parents.
      if (!parentKeys.isEmpty()) {
        for (NameSpaceContainer container : userCatalog.getEntities(parentKeys)) {
          if (container != null && container.getType() == Type.DATASET) { // missing parent
            if (container.getDataset() != null) {
              final VirtualDataset virtualDataset = container.getDataset().getVirtualDataset();
              if (virtualDataset != null) {
                if (virtualDataset.getParentsList() != null) {
                  // add parents of parents
                  for (ParentDataset parentDataset : virtualDataset.getParentsList()) {
                    final NamespaceKey parentKey =
                        new NamespaceKey(parentDataset.getDatasetPathList());
                    if (!parentsToLevelMap.containsKey(parentKey)) {
                      parentsToLevelMap.put(parentKey, parentDataset.getLevel() + 1);
                    }
                  }
                  // add grand parents of parent too
                  if (virtualDataset.getGrandParentsList() != null) {
                    for (ParentDataset grandParentDataset : virtualDataset.getGrandParentsList()) {
                      final NamespaceKey parentKey =
                          new NamespaceKey(grandParentDataset.getDatasetPathList());
                      if (!parentsToLevelMap.containsKey(parentKey)) {
                        parentsToLevelMap.put(parentKey, grandParentDataset.getLevel() + 1);
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      for (Map.Entry<NamespaceKey, Integer> entry : parentsToLevelMap.entrySet()) {
        if (entry.getValue() > 1) {
          grandParents.add(
              new ParentDataset()
                  .setDatasetPathList(entry.getKey().getPathComponents())
                  .setLevel(entry.getValue()));
        }
      }
      return grandParents;
    }

    /**
     * Return lists of {@link ParentDatasetInfo} from given list of directly referred tables in the
     * query.
     *
     * @return The list of directly referenced virtual or physical datasets
     */
    private List<ParentDatasetInfo> getParentsFromSql(
        List<SqlIdentifier> ancestors, List<TableVersionContext> versionContexts) {
      if (ancestors == null) {
        return null;
      }

      try {
        final List<ParentDatasetInfo> result = new ArrayList<>();
        for (SqlIdentifier sqlIdentifier : ancestors) {
          final NamespaceKey datasetPath = new NamespaceKey(sqlIdentifier.names);
          result.add(getDataset(datasetPath));
        }

        if (ancestors.size() != versionContexts.size()) {
          logger.warn(
              "The ancestors size {} doesn't equal to versionContexts size {}",
              ancestors.size(),
              versionContexts.size());

          return result;
        }

        final int versionContextsSize = versionContexts.size();

        for (int index = 0; index < versionContextsSize; ++index) {
          final TableVersionContext versionContext = versionContexts.get(index);
          if (versionContext == null) {
            continue;
          }

          final List<String> datasetPath = result.get(index).getDatasetPathList();
          final CatalogEntityKey catalogEntityKey =
              CatalogEntityKey.newBuilder()
                  .keyComponents(datasetPath)
                  .tableVersionContext(versionContext)
                  .build();
          final DatasetType datasetType = systemCatalog.getDatasetType(catalogEntityKey);

          result.get(index).setType(datasetType);
          result.get(index).setVersionContext(versionContext.serialize());
        }

        return result;
      } catch (Throwable e) {
        logger.warn(
            "Failure while attempting to extract parents from dataset. This is likely due to  "
                + "a datasource no longer being available that was used in a past job.",
            e);
        return Collections.emptyList();
      }
    }

    private ParentDatasetInfo getDataset(NamespaceKey path) {
      // fallback
      String rootEntityName = path.getRoot();
      List<String> cleanedPathComponents = Lists.newArrayList();

      if (rootEntityName.indexOf(PathUtils.getPathDelimiter()) > -1) {
        final List<String> spacePathComponents = PathUtils.parseFullPath(path.getRoot());
        cleanedPathComponents.addAll(spacePathComponents);
        List<String> pathComponents = path.getPathComponents();
        for (String folderName : pathComponents.subList(1, pathComponents.size())) {
          cleanedPathComponents.add(folderName);
        }
        rootEntityName = spacePathComponents.get(0);
      } else {
        cleanedPathComponents.addAll(path.getPathComponents());
      }

      // try the original path and then try the cleaned path.
      for (List<String> paths : Arrays.asList(path.getPathComponents(), cleanedPathComponents)) {
        List<NameSpaceContainer> containers =
            userCatalog.getEntities(Collections.singletonList(new NamespaceKey(paths)));
        if (!containers.isEmpty()) {
          final NameSpaceContainer container = containers.get(0);
          if (container != null && container.getType() == Type.DATASET) {
            DatasetConfig config = container.getDataset();
            return new ParentDatasetInfo()
                .setDatasetPathList(config.getFullPathList())
                .setType(config.getType());
          }
        }
      }

      // TODO we couldn't find a dataset corresponding to path, should we throw an exception instead
      // ??
      return new ParentDatasetInfo().setDatasetPathList(cleanedPathComponents);
    }
  }

  /**
   * Retrieves a list of source names referenced in the DatasetConfig.
   *
   * @param datasetConfig the DatasetConfig to inspect.
   * @return a list of source names found referenced in the DatasetConfig.
   */
  public static List<String> getSources(Catalog userCatalog, DatasetConfig datasetConfig) {
    return getSources(
        userCatalog,
        datasetConfig.getType(),
        datasetConfig.getVirtualDataset(),
        datasetConfig.getFullPathList());
  }

  /**
   * Retrieves a list of source names referenced in the DatasetMetadata.
   *
   * @param datasetMetadata the DatasetMetadata to inspect.
   * @return a list of source names found referenced in the DatasetMetadata.
   */
  public static List<String> getSources(Catalog userCatalog, DatasetMetadata datasetMetadata) {
    return getSources(
        userCatalog,
        datasetMetadata.getType(),
        datasetMetadata.getVirtualDataset(),
        datasetMetadata.getFullPathList());
  }

  private static List<String> getSources(
      Catalog userCatalog,
      DatasetType datasetType,
      VirtualDataset virtualDataset,
      List<String> fullPath) {
    if (datasetType == null) {
      return List.of();
    }
    switch (datasetType) {
      case VIRTUAL_DATASET:
        if (virtualDataset.getParentsList() == null) {
          return List.of();
        }
        return virtualDataset.getParentsList().stream()
            .map(ParentDataset::getDatasetPathList)
            .map(NamespaceKey::new)
            .map(userCatalog::getUpstreamSources)
            .flatMap(List::stream)
            .distinct()
            .collect(Collectors.toList());
      case PHYSICAL_DATASET: // Intentional fall-through
      case PHYSICAL_DATASET_SOURCE_FILE: // Intentional fall-through
      case PHYSICAL_DATASET_SOURCE_FOLDER: // Intentional fall-through
      case PHYSICAL_DATASET_HOME_FILE: // Intentional fall-through
      case PHYSICAL_DATASET_HOME_FOLDER:
        if (fullPath != null && !fullPath.isEmpty()) {
          return List.of(fullPath.get(0));
        } else {
          return List.of();
        }
      case INVALID_DATASET_TYPE: // Intentional fall-through
      case OTHERS: // Intentional fall-through
      default:
        throw new IllegalStateException("Unexpected value: " + datasetType);
    }
  }

  public static List<ScanPath> getScans(RelNode logicalPlan) {
    final ImmutableList.Builder<ScanPath> builder = ImmutableList.builder();
    logicalPlan.accept(
        new StatelessRelShuttleImpl() {
          @Override
          public RelNode visit(final TableScan scan) {
            ScanPath path = new ScanPath().setPathList(scan.getTable().getQualifiedName());
            TableVersionContext versionContext =
                ((ScanRelBase) scan).getTableMetadata().getVersionContext();
            if (versionContext != null) {
              path.setVersionContext(versionContext.serialize());
            }
            // If scanning an iceberg table, save the snapshot ID to the scanpath, so it can be used
            // in the DependencyGraph for reflections
            IcebergMetadata icebergMetadata =
                ((ScanRelBase) scan)
                    .getTableMetadata()
                    .getDatasetConfig()
                    .getPhysicalDataset()
                    .getIcebergMetadata();
            if (icebergMetadata != null) {
              path.setSnapshotId(icebergMetadata.getSnapshotId());
            }
            builder.add(path);
            return super.visit(scan);
          }

          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof ContainerRel) {
              ContainerRel containerRel = (ContainerRel) other;
              containerRel.getSubTree().accept(this);
            }
            return super.visit(other);
          }
        });
    return builder.build();
  }

  public static List<String> getSinkPath(RelNode logicalPlan, String jobResultsSourceName) {
    final class SinkFinder extends StatelessRelShuttleImpl {
      private List<String> sinkPath = null;

      @Override
      public RelNode visit(RelNode other) {

        if (other instanceof WriterRel) {
          final NamespaceKey namespaceKey =
              ((WriterRel) other).getCreateTableEntry().getDatasetPath();
          setSinkPath(namespaceKey);
        }
        if (other instanceof TableModifyRel) {
          final NamespaceKey namespaceKey =
              ((TableModifyRel) other).getCreateTableEntry().getDatasetPath();
          setSinkPath(namespaceKey);
        }
        if (other instanceof TableOptimizeRel) {
          final NamespaceKey namespaceKey =
              ((TableOptimizeRel) other).getCreateTableEntry().getDatasetPath();
          setSinkPath(namespaceKey);
        }
        if (other instanceof VacuumCatalogRelBase) {
          final NamespaceKey sourceName =
              new NamespaceKey(((VacuumCatalogRelBase) other).getSourceName());
          setSinkPath(sourceName);
        }
        return super.visit(other);
      }

      private void setSinkPath(NamespaceKey namespaceKey) {
        final String sourceName = namespaceKey.getRoot();
        if (!sourceName.equals(jobResultsSourceName)) {
          sinkPath = namespaceKey.getPathComponents();
        }
      }
    }

    final SinkFinder sinkFinder = new SinkFinder();
    logicalPlan.accept(sinkFinder);
    return sinkFinder.sinkPath;
  }

  /*
   * extracting external query source name, plus the sql string for
   * reflection dependency
   */
  public static List<String> getExternalQuerySources(RelNode logicalAfter) {
    final ImmutableList.Builder<String> builder = ImmutableList.builder();
    logicalAfter.accept(
        new StatelessRelShuttleImpl() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof ExternalQueryScanDrel) {
              ExternalQueryScanDrel drel = (ExternalQueryScanDrel) other;
              builder.add(drel.getPluginId().getConfig().getName());
              builder.add(drel.getSql());
            }
            return super.visit(other);
          }
        });
    return builder.build();
  }
}
