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

package com.dremio.exec.store.deltalake;

import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_ADD;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_COMMIT_INFO;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_METADATA;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_METADATA_CONFIGURATION;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_METADATA_PARTITION_COLS;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_METADATA_SCHEMA_STRING;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_PROTOCOL;
import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_TIMESTAMP;
import static com.dremio.exec.store.deltalake.DeltaConstants.FORMAT_NOT_SUPPORTED_VERSION;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_METRICS;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_ADDED_BYTES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_ADDED_FILES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_DELETED_ROWS;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_FILES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_OUTPUT_BYTES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_OUTPUT_ROWS;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_REMOVED_BYTES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_REMOVED_FILES;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_TARGET_FILES_ADDED;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_TARGET_FILES_REMOVED;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_TARGET_ROWS_DELETED;
import static com.dremio.exec.store.deltalake.DeltaConstants.OP_NUM_TARGET_ROWS_INSERTED;
import static com.dremio.exec.store.deltalake.DeltaConstants.PROTOCOL_MIN_READER_VERSION;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_DATA_CHANGE;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_STATS;
import static com.dremio.exec.store.deltalake.DeltaLogReaderUtils.parseStatsFromJson;

import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.store.file.proto.FileProtobuf;
import com.dremio.io.FSInputStream;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.store.deltalake.proto.DeltaLakeProtobuf;
import com.dremio.sabot.exec.store.easy.proto.EasyProtobuf;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeltaLog reader, specifically for the DeltaLake commit JSON log files. This reader is
 * specifically dedicated to read the initial portions of the data, which identify dataset size,
 * protocol version and the schema.
 */
public class DeltaLogCommitJsonReader implements DeltaLogReader {
  private static final ObjectMapper OBJECT_MAPPER = getObjectMapper();
  private static final Logger logger = LoggerFactory.getLogger(DeltaLogCommitJsonReader.class);

  DeltaLogCommitJsonReader() {
    // to be instantiated only from DeltaLogParser
  }

  @Override
  public DeltaLogSnapshot parseMetadata(
      Path rootFolder,
      PluginSabotContext pluginSabotContext,
      FileSystem fs,
      List<FileAttributes> fileAttributes,
      long version)
      throws IOException {
    final boolean fullRowCountEnabled =
        DeltaLogReaderUtils.isFullRowCountEnabled(pluginSabotContext.getOptionManager());
    final int minReaderVersionSupported = 2;

    final Path commitFilePath = fileAttributes.get(0).getPath();
    try (final FSInputStream commitFileIs = fs.open(commitFilePath);
        final BufferedReader bufferedReader =
            new BufferedReader(new InputStreamReader(commitFileIs))) {

      DeltaLogSnapshot snapshot = new DeltaLogSnapshot();
      String nextLine;
      long numRecords = 0L;
      JsonNode metadataNode = null;
      boolean commitInfoHasRowCount = false;
      boolean foundMetadata = false;
      boolean foundCommitInfo = false;

      /*
       * Apart from commitInfo, other sections are optional.
       * operationMetrics are used to get row/file stats.
       * If no operationMetrics is found, row stats are obtained from Add/Remove actions.
       */
      while ((nextLine = bufferedReader.readLine()) != null) {
        final JsonNode json = OBJECT_MAPPER.readTree(nextLine);
        if (json.has(DELTA_FIELD_METADATA)) {
          metadataNode = json.get(DELTA_FIELD_METADATA);
          foundMetadata = true;
        } else if (json.has(DELTA_FIELD_PROTOCOL)) {
          final int minReaderVersion =
              get(json, 1, JsonNode::intValue, DELTA_FIELD_PROTOCOL, PROTOCOL_MIN_READER_VERSION);
          if (minReaderVersion > minReaderVersionSupported) {
            throw new IOException(
                String.format(
                    FORMAT_NOT_SUPPORTED_VERSION, minReaderVersion, minReaderVersionSupported));
          }
        } else if (json.has(DELTA_FIELD_COMMIT_INFO)) {
          snapshot = OBJECT_MAPPER.readValue(nextLine, DeltaLogSnapshot.class);
          if (snapshot.getDataFileEntryCount() > 0 || snapshot.getNetOutputRows() != 0) {
            // commitInfo doesn't provide row count in opMetrics for all operations
            // so only prevent full row count scan if information was provided
            commitInfoHasRowCount = true;
          }
          foundCommitInfo = true;
        } else if (fullRowCountEnabled && !commitInfoHasRowCount && json.has(DELTA_FIELD_ADD)) {
          // parse ADD rows as a fallback of not being able to get operationMetrics
          JsonNode addNode = json.get(DELTA_FIELD_ADD);
          if (addNode.has(SCHEMA_DATA_CHANGE) && addNode.get(SCHEMA_DATA_CHANGE).asBoolean()) {
            if (addNode.has(SCHEMA_STATS)) {
              Long numRecord = parseStatsFromJson(addNode.get(SCHEMA_STATS).textValue());
              if (numRecord != null) {
                numRecords += numRecord;
              }
            }
          }
        }

        // Condition for early exit
        if (foundCommitInfo && foundMetadata && (!fullRowCountEnabled || commitInfoHasRowCount)) {
          break;
        }
      }
      if (metadataNode != null) {
        populateSchema(snapshot, metadataNode);
      }

      if (fullRowCountEnabled && !commitInfoHasRowCount && numRecords != 0L) {
        // fill row stats from Add/Remove actins
        snapshot.setNetOutputRows(numRecords);
      }

      snapshot.setSplits(
          generateSplits(fileAttributes.get(0), snapshot.getDataFileEntryCount(), version));

      logger.debug("For file {}, snapshot is {}", commitFilePath, snapshot.toString());
      return snapshot;
    }
  }

  public static long parseTimestamp(FileSystem fs, FileAttributes file) throws IOException {
    try (final FSInputStream commitFileIs = fs.open(file.getPath());
        final BufferedReader bufferedReader =
            new BufferedReader(new InputStreamReader(commitFileIs))) {
      String nextLine;
      while ((nextLine = bufferedReader.readLine()) != null) {
        final JsonNode json = OBJECT_MAPPER.readTree(nextLine);
        if (json.has(DELTA_FIELD_COMMIT_INFO)) {
          return get(json, 0L, JsonNode::asLong, DELTA_FIELD_COMMIT_INFO, "timestamp");
        }
      }
      throw new IOException(
          DELTA_FIELD_COMMIT_INFO + " section not found in json commit " + file.getPath());
    }
  }

  public static DeltaLogSnapshot parseCommitInfo(FileSystem fs, Path commitPath)
      throws IOException {
    try (final FSInputStream commitFileIs = fs.open(commitPath);
        final BufferedReader bufferedReader =
            new BufferedReader(new InputStreamReader(commitFileIs))) {
      String nextLine;
      while ((nextLine = bufferedReader.readLine()) != null) {
        final JsonNode json = OBJECT_MAPPER.readTree(nextLine);
        if (json.has(DELTA_FIELD_COMMIT_INFO)) {
          DeltaLogSnapshot snapshot = OBJECT_MAPPER.convertValue(json, DeltaLogSnapshot.class);
          snapshot.setVersionId(DeltaFilePathResolver.getVersionFromPath(commitPath));
          return snapshot;
        }
      }
      throw new IOException(
          DELTA_FIELD_COMMIT_INFO + " section not found in json commit " + commitPath);
    }
  }

  public void populateSchema(DeltaLogSnapshot snapshot, JsonNode metadata) throws IOException {
    // Check data file format
    final String format = get(metadata, null, JsonNode::asText, "format", "provider");
    if (!"parquet".equalsIgnoreCase(format)) {
      throw new IOException("Non-parquet delta lake tables aren't supported");
    }

    final String schemaString =
        get(metadata, null, JsonNode::asText, DELTA_FIELD_METADATA_SCHEMA_STRING);

    final List<String> partitionCols = new ArrayList<>();
    // Fetch partitions
    final JsonNode partitionColsJson = metadata.get(DELTA_FIELD_METADATA_PARTITION_COLS);
    if (partitionColsJson != null) {
      for (int i = 0; i < partitionColsJson.size(); i++) {
        partitionCols.add(partitionColsJson.get(i).asText());
      }
    }

    Map<String, String> configuration = null;
    final JsonNode configurationJson = metadata.get(DELTA_FIELD_METADATA_CONFIGURATION);
    if (configurationJson != null) {
      configuration =
          StreamSupport.stream(
                  Spliterators.spliteratorUnknownSize(configurationJson.fields(), 0), false)
              .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().asText()));
    }

    snapshot.setSchema(schemaString, partitionCols, configuration);
  }

  private static ObjectMapper getObjectMapper() {
    final ObjectMapper objectMapper = new ObjectMapper();
    final SimpleModule module = new SimpleModule();
    module.addDeserializer(DeltaLogSnapshot.class, new DeltaSnapshotDeserializer());
    objectMapper.registerModule(module);
    return objectMapper;
  }

  private static class DeltaSnapshotDeserializer extends StdDeserializer<DeltaLogSnapshot> {
    public DeltaSnapshotDeserializer() {
      this(null);
    }

    public DeltaSnapshotDeserializer(Class<?> vc) {
      super(vc);
    }

    @Override
    public DeltaLogSnapshot deserialize(JsonParser jp, DeserializationContext ctxt)
        throws IOException {
      final JsonNode fullCommitInfoJson = jp.getCodec().readTree(jp);
      final JsonNode commitInfo = fullCommitInfoJson.get(DELTA_FIELD_COMMIT_INFO);
      logger.debug("CommitInfoJson is {}", commitInfo);
      final Function<JsonNode, Long> asLong = JsonNode::asLong;
      final String operationType =
          get(commitInfo, DeltaConstants.OPERATION_UNKNOWN, JsonNode::asText, OP);

      // CREATE, REPLACE, WRITE, and COPY INTO - report numFiles
      // STREAMING UPDATE, DELETE, UPDATE, and TRUNCATE - report numAddedFiles and numRemovedFiles
      // MERGE - reports numTargetFilesAdded and numTargetFilesRemoved
      // individual metrics are reported for some operations, and are zero for other,
      // thus all metrics could be safely aggregated in one expression
      final long numFiles = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_FILES);
      final long numAddedFiles = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_ADDED_FILES);
      final long numRemovedFiles = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_REMOVED_FILES);
      final long numTargetFilesAdded =
          get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_TARGET_FILES_ADDED);
      final long numTargetFilesRemoved =
          get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_TARGET_FILES_REMOVED);
      final long netFilesAdded =
          numFiles + numAddedFiles - numRemovedFiles + numTargetFilesAdded - numTargetFilesRemoved;
      final long totalFileEntries =
          numFiles + numAddedFiles + numRemovedFiles + numTargetFilesAdded + numTargetFilesRemoved;

      // CREATE, REPLACE, WRITE, COPY INTO, and STREAMING UPDATE - report numOutputRows
      // DELETE - reports numDeletedRows
      // MERGE - reports numTargetRowsInserted and numTargetRowsDeleted.
      //         numOutputRows is also reported, but should be excluded for number of changed rows
      // calculation
      final long numOutputRows = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_OUTPUT_ROWS);
      final long numDeletedRows = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_DELETED_ROWS);
      final long numTargetRowsInserted =
          get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_TARGET_ROWS_INSERTED);
      final long numTargetRowsDeleted =
          get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_TARGET_ROWS_DELETED);
      final long netOutputRows =
          operationType.equalsIgnoreCase(DeltaConstants.OPERATION_MERGE)
              ? (numTargetRowsInserted - numTargetRowsDeleted)
              : (numOutputRows - numDeletedRows);

      // CREATE, REPLACE, WRITE, COPY INTO, and STREAMING UPDATE - report numOutputBytes
      // OPTIMIZE - reports numAddedBytes and numRemovedBytes
      final long numOutputBytes = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_OUTPUT_BYTES);
      final long numAddedBytes = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_ADDED_BYTES);
      final long numRemovedBytes = get(commitInfo, 0L, asLong, OP_METRICS, OP_NUM_REMOVED_BYTES);
      final long netBytesAdded = numOutputBytes + numAddedBytes - numRemovedBytes;

      final long timestamp = get(commitInfo, 0L, asLong, DELTA_TIMESTAMP);
      return new DeltaLogSnapshot(
          operationType,
          netFilesAdded,
          netBytesAdded,
          netOutputRows,
          totalFileEntries,
          timestamp,
          false);
    }
  }

  private static JsonNode findNode(JsonNode node, String... paths) {
    for (String path : paths) {
      if (node == null) {
        return node;
      }
      node = node.get(path);
    }
    return node;
  }

  // get an optional value
  private static <T> T get(
      JsonNode node, T defaultVal, Function<JsonNode, T> typeFunc, String... paths) {
    node = findNode(node, paths);
    return (node == null) ? defaultVal : typeFunc.apply(node);
  }

  private List<DatasetSplit> generateSplits(
      FileAttributes fileAttributes, long totalFileEntries, long version) throws IOException {
    List<DatasetSplit> splits = new ArrayList<>(1);
    if (fileAttributes == null) {
      throw new IOException(
          String.format("File attributes are not set for version %s", Long.toString(version)));
    }

    DeltaLakeProtobuf.DeltaCommitLogSplitXAttr deltaExtended =
        DeltaLakeProtobuf.DeltaCommitLogSplitXAttr.newBuilder().setVersion(version).build();

    final EasyProtobuf.EasyDatasetSplitXAttr splitExtended =
        EasyProtobuf.EasyDatasetSplitXAttr.newBuilder()
            .setPath(fileAttributes.getPath().toString())
            .setStart(0)
            .setLength(fileAttributes.size())
            .setUpdateKey(
                FileProtobuf.FileSystemCachedEntity.newBuilder()
                    .setPath(fileAttributes.getPath().toString())
                    .setLength(fileAttributes.size())
                    .setLastModificationTime(fileAttributes.lastModifiedTime().toMillis()))
            .setExtendedProperty(deltaExtended.toByteString())
            .build();

    splits.add(
        DatasetSplit.of(
            Collections.emptyList(),
            fileAttributes.size(),
            totalFileEntries,
            splitExtended::writeTo));
    return splits;
  }
}
