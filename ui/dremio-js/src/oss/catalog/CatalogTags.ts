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

export class CatalogTags {
  readonly catalogReference: CatalogReference;
  readonly tags: string[];

  #version: CatalogTagsProperties["version"];
  #config: SonarV3Config;

  constructor(properties: CatalogTagsProperties, config: SonarV3Config) {
    this.catalogReference = properties.catalogReference;
    this.tags = properties.tags;
    this.#version = properties.version;
    this.#config = config;
  }

  update(properties: { tags: string[] }) {
    return this.#config
      .sonarV3Request(`catalog/${this.catalogReference.id}/collaboration/tag`, {
        body: JSON.stringify({
          tags: properties.tags,
          version: this.#version,
        }),
        headers: {
          "Content-Type": "application/json",
        },
        keepalive: true,
        method: "POST",
      })
      .then((res) => res.json())
      .then((entity) =>
        Ok(
          new CatalogTags(
            catalogTagsEntityToProperties(this.catalogReference, entity),
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

export const catalogTagsEntityToProperties = (
  catalogReference: CatalogReference,
  entity: {
    tags: string[];
    version: number;
  },
) => ({
  ...entity,
  catalogReference,
});

type CatalogTagsProperties = ReturnType<typeof catalogTagsEntityToProperties>;
