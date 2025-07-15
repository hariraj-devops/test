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
package com.dremio.dac.server;

import static org.junit.Assume.assumeFalse;

import javax.ws.rs.core.Response;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests LivenessService. */
public class LivenessServiceTest extends BaseTestServer {

  @BeforeClass
  public static void init() throws Exception {
    assumeFalse(isMultinode());
    BaseTestServer.init();
  }

  @Test
  public void testMetricsEndpointIsUp() {
    expectSuccess(getBuilder(getHttpClient().getMetricsEndpoint()).buildGet());
  }

  @Test
  public void testMetricTraceMethodIsDisabled() {
    expectStatus(
        Response.Status.METHOD_NOT_ALLOWED,
        getBuilder(getHttpClient().getMetricsEndpoint()).build("TRACE"));
  }

  @Test
  public void testTraceMethodIsDisabledOnLivenessServer() {
    expectStatus(
        Response.Status.METHOD_NOT_ALLOWED,
        getBuilder(getHttpClient().getLivenessRoot()).build("TRACE"));
  }

  @Test
  public void testOptionsMethodIsDisabledOnLivenessServe() {
    expectStatus(
        Response.Status.METHOD_NOT_ALLOWED,
        getBuilder(getHttpClient().getLivenessRoot()).build("OPTIONS"));
  }
}
