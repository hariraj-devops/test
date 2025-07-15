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
import type { SonarV3Config } from "../../../_internal/types/Config.ts";

import {
  retrieveCatalogGrants,
  type CatalogGrants,
} from "./retrieveCatalogGrants.ts";
import { FolderCatalogReference } from "../../../oss/catalog/CatalogReferences/FolderCatalogReference.ts";
import type {
  BaseCatalogReferenceProperties,
  RetrieveByPath,
} from "../../../oss/catalog/CatalogReferences/BaseCatalogReference.ts";

type FolderAvailablePrivileges =
  | "ALTER"
  | "ALTER REFLECTION"
  | "DELETE"
  | "DROP"
  | "INSERT"
  | "MANAGE GRANTS"
  | "OWNERSHIP"
  | "SELECT"
  | "TRUNCATE"
  | "UPDATE"
  | "VIEW REFLECTION";

export class EnterpriseFolderCatalogReference extends FolderCatalogReference {
  #config: SonarV3Config;

  constructor(
    properties: BaseCatalogReferenceProperties,
    config: SonarV3Config,
    retrieveByPath: RetrieveByPath,
  ) {
    super(properties, config, retrieveByPath);
    this.#config = config;
  }

  async grants() {
    return retrieveCatalogGrants(this.#config)(this) as Promise<
      Result<CatalogGrants<FolderAvailablePrivileges>, unknown>
    >;
  }
}
