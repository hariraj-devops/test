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

import static org.junit.Assert.assertEquals;

import com.dremio.BaseTestQuery;
import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.connector.metadata.PartitionChunk;
import com.dremio.datastore.LegacyProtobufSerializer;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf.ColumnValueCount;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf.ParquetDatasetSplitXAttr;
import com.dremio.service.namespace.MetadataProtoUtils;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

public class TestIcebergColumnCounts extends BaseTestQuery {

  @Test
  public void tableWithNulls() throws Exception {
    final String tableName = "column_counts";
    try {
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s  " + " AS SELECT * from cp.\"parquet/null_test_data.json\"",
              TEMP_SCHEMA_HADOOP, tableName);

      test(ctasQuery);

      File tableFolder = new File(getDfsTestTmpSchemaLocation(), tableName);
      IcebergTableWrapper tableWrapper =
          new IcebergTableWrapper(
              getSabotContext(),
              localFs,
              getIcebergModel(TEMP_SCHEMA_HADOOP),
              tableFolder.toPath().toString());

      List<PartitionChunk> chunks =
          ImmutableList.copyOf(tableWrapper.getTableInfo().getPartitionChunkListing().iterator());
      assertEquals(1, chunks.size());

      DatasetSplit split = chunks.get(0).getSplits().iterator().next();
      ParquetDatasetSplitXAttr xattr =
          LegacyProtobufSerializer.parseFrom(
              ParquetDatasetSplitXAttr.parser(),
              MetadataProtoUtils.toProtobuf(split.getExtraInfo()));
      assertEquals(2, xattr.getColumnValueCountsCount());

      // both the columns have null values.
      assertEquals(8, tableWrapper.getTableInfo().getRecordCount());
      assertEquals(6, xattr.getColumnValueCounts(0).getCount());
      assertEquals(4, xattr.getColumnValueCounts(1).getCount());
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
    }
  }

  @Test
  public void testComplex() throws Exception {
    final String tableName = "complex_column_counts";

    try {
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s " + " AS SELECT * from cp.\"complex_student.json\"",
              TEMP_SCHEMA_HADOOP, tableName);

      test(ctasQuery);

      testBuilder()
          .sqlQuery(String.format("select count(*) c from %s.%s", TEMP_SCHEMA_HADOOP, tableName))
          .unOrdered()
          .baselineColumns("c")
          .baselineValues(10L)
          .build()
          .run();

      File tableFolder = new File(getDfsTestTmpSchemaLocation(), tableName);
      IcebergTableWrapper tableWrapper =
          new IcebergTableWrapper(
              getSabotContext(),
              localFs,
              getIcebergModel(TEMP_SCHEMA_HADOOP),
              tableFolder.toPath().toString());

      List<PartitionChunk> chunks =
          ImmutableList.copyOf(tableWrapper.getTableInfo().getPartitionChunkListing().iterator());
      assertEquals(1, chunks.size());

      DatasetSplit split = chunks.get(0).getSplits().iterator().next();
      ParquetDatasetSplitXAttr xattr =
          LegacyProtobufSerializer.parseFrom(
              ParquetDatasetSplitXAttr.parser(),
              MetadataProtoUtils.toProtobuf(split.getExtraInfo()));

      assertEquals(10, tableWrapper.getTableInfo().getRecordCount());
      Map<String, Long> expectedValueCounts =
          Map.of(
              "rownum", 10L,
              "name", 10L,
              "age", 10L,
              "gpa", 10L,
              "studentnum", 10L,
              "create_time", 10L,
              "interests.element", 30L,
              "favorites.color", 10L,
              "favorites.sport", 10L,
              "favorites.food", 10L);
      assertEquals(expectedValueCounts.size(), xattr.getColumnValueCountsList().size());
      for (ColumnValueCount entry : xattr.getColumnValueCountsList()) {
        assertEquals(expectedValueCounts.get(entry.getColumn()).longValue(), entry.getCount());
      }
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
    }
  }
}
