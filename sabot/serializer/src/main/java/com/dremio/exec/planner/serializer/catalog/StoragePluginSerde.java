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

import com.dremio.exec.catalog.StoragePluginId;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.StringReader;

public class StoragePluginSerde {

  public static ByteString storagePluginIdToBson(
      StoragePluginId storagePluginId, CatalogToProto toProto) {
    if (storagePluginId == null) {
      return ByteString.empty();
    }
    try {
      String value = toProto.getObjectWriter().writeValueAsString(storagePluginId);
      return ByteString.copyFrom(value, "UTF-8");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static StoragePluginId storagePluginIdFromBson(
      ByteString bson, CatalogFromProto fromProto) {
    if (bson.isEmpty()) {
      return null;
    }
    try {
      String json = bson.toStringUtf8();
      return fromProto.getObjectReader().readValue(new StringReader(json), StoragePluginId.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
