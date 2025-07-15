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
  DatasetCatalogObject,
  datasetEntityToProperties,
  type DatasetEntity,
} from "../../../oss/catalog/CatalogObjects/DatasetCatalogObject.ts";
import type { RetrieveByPath } from "../../../oss/catalog/CatalogReferences/BaseCatalogReference.ts";
import type { Grantee } from "../../Grantee.ts";

import { EnterpriseDatasetCatalogReference } from "../CatalogReferences/EnterpriseDatasetCatalogReference.ts";

export class EnterpriseDatasetCatalogObject extends DatasetCatalogObject {
  declare catalogReference: EnterpriseDatasetCatalogReference;
  readonly owner?: Grantee;

  constructor(properties: EnterpriseDatasetCatalogObjectProperties) {
    super(properties);
    this.owner = properties.owner;
  }

  grants() {
    return this.catalogReference.grants();
  }
}

export type EnterpriseDatasetCatalogObjectProperties = ReturnType<
  typeof enterpriseDatasetEntityToProperties
> & { owner?: Grantee };

type EnterpriseDatasetEntity = DatasetEntity & {
  owner?: { ownerId: string; ownerType: "ROLE" | "USER" };
};

export const enterpriseDatasetEntityToProperties = (
  entity: EnterpriseDatasetEntity,
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
) => {
  const datasetProperties = datasetEntityToProperties(
    entity,
    config,
    retrieveByPath,
  );
  return {
    ...datasetProperties,
    catalogReference: new EnterpriseDatasetCatalogReference(
      {
        id: entity.id,
        path: entity.path,
        type: datasetProperties.type,
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
