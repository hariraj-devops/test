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

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.dac.cluster.ClusterIdResponse;
import org.junit.Test;

public class TestClusterId extends BaseTestServer {
  @Test
  public void testClusterId() {
    var clusterIdResponse =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("/cluster/id")).buildGet(),
            ClusterIdResponse.class);
    assertThat(clusterIdResponse.getId()).isNotBlank();
  }
}
