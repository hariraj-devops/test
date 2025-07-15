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

import type {
  ResourceConfig,
  SonarV3Config,
  V3Config,
} from "../_internal/types/Config.ts";
import { ArcticResource } from "./arctic/ArcticResource.ts";
import { EnterpriseCatalogResource } from "../enterprise/catalog/EnterpriseCatalogResource.ts";
import { RolesResource } from "../enterprise/roles/RolesResource.ts";
import { EnterpriseScriptsResource } from "../enterprise/scripts/EnterpriseScriptsResource.ts";
import { EnginesResource } from "./engines/EnginesResource.ts";
import { ProjectsResource } from "./projects/ProjectsResource.ts";
import { CloudUsersResource } from "./users/CloudUsersResource.ts";
import { JobsResource } from "../oss/jobs/JobsResource.ts";
import moize from "moize";

/**
 * moize is required on these resources because they're recreated every time they're called
 * with a different or the same projectId, and this breaks caching across `batch` collectors
 */

/**
 * @internal
 * @hidden
 */
export const Resources = (
  config: (projectId: string) => ResourceConfig & SonarV3Config & V3Config,
) => ({
  //@ts-ignore
  arctic: moize((projectId: string) => ArcticResource(config(projectId))) as (
    projectId: string,
  ) => ReturnType<typeof ArcticResource>,
  //@ts-ignore
  catalog: moize((projectId: string) =>
    EnterpriseCatalogResource(config(projectId)),
  ) as (projectId: string) => ReturnType<typeof EnterpriseCatalogResource>,

  //@ts-ignore
  engines: moize((projectId: string) => EnginesResource(config(projectId))) as (
    projectId: string,
  ) => ReturnType<typeof EnginesResource>,

  //@ts-ignore
  jobs: moize((projectId: string) =>
    JobsResource(config(projectId) as any),
  ) as (projectId: string) => ReturnType<typeof JobsResource>,

  projects: ProjectsResource(config(null as any)),

  //@ts-ignore
  // reflections: moize((projectId: string) =>
  //   ReflectionsResource(config(projectId)),
  // ) as (projectId: string) => ReturnType<typeof ReflectionsResource>,

  //@ts-ignore
  roles: moize((projectId: string) => RolesResource(config(projectId))) as (
    projectId: string,
  ) => ReturnType<typeof RolesResource>,

  //@ts-ignore
  scripts: moize((projectId: string) =>
    EnterpriseScriptsResource(config(projectId)),
  ) as (projectId: string) => ReturnType<typeof EnterpriseScriptsResource>,

  //@ts-ignore
  users: moize((projectId: string) =>
    CloudUsersResource(config(projectId)),
  ) as (projectId: string) => ReturnType<typeof CloudUsersResource>,
});
