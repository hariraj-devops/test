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
package com.dremio.dac.support;

import static com.dremio.dac.support.ClusterIdSupplier.DREMIO_CLUSTER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
class ClusterIdSupplierTest {
  @SystemStub private EnvironmentVariables environmentVariables;
  private ClusterIdSupplier supplier = spy(new ClusterIdSupplier());

  @Test
  void testEnvVar() {
    var clusterId = UUID.randomUUID().toString();
    environmentVariables.set(DREMIO_CLUSTER_ID, clusterId);

    var actualId = supplier.get();
    assertThat(actualId).isEqualTo(clusterId);
  }

  @Test
  void testRandom() {
    var clusterId = UUID.randomUUID().toString();
    when(supplier.random()).thenReturn(clusterId);

    var actualId = supplier.get();
    assertThat(actualId).isEqualTo(clusterId);
  }
}
