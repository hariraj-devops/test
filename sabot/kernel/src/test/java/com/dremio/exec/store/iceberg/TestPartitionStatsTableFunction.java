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

import static com.dremio.exec.ExecConstants.ICEBERG_CATALOG_TYPE_KEY;
import static com.dremio.exec.store.SystemSchemas.FILE_PATH;
import static com.dremio.exec.store.SystemSchemas.FILE_TYPE;
import static com.dremio.exec.store.SystemSchemas.METADATA_FILE_PATH;
import static com.dremio.exec.store.iceberg.IcebergFileType.METADATA_JSON;
import static com.dremio.sabot.Fixtures.NULL_BIGINT;
import static com.dremio.sabot.Fixtures.NULL_VARCHAR;
import static com.dremio.sabot.Fixtures.t;
import static com.dremio.sabot.Fixtures.th;
import static com.dremio.sabot.Fixtures.tr;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.hadoop.HadoopFileSystemConfigurationAdapter;
import com.dremio.exec.physical.config.CarryForwardAwareTableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionPOP;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.iceberg.IcebergTestTables.Table;
import com.dremio.exec.store.iceberg.model.IcebergCatalogType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.BaseTestTableFunction;
import com.dremio.sabot.Fixtures;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.dremio.service.catalog.DatasetCatalogServiceGrpc;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.io.FileIO;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;

/** Tests for {@link PartitionStatsScanTableFunction} */
public class TestPartitionStatsTableFunction extends BaseTestTableFunction {
  private static final Configuration CONF = new Configuration();
  private static FileSystem fs;
  @Mock private StoragePluginId pluginId;

  @Mock(extraInterfaces = {SupportsIcebergRootPointer.class})
  private SupportsIcebergMutablePlugin plugin;

  @BeforeClass
  public static void initStatics() throws Exception {
    CONF.set(ICEBERG_CATALOG_TYPE_KEY, IcebergCatalogType.HADOOP.name());
    fs = HadoopFileSystem.get(Path.of("/"), CONF);
  }

  @Before
  public void prepareMocks() throws Exception {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    when(plugin.createFS(any())).thenReturn(fs);
    when(plugin.getFsConfCopy()).thenReturn(CONF);
    when(plugin.createIcebergFileIO(any(), any(), any(), any(), any())).thenReturn(fileIO);
    SabotContext context = mock(SabotContext.class);
    DatasetCatalogServiceGrpc.DatasetCatalogServiceBlockingStub stub =
        mock(DatasetCatalogServiceGrpc.DatasetCatalogServiceBlockingStub.class);
    when(context.getDatasetCatalogBlockingStub()).thenReturn(() -> stub);
    IcebergModel model =
        IcebergModelCreator.createIcebergModel(
            CONF, context, fileIO, mock(OperatorContext.class), plugin);
    when(plugin.getIcebergModel(any(), any(), any(), any(), any())).thenReturn(model);
    when(fec.getStoragePlugin(eq(pluginId))).thenReturn(plugin);
  }

  @Test
  public void testPartitionStatsFromDifferentMetadata() throws Exception {
    Table testTable = IcebergTestTables.PARTITIONED_NATION.get();

    Fixtures.Table input =
        t(
            th(
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            tr(
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"));

    Fixtures.Table output =
        t(
            // Entries from v3.metadata.json
            th(
                SystemSchemas.FILE_PATH,
                SystemSchemas.FILE_TYPE,
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4709042947025192029.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),

            // Entries from v4.metadata.json
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4447362982003292979.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-9f0488cd-235e-44d2-b88b-c901424ee372.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR));

    validateSingle(getPop(false), TableFunctionOperator.class, input, output, 6);

    testTable.close();
  }

  @Test
  public void testExceedBatchSizeWithCarryForward() throws Exception {
    Table testTable = IcebergTestTables.PARTITIONED_NATION.get();
    Fixtures.Table input =
        t(
            th(
                SystemSchemas.FILE_PATH,
                SystemSchemas.FILE_TYPE,
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            tr(
                "/tmp/iceberg/metadata-a.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "/tmp/iceberg/metadata-b.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"));

    Fixtures.Table output =
        t(
            th(
                SystemSchemas.FILE_PATH,
                SystemSchemas.FILE_TYPE,
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            // Carry forward entries
            tr(
                "/tmp/iceberg/metadata-a.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "/tmp/iceberg/metadata-b.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),

            // Entries from v3.metadata.json
            tr(
                "/tmp/iceberg/metadata/v3.metadata.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4709042947025192029.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),

            // Entries from v4.metadata.json
            tr(
                "/tmp/iceberg/metadata/v4.metadata.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4447362982003292979.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-9f0488cd-235e-44d2-b88b-c901424ee372.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR));

    validateSingle(getPop(true), TableFunctionOperator.class, input, output, 3);

    testTable.close();
  }

  @Test
  public void testExceedBatchSizeWithCarryForwardAndDataset() throws Exception {
    Table testTable = IcebergTestTables.PARTITIONED_NATION.get();
    Fixtures.Table input =
        t(
            th(
                SystemSchemas.DATASET_FIELD,
                SystemSchemas.FILE_PATH,
                SystemSchemas.FILE_TYPE,
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            tr(
                NULL_VARCHAR,
                "/tmp/iceberg/metadata-a.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation",
                "/tmp/iceberg/metadata-b.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation_t1",
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                "iceberg.partitioned_nation_t2",
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"));

    Fixtures.Table output =
        t(
            th(
                SystemSchemas.DATASET_FIELD,
                SystemSchemas.FILE_PATH,
                SystemSchemas.FILE_TYPE,
                SystemSchemas.METADATA_FILE_PATH,
                SystemSchemas.SNAPSHOT_ID,
                SystemSchemas.MANIFEST_LIST_PATH),
            // Carry forward entries
            tr(
                NULL_VARCHAR,
                "/tmp/iceberg/metadata-a.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                NULL_VARCHAR,
                "/tmp/iceberg/metadata-b.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),

            // Entries from v3.metadata.json
            tr(
                NULL_VARCHAR,
                "/tmp/iceberg/metadata/v3.metadata.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation_t1",
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v3"),
                4709042947025192029L,
                "/tmp/iceberg/metadata/snap-4709042947025192029-1-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro"),
            tr(
                "iceberg.partitioned_nation_t1",
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4709042947025192029.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation_t1",
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-348cabd1-9bc4-442c-92b4-7f8ac8e26a6d.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),

            // Entries from v4.metadata.json
            tr(
                NULL_VARCHAR,
                "/tmp/iceberg/metadata/v4.metadata.json",
                METADATA_JSON.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation_t2",
                NULL_VARCHAR,
                NULL_VARCHAR,
                p("v4"),
                4447362982003292979L,
                "/tmp/iceberg/metadata/snap-4447362982003292979-1-9f0488cd-235e-44d2-b88b-c901424ee372.avro"),
            tr(
                "iceberg.partitioned_nation_t2",
                "file:///tmp/iceberg/metadata/dremio-partitionStatsMetadata-4447362982003292979.json",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR),
            tr(
                "iceberg.partitioned_nation_t2",
                "file:///tmp/iceberg/metadata/dremio-partitionStats-0-9f0488cd-235e-44d2-b88b-c901424ee372.avro",
                IcebergFileType.PARTITION_STATS.name(),
                NULL_VARCHAR,
                NULL_BIGINT,
                NULL_VARCHAR));

    validateSingle(
        getPop(
            true,
            SystemSchemas.ICEBERG_SNAPSHOTS_SCAN_SCHEMA.merge(
                SystemSchemas.CARRY_FORWARD_FILE_PATH_TYPE_WITH_DATASET_SCHEMA)),
        TableFunctionOperator.class,
        input,
        output,
        3);

    testTable.close();
  }

  private String p(String ver) throws Exception {
    return String.format("/tmp/iceberg/metadata/%s.metadata.json", ver);
  }

  private TableFunctionPOP getPop(boolean enableCarryForward) {
    BatchSchema schema =
        SystemSchemas.ICEBERG_SNAPSHOTS_SCAN_SCHEMA.merge(
            SystemSchemas.CARRY_FORWARD_FILE_PATH_TYPE_SCHEMA);
    return getPop(enableCarryForward, schema);
  }

  private TableFunctionPOP getPop(boolean enableCarryForward, BatchSchema schema) {
    return new TableFunctionPOP(
        PROPS,
        null,
        new TableFunctionConfig(
            TableFunctionConfig.FunctionType.ICEBERG_PARTITION_STATS_SCAN,
            true,
            new CarryForwardAwareTableFunctionContext(
                schema,
                pluginId,
                enableCarryForward,
                ImmutableMap.of(
                    SchemaPath.getSimplePath(METADATA_FILE_PATH),
                    SchemaPath.getSimplePath(FILE_PATH)),
                FILE_TYPE,
                METADATA_JSON.name(),
                IcebergUtils.getDefaultPathScheme(fs.getScheme(), CONF),
                List.of())));
  }
}
