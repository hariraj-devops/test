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
package com.dremio.exec.server;

import static com.dremio.exec.ExecConstants.SLICE_TARGET_OPTION;

import com.dremio.BaseTestQuery;
import com.dremio.exec.ExecConstants;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

/**
 * To run this unit class you need to download the following data file:
 * http://apache-drill.s3.amazonaws.com/files/tpcds-sf1-parquet.tgz and untar it in a some folder
 * (e.g. /tpcds-sf1-parquet) then add the following workspace to
 * exec/java-exec/src/test/resources/bootstrap-storage-plugins.json
 *
 * <p>,"tpcds" : { location: "/tpcds-sf1-parquet", writable: false }
 */
@Ignore
public class TestTpcdsSf1Leaks extends BaseTestQuery {

  @Rule public final TestRule TIMEOUT = new Timeout(0); // wait forever

  @BeforeClass
  public static void initCluster() {
    updateTestCluster(3, null);
  }

  @Test
  public void testSortSpill() throws Exception {
    final String query =
        "CREATE TABLE dfs_test.test PARTITION BY (ss_store_sk) LOCALSORT BY (ss_customer_sk) AS SELECT * FROM dfs_test.tpcds.store_sales";
    try (AutoCloseable ignored = withOption(ExecConstants.TEST_MEMORY_LIMIT, 1000000000L)) {
      testSql(query);
    }
  }

  @Test
  public void test() throws Exception {
    try (AutoCloseable ignored = withOption(SLICE_TARGET_OPTION, 10)) {
      final String query = getFile("tpcds-sf1/q73.sql");
      for (int i = 0; i < 20; i++) {
        System.out.printf("%nRun #%d%n", i + 1);
        runSQL(query);
      }
    }
  }
}
