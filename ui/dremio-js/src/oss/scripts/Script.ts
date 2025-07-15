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
import { Err, Ok } from "ts-results-es";
import { Query } from "../../common/Query.ts";
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import { HttpError } from "../../common/HttpError.ts";
import { duplicateScriptNameError } from "./ScriptErrors.ts";

export class Script {
  readonly createdAt: ScriptProperties["createdAt"];
  readonly createdBy: ScriptProperties["createdBy"];
  readonly id: ScriptProperties["id"];
  readonly modifiedAt: ScriptProperties["modifiedAt"];
  readonly modifiedBy: ScriptProperties["modifiedBy"];
  readonly name: ScriptProperties["name"];
  readonly query: ScriptProperties["query"];
  #config: SonarV3Config;

  constructor(properties: ScriptProperties, config: SonarV3Config) {
    this.createdAt = properties.createdAt;
    this.createdBy = properties.createdBy;
    this.id = properties.id;
    this.modifiedAt = properties.modifiedAt;
    this.modifiedBy = properties.modifiedBy;
    this.name = properties.name;
    this.query = properties.query;
    this.#config = config;
  }

  async delete() {
    return this.#config
      .sonarV3Request(`scripts/${this.id}`, {
        keepalive: true,
        method: "DELETE",
      })
      .then(() => Ok(undefined))
      .catch((e: unknown) => Err(e));
  }

  async save(properties: ScriptPatchableProperties) {
    return saveScript(
      this.id,
      this.#config,
    )(properties).then((result) =>
      result.map(
        (value) => new Script(scriptEntityToProperties(value), this.#config),
      ),
    );
  }
}

export const scriptEntityToProperties = (entity: ScriptEntity) => ({
  createdAt: Temporal.Instant.from(entity.createdAt),
  createdBy: entity.createdBy,
  id: entity.id,
  modifiedAt: Temporal.Instant.from(entity.modifiedAt),
  modifiedBy: entity.modifiedBy,
  name: entity.name,
  query: new Query(entity.content, entity.context),
});

export type ScriptEntity = {
  content: string;
  context: string[];
  createdAt: string;
  createdBy: string;
  id: string;
  modifiedAt: string;
  modifiedBy: string;
  name: string;
};

export type ScriptPatchableProperties = {
  name?: ScriptProperties["name"];
  query?: ScriptProperties["query"];
};

export type ScriptProperties = ReturnType<typeof scriptEntityToProperties>;

export const createScriptPatchedFields = (
  properties: ScriptPatchableProperties,
) => {
  const patchedFields = {} as {
    name?: string;
    content?: string;
    context?: string[];
  };
  if (properties.name) {
    patchedFields.name = properties.name;
  }
  if (properties.query) {
    patchedFields.content = properties.query.sql;
    patchedFields.context = properties.query.context;
  }
  return patchedFields;
};

export const saveScript =
  (id: string, config: SonarV3Config) => (body: Record<string, any>) => {
    return config
      .sonarV3Request(`scripts/${id}`, {
        body: JSON.stringify(body),
        headers: {
          "Content-Type": "application/json",
        },
        keepalive: true,
        method: "PATCH",
      })
      .then((res) => res.json())
      .then((entity: ScriptEntity) => Ok(entity))
      .catch((e: unknown) => {
        if (e instanceof HttpError) {
          if (e.body.detail?.includes("Cannot reuse the same script name")) {
            return Err(duplicateScriptNameError(body["name"]));
          }
          return Err(e.body);
        }

        throw e;
      });
  };
