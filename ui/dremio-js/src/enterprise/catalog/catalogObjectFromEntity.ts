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
import { VersionedDatasetCatalogObject } from "../../oss/catalog/CatalogObjects/VersionedDatasetCatalogObject.ts";
import { FunctionCatalogReference } from "../../oss/catalog/CatalogReferences/FunctionCatalogReference.ts";
import type { EnterpriseCatalogObject } from "../interfaces.ts";
import {
  EnterpriseDatasetCatalogObject,
  enterpriseDatasetEntityToProperties,
} from "./CatalogObjects/EnterpriseDatasetCatalogObject.ts";
import { EnterpriseFileCatalogObject } from "./CatalogObjects/EnterpriseFileCatalogObject.ts";
import { EnterpriseFolderCatalogObject } from "./CatalogObjects/EnterpriseFolderCatalogObject.ts";
import { EnterpriseFunctionCatalogObject } from "./CatalogObjects/EnterpriseFunctionCatalogObject.ts";
import { EnterpriseHomeCatalogObject } from "./CatalogObjects/EnterpriseHomeCatalogObject.ts";
import {
  EnterpriseSourceCatalogObject,
  enterpriseSourceEntityToProperties,
} from "./CatalogObjects/EnterpriseSourceCatalogObject.ts";
import { EnterpriseSpaceCatalogObject } from "./CatalogObjects/EnterpriseSpaceCatalogObject.ts";

export const catalogObjectFromEntity =
  (config: SonarV3Config, retrieveByPath: any) =>
  (entity: any): EnterpriseCatalogObject => {
    {
      switch (entity.entityType) {
        case "EnterpriseFolder":
        case "folder":
          return EnterpriseFolderCatalogObject.fromResource(
            entity,
            config,
            retrieveByPath,
          ) as EnterpriseFolderCatalogObject;
        case "EnterpriseDataset":
        case "dataset": {
          try {
            JSON.parse(entity.id);
            return VersionedDatasetCatalogObject.fromResource(
              entity,
              retrieveByPath,
              config,
            ) as any;
          } catch (e) {
            // continue
          }
          return new EnterpriseDatasetCatalogObject(
            enterpriseDatasetEntityToProperties(entity, config, retrieveByPath),
          );
        }
        case "EnterpriseSource":
        case "source": {
          return new EnterpriseSourceCatalogObject(
            enterpriseSourceEntityToProperties(entity, config, retrieveByPath),
          );
        }

        case "home":
          return EnterpriseHomeCatalogObject.fromResource(
            entity,
            config,
            retrieveByPath,
          );
        case "EnterpriseSpace":
        case "space":
          return EnterpriseSpaceCatalogObject.fromResource(
            entity,
            config,
            retrieveByPath,
          );
        case "file":
          return EnterpriseFileCatalogObject.fromResource(
            entity,
            retrieveByPath,
          );
        case "EnterpriseFunction":
        case "function":
          return new EnterpriseFunctionCatalogObject({
            catalogReference: new FunctionCatalogReference(
              {
                id: entity.id,
                path: entity.path,
              },
              retrieveByPath,
            ),
            createdAt: new Date(entity.createdAt),
            id: entity.id,
            isScalar: entity.isScalar,
            lastModified: new Date(entity.lastModified),
            path: entity.path,
            returnType: entity.returnType,
            tag: entity.tag,
          });
        default:
          throw new Error("Unexpected " + entity.entityType);
      }
    }
  };
