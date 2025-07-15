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

const mapFieldValue = (fieldType: string, fieldValue: unknown) => {
  switch (fieldType) {
    case "TIMESTAMP":
      return new Date((fieldValue as string) + "Z");
    case "BIGINT":
      return BigInt(fieldValue as number);
    default:
      return fieldValue;
  }
};

export const createRowTypeMapper = (schema: {
  fields: { name: string; type: { name: string } }[];
}) => {
  const fieldTypes = schema.fields.reduce((accum, field) => {
    accum.set(field.name, field.type.name);
    return accum;
  }, new Map<string, string>());

  return (row: Record<string, unknown>) => {
    for (const property in row) {
      row[property] = mapFieldValue(fieldTypes.get(property)!, row[property]);
    }
  };
};
