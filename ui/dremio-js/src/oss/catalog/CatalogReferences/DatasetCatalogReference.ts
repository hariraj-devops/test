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
import type { Result } from "ts-results-es";
import {
  BaseCatalogReference,
  type BaseCatalogReferenceProperties,
  type RetrieveByPath,
} from "./BaseCatalogReference.ts";
import type { DatasetCatalogObject } from "../CatalogObjects/DatasetCatalogObject.ts";
import { getTags } from "./utils/getTags.ts";
import type { SonarV3Config } from "../../../_internal/types/Config.ts";

export class DatasetCatalogReference extends BaseCatalogReference {
  readonly type: `DATASET_${"DIRECT" | "PROMOTED" | "VIRTUAL"}`;
  #retrieveByPath: RetrieveByPath;
  #config: SonarV3Config;

  constructor(
    properties: BaseCatalogReferenceProperties & {
      type: `DATASET_${"DIRECT" | "PROMOTED" | "VIRTUAL"}`;
    },
    retrieveByPath: RetrieveByPath,
    config: SonarV3Config,
  ) {
    super(properties);
    this.type = properties.type;
    this.#retrieveByPath = retrieveByPath;
    this.#config = config;
  }

  catalogObject() {
    return this.#retrieveByPath(this.path) as Promise<
      Result<DatasetCatalogObject, unknown>
    >;
  }

  tags() {
    return getTags(this.#config, this)();
  }
}

export const mappedType = {
  PHYSICAL_DATASET: "DATASET_PROMOTED",
  VIRTUAL_DATASET: "DATASET_VIRTUAL",
} as const;
