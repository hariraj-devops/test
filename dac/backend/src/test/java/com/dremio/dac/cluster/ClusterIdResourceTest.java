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
package com.dremio.dac.cluster;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.dac.proto.model.source.ClusterIdentity;
import com.dremio.dac.support.SupportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("checkstyle:VisibilityModifier")
@ExtendWith(MockitoExtension.class)
public class ClusterIdResourceTest {
  @Mock SupportService supportService;
  @InjectMocks ClusterIdResource clusterIdResource;

  @Test
  void idIsMemoized() {
    var expectedClusterId = randomUUID().toString();
    var clusterIdentity = new ClusterIdentity().setIdentity(expectedClusterId);

    when(supportService.getClusterId()).thenReturn(clusterIdentity);

    var response = clusterIdResource.id();
    assertThat(response.getId()).isEqualTo(expectedClusterId);

    response = clusterIdResource.id();
    assertThat(response.getId()).isEqualTo(expectedClusterId);

    verify(supportService, times(1)).getClusterId();
  }
}
