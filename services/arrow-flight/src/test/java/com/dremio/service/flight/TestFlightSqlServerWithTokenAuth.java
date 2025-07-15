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

package com.dremio.service.flight;

import static com.dremio.service.flight.BaseFlightQueryTest.setupBaseFlightQueryTest;

import com.dremio.common.util.TestTools;
import com.dremio.service.flight.impl.FlightWorkManager;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.flight.CallOption;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/** Test FlightServer with bearer token authentication using FlightSql producer. */
@RunWith(Enclosed.class)
public class TestFlightSqlServerWithTokenAuth {

  @Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(180, TimeUnit.SECONDS);

  private static void setup() throws Exception {
    setupBaseFlightQueryTest(
        false,
        true,
        "flight.endpoint.port",
        FlightWorkManager.RunQueryResponseHandlerFactory.DEFAULT);
  }

  /** Query execution tests. */
  public static class QueryExecutionTests extends AbstractTestFlightSqlServer {
    public QueryExecutionTests(ExecutionMode executionMode) {
      super(executionMode);
    }

    @BeforeClass
    public static void setupDefaultTestCluster() throws Exception {
      TestFlightSqlServerWithTokenAuth.setup();
    }

    @Override
    protected String getAuthMode() {
      return DremioFlightService.FLIGHT_AUTH2_AUTH_MODE;
    }

    @Override
    CallOption[] getCallOptions() {
      final FlightClientUtils.FlightClientWrapper wrapper = getFlightClientWrapper();
      return new CallOption[] {wrapper.getTokenCallOption()};
    }
  }

  /** Catalog methods tests. */
  public static class CatalogMethodsTests extends AbstractTestFlightSqlServerCatalogMethods {
    @BeforeClass
    public static void setupDefaultTestCluster() throws Exception {
      TestFlightSqlServerWithTokenAuth.setup();
    }

    @Override
    CallOption[] getCallOptions() {
      final FlightClientUtils.FlightClientWrapper wrapper = getFlightClientWrapper();
      return new CallOption[] {wrapper.getTokenCallOption()};
    }
  }
}
