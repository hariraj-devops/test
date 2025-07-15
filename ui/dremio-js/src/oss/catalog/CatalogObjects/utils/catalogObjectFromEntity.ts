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

import type { SonarV3Config } from "../../../../_internal/types/Config.ts";
import { FunctionCatalogObject } from "../FunctionCatalogObject.ts";

import {
  DatasetCatalogObject,
  datasetEntityToProperties,
} from "../DatasetCatalogObject.ts";
import { FileCatalogObject } from "../FileCatalogObject.ts";
import { FolderCatalogObject } from "../FolderCatalogObject.ts";
import { HomeCatalogObject } from "../HomeCatalogObject.ts";
import {
  SourceCatalogObject,
  sourceEntityToProperties,
} from "../SourceCatalogObject.ts";
import { SpaceCatalogObject } from "../SpaceCatalogObject.ts";
import type { RetrieveByPath } from "../../CatalogReferences/BaseCatalogReference.ts";
import { VersionedDatasetCatalogObject } from "../VersionedDatasetCatalogObject.ts";
import { FunctionCatalogReference } from "../../CatalogReferences/FunctionCatalogReference.ts";

export const catalogObjectFromEntity =
  (config: SonarV3Config, retrieveByPath: RetrieveByPath) => (entity: any) => {
    {
      switch (entity.entityType) {
        case "EnterpriseFolder":
        case "folder":
          return FolderCatalogObject.fromResource(
            entity,
            config,
            retrieveByPath,
          );
        case "EnterpriseDataset":
        case "dataset": {
          try {
            JSON.parse(entity.id);
            return VersionedDatasetCatalogObject.fromResource(
              entity,
              retrieveByPath as any,
              config,
            );
          } catch (e) {
            // continue
          }
          return new DatasetCatalogObject(
            datasetEntityToProperties(entity, config, retrieveByPath),
          );
        }
        case "EnterpriseSource":
        case "source": {
          return new SourceCatalogObject(
            sourceEntityToProperties(entity, config, retrieveByPath),
          );
        }

        case "home":
          return HomeCatalogObject.fromResource(entity, config, retrieveByPath);
        case "EnterpriseSpace":
        case "space":
          return SpaceCatalogObject.fromResource(
            entity,
            config,
            retrieveByPath,
          );
        case "file":
          return FileCatalogObject.fromResource(entity, retrieveByPath);
        case "EnterpriseFunction":
        case "function":
          return new FunctionCatalogObject({
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
