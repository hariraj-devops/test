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
package com.dremio.exec.util;

import static com.dremio.common.util.SchemaPathDeserializer.deserializeSchemaPath;

import com.dremio.common.expression.SchemaPath;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Map entry deserializer, specific to the SchemaPath entries. */
public class SchemaPathMapDeserializer extends StdDeserializer<Map<SchemaPath, SchemaPath>> {
  public SchemaPathMapDeserializer() {
    super(Map.class);
  }

  @Override
  public Map<SchemaPath, SchemaPath> deserialize(
      JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
    JsonNode node = jsonParser.getCodec().readTree(jsonParser);
    if (node == null) {
      return null;
    }
    Map<SchemaPath, SchemaPath> deserialized = new HashMap<>();
    node.fields()
        .forEachRemaining(
            mapEntry ->
                deserialized.put(
                    deserializeSchemaPath(mapEntry.getKey()),
                    deserializeSchemaPath(mapEntry.getValue().asText())));
    return deserialized;
  }
}
