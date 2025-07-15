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
package com.dremio.dac.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import org.apache.arrow.vector.types.pojo.Field;

/** Serializes a list of arrow field definitions into JSON */
public class DatasetFieldsSerializer extends JsonSerializer<DatasetFields> {
  @Override
  public void serialize(
      DatasetFields fields, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
      throws IOException, JsonProcessingException {
    jsonGenerator.writeStartArray();

    for (Field field : fields.getFields()) {
      boolean isPartitioned =
          fields.getPartitionedFields() != null
              && fields.getPartitionedFields().contains(field.getName());
      boolean isSorted =
          fields.getSortedFields() != null && fields.getSortedFields().contains(field.getName());
      APIFieldDescriber.FieldDescriber describer =
          new APIFieldDescriber.FieldDescriber(
              jsonGenerator, field, isPartitioned, isSorted, false);
      field.getType().accept(describer);
    }

    jsonGenerator.writeEndArray();
  }
}
