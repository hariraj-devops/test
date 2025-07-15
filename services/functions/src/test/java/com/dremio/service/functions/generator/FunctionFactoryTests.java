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
package com.dremio.service.functions.generator;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.planner.sql.DremioCompositeSqlOperatorTable;
import com.dremio.service.functions.model.Function;
import com.dremio.service.functions.model.FunctionSignature;
import com.dremio.test.GoldenFileTestBuilder;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

/**
 * Tests for FunctionFactory that creates a Function from a SqlFunction and serializes the result
 * for golden file testing.
 */
public final class FunctionFactoryTests {
  private static final SabotConfig SABOT_CONFIG = SabotConfig.create();
  private static final ScanResult SCAN_RESULT = ClassPathScanner.fromPrescan(SABOT_CONFIG);
  private static final FunctionImplementationRegistry FUNCTION_IMPLEMENTATION_REGISTRY =
      FunctionImplementationRegistry.create(SABOT_CONFIG, SCAN_RESULT);
  private static final SqlOperatorTable OPERATOR_TABLE =
      DremioCompositeSqlOperatorTable.create(FUNCTION_IMPLEMENTATION_REGISTRY);
  private static final FunctionFactory FUNCTION_FACTORY =
      FunctionFactory.makeFunctionFactory(OPERATOR_TABLE);

  @Test
  public void production() {
    List<String> names =
        OPERATOR_TABLE.getOperatorList().stream()
            .filter(sqlOperator -> sqlOperator instanceof SqlFunction)
            .map(sqlOperator -> sqlOperator.getName().toUpperCase())
            .distinct()
            .sorted()
            .collect(Collectors.toList());

    GoldenFileTestBuilder.create((String functionName) -> executeTest(functionName))
        .addListByRule(names, (name) -> Pair.of(name, name))
        .runTests();
  }

  private Result executeTest(String functionName) {
    Function function =
        FunctionMerger.merge(
            OPERATOR_TABLE.getOperatorList().stream()
                .filter(sqlOperator -> sqlOperator instanceof SqlFunction)
                .filter(sqlFunction -> sqlFunction.getName().equalsIgnoreCase(functionName))
                .map(sqlOperator -> (SqlFunction) sqlOperator)
                .map(sqlFunction -> FUNCTION_FACTORY.fromSqlFunction(sqlFunction))
                .collect(ImmutableList.toImmutableList()));

    String name = function.getName();
    List<String> signatures = new ArrayList<>();
    for (FunctionSignature functionSignature : function.getSignatures()) {
      signatures.add(functionSignature.toString());
    }

    return new Result(name, signatures);
  }

  private static final class Result {
    private final String name;
    private final List<String> signatures;

    public Result(String name, List<String> signatures) {
      this.name = name;
      this.signatures = signatures;
    }

    public String getName() {
      return name;
    }

    public List<String> getSignatures() {
      return signatures;
    }
  }
}
