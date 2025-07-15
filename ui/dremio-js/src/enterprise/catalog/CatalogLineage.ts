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

import type { SonarV3Config } from "../../_internal/types/Config.ts";
import type { RetrieveByPath } from "../../oss/catalog/CatalogReferences/BaseCatalogReference.ts";
import { EnterpriseSourceCatalogReference } from "./CatalogReferences/EnterpriseSourceCatalogReference.ts";

export class CatalogLineage {
  readonly sources: CatalogLineageProperties["sources"];

  constructor(properties: CatalogLineageProperties) {
    this.sources = properties.sources;
  }
}

export const catalogLineageEntityToProperties = (
  entity: {
    sources: {
      id: string;
      path: string[];
      tag: string;
      type: "CONTAINER";
      containerType: "SOURCE";
      createdAt: string;
    }[];
    parents: {
      id: string;
      path: string[];
      tag: string;
      type: string;
      datasetType: string;
      createdAt: string;
    }[];
    children: {
      id: string;
      path: string[];
      tag: string;
      type: string;
      datasetType: string;
      createdAt: string;
    }[];
  },
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
) => ({
  sources: entity.sources.map(
    (sourceEntity) =>
      new EnterpriseSourceCatalogReference(
        { id: sourceEntity.id, path: sourceEntity.path },
        config,
        retrieveByPath,
      ),
  ),
});

type CatalogLineageProperties = ReturnType<
  typeof catalogLineageEntityToProperties
>;
