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
package com.dremio.exec.planner.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.BaseTestQuery;
import java.util.Properties;
import org.junit.BeforeClass;
import org.junit.Test;

/** Ensure that the protobuf serializer is used when it is available on the path. */
public class TestProtoExplainJson extends BaseTestQuery {

  @BeforeClass
  public static void setupClass() throws Exception {
    final Properties properties = cloneDefaultTestConfigProperties();
    properties.setProperty(
        "dremio.planning.serializer",
        "com.dremio.exec.planner.serializer.ProtoRelSerializerFactory");
    updateClient(properties);
  }

  @Test
  public void basicQuerySerializes() throws Exception {
    assertThat(getValueInFirstRecord("explain json for select 1", "json"))
        .contains("PLogicalValues");
  }
}
