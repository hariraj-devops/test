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
import type {
  BaseCatalogReferenceProperties,
  RetrieveByPath,
} from "../BaseCatalogReference.ts";
import { DatasetCatalogReference } from "../DatasetCatalogReference.ts";
import { FileCatalogReference } from "../FileCatalogReference.ts";
import { FolderCatalogReference } from "../FolderCatalogReference.ts";
import { FunctionCatalogReference } from "../FunctionCatalogReference.ts";
import { HomeCatalogReference } from "../HomeCatalogReference.ts";
import { SourceCatalogReference } from "../SourceCatalogReference.ts";
import { SpaceCatalogReference } from "../SpaceCatalogReference.ts";
import type { CatalogReference } from "../index.ts";

export const catalogReferenceFromProperties = (
  properties: BaseCatalogReferenceProperties & { type: string },
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
): CatalogReference => {
  switch (properties.type) {
    case "DATASET_DIRECT":
    case "DATASET_PROMOTED":
    case "DATASET_VIRTUAL":
      return new DatasetCatalogReference(
        properties as any,
        retrieveByPath,
        config,
      );
    case "FILE":
      return new FileCatalogReference(properties, retrieveByPath);
    case "FOLDER":
      return new FolderCatalogReference(properties, config, retrieveByPath);
    case "FUNCTION":
      return new FunctionCatalogReference(properties, retrieveByPath);
    case "HOME":
      return new HomeCatalogReference(properties, config, retrieveByPath);
    case "SOURCE":
      return new SourceCatalogReference(properties, config, retrieveByPath);
    case "SPACE":
      return new SpaceCatalogReference(properties, config, retrieveByPath);
    default:
      throw new Error("Unknown catalogReference type: " + properties.type);
  }
};
