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

import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_MERGE_ON_READ_SCAN_WITH_EQUALITY_DELETE;
import static com.dremio.exec.store.iceberg.IcebergSerDe.deserializeNameMappingFromJson;
import static com.dremio.exec.store.iceberg.IcebergUtils.createFileIOForIcebergMetadata;
import static com.dremio.exec.store.iceberg.IcebergUtils.getValueFromByteBuffer;
import static com.dremio.exec.store.iceberg.IcebergUtils.isNonAddOnField;
import static com.dremio.exec.store.iceberg.IcebergUtils.writeSplitIdentity;
import static com.dremio.exec.store.iceberg.IcebergUtils.writeToVector;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.SplitIdentity;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.DefaultNameMapping;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.IcebergDatasetXAttr.Builder;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.impl.NullableStructWriter;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ManifestEvaluator;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.types.Type;

/** Manifest list record reader */
public class IcebergManifestListRecordReader implements RecordReader {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(IcebergManifestListRecordReader.class);

  private final BatchSchema schema;
  private final List<String> dataset;
  private final OperatorContext context;
  private final List<String> partitionCols;
  private final Map<String, Integer> partColToKeyMap;
  private final IcebergExtendedProp icebergExtendedProp;

  private byte[] icebergDatasetXAttr;
  protected final SupportsIcebergRootPointer pluginForIceberg;
  private final OpProps props;
  private ArrowBuf tmpBuf;
  private boolean emptyTable;
  private final String datasourcePluginUID;
  private Expression icebergFilterExpression;
  private final String metadataLocation;
  private final ManifestContentType manifestContent;
  private Map<Integer, PartitionSpec> partitionSpecMap;

  private final boolean isInternalIcebergScanTableMetadata;

  private StructVector splitIdentityVector;
  private VarBinaryVector splitInfoVector;
  private VarBinaryVector colIdsVector;
  private VarCharVector outputFilePath;
  private VarCharVector outputFileType;

  protected Iterator<ManifestFile> manifestFileIterator;
  protected Schema icebergTableSchema;
  protected OutputMutator output;
  protected Consumer<Integer> setColIds;
  private final String schemeVariate;
  private Configuration conf;
  private String fsScheme;
  private final boolean appendManifestInfo;

  /***
   * defaultNameMapping contains the default name/id mapping
   * from parquet field name to iceberg field id.
   * In case parquet files dont have field id and the Iceberg table we could use the
   * name/id mapping defined in table property "schema.name-mapping.default"
   */
  protected NameMapping defaultNameMapping;

  public IcebergManifestListRecordReader(
      OperatorContext context,
      String metadataLocation,
      SupportsIcebergRootPointer pluginForIceberg,
      List<String> dataset,
      String dataSourcePluginId,
      BatchSchema fullSchema,
      OpProps props,
      List<String> partitionCols,
      IcebergExtendedProp icebergExtendedProp,
      ManifestContentType manifestContent,
      boolean isInternalIcebergScanTableMetadata,
      String schemeVariate) {
    this(
        context,
        metadataLocation,
        pluginForIceberg,
        dataset,
        dataSourcePluginId,
        fullSchema,
        props,
        partitionCols,
        icebergExtendedProp,
        manifestContent,
        isInternalIcebergScanTableMetadata,
        schemeVariate,
        false);
  }

  public IcebergManifestListRecordReader(
      OperatorContext context,
      String metadataLocation,
      SupportsIcebergRootPointer pluginForIceberg,
      List<String> dataset,
      String dataSourcePluginId,
      BatchSchema fullSchema,
      OpProps props,
      List<String> partitionCols,
      IcebergExtendedProp icebergExtendedProp,
      ManifestContentType manifestContent,
      boolean isInternalIcebergScanTableMetadata,
      String schemeVariate,
      boolean appendManifestInfo) {
    this.metadataLocation = metadataLocation;
    this.context = context;
    this.pluginForIceberg = pluginForIceberg;
    this.dataset = dataset;
    this.datasourcePluginUID = dataSourcePluginId;
    this.schema = fullSchema;
    this.props = props;
    this.partitionCols = partitionCols;
    this.partColToKeyMap =
        partitionCols != null
            ? IntStream.range(0, partitionCols.size())
                .boxed()
                .collect(Collectors.toMap(i -> partitionCols.get(i).toLowerCase(), i -> i))
            : null;
    this.icebergExtendedProp = icebergExtendedProp;
    this.manifestContent = manifestContent;
    this.isInternalIcebergScanTableMetadata = isInternalIcebergScanTableMetadata;
    this.schemeVariate = schemeVariate;
    this.appendManifestInfo = appendManifestInfo;
    try {
      this.icebergFilterExpression =
          IcebergSerDe.deserializeFromByteArray(icebergExtendedProp.getIcebergExpression());
    } catch (IOException e) {
      throw new RuntimeIOException(e, "failed to deserialize Iceberg Expression");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("failed to deserialize Iceberg Expression", e);
    }
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    this.output = output;
    FileIO io = createIO(metadataLocation);
    TableMetadata tableMetadata =
        pluginForIceberg.loadTableMetadata(io, context, dataset, metadataLocation);
    initializeFileSystemScheme(io);
    partitionSpecMap = tableMetadata.specsById();
    final long snapshotId = icebergExtendedProp.getSnapshotId();
    final Snapshot snapshot =
        snapshotId == -1 ? tableMetadata.currentSnapshot() : tableMetadata.snapshot(snapshotId);
    if (snapshot == null) {
      emptyTable = true;
      return;
    }
    icebergTableSchema =
        icebergExtendedProp.getIcebergSchema() != null
            ? IcebergSerDe.deserializedJsonAsSchema(icebergExtendedProp.getIcebergSchema())
            : tableMetadata.schema();

    long numEqualityDeletes =
        snapshot.summary() != null
            ? Long.parseLong(snapshot.summary().getOrDefault("total-equality-deletes", "0"))
            : 0;
    if (numEqualityDeletes > 0
        && !context
            .getOptions()
            .getOption(ENABLE_ICEBERG_MERGE_ON_READ_SCAN_WITH_EQUALITY_DELETE)) {
      throw UserException.unsupportedError()
          .message("Iceberg V2 tables with equality deletes are not supported.")
          .buildSilently();
    }

    String defaultNameMappingJson =
        tableMetadata.property(TableProperties.DEFAULT_NAME_MAPPING, null);
    if (defaultNameMappingJson != null) {
      defaultNameMapping = deserializeNameMappingFromJson(defaultNameMappingJson);
    }

    List<ManifestFile> manifestFileList = filterManifestFiles(getManifests(snapshot, io));
    manifestFileIterator = manifestFileList.iterator();

    initializeDatasetXAttr();
    initializeOutVectors();
  }

  protected void initializeDatasetXAttr() {
    Map<String, Integer> colIdMap =
        manifestContent == ManifestContentType.DELETES
            ? IcebergUtils.getColIDMapWithReservedDeleteFields(icebergTableSchema)
            : IcebergUtils.getIcebergColumnNameToIDMap(icebergTableSchema);

    Builder icebergDatasetXAttrBuilder =
        IcebergProtobuf.IcebergDatasetXAttr.newBuilder()
            .addAllColumnIds(
                colIdMap.entrySet().stream()
                    .map(
                        c ->
                            IcebergProtobuf.IcebergSchemaField.newBuilder()
                                .setSchemaPath(c.getKey())
                                .setId(c.getValue())
                                .build())
                    .collect(Collectors.toList()));

    if (defaultNameMapping != null && defaultNameMapping.asMappedFields().size() > 0) {
      List<DefaultNameMapping> defaultNameMappingList = new ArrayList<>();
      defaultNameMapping
          .asMappedFields()
          .fields()
          .forEach(
              (field) -> {
                field
                    .names()
                    .forEach(
                        (name) -> {
                          Integer id = field.id();
                          if (id != null) {
                            defaultNameMappingList.add(
                                DefaultNameMapping.newBuilder().setName(name).setId(id).build());
                          }
                        });
              });

      icebergDatasetXAttrBuilder.addAllDefaultNameMapping(defaultNameMappingList);
    }
    icebergDatasetXAttr = icebergDatasetXAttrBuilder.build().toByteArray();
    setColIds = outIndex -> colIdsVector.setSafe(outIndex, icebergDatasetXAttr);
  }

  protected void initializeFileSystemScheme(FileIO io) {
    conf = pluginForIceberg.getFsConfCopy();
    fsScheme = io instanceof DremioFileIO ? ((DremioFileIO) io).getFs().getScheme() : null;
  }

  protected FileIO createIO(String path) {
    return createFileIOForIcebergMetadata(
        pluginForIceberg, context, datasourcePluginUID, props, dataset, path);
  }

  protected void initializeOutVectors() {

    tmpBuf = context.getAllocator().buffer(4096);

    splitIdentityVector = (StructVector) output.getVector(RecordReader.SPLIT_IDENTITY);
    splitInfoVector = (VarBinaryVector) output.getVector(RecordReader.SPLIT_INFORMATION);
    colIdsVector = (VarBinaryVector) output.getVector(RecordReader.COL_IDS);
    if (appendManifestInfo) {
      outputFilePath = (VarCharVector) output.getVector(SystemSchemas.FILE_PATH);
      outputFileType = (VarCharVector) output.getVector(SystemSchemas.FILE_TYPE);
    }
  }

  private List<ManifestFile> getManifests(Snapshot snapshot, FileIO io) {
    try {
      switch (manifestContent) {
        case DATA:
          return snapshot.dataManifests(io);
        case DELETES:
          return snapshot.deleteManifests(io);
        case ALL:
          return snapshot.allManifests(io);
        default:
          throw new IllegalStateException("Invalid ManifestContentType " + manifestContent);
      }
    } catch (NotFoundException nfe) {
      logger.error(
          String.format("Unable to read manifest list [%s]", snapshot.manifestListLocation()), nfe);
      throw UserRemoteException.dataReadError(nfe) // Job is not re-attempted on this error code
          .message(
              "This version of the table [%s] is not available - [snapshot with id %d created at %s].",
              String.join(".", dataset),
              snapshot.snapshotId(),
              SqlHandlerUtil.getTimestampFromMillis(snapshot.timestampMillis()))
          .build(logger);
    }
  }

  @Override
  public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
    for (final ValueVector v : vectorMap.values()) {
      v.allocateNew();
    }
  }

  @Override
  public int next() {
    return nextBatch(0, context.getTargetBatchSize());
  }

  public int nextBatch(int startOutIndex, int maxOutIndex) {
    if (emptyTable) {
      return 0;
    }

    int outIndex = startOutIndex;
    try {
      NullableStructWriter splitIdentityWriter = splitIdentityVector.getWriter();
      while (manifestFileIterator.hasNext() && outIndex < maxOutIndex) {
        ManifestFile manifestFile = manifestFileIterator.next();
        String manifestFilePath =
            StringUtils.isEmpty(schemeVariate)
                ? manifestFile.path()
                : IcebergUtils.getIcebergPathAndValidateScheme(
                    manifestFile.path(), conf, fsScheme, schemeVariate);

        SplitIdentity splitIdentity =
            new SplitIdentity(manifestFilePath, 0, manifestFile.length(), manifestFile.length());

        writeSplitIdentity(splitIdentityWriter, outIndex, splitIdentity, tmpBuf);
        splitInfoVector.setSafe(outIndex, IcebergSerDe.serializeToByteArray(manifestFile));
        setColIds.accept(outIndex);
        if (appendManifestInfo) {
          outputFilePath.setSafe(outIndex, manifestFilePath.getBytes(StandardCharsets.UTF_8));
          outputFileType.setSafe(
              outIndex, IcebergFileType.MANIFEST.name().getBytes(StandardCharsets.UTF_8));
        }

        for (Field field : schema) {
          if (isNonAddOnField(field.getName())) {
            continue;
          }
          writeToVector(
              output.getVector(field.getName()), outIndex, getStatValue(manifestFile, field));
        }
        outIndex++;
      }

      int lastOutIndex = outIndex;
      output.getVectors().forEach(v -> v.setValueCount(lastOutIndex));
      return lastOutIndex - startOutIndex;
    } catch (Exception e) {
      throw UserException.dataReadError(e)
          .message(
              "Unable to read manifest list files for table '%s'",
              PathUtils.constructFullPath(dataset))
          .build(logger);
    }
  }

  private Object getStatValue(ManifestFile manifestFile, Field field) {
    String fieldName = field.getName();
    Preconditions.checkArgument(fieldName.length() > 4);
    String colName = fieldName.substring(0, fieldName.length() - 4).toLowerCase();
    String suffix = fieldName.substring(fieldName.length() - 3).toLowerCase();
    Preconditions.checkArgument(
        partColToKeyMap.containsKey(colName), "partition column not present");
    int key = partColToKeyMap.get(colName);
    Type fieldType = icebergTableSchema.caseInsensitiveFindField(colName).type();
    Object value;

    switch (suffix) {
      case "min":
        value =
            key < manifestFile.partitions().size()
                ? getValueFromByteBuffer(manifestFile.partitions().get(key).lowerBound(), fieldType)
                : null;
        break;
      case "max":
        value =
            key < manifestFile.partitions().size()
                ? getValueFromByteBuffer(manifestFile.partitions().get(key).upperBound(), fieldType)
                : null;
        break;
      default:
        throw UserException.unsupportedError()
            .message("unexpected suffix for column: " + fieldName)
            .buildSilently();
    }

    return value;
  }

  @Override
  public void close() throws Exception {
    context.getStats().setReadIOStats();
    AutoCloseables.close(tmpBuf);
  }

  private List<ManifestFile> filterManifestFiles(List<ManifestFile> manifestFileList) {
    if (icebergFilterExpression == null) {
      return manifestFileList;
    }
    Map<Integer, ManifestEvaluator> evaluatorMap = new HashMap<>();
    for (Map.Entry<Integer, PartitionSpec> spec : partitionSpecMap.entrySet()) {
      ManifestEvaluator partitionEval =
          ManifestEvaluator.forRowFilter(icebergFilterExpression, spec.getValue(), false);
      evaluatorMap.put(spec.getKey(), partitionEval);
    }

    return manifestFileList.stream()
        .filter(
            file -> {
              if (this.isInternalIcebergScanTableMetadata) {
                PartitionSpec boundPartitionSpec = partitionSpecMap.get(file.partitionSpecId());
                // The partitionType is what the ManifestEvaluator uses to bind field names into the
                // icebergFilterExpression
                // This will not catch if the manifest file has different types but the name number
                // of partitions.
                // Unfortunately the ManifestFile doesn't contain a full ParititionSpec just a
                // List<PartitionFieldSummary>
                if (boundPartitionSpec.isPartitioned()
                    && boundPartitionSpec.partitionType().fields().size()
                        == file.partitions().size()) {
                  return evaluatorMap.get(file.partitionSpecId()).eval(file);
                } else {
                  return true;
                }
              } else {
                return evaluatorMap.get(file.partitionSpecId()).eval(file);
              }
            })
        .collect(Collectors.toList());
  }
}
