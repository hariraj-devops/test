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
package com.dremio.dac.explore.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class TestDatasetSummary {

  @Test
  public void testCreateNewInstance() {
    DatasetSummary.newInstance(
        new DatasetConfig().setType(DatasetType.PHYSICAL_DATASET),
        0,
        0,
        Collections.emptyMap(),
        Collections.emptyList(),
        false,
        NameSpaceContainer.Type.SPACE);
  }

  @Test
  public void testGetLinksSpace() {
    var datasetSummary =
        DatasetSummary.newInstance(
            new DatasetConfig()
                .setType(DatasetType.PHYSICAL_DATASET)
                .setFullPathList(Arrays.asList("spacename", "foo")),
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyList(),
            false,
            NameSpaceContainer.Type.SPACE);

    Map<String, String> expectedLinks =
        Map.of(
            "jobs",
                "/jobs?filters=%7B%22ads%22%3A%5B%22spacename.foo%22%5D%2C%22qt%22%3A%5B%22UI%22%2C%22EXTERNAL%22%5D%7D",
            "query", "/space/spacename/foo",
            "self", "/space/spacename/dataset/foo");

    assertThat(datasetSummary.getLinks()).isEqualTo(expectedLinks);
  }

  @Test
  public void testGetLinksSource() {
    var datasetSummary =
        DatasetSummary.newInstance(
            new DatasetConfig()
                .setType(DatasetType.PHYSICAL_DATASET)
                .setFullPathList(Arrays.asList("my", "source")),
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyList(),
            false,
            NameSpaceContainer.Type.SOURCE);

    Map<String, String> expectedLinks =
        Map.of(
            "jobs",
                "/jobs?filters=%7B%22ads%22%3A%5B%22my.source%22%5D%2C%22qt%22%3A%5B%22UI%22%2C%22EXTERNAL%22%5D%7D",
            "query", "/source/my/source",
            "self", "/source/my/dataset/source");

    assertThat(datasetSummary.getLinks()).isEqualTo(expectedLinks);
  }

  @Test
  public void testGetLinksHome() {
    var datasetSummary =
        DatasetSummary.newInstance(
            new DatasetConfig()
                .setType(DatasetType.PHYSICAL_DATASET)
                .setFullPathList(Arrays.asList("@my", "homie")),
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyList(),
            false,
            NameSpaceContainer.Type.HOME);

    Map<String, String> expectedLinks =
        Map.of(
            "jobs",
                "/jobs?filters=%7B%22ads%22%3A%5B%22%5C%22%40my%5C%22.homie%22%5D%2C%22qt%22%3A%5B%22UI%22%2C%22EXTERNAL%22%5D%7D",
            "query", "/home/%40my/homie",
            "self", "/home/%40my/dataset/homie");

    assertThat(datasetSummary.getLinks()).isEqualTo(expectedLinks);
  }

  @Test
  public void testGetLinksTemp() {
    var datasetSummary =
        DatasetSummary.newInstance(
            new DatasetConfig()
                .setType(DatasetType.PHYSICAL_DATASET)
                .setFullPathList(Arrays.asList("tmp", "temporary")),
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyList(),
            false,
            NameSpaceContainer.Type.FOLDER);

    Map<String, String> expectedLinks =
        Map.of(
            "jobs",
                "/jobs?filters=%7B%22ads%22%3A%5B%22tmp.temporary%22%5D%2C%22qt%22%3A%5B%22UI%22%2C%22EXTERNAL%22%5D%7D",
            "query", "/tmp/tmp/temporary",
            "self", "/tmp/tmp/dataset/temporary");

    assertThat(datasetSummary.getLinks()).isEqualTo(expectedLinks);
  }

  @Test
  public void testGetLinksOther() {
    var datasetSummary =
        DatasetSummary.newInstance(
            new DatasetConfig()
                .setType(DatasetType.PHYSICAL_DATASET)
                .setFullPathList(Arrays.asList("funny", "function")),
            0,
            0,
            Collections.emptyMap(),
            Collections.emptyList(),
            false,
            NameSpaceContainer.Type.FUNCTION);

    assertThatThrownBy(datasetSummary::getLinks)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unexpected rootContainerType: FUNCTION for name: funny");
  }
}
