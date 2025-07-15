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

import type { VersionedDatasetCatalogObject } from "../../../oss/interfaces.ts";
import type { EnterpriseDatasetCatalogObject } from "./EnterpriseDatasetCatalogObject.ts";
import type { EnterpriseFileCatalogObject } from "./EnterpriseFileCatalogObject.ts";
import type { EnterpriseFolderCatalogObject } from "./EnterpriseFolderCatalogObject.ts";
import type { EnterpriseFunctionCatalogObject } from "./EnterpriseFunctionCatalogObject.ts";
import type { EnterpriseHomeCatalogObject } from "./EnterpriseHomeCatalogObject.ts";
import type { EnterpriseSourceCatalogObject } from "./EnterpriseSourceCatalogObject.ts";
import type { EnterpriseSpaceCatalogObject } from "./EnterpriseSpaceCatalogObject.ts";

export type {
  EnterpriseDatasetCatalogObject,
  EnterpriseFileCatalogObject,
  EnterpriseFolderCatalogObject,
  EnterpriseFunctionCatalogObject,
  EnterpriseHomeCatalogObject,
  EnterpriseSourceCatalogObject,
  EnterpriseSpaceCatalogObject,
};

export type EnterpriseCatalogObject =
  | EnterpriseDatasetCatalogObject
  | EnterpriseFileCatalogObject
  | EnterpriseFolderCatalogObject
  | EnterpriseFunctionCatalogObject
  | EnterpriseHomeCatalogObject
  | EnterpriseSourceCatalogObject
  | EnterpriseSpaceCatalogObject
  | VersionedDatasetCatalogObject;
