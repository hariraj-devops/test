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
import type {
  BaseCatalogReferenceProperties,
  RetrieveByPath,
} from "../../oss/catalog/CatalogReferences/BaseCatalogReference.ts";
import { type EnterpriseCatalogReference } from "./CatalogReferences/index.ts";
import { EnterpriseDatasetCatalogReference } from "./CatalogReferences/EnterpriseDatasetCatalogReference.ts";
import { EnterpriseFileCatalogReference } from "./CatalogReferences/EnterpriseFileCatalogReference.ts";
import { EnterpriseFolderCatalogReference } from "./CatalogReferences/EnterpriseFolderCatalogReference.ts";
import { EnterpriseFunctionCatalogReference } from "./CatalogReferences/EnterpriseFunctionCatalogReference.ts";
import { EnterpriseHomeCatalogReference } from "./CatalogReferences/EnterpriseHomeCatalogReference.ts";
import { EnterpriseSourceCatalogReference } from "./CatalogReferences/EnterpriseSourceCatalogReference.ts";
import { EnterpriseSpaceCatalogReference } from "./CatalogReferences/EnterpriseSpaceCatalogReference.ts";

export const catalogReferenceFromProperties = (
  properties: BaseCatalogReferenceProperties & { type: string },
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
): EnterpriseCatalogReference => {
  switch (properties.type) {
    case "DATASET_DIRECT":
    case "DATASET_PROMOTED":
    case "DATASET_VIRTUAL":
      return new EnterpriseDatasetCatalogReference(
        properties as any,
        config,
        retrieveByPath,
      );
    case "FILE":
      return new EnterpriseFileCatalogReference(properties, retrieveByPath);
    case "FOLDER":
      return new EnterpriseFolderCatalogReference(
        properties,
        config,
        retrieveByPath,
      );
    case "FUNCTION":
      return new EnterpriseFunctionCatalogReference(properties, retrieveByPath);
    case "HOME":
      return new EnterpriseHomeCatalogReference(
        properties,
        config,
        retrieveByPath,
      );
    case "SOURCE":
      return new EnterpriseSourceCatalogReference(
        properties,
        config,
        retrieveByPath,
      );
    case "SPACE":
      return new EnterpriseSpaceCatalogReference(
        properties,
        config,
        retrieveByPath,
      );
    default:
      throw new Error("Unknown catalogReference type: " + properties.type);
  }
};
