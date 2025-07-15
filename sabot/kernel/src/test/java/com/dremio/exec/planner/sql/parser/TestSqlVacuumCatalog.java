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
package com.dremio.exec.planner.sql.parser;

import static com.dremio.exec.calcite.SqlNodes.DREMIO_DIALECT;
import static com.dremio.exec.planner.sql.parser.TestParserUtil.parse;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.junit.Test;

/** Validates VACUUM CATALOG sql syntax */
public class TestSqlVacuumCatalog {
  private SqlPrettyWriter writer = new SqlPrettyWriter(DREMIO_DIALECT);

  @Test
  public void testDefaultCatalogOptions() throws SqlParseException {
    SqlNode parsed = parse("VACUUM CATALOG versionedCatalog");
    assertThat(parsed).isInstanceOf(SqlVacuumCatalog.class);

    SqlVacuumCatalog sqlVacuumCatalog = (SqlVacuumCatalog) parsed;
    assertThat(sqlVacuumCatalog.getCatalogSource().getSimple()).isEqualTo("versionedCatalog");
  }

  @Test
  public void testExcludeTables() throws SqlParseException {
    SqlNode parsed = parse("VACUUM CATALOG src EXCLUDE (t1, src.fldr1.t2, t3 AT BRANCH dev)");
    assertThat(parsed).isInstanceOf(SqlVacuumCatalog.class);

    SqlVacuumCatalog sqlVacuumCatalog = (SqlVacuumCatalog) parsed;
    assertThat(sqlVacuumCatalog.getCatalogSource().getSimple()).isEqualTo("src");
    sqlVacuumCatalog.unparse(writer, 0, 0);
    assertThat(writer.toString())
        .isEqualTo(
            "VACUUM CATALOG \"src\" EXCLUDE (\"t1\", \"src\".\"fldr1\".\"t2\", \"t3\" AT BRANCH dev)");
  }
}
