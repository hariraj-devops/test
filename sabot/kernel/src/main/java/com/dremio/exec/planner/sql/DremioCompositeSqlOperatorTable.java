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
package com.dremio.exec.planner.sql;

import static com.dremio.exec.planner.physical.PlannerSettings.ENABLE_EXPERIMENTAL_FUNCTIONS;
import static com.dremio.exec.planner.physical.PlannerSettings.ENABLE_TEST_ONLY_FUNCTIONS;

import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.options.OptionResolver;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlLibraryOperators;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlNameMatcher;

/** Composes all the SqlOperatorTables needed for Dremio. */
public final class DremioCompositeSqlOperatorTable {
  private static final SqlOperatorTable DREMIO_OT = DremioSqlOperatorTable.instance();
  private static final SqlOperatorTable STD_OT =
      FilteredSqlOperatorTable.create(
          SqlStdOperatorTable.instance(),
          SqlStdOperatorTable.ROUND,
          SqlStdOperatorTable.TRUNCATE,
          // REPLACE just uses the precision of it's first argument, which is problematic if the
          // string increases in length after replacement.
          SqlStdOperatorTable.REPLACE,
          // CARDINALITY in Calcite accepts MAP, LIST and STRUCT. In Dremio, we plan to support only
          // MAP and LIST.
          SqlStdOperatorTable.CARDINALITY,
          SqlStdOperatorTable.BIT_XOR);
  private static final SqlOperatorTable ORACLE_OT =
      FilteredSqlOperatorTable.create(
          SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(SqlLibrary.ORACLE),
          SqlLibraryOperators.LTRIM,
          SqlLibraryOperators.RTRIM,
          SqlLibraryOperators.SUBSTR, // calcite does not support oracles substring CALCITE-4408
          SqlLibraryOperators.DECODE, // Dremio currently uses hive decode
          SqlLibraryOperators.REGEXP_REPLACE, // Dremio already has implementation
          SqlLibraryOperators.TO_DATE, // Dremio already has implementation
          SqlLibraryOperators.TO_TIMESTAMP); // Dremio already has implementation

  private static final SqlOperatorTable TEST_ONLY_FUNCTIONS = TestOnlySqlOperatorTable.instance();
  private static final SqlOperatorTable EXPERIMENTAL_FUNCTIONS =
      ExperimentalSqlOperatorTable.instance();

  /**
   * These are the SqlOperators that are known at compile time. They will later get mixed in with
   * SqlOperators generated at runtime (like hive functions and UDFs).
   */
  private static final SqlOperatorTable COMPILE_TIME_OPERATOR_TABLE =
      ChainedSqlOperatorTable.of(DREMIO_OT, STD_OT, ORACLE_OT);

  private DremioCompositeSqlOperatorTable() {}

  public static SqlOperatorTable create() {
    return create((OptionResolver) null);
  }

  public static SqlOperatorTable create(OptionResolver optionResolver) {
    SqlOperatorTable chainedOperatorTable = COMPILE_TIME_OPERATOR_TABLE;

    if (optionResolver != null) {
      if (optionResolver.getOption(ENABLE_EXPERIMENTAL_FUNCTIONS)) {
        chainedOperatorTable =
            ChainedSqlOperatorTable.of(chainedOperatorTable, EXPERIMENTAL_FUNCTIONS);
      }

      if (optionResolver.getOption(ENABLE_TEST_ONLY_FUNCTIONS)) {
        chainedOperatorTable =
            ChainedSqlOperatorTable.of(chainedOperatorTable, TEST_ONLY_FUNCTIONS);
      }
    }

    return chainedOperatorTable;
  }

  public static SqlOperatorTable create(
      FunctionImplementationRegistry functionImplementationRegistry) {
    return create(functionImplementationRegistry, null);
  }

  public static SqlOperatorTable create(
      FunctionImplementationRegistry functionImplementationRegistry,
      OptionResolver optionResolver) {
    SqlOperatorTable functionRegistryOperatorTable =
        FilteredSqlOperatorTable.create(
            RuntimeSqlOperatorTable.create(functionImplementationRegistry.listOperators()),
            // SqlValidator has a convertlet that converts NVL for us, but only if it matches the
            // ORACLE operator and not the GANDIVA operator.
            "NVL",
            // The default SqlOperator generated from the Java code accepts ANY parameter, but we
            // only want it to be ARRAYs and MAPs.
            "CARDINALITY",
            // These types return ANY when they should return ARRAY
            "REGEXP_SPLIT",
            // We have one of these in DremioSqlOperatorTable which does the var args better.
            "CONCAT",
            // The autogenerated version of these have poor type inference and don't handle var-args
            "GREATEST",
            "LEAST",
            // We have manual versions of these in DremioSqlOperatorTable that are better:
            "ARRAY_MIN",
            "ARRAY_MAX",
            "ARRAY_SUM",
            "ARRAY_CONTAINS",
            "ARRAY_REMOVE");
    return create(functionRegistryOperatorTable, optionResolver);
  }

  public static SqlOperatorTable create(
      SqlOperatorTable sqlOperatorTable, OptionResolver optionResolver) {
    SqlOperatorTable compositeOperatorTable =
        ChainedSqlOperatorTable.of(create(optionResolver), sqlOperatorTable);
    return new IgnoreNullOpNameSqlOperatorTable(compositeOperatorTable);
  }

  private static class RuntimeSqlOperatorTable implements SqlOperatorTable {
    private final ArrayListMultimap<String, SqlOperator> map;

    private RuntimeSqlOperatorTable(ArrayListMultimap<String, SqlOperator> map) {
      this.map = map;
    }

    @Override
    public void lookupOperatorOverloads(
        SqlIdentifier opName,
        SqlFunctionCategory category,
        SqlSyntax syntax,
        List<SqlOperator> operatorList,
        SqlNameMatcher nameMatcher) {
      // The runtime generated functions do not properly generate category / syntax / and are case
      // insensitive
      // So we ignore checking that and just see if the names match up
      if (syntax != SqlSyntax.FUNCTION) {
        return;
      }

      if (!opName.isSimple()) {
        return;
      }

      List<SqlOperator> overloads = map.get(opName.getSimple().toUpperCase());
      operatorList.addAll(overloads);
    }

    @Override
    public List<SqlOperator> getOperatorList() {
      return map.values().stream().collect(Collectors.toList());
    }

    public static RuntimeSqlOperatorTable create(List<SqlOperator> sqlOperators) {
      ArrayListMultimap<String, SqlOperator> runtimeOperatorMap = ArrayListMultimap.create();
      for (SqlOperator sqlOperator : sqlOperators) {
        runtimeOperatorMap.put(sqlOperator.getName().toUpperCase(), sqlOperator);
      }

      return new RuntimeSqlOperatorTable(runtimeOperatorMap);
    }
  }

  /**
   * Takes a SqlOperatorTable, but ignores lookups for a select number of functions. This is needed,
   * since we don't want the default behavior or calcite's operator table, but we also don't want to
   * create a whole new operator table which may have different behavior.
   */
  private static final class FilteredSqlOperatorTable implements SqlOperatorTable {
    private final SqlOperatorTable sqlOperatorTable;
    private final ImmutableSet<String> functionsToFilter;

    private FilteredSqlOperatorTable(
        SqlOperatorTable sqlOperatorTable, ImmutableSet<String> functionsToFilter) {
      this.functionsToFilter = functionsToFilter;
      this.sqlOperatorTable = sqlOperatorTable;
    }

    @Override
    public void lookupOperatorOverloads(
        SqlIdentifier opName,
        SqlFunctionCategory category,
        SqlSyntax syntax,
        List<SqlOperator> operatorList,
        SqlNameMatcher nameMatcher) {
      if (opName.isSimple() && functionsToFilter.contains(opName.getSimple().toUpperCase())) {
        return;
      }

      sqlOperatorTable.lookupOperatorOverloads(opName, category, syntax, operatorList, nameMatcher);
    }

    @Override
    public List<SqlOperator> getOperatorList() {
      return sqlOperatorTable.getOperatorList().stream()
          .filter(operator -> !functionsToFilter.contains(operator.getName().toUpperCase()))
          .collect(Collectors.toList());
    }

    public static SqlOperatorTable create(
        SqlOperatorTable sqlOperatorTable, SqlOperator... operatorsToFilter) {
      ImmutableSet<String> functionsToFilter =
          Arrays.stream(operatorsToFilter)
              .map(operator -> operator.getName().toUpperCase())
              .collect(ImmutableSet.toImmutableSet());

      return new FilteredSqlOperatorTable(sqlOperatorTable, functionsToFilter);
    }

    public static SqlOperatorTable create(
        SqlOperatorTable sqlOperatorTable, String... operatorNamesToFilter) {
      ImmutableSet<String> functionsToFilter =
          Arrays.stream(operatorNamesToFilter)
              .map(operatorName -> operatorName.toUpperCase())
              .collect(ImmutableSet.toImmutableSet());

      return new FilteredSqlOperatorTable(sqlOperatorTable, functionsToFilter);
    }
  }

  /**
   * This proxy handles the fact that some functions (like udf arguments) have a null opName, So we
   * just want to ignore them to avoid NPE in the inner SqlOperatorTable. This works out, because
   * the only time it really matters that we match the overloads is during type validation. In
   * SqlFunction.deriveType we have SqlUtil.lookupRoutine(validator.getOperatorTable(),
   * getNameAsId() ... which generates a non null opName
   */
  private static final class IgnoreNullOpNameSqlOperatorTable implements SqlOperatorTable {
    private final SqlOperatorTable inner;

    public IgnoreNullOpNameSqlOperatorTable(SqlOperatorTable inner) {
      this.inner = inner;
    }

    @Override
    public void lookupOperatorOverloads(
        SqlIdentifier opName,
        SqlFunctionCategory category,
        SqlSyntax syntax,
        List<SqlOperator> operatorList,
        SqlNameMatcher nameMatcher) {
      if (opName == null) {
        return;
      }

      inner.lookupOperatorOverloads(opName, category, syntax, operatorList, nameMatcher);
    }

    @Override
    public List<SqlOperator> getOperatorList() {
      return inner.getOperatorList();
    }
  }
}
