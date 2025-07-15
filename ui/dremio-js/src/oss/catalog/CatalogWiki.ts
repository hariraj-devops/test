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
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import type { CatalogReference } from "../interfaces.ts";
import { HttpError } from "../../common/HttpError.ts";
import { versionConflictError } from "../../common/problems.ts";

export class CatalogWiki {
  readonly catalogReference: CatalogReference;
  readonly text: CatalogWikiProperties["text"];

  #version: CatalogWikiProperties["version"];
  #config: SonarV3Config;

  constructor(properties: CatalogWikiProperties, config: SonarV3Config) {
    this.catalogReference = properties.catalogReference;
    this.text = properties.text;
    this.#version = properties.version;
    this.#config = config;
  }

  update(properties: { text: string }) {
    return this.#config
      .sonarV3Request(
        `catalog/${this.catalogReference.id}/collaboration/wiki`,
        {
          body: JSON.stringify({
            text: properties.text,
            version: this.#version,
          }),
          headers: {
            "Content-Type": "application/json",
          },
          keepalive: true,
          method: "POST",
        },
      )
      .then((res) => res.json())
      .then((entity) =>
        Ok(
          new CatalogWiki(
            catalogWikiEntityToProperties(this.catalogReference, entity),
            this.#config,
          ),
        ),
      )
      .catch((e: unknown) => {
        if (e instanceof HttpError) {
          if (e.status === 409) {
            return Err(versionConflictError);
          }
          return Err(e);
        }
        throw e;
      });
  }
}

export const catalogWikiEntityToProperties = (
  catalogReference: CatalogReference,
  entity: {
    text: string;
    version: number;
  },
) => ({
  ...entity,
  catalogReference,
});

type CatalogWikiProperties = ReturnType<typeof catalogWikiEntityToProperties>;
