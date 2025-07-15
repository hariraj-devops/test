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
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.planner.physical.TableFunctionUtil.getDataset;
import static com.dremio.exec.store.SystemSchemas.DATASET_FIELD;
import static com.dremio.exec.store.SystemSchemas.MANIFEST_LIST_PATH;
import static com.dremio.exec.store.SystemSchemas.METADATA_FILE_PATH;
import static com.dremio.exec.store.SystemSchemas.SNAPSHOT_ID;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.expression.BasePath;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.CarryForwardAwareTableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.dfs.AbstractTableFunction;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.MutatorSchemaChangeCallBack;
import com.dremio.sabot.op.scan.ScanOperator.ScanMutator;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.expressions.Expressions;

/** Table function for Iceberg manifest list file scan */
public class ManifestListScanTableFunction extends AbstractTableFunction {
  private final FragmentExecutionContext fragmentExecutionContext;
  private final OperatorStats operatorStats;
  private final OpProps props;

  private SupportsIcebergMutablePlugin icebergMutablePlugin;
  private List<String> tablePath;

  private ScanMutator mutator;
  private MutatorSchemaChangeCallBack callBack = new MutatorSchemaChangeCallBack();

  private IcebergManifestListRecordReader manifestListRecordReader;

  private VarCharVector inputMetadataLocation;
  private VarCharVector inputManifestListLocation;
  private BigIntVector inputSnapshotId;
  private Optional<VarCharVector> inputDataset;

  private int inputIndex;

  public ManifestListScanTableFunction(
      FragmentExecutionContext fragmentExecutionContext,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    super(context, functionConfig);
    this.fragmentExecutionContext = fragmentExecutionContext;
    this.props = props;
    this.operatorStats = context.getStats();
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    super.setup(accessible);

    icebergMutablePlugin =
        fragmentExecutionContext.getStoragePlugin(
            functionConfig.getFunctionContext().getPluginId());
    tablePath = getDataset(functionConfig);

    inputMetadataLocation = (VarCharVector) getVectorFromSchemaPath(incoming, METADATA_FILE_PATH);
    inputManifestListLocation =
        (VarCharVector) getVectorFromSchemaPath(incoming, MANIFEST_LIST_PATH);
    inputSnapshotId = (BigIntVector) getVectorFromSchemaPath(incoming, SNAPSHOT_ID);
    // See if dataset column exists
    inputDataset =
        Streams.stream(incoming)
            .filter(vw -> vw.getValueVector().getName().equals(DATASET_FIELD))
            .findFirst()
            .map(vw -> (VarCharVector) vw.getValueVector());

    VectorContainer outgoing = (VectorContainer) super.setup(incoming);
    this.mutator = new ScanMutator(outgoing, context, callBack);
    this.mutator.allocate();

    return outgoing;
  }

  @Override
  public void startBatch(int records) {
    outgoing.allocateNew();
  }

  @Override
  public void startRow(int row) throws Exception {
    inputIndex = row;
    TableFunctionContext functionContext = functionConfig.getFunctionContext();
    List<String> dataset =
        inputDataset.map(v -> Arrays.asList(new String(v.get(row)).split("\\."))).orElse(tablePath);

    Preconditions.checkState(
        functionContext instanceof CarryForwardAwareTableFunctionContext,
        "CarryForwardAwareTableFunctionContext is expected");
    CarryForwardAwareTableFunctionContext manifestListScanContext =
        (CarryForwardAwareTableFunctionContext) functionContext;
    final String schemeVariate = manifestListScanContext.getSchemeVariate();

    if (!inputManifestListLocation.isNull(inputIndex)) {
      byte[] manifestListPathBytes = inputManifestListLocation.get(inputIndex);
      String manifestListLocation = new String(manifestListPathBytes, StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(manifestListLocation)) {
        manifestListRecordReader =
            new LeanIcebergManifestListRecordReader(
                context,
                manifestListLocation,
                icebergMutablePlugin,
                dataset,
                functionContext.getPluginId().getName(),
                functionContext.getFullSchema(),
                props,
                functionContext.getPartitionColumns(),
                Optional.empty(),
                schemeVariate,
                true);
      }
    } else {
      // Initialize the reader for the current processing snapshot id
      byte[] pathBytes = inputMetadataLocation.get(inputIndex);
      String metadataLocation = new String(pathBytes, StandardCharsets.UTF_8);
      Long snapshotId = inputSnapshotId.get(inputIndex);

      final IcebergExtendedProp icebergExtendedProp =
          new IcebergExtendedProp(
              null, IcebergSerDe.serializeToByteArray(Expressions.alwaysTrue()), snapshotId, null);

      manifestListRecordReader =
          new IcebergManifestListRecordReader(
              context,
              metadataLocation,
              icebergMutablePlugin,
              dataset,
              functionContext.getPluginId().getName(),
              functionContext.getFullSchema(),
              props,
              functionContext.getPartitionColumns(),
              icebergExtendedProp,
              ManifestContentType.ALL,
              false,
              schemeVariate,
              true);
    }
    manifestListRecordReader.setup(mutator);
    operatorStats.addLongStat(TableFunctionOperator.Metric.NUM_SNAPSHOT_IDS, 1L);
  }

  @Override
  public int processRow(int startOutIndex, int maxRecords) throws Exception {
    int outputCount = manifestListRecordReader.nextBatch(startOutIndex, startOutIndex + maxRecords);
    int totalRecordCount = startOutIndex + outputCount;

    if (inputDataset.isPresent()
        && outgoing.getSchema().getFieldId(BasePath.getSimple(DATASET_FIELD)) != null) {
      VarCharVector datasetOutVector =
          (VarCharVector) getVectorFromSchemaPath(outgoing, DATASET_FIELD);
      for (int i = startOutIndex; i < totalRecordCount; i++) {
        datasetOutVector.setSafe(i, inputDataset.get().get(inputIndex));
      }
    }

    outgoing.forEach(vw -> vw.getValueVector().setValueCount(totalRecordCount));
    outgoing.setRecordCount(totalRecordCount);
    return outputCount;
  }

  @Override
  public void close() throws Exception {
    closeRow();
    super.close();
  }

  @Override
  public void closeRow() throws Exception {
    if (manifestListRecordReader != null) {
      manifestListRecordReader.close();
      manifestListRecordReader = null;
    }
  }
}
