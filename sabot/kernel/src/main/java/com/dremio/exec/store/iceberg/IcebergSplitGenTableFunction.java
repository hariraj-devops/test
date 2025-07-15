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

import static com.dremio.exec.store.iceberg.IcebergUtils.writeSplitIdentity;
import static com.dremio.exec.store.iceberg.model.IcebergConstants.FILE_VERSION;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.AutoCloseables;
import com.dremio.common.expression.BasePath;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.physical.config.SplitProducerTableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.BlockBasedSplitGenerator;
import com.dremio.exec.store.SplitAndPartitionInfo;
import com.dremio.exec.store.SplitIdentity;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.dfs.AbstractTableFunction;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.impl.NullableStructWriter;
import org.apache.arrow.vector.util.TransferPair;

/** Table function implementation which generates splits for input data files. */
public class IcebergSplitGenTableFunction extends AbstractTableFunction {
  private final BlockBasedSplitGenerator splitGenerator;

  private VarCharVector inputDataFilePath;
  private BigIntVector inputDataFileGroupIndex;
  private BigIntVector inputFileSize;
  private VarBinaryVector inputPartitionInfo;
  private StructVector outputSplitIdentity;
  private VarBinaryVector outputSplits;
  private List<TransferPair> transfers;
  private int inputIndex;
  private String dataFilePath;
  private Long dataFileGroupIndex;
  private long fileSize;
  private PartitionProtobuf.NormalizedPartitionInfo partitionInfo;
  private ArrowBuf buf;
  private long currentDataFileOffset;
  private final FileType fileType;

  public IcebergSplitGenTableFunction(
      FragmentExecutionContext fragmentExecutionContext,
      OperatorContext context,
      TableFunctionConfig functionConfig) {
    super(context, functionConfig);
    TableFunctionContext functionContext = functionConfig.getFunctionContext();
    fileType = getFileType(functionContext);
    byte[] extendedProperty =
        functionContext.getExtendedProperty() != null
            ? functionContext.getExtendedProperty().toByteArray()
            : null;
    SupportsIcebergRootPointer plugin =
        IcebergUtils.getSupportsIcebergRootPointerPlugin(
            fragmentExecutionContext, functionContext.getPluginId());

    boolean isOneSplitPerFile =
        functionConfig
            .getFunctionContext(SplitProducerTableFunctionContext.class)
            .isOneSplitPerFile();

    splitGenerator =
        new BlockBasedSplitGenerator(
            context,
            plugin,
            extendedProperty,
            functionContext.isConvertedIcebergDataset(),
            isOneSplitPerFile);
  }

  /**
   * Extract file format from TableFunctionContext Special handle cases where it is missing or cases
   * where it is FileType such as ICEBERG
   */
  @VisibleForTesting
  static FileType getFileType(TableFunctionContext functionContext) {
    FileType result = null;
    if (functionContext.getFormatSettings() != null
        && functionContext.getFormatSettings().getType() != null) {
      result = functionContext.getFormatSettings().getType();
    }
    // If missing we return the default which is PARQUET
    // ICEBERG is not a file format that splitGenerator.getSplitAndPartitionInfo accepts
    // We only support Parquet with ICEBERG, so we return Parquet here
    if (result == null || FileType.ICEBERG.equals(result)) {
      result = FileType.PARQUET;
    }
    return result;
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    super.setup(accessible);

    inputDataFilePath =
        (VarCharVector) getVectorFromSchemaPath(incoming, SystemSchemas.DATAFILE_PATH);
    if (incoming.getSchema().findFieldIgnoreCase(SystemSchemas.FILE_GROUP_INDEX).isPresent()) {
      inputDataFileGroupIndex =
          (BigIntVector) getVectorFromSchemaPath(incoming, SystemSchemas.FILE_GROUP_INDEX);
    }
    inputFileSize = (BigIntVector) getVectorFromSchemaPath(incoming, SystemSchemas.FILE_SIZE);
    inputPartitionInfo =
        (VarBinaryVector) getVectorFromSchemaPath(incoming, SystemSchemas.PARTITION_INFO);
    outputSplitIdentity =
        (StructVector) getVectorFromSchemaPath(outgoing, SystemSchemas.SPLIT_IDENTITY);
    outputSplits =
        (VarBinaryVector) getVectorFromSchemaPath(outgoing, SystemSchemas.SPLIT_INFORMATION);
    buf = context.getAllocator().buffer(4096);

    // create transfer pairs for any additional input columns
    transfers =
        Streams.stream(incoming)
            .filter(
                vw ->
                    outgoing
                            .getSchema()
                            .getFieldId(BasePath.getSimple(vw.getValueVector().getName()))
                        != null)
            .map(
                vw ->
                    vw.getValueVector()
                        .makeTransferPair(
                            getVectorFromSchemaPath(outgoing, vw.getValueVector().getName())))
            .collect(Collectors.toList());

    return outgoing;
  }

  @Override
  public void startBatch(int records) {
    outgoing.allocateNew();
  }

  @Override
  public void startRow(int row) throws Exception {
    inputIndex = row;
    dataFilePath = new String(inputDataFilePath.get(row), StandardCharsets.UTF_8);
    if (inputDataFileGroupIndex != null) {
      dataFileGroupIndex = inputDataFileGroupIndex.get(row);
    }
    fileSize = inputFileSize.get(row);
    partitionInfo = IcebergSerDe.deserializeFromByteArray(inputPartitionInfo.get(row));
    if (partitionInfo == null) {
      // create an empty partitionInfo
      partitionInfo =
          PartitionProtobuf.NormalizedPartitionInfo.newBuilder().setId(String.valueOf(1)).build();
    }
  }

  @Override
  public int processRow(int startOutIndex, int maxRecords) throws Exception {
    long version = PathUtils.getQueryParam(dataFilePath, FILE_VERSION, 0L, Long::parseLong);
    int currentOutputCount = 0;
    final List<SplitIdentity> splitsIdentity = new ArrayList<>();
    final String path = PathUtils.withoutQueryParams(dataFilePath);

    List<SplitAndPartitionInfo> splits =
        splitGenerator.getSplitAndPartitionInfo(
            maxRecords,
            partitionInfo,
            path,
            currentDataFileOffset,
            fileSize,
            version,
            fileType.toString(),
            dataFileGroupIndex,
            splitsIdentity);
    currentDataFileOffset = splitGenerator.getCurrentOffset();
    Preconditions.checkState(
        splits.size() == splitsIdentity.size(),
        "Splits count is not same as splits identity count");
    Iterator<SplitAndPartitionInfo> splitsIterator = splits.iterator();
    Iterator<SplitIdentity> splitIdentityIterator = splitsIdentity.iterator();
    NullableStructWriter splitsIdentityWriter = outputSplitIdentity.getWriter();

    while (splitsIterator.hasNext()) {
      writeSplitIdentity(
          splitsIdentityWriter,
          startOutIndex + currentOutputCount,
          splitIdentityIterator.next(),
          buf);
      outputSplits.setSafe(
          startOutIndex + currentOutputCount,
          IcebergSerDe.serializeToByteArray(splitsIterator.next()));

      for (TransferPair transfer : transfers) {
        transfer.copyValueSafe(inputIndex, startOutIndex + currentOutputCount);
      }

      currentOutputCount++;
    }

    int totalRecordCount = startOutIndex + currentOutputCount;
    outgoing.forEach(vw -> vw.getValueVector().setValueCount(totalRecordCount));
    outgoing.setRecordCount(totalRecordCount);

    return currentOutputCount;
  }

  @Override
  public void closeRow() throws Exception {
    currentDataFileOffset = 0;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(buf);
    buf = null;
    super.close();
  }
}
