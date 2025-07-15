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
  SonarV2Config,
  SonarV3Config,
  V3Config,
} from "../_internal/types/Config.ts";
import { JobsResource } from "../oss/jobs/JobsResource.ts";
import { Resources as CommunityResources } from "../oss/resources.ts";
import { EnterpriseCatalogResource } from "./catalog/EnterpriseCatalogResource.ts";
import { RolesResource } from "./roles/RolesResource.ts";
import { EnterpriseScriptsResource } from "./scripts/EnterpriseScriptsResource.ts";
import { EnterpriseUsersResource } from "./users/EnterpriseUsersResource.ts";

/**
 * @internal
 * @hidden
 */
export const Resources = (
  config: ResourceConfig & SonarV2Config & SonarV3Config & V3Config,
) => ({
  ...CommunityResources(config),
  catalog: EnterpriseCatalogResource(config),
  jobs: JobsResource(config),
  roles: RolesResource(config),
  scripts: EnterpriseScriptsResource(config),
  users: EnterpriseUsersResource(config),
});
