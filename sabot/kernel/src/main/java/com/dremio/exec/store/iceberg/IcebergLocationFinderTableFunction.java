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

import static com.dremio.exec.store.SystemSchemas.DATASET_FIELD;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.IcebergLocationFinderFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.dfs.AbstractTableFunction;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.VarCharVector;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;

/** Table function to return the table location by reading the metadata. */
public class IcebergLocationFinderTableFunction extends AbstractTableFunction {

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(IcebergLocationFinderTableFunction.class);
  private VarCharVector datasetVector;
  private VarCharVector metadataLocationVector;
  private VarCharVector tableLocationVector;
  private int inputIndex;
  private boolean hasMoreToConsume;
  private final FragmentExecutionContext fragmentExecutionContext;
  private final OpProps props;
  private final Map<String, String> tablePropertiesSkipCriteria;
  private final boolean continueOnError;
  private SupportsIcebergMutablePlugin storagePlugin;

  public IcebergLocationFinderTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    super(context, functionConfig);
    this.fragmentExecutionContext = fec;
    this.props = props;
    this.tablePropertiesSkipCriteria =
        ((IcebergLocationFinderFunctionContext) functionConfig.getFunctionContext())
            .getTablePropertiesSkipCriteria();
    continueOnError =
        ((IcebergLocationFinderFunctionContext) functionConfig.getFunctionContext())
            .continueOnError();
  }

  @Override
  public VectorAccessible setup(VectorAccessible incoming) throws Exception {
    VectorContainer outgoing = (VectorContainer) super.setup(incoming);
    storagePlugin =
        fragmentExecutionContext.getStoragePlugin(
            functionConfig.getFunctionContext().getPluginId());

    this.datasetVector = (VarCharVector) getVectorFromSchemaPath(incoming, DATASET_FIELD);
    this.metadataLocationVector =
        (VarCharVector) getVectorFromSchemaPath(incoming, SystemSchemas.METADATA_FILE_PATH);
    this.tableLocationVector =
        (VarCharVector) getVectorFromSchemaPath(outgoing, SystemSchemas.TABLE_LOCATION);

    return outgoing;
  }

  @Override
  public void startRow(int row) throws Exception {
    this.inputIndex = row;
    hasMoreToConsume = true;
  }

  @Override
  public int processRow(final int startOutIndex, final int maxRecords) throws Exception {
    if (!this.hasMoreToConsume) {
      return 0;
    }
    byte[] datasetBytes = datasetVector.get(inputIndex);
    List<String> dataset =
        Arrays.asList(new String(datasetBytes, StandardCharsets.UTF_8).split("\\."));
    byte[] metadataLocationBytes = metadataLocationVector.get(inputIndex);
    String metadataLocation = new String(metadataLocationBytes, StandardCharsets.UTF_8);

    try {
      // IO objects are being created based on the dataset value recieved through datasetVector as
      // this can vary between input rows and cannot be supplied through function context alone.
      FileSystem fs =
          storagePlugin.createFS(
              SupportsFsCreation.builder()
                  .userName(props.getUserName())
                  .operatorContext(context)
                  .dataset(dataset));
      FileIO io =
          storagePlugin.createIcebergFileIO(
              fs,
              context,
              dataset,
              functionConfig.getFunctionContext().getPluginId().getName(),
              null);
      TableMetadata tableMetadata = TableMetadataParser.read(io, metadataLocation);
      if (tablePropertiesSkipCriteria.entrySet().stream()
          .anyMatch(
              e ->
                  tableMetadata.properties().containsKey(e.getKey())
                      && e.getValue().equals(tableMetadata.properties().get(e.getKey())))) {
        return 0;
      }
      this.tableLocationVector.setSafe(
          startOutIndex, tableMetadata.location().getBytes(StandardCharsets.UTF_8));
    } catch (UserException | NotFoundException e) {
      if (continueOnError) {
        LOGGER.warn(
            "Skipping table due to exception while getting table location: " + metadataLocation, e);
        return 0;
      } else {
        throw e;
      }
    }
    final int valueCount = startOutIndex + 1;
    outgoing.setAllCount(valueCount);
    this.hasMoreToConsume = false;
    return 1;
  }

  @Override
  public void startBatch(int records) {
    outgoing.allocateNew();
  }

  @Override
  public void closeRow() throws Exception {
    // Do nothing
  }
}
