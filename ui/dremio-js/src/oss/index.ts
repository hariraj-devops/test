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

import { createRequest } from "../_internal/createRequest.ts";
import type {
  Config,
  ResourceConfig,
  SonarV2Config,
  SonarV3Config,
} from "../_internal/types/Config.ts";
import { Resources } from "./resources.ts";
import { HttpError } from "../common/HttpError.ts";

const getSonarResourceConfig = (config: Config) => {
  const request = createRequest(config);
  return {
    logger: config.logger,
    request,
    sonarV2Request: (path, init) => request(`/apiv2/${path}`, init),
    sonarV3Request: (path, init) => request(`/api/v3/${path}`, init),
  } satisfies ResourceConfig & SonarV2Config & SonarV3Config;
};

export const Dremio = (config: Config) => {
  const sonarResourceConfig = getSonarResourceConfig(config);
  return {
    ...Resources(sonarResourceConfig),
    _request: sonarResourceConfig.request,
    _sonarResourceConfig: sonarResourceConfig,
    _sonarV2Request: sonarResourceConfig.sonarV2Request,
    _sonarV3Request: sonarResourceConfig.sonarV3Request,
  };
};

export * from "../common/Problem.ts";
export * from "../common/Query.ts";
export * from "./credentials/index.ts";
export * from "./interfaces.ts";
export { createRequest, HttpError, Resources as _Resources };
