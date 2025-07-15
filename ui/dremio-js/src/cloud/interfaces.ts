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

import type { ArcticCatalog } from "./arctic/ArcticCatalog.ts";
import type { CloudUser } from "./users/CloudUser.ts";
import type { Engine } from "./engines/Engine.ts";
import type { Project } from "./projects/Project.ts";

import type { Job, Reflection } from "../oss/interfaces.ts";
import type { EnterpriseScript } from "../enterprise/scripts/EnterpriseScript.ts";
import type { ReflectionSummary } from "../enterprise/reflections/ReflectionSummary.ts";
import type { Role } from "../enterprise/roles/Role.ts";
import type {
  BranchHeadVersionReference,
  BareCommitVersionReference,
  TagVersionReference,
  VersionReference,
} from "../oss/catalog/VersionReference.ts";

export type { ArcticCatalog, CloudUser, Engine, Project };
export type {
  Job,
  Reflection,
  EnterpriseScript,
  ReflectionSummary,
  Role,
  BranchHeadVersionReference,
  BareCommitVersionReference,
  TagVersionReference,
  VersionReference,
};
export * from "../enterprise/catalog/CatalogObjects/index.ts";
export * from "../enterprise/catalog/CatalogReferences/index.ts";
