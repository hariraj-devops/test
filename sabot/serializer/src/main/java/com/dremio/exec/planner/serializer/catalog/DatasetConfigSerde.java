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
package com.dremio.exec.planner.serializer.catalog;

import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.protobuf.ByteString;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;

public class DatasetConfigSerde {

  public static ByteString toProto(DatasetConfig datasetConfig) {
    if (datasetConfig == null) {
      return ByteString.EMPTY;
    }
    LinkedBuffer buffer = LinkedBuffer.allocate();

    byte[] bytes = ProtostuffIOUtil.toByteArray(datasetConfig, DatasetConfig.getSchema(), buffer);
    return ByteString.copyFrom(bytes);
  }

  public static DatasetConfig fromProto(ByteString proto) {
    if (proto.isEmpty()) {
      return null;
    }
    DatasetConfig datasetConfig = new DatasetConfig();
    ProtostuffIOUtil.mergeFrom(proto.toByteArray(), datasetConfig, DatasetConfig.getSchema());
    return datasetConfig;
  }
}
