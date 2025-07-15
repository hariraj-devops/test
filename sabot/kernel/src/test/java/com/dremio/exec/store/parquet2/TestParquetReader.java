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
package com.dremio.exec.store.parquet2;

import static com.dremio.common.arrow.DremioArrowSchema.DREMIO_ARROW_SCHEMA;
import static com.dremio.common.arrow.DremioArrowSchema.DREMIO_ARROW_SCHEMA_2_1;
import static com.dremio.exec.store.parquet.ParquetFormatDatasetAccessor.PARQUET_TEST_SCHEMA_FALLBACK_ONLY_VALIDATOR;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.BaseTestQuery;
import com.dremio.common.arrow.DremioArrowSchema;
import com.dremio.common.util.TestTools;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.parquet.SingletonParquetFooterCache;
import com.dremio.io.file.Path;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.junit.Test;

public class TestParquetReader extends BaseTestQuery {
  private static final String WORKING_PATH = TestTools.getWorkingPath();

  private void testColumn(String columnName) throws Exception {
    testNoResult("alter session set \"store.parquet.use_new_reader\" = true");

    BigDecimal result = new BigDecimal("1.20000000");

    testBuilder()
        .ordered()
        .sqlQuery("select %s from cp.\"parquet2/decimal28_38.parquet\"", columnName)
        .baselineColumns(columnName)
        .baselineValues(result)
        .go();

    testNoResult("alter session set \"store.parquet.use_new_reader\" = false");
  }

  @Test
  public void testRequiredDecimal28() throws Exception {
    testColumn("d28_req");
  }

  @Test
  public void testRequiredDecimal38() throws Exception {
    testColumn("d38_req");
  }

  @Test
  public void testOptionalDecimal28() throws Exception {
    testColumn("d28_opt");
  }

  @Test
  public void testOptionalDecimal38() throws Exception {
    testColumn("d38_opt");
  }

  @Test
  public void test4349() throws Exception {
    // start by creating a parquet file from the input csv file
    runSQL(
        "CREATE TABLE dfs_test.\"4349\" AS SELECT columns[0] id, CAST(NULLIF(columns[1], '') AS DOUBLE) val FROM cp.\"parquet2/4349.csv.gz\"");

    // querying the parquet file should return the same results found in the csv file
    testBuilder()
        .unOrdered()
        .sqlQuery("SELECT * FROM dfs_test.\"4349\" WHERE id = 'b'")
        .sqlBaselineQuery(
            "SELECT columns[0] id, CAST(NULLIF(columns[1], '') AS DOUBLE) val FROM cp.\"parquet2/4349.csv.gz\" WHERE columns[0] = 'b'")
        .go();
  }

  @Test
  public void testArrowSchema205InFooter() throws Exception {
    URL parquet205 = getClass().getResource("/dremio-region-205.parquet");
    Path filePath = Path.of(parquet205.toURI());
    ParquetMetadata parquetMetadata =
        SingletonParquetFooterCache.readFooter(
            localFs,
            filePath,
            ParquetMetadataConverter.NO_FILTER,
            ExecConstants.PARQUET_MAX_FOOTER_LEN_VALIDATOR.getDefault().getNumVal());
    Map<String, String> metadata = parquetMetadata.getFileMetaData().getKeyValueMetaData();

    // should have DREMIO_ARROW_SCHEMA field, but no DREMIO_ARROW_SCHEMA_2_1
    assertTrue(metadata.containsKey(DREMIO_ARROW_SCHEMA));
    assertFalse(metadata.containsKey(DREMIO_ARROW_SCHEMA_2_1));

    Schema schema = DremioArrowSchema.fromMetaData(metadata);

    assertNotNull(schema);
  }

  @Test
  public void testArrowSchema210InFooter() throws Exception {
    URL parquet210 = getClass().getResource("/dremio-region-210.parquet");
    Path filePath210 = Path.of(parquet210.toURI());
    ParquetMetadata parquetMetadata210 =
        SingletonParquetFooterCache.readFooter(
            localFs,
            filePath210,
            ParquetMetadataConverter.NO_FILTER,
            ExecConstants.PARQUET_MAX_FOOTER_LEN_VALIDATOR.getDefault().getNumVal());
    Map<String, String> metadata210 = parquetMetadata210.getFileMetaData().getKeyValueMetaData();

    // should not have DREMIO_ARROW_SCHEMA field, but should have DREMIO_ARROW_SCHEMA_2_1
    assertFalse(metadata210.containsKey(DREMIO_ARROW_SCHEMA));
    assertTrue(metadata210.containsKey(DREMIO_ARROW_SCHEMA_2_1));

    Schema schema210 = DremioArrowSchema.fromMetaData(metadata210);

    assertNotNull(schema210);
  }

  @Test
  public void testFilterOnNonExistentColumn() throws Exception {
    final String parquetFiles =
        TestParquetUtil.setupParquetFiles(
            "testFilterOnNonExistentColumn", "nonexistingcols", "bothcols.parquet", WORKING_PATH);
    try {
      testBuilder()
          .sqlQuery("SELECT * FROM dfs.\"" + parquetFiles + "\" where col1='bothvalscol1'")
          .ordered()
          .baselineColumns("col1", "col2")
          .baselineValues("bothvalscol1", "bothvalscol2")
          .go();

      testBuilder()
          .sqlQuery("SELECT col2 FROM dfs.\"" + parquetFiles + "\" where col1 is null")
          .ordered()
          .baselineColumns("col2")
          .baselineValues("singlevalcol2")
          .go();
    } finally {
      TestParquetUtil.delete(Paths.get(parquetFiles));
    }
  }

  @Test
  public void testAggregationFilterOnNonExistentColumn() throws Exception {
    final String parquetFiles =
        TestParquetUtil.setupParquetFiles(
            "testAggregationFilterOnNonExistentColumn",
            "nonexistingcols",
            "bothcols.parquet",
            WORKING_PATH);
    try {
      testBuilder()
          .sqlQuery(
              "SELECT count(*) as cnt FROM dfs.\""
                  + parquetFiles
                  + "\" where col1 = 'doesnotexist'")
          .ordered()
          .baselineColumns("cnt")
          .baselineValues(0L)
          .go();

      testBuilder()
          .sqlQuery("SELECT count(*) as cnt FROM dfs.\"" + parquetFiles + "\"")
          .ordered()
          .baselineColumns("cnt")
          .baselineValues(2L)
          .go();

      testBuilder()
          .sqlQuery(
              "SELECT count(*) cnt FROM dfs.\"" + parquetFiles + "\" where col1='bothvalscol1'")
          .ordered()
          .baselineColumns("cnt")
          .baselineValues(1L)
          .go();
    } finally {
      TestParquetUtil.delete(Paths.get(parquetFiles));
    }
  }

  @Test
  public void testChainedVectorizedRowiseReaderCase() throws Exception {
    /*
     * Parquet A and B contain very different columns. We create a filter on a ParquetA column, and try to list ParquetB.
     * Column chosen for filter condition is vectorisable
     * Parquet B columns are complex, hence non vectorisable. These are delegated to rowwise reader.
     *
     * This case expects no records to be returned in the query when there's no match. Also, there shouldn't be an error.
     */

    final String parquetFiles =
        TestParquetUtil.setupParquetFiles(
            "testChainedVectorizedRowiseReaderNoResultCase",
            "chained_vectorised_rowwise_case",
            "yes_filter_col.parquet",
            WORKING_PATH);
    try {
      // No match case
      testBuilder()
          .sqlQuery("SELECT * FROM dfs.\"" + parquetFiles + "\" where filtercol = 'invalidstring'")
          .unOrdered()
          .baselineColumns("messageId", "complexFld1", "complexFld2", "filtercol")
          .expectsEmptyResultSet() // partition tinyint_part=65 doesn't have any rows.
          .go();

      // filter column not present in other parquet files.
      testBuilder()
          .sqlQuery(
              "SELECT complexFld1, complexFld2 FROM dfs.\""
                  + parquetFiles
                  + "\" where filtercol = '5'")
          .ordered()
          .baselineColumns("complexFld1", "complexFld2")
          .baselineValues(null, null)
          .go();

      // filter column not present; should match is null condition
      testBuilder()
          .sqlQuery(
              "SELECT complexFld1['cf1subfield1'] as fld1, complexFld2['cf2subfield2'] as fld2 FROM dfs.\""
                  + parquetFiles
                  + "\" where filtercol is null")
          .ordered()
          .baselineColumns("fld1", "fld2")
          .baselineValues("F1Val1", "F2Val2")
          .baselineValues("F1Val1", "F2Val2")
          .go();
    } finally {
      TestParquetUtil.delete(Paths.get(parquetFiles));
    }
  }

  @Test
  public void testZeroRowsParquetPromotion() throws Exception {
    try (AutoCloseable ac = withSystemOption(PARQUET_TEST_SCHEMA_FALLBACK_ONLY_VALIDATOR, true)) {
      final String parquetInputFile = WORKING_PATH + "/src/test/resources/parquet/zero_row.parquet";
      String parquetFiles = Files.createTempDirectory("zero_row_test").toString();
      Files.copy(
          Paths.get(parquetInputFile),
          Paths.get(parquetFiles),
          StandardCopyOption.REPLACE_EXISTING);
      testBuilder()
          .sqlQuery("select * from dfs.\"" + parquetFiles + "\"")
          .unOrdered()
          .expectsEmptyResultSet()
          .go();
    }
  }
}
