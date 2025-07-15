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

import type { SonarV3Config } from "../../../_internal/types/Config.ts";
import {
  SourceCatalogObject,
  sourceEntityToProperties,
} from "../../../oss/catalog/CatalogObjects/SourceCatalogObject.ts";
import type { RetrieveByPath } from "../../../oss/catalog/CatalogReferences/BaseCatalogReference.ts";
import type { Grantee } from "../../Grantee.ts";
import { EnterpriseSourceCatalogReference } from "../CatalogReferences/EnterpriseSourceCatalogReference.js";

export class EnterpriseSourceCatalogObject extends SourceCatalogObject {
  declare catalogReference: EnterpriseSourceCatalogReference;
  readonly owner?: Grantee;

  constructor(properties: EnterpriseSourceCatalogObjectProperties) {
    super(properties);
    this.owner = properties.owner;
  }

  grants() {
    return this.catalogReference.grants();
  }
}

export const enterpriseSourceEntityToProperties = (
  entity: any,
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
) => {
  const datasetProperties = sourceEntityToProperties(
    entity,
    config,
    retrieveByPath,
  );
  return {
    ...datasetProperties,
    catalogReference: new EnterpriseSourceCatalogReference(
      {
        id: entity.id,
        path: [entity.name], // Note: Currently the backend does not return a path, derive it from the name
      },
      config,
      retrieveByPath,
    ),
    owner: entity.owner
      ? {
          id: entity.owner.ownerId,
          type: entity.owner.ownerType,
        }
      : undefined,
  };
};

export type EnterpriseSourceCatalogObjectProperties = ReturnType<
  typeof enterpriseSourceEntityToProperties
>;
