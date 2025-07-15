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
package com.dremio.service.flight.impl;

import com.dremio.service.flight.BaseFlightQueryTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.arrow.vector.util.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ITFlightSqlPreparedStatements extends BaseFlightQueryTest {

  @BeforeClass
  public static void setupDefaultTestCluster() throws Exception {
    setupBaseFlightQueryTest(
        false,
        true,
        "flight.endpoint.port",
        FlightWorkManager.RunQueryResponseHandlerFactory.DEFAULT);
  }

  @Before
  public void initTables() throws Exception {
    runSQL(
        "create table dfs_test.flight("
            + "a boolean, "
            + "b int, "
            + "c bigint, "
            + "d float, "
            + "e double, "
            + "f decimal(2,1), "
            + "g varchar, "
            + "h varbinary, "
            + "i date, "
            + "j time, "
            + "k timestamp)");
    runSQL(
        "insert into dfs_test.flight values("
            + "true, "
            + "1, "
            + "1, "
            + "1.1, "
            + "1.1, "
            + "1.1, "
            + "'abc', "
            + "cast('abc' as varbinary), "
            + "'2000-01-01', "
            + "'17:30:50', "
            + "'2000-01-01 17:30:50')");
  }

  @After
  public void cleanupTables() throws Exception {
    runSQL("drop table dfs_test.flight");
  }

  @Test
  public void testParameterTypes() throws Exception {
    flightTestBuilder()
        .sqlQuery(
            "select * from dfs_test.flight where "
                + "a = ? and "
                + "b = ? and "
                + "c = ? and "
                + "d = ? and "
                + "e = ? and "
                + "f = ? and "
                + "g = ? and "
                + "h = ? and "
                + "i = ? and "
                + "j = ? and "
                + "k = ?")
        .parameters(
            true,
            1,
            1L,
            1.1F,
            1.1D,
            new BigDecimal("1.1"),
            new Text("abc"),
            "abc".getBytes(),
            LocalDateTime.of(2000, 1, 1, 0, 0),
            LocalDateTime.of(1970, 1, 1, 17, 30, 50),
            LocalDateTime.of(2000, 1, 1, 17, 30, 50))
        .baselineColumns("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")
        .baselineValues(
            true,
            1,
            1L,
            1.1F,
            1.1D,
            new BigDecimal("1.1"),
            new Text("abc"),
            "abc".getBytes(),
            LocalDateTime.of(2000, 1, 1, 0, 0),
            LocalDateTime.of(1970, 1, 1, 17, 30, 50),
            LocalDateTime.of(2000, 1, 1, 17, 30, 50))
        .go();
  }

  @Test
  public void testNullParameters() throws Exception {
    flightTestBuilder()
        .sqlQuery(
            "select * from dfs_test.flight where "
                + "a = ? and "
                + "b = ? and "
                + "c = ? and "
                + "d = ? and "
                + "e = ? and "
                + "f = ? and "
                + "g = ? and "
                + "h = ? and "
                + "i = ? and "
                + "j = ? and "
                + "k = ?")
        .parameters(null, null, null, null, null, null, null, null, null, null, null)
        .baselineColumns("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")
        .expectsEmptyResultSet()
        .go();
  }
}
