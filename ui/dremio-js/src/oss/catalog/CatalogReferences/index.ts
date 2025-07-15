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

import type { DatasetCatalogReference } from "./DatasetCatalogReference.ts";
import type { FileCatalogReference } from "./FileCatalogReference.ts";
import type { FolderCatalogReference } from "./FolderCatalogReference.ts";
import type { FunctionCatalogReference } from "./FunctionCatalogReference.ts";
import type { HomeCatalogReference } from "./HomeCatalogReference.ts";
import type { SourceCatalogReference } from "./SourceCatalogReference.ts";
import type { SpaceCatalogReference } from "./SpaceCatalogReference.ts";

export type {
  DatasetCatalogReference,
  FileCatalogReference,
  FolderCatalogReference,
  FunctionCatalogReference,
  HomeCatalogReference,
  SourceCatalogReference,
  SpaceCatalogReference,
};

export type CatalogReference =
  | DatasetCatalogReference
  | FileCatalogReference
  | FolderCatalogReference
  | FunctionCatalogReference
  | HomeCatalogReference
  | SourceCatalogReference
  | SpaceCatalogReference;
