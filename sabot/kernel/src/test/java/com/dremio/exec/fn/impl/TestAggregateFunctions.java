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
package com.dremio.exec.fn.impl;

import com.dremio.BaseTestQuery;
import com.dremio.PlanTestBase;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.common.util.TestTools;
import com.google.common.collect.Lists;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Ignore;
import org.junit.Test;

public class TestAggregateFunctions extends BaseTestQuery {

  private static final String TEST_RES_PATH = TestTools.getWorkingPath() + "/src/test/resources";

  @Ignore
  @Test // DRILL-2092: count distinct, non distinct aggregate with group-by
  public void testDrill2092() throws Exception {
    String query =
        "select a1, b1, count(distinct c1) as dist1, \n"
            + "sum(c1) as sum1, count(c1) as cnt1, count(*) as cnt \n"
            + "from cp.\"agg/bugs/drill2092/input.json\" \n"
            + "group by a1, b1 order by a1, b1";

    String baselineQuery =
        "select case when columns[0]='null' then cast(null as bigint) else cast(columns[0] as bigint) end as a1, \n"
            + "case when columns[1]='null' then cast(null as bigint) else cast(columns[1] as bigint) end as b1, \n"
            + "case when columns[2]='null' then cast(null as bigint) else cast(columns[2] as bigint) end as dist1, \n"
            + "case when columns[3]='null' then cast(null as bigint) else cast(columns[3] as bigint) end as sum1, \n"
            + "case when columns[4]='null' then cast(null as bigint) else cast(columns[4] as bigint) end as cnt1, \n"
            + "case when columns[5]='null' then cast(null as bigint) else cast(columns[5] as bigint) end as cnt \n"
            + "from cp.\"agg/bugs/drill2092/result.tsv\"";

    // NOTE: this type of query gets rewritten by Calcite into an inner join of subqueries, so
    // we need to test with both hash join and merge join

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .optionSettingQueriesForTestQuery("alter system set \"planner.enable_hashjoin\" = true")
        .sqlBaselineQuery(baselineQuery)
        .build()
        .run();

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .optionSettingQueriesForTestQuery("alter system set \"planner.enable_hashjoin\" = false")
        .sqlBaselineQuery(baselineQuery)
        .build()
        .run();
  }

  @Test // DRILL-2170: Subquery has group-by, order-by on aggregate function and limit
  public void testDrill2170() throws Exception {
    String query =
        "select count(*) as cnt from "
            + "cp.\"tpch/orders.parquet\" o inner join\n"
            + "(select l_orderkey, sum(l_quantity), sum(l_extendedprice) \n"
            + "from cp.\"tpch/lineitem.parquet\" \n"
            + "group by l_orderkey order by 3 limit 100) sq \n"
            + "on sq.l_orderkey = o.o_orderkey";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .optionSettingQueriesForTestQuery("alter system set \"planner.slice_target\" = 1000")
        .baselineColumns("cnt")
        .baselineValues(100L)
        .build()
        .run();
  }

  @Test // DRILL-2168
  public void testGBExprWithFunction() throws Exception {
    testBuilder()
        .ordered()
        .sqlQuery(
            "select concat(n_name, cast(n_nationkey as varchar(10))) as name, count(*) as cnt "
                + "from cp.\"tpch/nation.parquet\" "
                + "group by concat(n_name, cast(n_nationkey as varchar(10))) "
                + "having concat(n_name, cast(n_nationkey as varchar(10))) > 'UNITED'"
                + "order by concat(n_name, cast(n_nationkey as varchar(10)))")
        .baselineColumns("name", "cnt")
        .baselineValues("UNITED KINGDOM23", 1L)
        .baselineValues("UNITED STATES24", 1L)
        .baselineValues("VIETNAM21", 1L)
        .build()
        .run();
  }

  @Test // DRILL-2242
  public void testDRILLNestedGBWithSubsetKeys() throws Exception {
    String sql =
        " select count(*) as cnt from (select l_partkey from\n"
            + "   (select l_partkey, l_suppkey from cp.\"tpch/lineitem.parquet\"\n"
            + "      group by l_partkey, l_suppkey) \n"
            + "   group by l_partkey )";

    test(
        "alter session set \"planner.slice_target\" = 1; alter session set \"planner.enable_multiphase_agg\" = false ;");

    testBuilder()
        .ordered()
        .sqlQuery(sql)
        .baselineColumns("cnt")
        .baselineValues(2000L)
        .build()
        .run();

    test(
        "alter session set \"planner.slice_target\" = 1; alter session set \"planner.enable_multiphase_agg\" = true ;");

    testBuilder()
        .ordered()
        .sqlQuery(sql)
        .baselineColumns("cnt")
        .baselineValues(2000L)
        .build()
        .run();

    test("alter session set \"planner.slice_target\" = 100000");
  }

  @Test
  public void testStddevOnKnownType() throws Exception {
    testBuilder()
        .sqlQuery("select stddev_samp(cast(employee_id as int)) as col from cp.\"employee.json\"")
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(333.56708470261117)
        .go();
  }

  @Test
  @Ignore("DRILL-4473")
  public void sumEmptyNonexistentNullableInput() throws Exception {
    final String query =
        "select "
            + "sum(int_col) col1, sum(bigint_col) col2, sum(float4_col) col3, sum(float8_col) col4, sum(interval_year_col) col5 "
            + "from cp.\"employee.json\" where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5")
        .baselineValues(null, null, null, null, null)
        .go();
  }

  @Test
  @Ignore("DRILL-4473")
  public void avgEmptyNonexistentNullableInput() throws Exception {
    // test avg function
    final String query =
        "select "
            + "avg(int_col) col1, avg(bigint_col) col2, avg(float4_col) col3, avg(float8_col) col4, avg(interval_year_col) col5 "
            + "from cp.\"employee.json\" where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2", "col3", "col4", "col5")
        .baselineValues(null, null, null, null, null)
        .go();
  }

  /*
   * Streaming agg on top of a filter produces wrong results if the first two batches are filtered out.
   * In the below test we have three files in the input directory and since the ordering of reading
   * of these files may not be deterministic, we have three tests to make sure we test the case where
   * streaming agg gets two empty batches.
   */
  @Test
  public void drill3069() throws Exception {
    final String query = "select max(foo) col1 from dfs.\"%s/agg/bugs/drill3069\" where foo = %d";
    testBuilder()
        .sqlQuery(String.format(query, TEST_RES_PATH, 2))
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(2L)
        .go();

    testBuilder()
        .sqlQuery(String.format(query, TEST_RES_PATH, 4))
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(4L)
        .go();

    testBuilder()
        .sqlQuery(String.format(query, TEST_RES_PATH, 6))
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(6L)
        .go();
  }

  @Test // DRILL-2748
  public void testPushFilterPastAgg() throws Exception {
    final String query =
        " select cnt "
            + " from (select n_regionkey, count(*) cnt from cp.\"tpch/nation.parquet\" group by n_regionkey) "
            + " where n_regionkey = 2 ";

    // Validate the plan
    final String[] expectedPlan = {"(?s)Filter.*(StreamAgg|HashAgg).*group=\\Q[{}]\\E.*Filter"};
    final String[] excludedPatterns = {};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(5L)
        .build()
        .run();

    // having clause
    final String query2 =
        " select count(*) cnt from cp.\"tpch/nation.parquet\" group by n_regionkey "
            + " having n_regionkey = 2 ";
    PlanTestBase.testPlanMatchingPatterns(query2, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(5L)
        .build()
        .run();
  }

  @Test
  public void testPushFilterInExprPastAgg() throws Exception {
    final String query =
        " select cnt "
            + " from (select n_regionkey, count(*) cnt from cp.\"tpch/nation.parquet\" group by n_regionkey) "
            + " where n_regionkey + 100 - 100 = 2 ";

    // Validate the plan
    final String[] expectedPlan = {"(?s)(StreamAgg|HashAgg).*Filter"};
    final String[] excludedPatterns = {"(?s)Filter.*(StreamAgg|HashAgg)"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(5L)
        .build()
        .run();
  }

  @Test
  public void testNegPushFilterInExprPastAgg() throws Exception {
    // negative case: should not push filter, since it involves the aggregate result
    final String query =
        " select cnt "
            + " from (select n_regionkey, count(*) cnt from cp.\"tpch/nation.parquet\" group by n_regionkey) "
            + " where cnt + 100 - 100 = 5 ";

    // Validate the plan
    final String[] expectedPlan = {"(?s)Filter(?!StreamAgg|!HashAgg)"};
    final String[] excludedPatterns = {"(?s)(StreamAgg|HashAgg).*Filter"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    // negative case: should not push filter, since it is expression of group key + agg result.
    final String query2 =
        " select cnt "
            + " from (select n_regionkey, count(*) cnt from cp.\"tpch/nation.parquet\" group by n_regionkey) "
            + " where cnt + n_regionkey = 5 ";
    PlanTestBase.testPlanMatchingPatterns(query2, expectedPlan, excludedPatterns);
  }

  @Test // DRILL-3781
  // GROUP BY System functions in schema table.
  public void testGroupBySystemFuncSchemaTable() throws Exception {
    final String query =
        "select count(*) as cnt from INFORMATION_SCHEMA.CATALOGS group by CURRENT_DATE";
    final String[] expectedPlan = {"(?s)(StreamAgg|HashAgg)"};
    final String[] excludedPatterns = {};

    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);
  }

  @Test // DRILL-3781
  // GROUP BY System functions in csv, parquet, json table.
  public void testGroupBySystemFuncFileSystemTable() throws Exception {
    final String query =
        String.format(
            "select count(*) as cnt from dfs.\"%s/nation/nation.tbl\" group by CURRENT_DATE",
            TEST_RES_PATH);
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(25L)
        .build()
        .run();

    final String query2 =
        "select count(*) as cnt from cp.\"tpch/nation.parquet\" group by CURRENT_DATE";
    testBuilder()
        .sqlQuery(query2)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(25L)
        .build()
        .run();

    final String query3 = "select count(*) as cnt from cp.\"employee.json\" group by CURRENT_DATE";
    testBuilder()
        .sqlQuery(query3)
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(1155L)
        .build()
        .run();
  }

  @Test
  public void test4443() throws Exception {
    test("SELECT MIN(columns[1]) FROM dfs.\"%s/agg/4443.csv\" GROUP BY columns[0]", TEST_RES_PATH);
  }

  @Test
  public void testCountStarRequired() throws Exception {
    final String query = "select count(*) as col from cp.\"tpch/region.parquet\"";
    List<Pair<SchemaPath, MajorType>> expectedSchema = Lists.newArrayList();
    MajorType majorType = Types.required(MinorType.BIGINT);
    expectedSchema.add(Pair.of(SchemaPath.getSimplePath("col"), majorType));

    testBuilder().sqlQuery(query).schemaBaseLine(expectedSchema).build().run();

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(5L)
        .build()
        .run();
  }

  @Test // DRILL-4531
  public void testPushFilterDown() throws Exception {
    final String sql =
        "SELECT  cust.custAddress, \n"
            + "       lineitem.provider \n"
            + "FROM ( \n"
            + "      SELECT cast(c_custkey AS bigint) AS custkey, \n"
            + "             c_address                 AS custAddress \n"
            + "      FROM   cp.\"tpch/customer.parquet\" ) cust \n"
            + "LEFT JOIN \n"
            + "  ( \n"
            + "    SELECT DISTINCT l_linenumber, \n"
            + "           CASE \n"
            + "             WHEN l_partkey IN (1, 2) THEN 'Store1'\n"
            + "             WHEN l_partkey IN (5, 6) THEN 'Store2'\n"
            + "           END AS provider \n"
            + "    FROM  cp.\"tpch/lineitem.parquet\" \n"
            + "    WHERE ( l_orderkey >=20160101 AND l_partkey <=20160301) \n"
            + "      AND   l_partkey IN (1,2, 5, 6) ) lineitem\n"
            + "ON        cust.custkey = lineitem.l_linenumber \n"
            + "WHERE     provider IS NOT NULL \n"
            + "GROUP BY  cust.custAddress, \n"
            + "          lineitem.provider \n"
            + "ORDER BY  cust.custAddress, \n"
            + "          lineitem.provider";

    // Validate the plan
    final String[] expectedPlan = {
      "(?s)(Join).*inner"
    }; // With filter pushdown, left join will be converted into inner join
    final String[] excludedPatterns = {"(?s)(Join).*(left)"};
    PlanTestBase.testPlanMatchingPatterns(sql, expectedPlan, excludedPatterns);
  }
}
