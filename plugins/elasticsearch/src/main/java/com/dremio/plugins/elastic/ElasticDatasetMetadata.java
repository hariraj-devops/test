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
package com.dremio.plugins.elastic;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetStats;
import com.dremio.elastic.proto.ElasticReaderProto.ElasticTableXattr;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.planner.cost.ScanCostFactor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.store.SampleMutator;
import com.dremio.exec.vector.complex.fn.WorkingBuffer;
import com.dremio.plugins.elastic.ElasticActions.Result;
import com.dremio.plugins.elastic.execution.ElasticsearchRecordReader;
import com.dremio.plugins.elastic.execution.FieldReadDefinition;
import com.dremio.plugins.elastic.mapping.FieldAnnotation;
import com.dremio.plugins.elastic.mapping.SchemaMerger;
import com.dremio.plugins.elastic.mapping.SchemaMerger.MergeResult;
import com.dremio.plugins.elastic.planning.ElasticsearchScanSpec;
import com.dremio.sabot.exec.context.OperatorContextImpl;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Representation of datasetMetadata for the elastic plugin.
 *
 * <p>Contains methods to build datasetMetadata if it doesn't already exist.
 */
public class ElasticDatasetMetadata implements DatasetMetadata {
  private static final Logger logger = LoggerFactory.getLogger(ElasticDatasetMetadata.class);

  private static final Joiner RESOURCE_JOINER = Joiner.on('/');

  private static final int SAMPLE_FETCH_SIZE = 4000;

  private final BatchSchema oldSchema;

  private ElasticTableXattr extended;

  private DatasetMetadata datasetMetadata;
  private final ElasticPartitionChunkListing partitionChunkListing;
  private final ElasticDatasetHandle datasetHandle;
  private ElasticVersionBehaviorProvider elasticVersionBehaviorProvider;

  public ElasticDatasetMetadata(
      BatchSchema oldSchema,
      ElasticDatasetHandle datasetHandle,
      ElasticPartitionChunkListing listing) {
    this.partitionChunkListing = listing;
    this.datasetHandle = datasetHandle;

    this.oldSchema = oldSchema;
    this.elasticVersionBehaviorProvider =
        new ElasticVersionBehaviorProvider(datasetHandle.getConnection().getESVersionInCluster());
  }

  /**
   * if datasetMetadata doesn't already exist, build it from existing attributes
   *
   * @throws ConnectorException
   */
  public void build() throws ConnectorException {
    if (datasetMetadata != null) {
      return;
    }

    final SchemaMerger merger = new SchemaMerger(datasetHandle.getDatasetPath().toString());
    final boolean forceDoublePrecision = datasetHandle.getPluginConfig().isForceDoublePrecision();
    final MergeResult mergeResult =
        merger.merge(datasetHandle.getMapping(), oldSchema, forceDoublePrecision);
    // sample (whether we have seen stuff before or not). We always sample to improve understanding
    // of list fields that may occur.
    BatchSchema sampledSchema =
        getSampledSchema(
            mergeResult.getSchema(),
            FieldAnnotation.getAnnotationMap(mergeResult.getAnnotations()));
    final MergeResult mergeSampleSchemaResult =
        merger.merge(datasetHandle.getMapping(), sampledSchema, forceDoublePrecision);

    final ElasticTableXattr.Builder tableAttributesB =
        ElasticTableXattr.newBuilder()
            .addAllAnnotation(mergeSampleSchemaResult.getAnnotations())
            .setMappingHash(datasetHandle.getMapping().hashCode())
            .setVariationDetected(datasetHandle.getMapping().isVariationDetected())
            .setResource(
                RESOURCE_JOINER.join(
                    partitionChunkListing.getIndexOrAlias(), partitionChunkListing.getTypeName()));

    if (datasetHandle.isAlias()) {
      String aliasFilter = getAliasFilter();
      if (aliasFilter != null) {
        tableAttributesB.setAliasFilter(aliasFilter);
      }
    }

    extended = tableAttributesB.build();

    final BatchSchema finalMergedSchema = mergeSampleSchemaResult.getSchema().removeNullFields();
    logger.debug(
        "Final merged schema for {}: {}",
        datasetHandle.getDatasetPath().toString(),
        finalMergedSchema);
    this.datasetMetadata =
        DatasetMetadata.of(
            DatasetStats.of(
                partitionChunkListing.getRowCount(), false, ScanCostFactor.ELASTIC.getFactor()),
            finalMergedSchema,
            Collections.emptyList(),
            Collections.emptyList(),
            os -> extended.writeTo(os));
  }

  private BatchSchema getSampledSchema(
      BatchSchema schema, Map<SchemaPath, FieldAnnotation> annotations) throws ConnectorException {
    final ElasticsearchScanSpec spec =
        new ElasticsearchScanSpec(
            partitionChunkListing.getIndexOrAlias() + "/" + partitionChunkListing.getTypeName(),
            null /* match all */,
            SAMPLE_FETCH_SIZE,
            false);

    try (BufferAllocator sampleAllocator =
            datasetHandle
                .getContext()
                .getAllocator()
                .newChildAllocator("sample-alloc", 0, Long.MAX_VALUE);
        OperatorContextImpl operatorContext =
            new OperatorContextImpl(
                datasetHandle.getContext().getConfig(),
                datasetHandle.getContext().getDremioConfig(),
                sampleAllocator,
                datasetHandle.getContext().getOptionManager(),
                SAMPLE_FETCH_SIZE,
                datasetHandle.getContext().getExpressionSplitCache())) {

      WorkingBuffer buffer = new WorkingBuffer(operatorContext.getManagedBuffer());
      final int maxCellSize =
          Math.toIntExact(
              operatorContext.getOptions().getOption(ExecConstants.LIMIT_FIELD_SIZE_BYTES));
      final boolean forceDoublePrecision = datasetHandle.getPluginConfig().isForceDoublePrecision();
      final FieldReadDefinition readDefinition =
          FieldReadDefinition.getTree(
              schema,
              annotations,
              buffer,
              maxCellSize,
              elasticVersionBehaviorProvider,
              forceDoublePrecision);
      try (final ElasticsearchRecordReader reader =
              new ElasticsearchRecordReader(
                  null,
                  null,
                  null,
                  operatorContext,
                  spec,
                  false,
                  null /*split*/,
                  datasetHandle.getConnection(),
                  GroupScan.ALL_COLUMNS,
                  readDefinition,
                  datasetHandle.getPluginConfig());
          final SampleMutator mutator = new SampleMutator(sampleAllocator)) {

        schema.materializeVectors(GroupScan.ALL_COLUMNS, mutator);
        reader.setup(mutator);
        reader.next();
        mutator.getContainer().buildSchema(SelectionVectorMode.NONE);
        final BatchSchema sampledSchema = mutator.getContainer().getSchema();
        logger.debug(
            "Sample for {}.\nOriginal schema: {} \nSampled Schema: {}",
            datasetHandle.getDatasetPath().toString(),
            schema,
            sampledSchema);
        return sampledSchema;
      }

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new ConnectorException(e);
    }
  }

  private String getAliasFilter() {
    String filter = null;
    for (String alias : partitionChunkListing.getIndexOrAlias().split(",")) {
      String filterA = getAliasFilter(alias);
      if (filter == null) {
        filter = filterA;
      } else if (!filter.equals(filterA)) {
        // filter don't match, fail for now.
        throw UserException.validationError()
            .message("Unable to access a collection of aliases with differing filters.")
            .build(logger);
      }
    }
    return filter;
  }

  private String getAliasFilter(String name) {

    Result aliasResult =
        datasetHandle
            .getConnection()
            .executeAndHandleResponseCode(
                new ElasticActions.CatAlias(name),
                false,
                "Cannot get metadata for alias" + datasetHandle.isAlias());

    if (!aliasResult.success()) {
      // if we were unable to probe the alias, then it is dynamic alias (a wildcard query)
      return null;
    }

    JsonObject aliasObject = aliasResult.getAsJsonObject();
    if (0 == aliasObject.size()) {
      // It seems that ES 6.x return a successful result with no aliases, so verify the results.
      return null;
    }
    JsonObject firstIndex = aliasObject.entrySet().iterator().next().getValue().getAsJsonObject();
    JsonObject aliasesObject = firstIndex.getAsJsonObject("aliases");
    JsonObject aliasObject2 = aliasesObject.getAsJsonObject(name);
    if (aliasObject2 == null) {
      return null;
    }
    JsonObject filterObject = aliasObject2.getAsJsonObject("filter");
    if (filterObject == null) {
      return null;
    }
    return filterObject.toString();
  }

  @Override
  public DatasetStats getDatasetStats() {
    Preconditions.checkNotNull(datasetMetadata);

    return datasetMetadata.getDatasetStats();
  }

  @Override
  public Schema getRecordSchema() {
    Preconditions.checkNotNull(datasetMetadata);

    return datasetMetadata.getRecordSchema();
  }

  @Override
  public BytesOutput getExtraInfo() {
    Preconditions.checkNotNull(datasetMetadata);

    return datasetMetadata.getExtraInfo();
  }
}
