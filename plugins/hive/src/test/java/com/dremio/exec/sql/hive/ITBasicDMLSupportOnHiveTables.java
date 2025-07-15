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
package com.dremio.exec.sql.hive;

import static com.dremio.exec.store.hive.HiveTestDataGenerator.HIVE_TEST_PLUGIN_NAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Paths;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.hive.LazyDataGeneratingHiveTestBase;
import com.dremio.exec.store.hive.HiveConfFactory;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.users.SystemUser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Tests for Basic DML support like Create, CTAS, Drop commands on Iceberg tables in Hive catalog.
 */
public class ITBasicDMLSupportOnHiveTables extends LazyDataGeneratingHiveTestBase {
  private static final String SCHEME = "file:///";
  private static String WAREHOUSE_LOCATION;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @BeforeClass
  public static void setup() throws Exception {
    WAREHOUSE_LOCATION = dataGenerator.getWhDir() + "/";
    dataGenerator.updatePluginConfig(getCatalogService(),
      ImmutableMap.of(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, SCHEME + WAREHOUSE_LOCATION,
        HiveConfFactory.ENABLE_DML_TESTS_WITHOUT_LOCKING, "true"));
  }

  @Test
  public void testCreateEmptyIcebergTable() throws Exception {
    final String tableName = "iceberg_test";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(n int)")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCTASCreateNewIcebergTable() throws Exception {
    final String tableName = "iceberg_test_ctas";
    final String newTableName = "iceberg_test_ctas1";
    final String tableNameWithCatalog = HIVE_TEST_PLUGIN_NAME + "." + tableName  ;
    final String newTableNameWithCatalog =  HIVE_TEST_PLUGIN_NAME + "." + newTableName;

    try {
      testBuilder()
        .sqlQuery(getCreateTableQuery(tableNameWithCatalog , "(n int)"))
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
      runSQL(getInsertQuery(tableNameWithCatalog, " (2) "));
      runSQL(getCTASQuery(tableNameWithCatalog, newTableNameWithCatalog));
      testBuilder()
        .sqlQuery(getSelectQuery(newTableNameWithCatalog))
        .unOrdered()
        .baselineColumns("n")
        .baselineValues(2)
        .go();
    } finally {
      dataGenerator.executeDDL(getDropTableQuery(tableName));
      dataGenerator.executeDDL(getDropTableQuery(newTableName));
    }
  }

  @Test
  public void testCreateEmptyIcebergTableOnLocation() throws Exception {
    final String tableName = "iceberg_test_location";
    final String queryTableLocation = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "default", "location").toString();
    try {
      testBuilder()
        .sqlQuery(String.format("Create table %s.%s(n int) LOCATION '%s'", HIVE_TEST_PLUGIN_NAME, tableName, queryTableLocation))
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();

      File tableFolder = new File(queryTableLocation);
      assertTrue("Error in checking if the " + tableFolder.toString() + " exists", tableFolder.exists());
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCTASCreateNewIcebergTableWithLocation() throws Exception {
    final String tableName = "iceberg_test_ctas1";
    final String tableNameWithCatalog = HIVE_TEST_PLUGIN_NAME + "." + tableName;
    final String tableLocationFolder = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "default", "location").toString();

    try {
      runSQL(getCTASQueryWithLocation("(values (1), (2), (3))", tableNameWithCatalog, tableLocationFolder));
      File tableFolder = new File(tableLocationFolder);
      assertTrue("Error in checking if the " + tableFolder.toString() + " exists", tableFolder.exists());
    } finally {
      dataGenerator.executeDDL(getDropTableQuery(tableName));
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithIdentityTransform() throws Exception {
    final String tableName = "iceberg_test_identity_transform";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int, c_double double, c_bigint bigint) partition by (c_int)")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithBucketTransform() throws Exception {
    final String tableName = "iceberg_test_bucket_transform";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int, c_double double, c_bigint bigint) partition by (bucket(3,c_bigint))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithTruncateTransform() throws Exception {
    final String tableName = "iceberg_test_truncate_transform";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int, c_varchar varchar, c_bigint bigint) partition by (truncate(3,c_varchar))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithYearTransformOnDateColumn() throws Exception {
    final String tableName = "iceberg_test_year_transform_on_date_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_date date ) partition by (year(c_date))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithMonthTransformOnDateColumn() throws Exception {
    final String tableName = "iceberg_test_month_transform_on_date_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_date date ) partition by (month(c_date))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithDayTransformOnDateColumn() throws Exception {
    final String tableName = "iceberg_test_day_transform_on_date_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_date date ) partition by (day(c_date))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithYearTransformOnTimestampColumn() throws Exception {
    final String tableName = "iceberg_test_year_transform_on_timestamp_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_timestamp timestamp ) partition by (year(c_timestamp))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithMonthTransformOnTimestampColumn() throws Exception {
    final String tableName = "iceberg_test_month_transform_on_timestamp_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_timestamp timestamp ) partition by (month(c_timestamp))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithDayTransformOnTimestampColumn() throws Exception {
    final String tableName = "iceberg_test_day_transform_on_timestamp_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_timestamp timestamp ) partition by (day(c_timestamp))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void testCreateEmptyIcebergTableWithHourTransformOnTimestampColumn() throws Exception {
    final String tableName = "iceberg_test_hour_transform_on_timestamp_column";

    try {
      testBuilder()
        .sqlQuery("Create table " + HIVE_TEST_PLUGIN_NAME + "." + tableName + "(c_int int,  c_double double, c_timestamp timestamp ) partition by (hour(c_timestamp))")
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }
  }

  @Test
  public void truncateEmptyTable() throws Exception {

    String tableName = "truncTable0";
    try {
      String createSql = String.format("create table %s.%s(id int, name varchar)", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(createSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();


      String truncSql = String.format("TRUNCATE TABLE %s.\"default\".%s ", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(truncSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + ".\"default\"." + tableName + "] truncated")
        .go();

    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }

  }

  @Ignore("DX-61689")
  @Test
  public void truncateOnCreateAtLocation() throws Exception {

    String tableName = "truncTable1";
    final String queryTableLocation = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "default", "location-trunc").toString();
    try {
      testBuilder()
        .sqlQuery(String.format("Create table %s.%s(n int) LOCATION '%s'", HIVE_TEST_PLUGIN_NAME, tableName, queryTableLocation))
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table created")
        .go();

      File tableFolder = new File(queryTableLocation);
      assertTrue("Error in checking if the " + tableFolder.toString() + " exists", tableFolder.exists());

      String truncSql = String.format("TRUNCATE TABLE %s.\"default\".%s ", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(truncSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + ".\"default\"." + tableName + "] truncated")
        .go();

    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }

  }

  @Test
  public void truncateEmptyTableWithExists() throws Exception {

    String tableName = "truncTable2";
    try {
      String createSql = String.format("create table %s.%s(id int, name varchar)", HIVE_TEST_PLUGIN_NAME, tableName);
      runSQL(createSql);
      String truncSql = String.format("TRUNCATE TABLE IF EXISTS  %s.\"default\".%s ", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(truncSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + ".\"default\"." + tableName + "] truncated")
        .go();
    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }

  }

  @Test
  public void truncateAndSelect() throws Exception {

    String tableName = "truncTable3";
    try {
      String ctas = String.format("create table %s.%s as SELECT * FROM INFORMATION_SCHEMA.CATALOGS", HIVE_TEST_PLUGIN_NAME, tableName);
      test(ctas);

      testBuilder()
        .sqlQuery(String.format("select count(*) c from %s.%s", HIVE_TEST_PLUGIN_NAME, tableName))
        .unOrdered()
        .baselineColumns("c")
        .baselineValues(1L)
        .build()
        .run();

      String truncSql = String.format("TRUNCATE TABLE %s.\"default\".%s ", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(truncSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + ".\"default\"." + tableName + "] truncated")
        .go();

      testBuilder()
        .sqlQuery(String.format("select count(*) c from %s.%s", HIVE_TEST_PLUGIN_NAME, tableName))
        .unOrdered()
        .baselineColumns("c")
        .baselineValues(0L)
        .build()
        .run();

    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }

  }

  @Test
  public void truncateInsertSelect() throws Exception {

    String tableName = "truncTable4";
    try {
      String ctas = String.format("create table %s.%s  as SELECT * FROM INFORMATION_SCHEMA.CATALOGS", HIVE_TEST_PLUGIN_NAME, tableName);
      runSQL(ctas);

      String insertSql = String.format("INSERT INTO %s.%s  select * FROM INFORMATION_SCHEMA.CATALOGS", HIVE_TEST_PLUGIN_NAME, tableName);
      runSQL(insertSql);
      runSQL(insertSql);
      runSQL(insertSql);

      testBuilder()
        .sqlQuery(String.format("select count(*) c from %s.%s ", HIVE_TEST_PLUGIN_NAME, tableName))
        .unOrdered()
        .baselineColumns("c")
        .baselineValues(4L)
        .build()
        .run();

      String truncSql = String.format("TRUNCATE TABLE %s.\"default\".%s ", HIVE_TEST_PLUGIN_NAME, tableName);
      testBuilder()
        .sqlQuery(truncSql)
        .unOrdered()
        .baselineColumns("ok", "summary")
        .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + ".\"default\"." + tableName + "] truncated")
        .go();

      testBuilder()
        .sqlQuery(String.format("select count(*) c from %s.%s ", HIVE_TEST_PLUGIN_NAME, tableName))
        .unOrdered()
        .baselineColumns("c")
        .baselineValues(0L)
        .build()
        .run();

      runSQL(insertSql);

      testBuilder()
        .sqlQuery(String.format("select count(*) c from %s.%s", HIVE_TEST_PLUGIN_NAME, tableName))
        .unOrdered()
        .baselineColumns("c")
        .baselineValues(1L)
        .build()
        .run();

    } finally {
      dataGenerator.executeDDL("DROP TABLE IF EXISTS " + tableName);
    }

  }

  @Test
  public void tableDoesNotExistShouldThrowError() throws Exception {
    String truncSql = "TRUNCATE TABLE " + HIVE_TEST_PLUGIN_NAME + ".truncTable5";
    assertThatThrownBy(() -> test(truncSql))
      .isInstanceOf(UserException.class)
      .hasMessageContaining("Table [" + HIVE_TEST_PLUGIN_NAME + ".truncTable5] does not exist.");
  }

  @Test
  public void tableDoesNotExistWithExistenceCheck() throws Exception {
    String tableName = "truncTable6";
    String truncSql = "TRUNCATE TABLE IF EXISTS " + HIVE_TEST_PLUGIN_NAME + "." + tableName;
    testBuilder()
      .sqlQuery(truncSql)
      .unOrdered()
      .baselineColumns("ok", "summary")
      .baselineValues(true, "Table [" + HIVE_TEST_PLUGIN_NAME + "." + tableName + "] does not exist.")
      .build()
      .run();

  }

  @Test
  public void tableDoesNotExistForDrop() throws Exception {
    String dropSql = "DROP TABLE " + HIVE_TEST_PLUGIN_NAME + ".dropTable";
    assertThatThrownBy(() -> test(dropSql))
      .isInstanceOf(UserException.class)
      .hasMessageContaining("Table [%s.dropTable] does not exist", HIVE_TEST_PLUGIN_NAME);

  }

  @Test
  public void testDropPartitionColumn() throws Exception {
    final String tableName = "iceberg_test_testDropPartitionColumn";

    try {
      runSQL(String.format("CREATE TABLE %s.%s(n int, p1 int) PARTITION BY (p1)", HIVE_TEST_PLUGIN_NAME, tableName));
      runSQL(String.format("ALTER TABLE %s.%s drop PARTITION FIELD p1", HIVE_TEST_PLUGIN_NAME, tableName));
      NamespaceService ns = getSabotContext().getNamespaceService(SystemUser.SYSTEM_USERNAME);
      DatasetConfig datasetConfig = ns.getDataset(new NamespaceKey(ImmutableList.of(HIVE_TEST_PLUGIN_NAME, "default", tableName)));

      assertTrue(CollectionUtils.isEmpty(datasetConfig.getReadDefinition().getPartitionColumnsList()));
    } finally {
      runSQL(String.format("DROP TABLE %s.%s", HIVE_TEST_PLUGIN_NAME, tableName));
    }
  }
}
