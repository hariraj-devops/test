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
package com.dremio.exec.catalog;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.datastore.SearchTypes;
import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.NamespaceTable;
import com.dremio.exec.store.NamespaceTable.StatisticImpl;
import com.dremio.exec.store.TableMetadata;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.PartitionChunk;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Schema.TableType;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

/** DatasetTable that is used for table with options. */
public class MaterializedDatasetTable implements DremioTable {

  private final NamespaceKey canonicalPath;
  private final Supplier<DatasetConfig> datasetConfig;
  private final Supplier<List<PartitionChunk>> partitionChunks;
  private final StoragePluginId pluginId;
  private final String user;
  private final boolean complexTypeSupport;
  private final TableVersionContext versionContext;

  private final List<RelDataTypeField> extendedFields;
  private DatasetConfig savedDatasetconfig;

  public MaterializedDatasetTable(
      NamespaceKey canonicalPath,
      StoragePluginId pluginId,
      String user,
      Supplier<DatasetConfig> datasetConfig,
      Supplier<List<PartitionChunk>> partitionChunks,
      boolean complexTypeSupport,
      TableVersionContext versionContext,
      List<RelDataTypeField> extendedFields) {
    this.canonicalPath = canonicalPath;
    this.pluginId = pluginId;
    this.datasetConfig = datasetConfig;
    this.partitionChunks = partitionChunks;
    this.user = user;
    this.complexTypeSupport = complexTypeSupport;
    this.versionContext = versionContext;
    this.extendedFields = new ArrayList<>(extendedFields);
    this.savedDatasetconfig = null;
  }

  private void setDatasetConfig(DatasetConfig datasetConfig) {
    savedDatasetconfig = datasetConfig;
  }

  @Override
  public NamespaceKey getPath() {
    return canonicalPath;
  }

  @Override
  public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
    return new ScanCrel(
        context.getCluster(),
        context.getCluster().traitSetOf(Convention.NONE),
        pluginId,
        new MaterializedTableMetadata(
            pluginId, datasetConfig.get(), user, partitionChunks.get(), versionContext),
        null,
        1.0d,
        ImmutableList.of(),
        true,
        true);
  }

  @Override
  public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    return CalciteArrowHelper.wrap(CalciteArrowHelper.fromDataset(getDatasetConfig()))
        .toCalciteRecordType(
            typeFactory,
            (Field f) -> !NamespaceTable.SYSTEM_COLUMNS.contains(f.getName()),
            complexTypeSupport);
  }

  @Override
  public Statistic getStatistic() {
    return new StatisticImpl() {
      @Override
      public Double getRowCount() {
        return (double) datasetConfig.get().getReadDefinition().getScanStats().getRecordCount();
      }

      @Override
      public List<RelReferentialConstraint> getReferentialConstraints() {
        return ImmutableList.of();
      }
    };
  }

  @Override
  public TableType getJdbcTableType() {
    return TableType.TABLE;
  }

  @Override
  public String getVersion() {
    return getDatasetConfig().getTag();
  }

  @Override
  public BatchSchema getSchema() {
    return BatchSchema.deserialize((getDatasetConfig().getRecordSchema()));
  }

  @Override
  public DatasetConfig getDatasetConfig() {
    return datasetConfig.get();
  }

  @Override
  public boolean isRolledUp(String column) {
    return false;
  }

  @Override
  public boolean rolledUpColumnValidInsideAgg(
      String column, SqlCall call, SqlNode parent, CalciteConnectionConfig config) {
    return true;
  }

  @Override
  public TableMetadata getDataset() {
    return new MaterializedTableMetadata(
        pluginId, datasetConfig.get(), user, partitionChunks.get(), versionContext);
  }

  private static class MaterializedTableMetadata extends TableMetadataImpl {

    private final TableVersionContext versionContext;

    public MaterializedTableMetadata(
        StoragePluginId plugin,
        DatasetConfig config,
        String user,
        List<PartitionChunk> splits,
        TableVersionContext versionContext) {
      super(
          plugin,
          config,
          user,
          MaterializedSplitsPointer.oldObsoleteOf(getSplitVersion(config), splits, splits.size()),
          null);
      this.versionContext = versionContext;
    }

    private static long getSplitVersion(DatasetConfig datasetConfig) {
      return Optional.ofNullable(datasetConfig)
          .map(DatasetConfig::getReadDefinition)
          .map(ReadDefinition::getSplitVersion)
          .orElse(0L);
    }

    @Override
    public TableMetadata prune(SearchTypes.SearchQuery partitionFilterQuery) {
      // Don't prune based on lucene query
      return this;
    }

    @Override
    public TableVersionContext getVersionContext() {
      return versionContext;
    }
  }

  @Override
  public Table extend(List<RelDataTypeField> fields) {
    boolean tryingToExtendExistingField =
        getSchema().getFields().stream()
            .anyMatch(
                field ->
                    fields.stream()
                        .anyMatch(
                            extendingField -> extendingField.getName().equals(field.getName())));
    if (tryingToExtendExistingField) {
      return this;
    }

    return new MaterializedDatasetTable(
        canonicalPath,
        pluginId,
        user,
        datasetConfig,
        partitionChunks,
        complexTypeSupport,
        versionContext,
        fields) {
      private BatchSchema schema;
      private DatasetConfig extendedDatasetConfig;

      @Override
      public BatchSchema getSchema() {
        if (schema == null) {
          schema =
              BatchSchema.deserialize((datasetConfig.get().getRecordSchema()))
                  .cloneWithFields(
                      fields.stream()
                          .map(
                              field ->
                                  CalciteArrowHelper.fieldFromCalciteRowType(
                                          field.getName(), field.getType())
                                      .get())
                          .collect(ImmutableList.toImmutableList()));
          return schema;
        }

        return schema;
      }

      @Override
      public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {

        return new ScanCrel(
            context.getCluster(),
            context.getCluster().traitSetOf(Convention.NONE),
            pluginId,
            getDataset(),
            null,
            1.0d,
            ImmutableList.of(),
            true,
            true);
      }

      /**
       * The original datasetConfig should not be touched, however the extended materialized table
       * should have the Extended DatasetConfig as tableMetaData.
       *
       * @return MaterializedTableMetadata with extended DatasetConfig
       */
      @Override
      public TableMetadata getDataset() {
        return new MaterializedTableMetadata(
            pluginId, getExtendedDatasetConfig(), user, partitionChunks.get(), versionContext);
      }

      private DatasetConfig getExtendedDatasetConfig() {
        if (extendedDatasetConfig == null) {
          extendedDatasetConfig = new DatasetConfig();
          extendedDatasetConfig.setVirtualDataset(datasetConfig.get().getVirtualDataset());
          extendedDatasetConfig.setPhysicalDataset(datasetConfig.get().getPhysicalDataset());
          extendedDatasetConfig.setTag(datasetConfig.get().getTag());
          extendedDatasetConfig.setId(datasetConfig.get().getId());
          extendedDatasetConfig.setDatasetFieldsList(datasetConfig.get().getDatasetFieldsList());
          extendedDatasetConfig.setCreatedAt(datasetConfig.get().getCreatedAt());
          extendedDatasetConfig.setEngineName(datasetConfig.get().getEngineName());
          extendedDatasetConfig.setFullPathList(datasetConfig.get().getFullPathList());
          extendedDatasetConfig.setLastModified(datasetConfig.get().getLastModified());
          extendedDatasetConfig.setName(datasetConfig.get().getName());
          extendedDatasetConfig.setOwner(datasetConfig.get().getOwner());
          extendedDatasetConfig.setType(datasetConfig.get().getType());
          extendedDatasetConfig.setReadDefinition(datasetConfig.get().getReadDefinition());
          extendedDatasetConfig.setQueueId(datasetConfig.get().getQueueId());
          extendedDatasetConfig.setSchemaVersion(datasetConfig.get().getSchemaVersion());
          extendedDatasetConfig.setTotalNumSplits(datasetConfig.get().getTotalNumSplits());
          extendedDatasetConfig.setRecordSchema(getSchema().toByteString());
        }
        return extendedDatasetConfig;
      }

      /**
       * Return the extended columns with FILEPATH column and ROW_INDEX column
       *
       * @param relDataTypeFactory
       * @return Columns with extended fields
       */
      @Override
      public RelDataType getRowType(RelDataTypeFactory relDataTypeFactory) {
        return CalciteArrowHelper.wrap(getSchema())
            .toCalciteRecordType(
                relDataTypeFactory,
                (Field f) -> !NamespaceTable.SYSTEM_COLUMNS.contains(f.getName()),
                complexTypeSupport);
      }
    };
  }

  @Override
  public int getExtendedColumnOffset() {
    return getSchema().getFieldCount() - extendedFields.size();
  }

  @Override
  public String getExtendTableSql() {
    if (extendedFields.isEmpty()) {
      return "";
    }

    return String.format(
        "EXTEND (%s)",
        extendedFields.stream()
            .map(field -> String.format("\"%s\" %s", field.getName(), field.getType()))
            .collect(Collectors.joining(", ")));
  }

  @Override
  public boolean hasAtSpecifier() {
    return true;
  }
}
