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
import { Err, Ok, type Result } from "ts-results-es";
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import { engineEntityToProperties, enginePropertiesToEntity } from "./utils.ts";

export class Engine implements EngineInterface {
  readonly activeReplicas: EngineInterface["activeReplicas"];
  readonly additionalEngineStateInfo: EngineInterface["additionalEngineStateInfo"];
  readonly configuration: EngineInterface["configuration"];
  readonly id: EngineInterface["id"];
  readonly instanceFamily: EngineInterface["instanceFamily"];
  readonly name: EngineInterface["name"];
  readonly state: EngineInterface["state"];
  readonly statusChangedAt: EngineInterface["statusChangedAt"];
  readonly queriedAt: EngineInterface["queriedAt"];

  #config: SonarV3Config;

  constructor(properties: EngineProperties, config: SonarV3Config) {
    this.activeReplicas = properties.activeReplicas;
    this.additionalEngineStateInfo = properties.additionalEngineStateInfo;
    this.configuration = properties.configuration;
    this.id = properties.id;
    this.instanceFamily = properties.instanceFamily;
    this.name = properties.name;
    this.state = properties.state;
    this.statusChangedAt = properties.statusChangedAt;
    this.queriedAt = properties.queriedAt;
    this.#config = config;
  }

  update(
    properties: Partial<EngineConfiguration>,
  ): Promise<Result<Engine, unknown>> {
    return this.#config
      .sonarV3Request(`engines/${this.id}`, {
        body: JSON.stringify({
          ...enginePropertiesToEntity(this.configuration),
          ...enginePropertiesToEntity(properties),
        }),
        headers: {
          "Content-Type": "application/json",
        },
        method: "PUT",
      })
      .then((res) => res.json())
      .then((properties) =>
        Ok(new Engine(engineEntityToProperties(properties), this.#config)),
      )
      .catch((e) => Err(e));
  }
}

export type EngineSize =
  | "XX_SMALL_V1"
  | "X_SMALL_V1"
  | "SMALL_V1"
  | "MEDIUM_V1"
  | "LARGE_V1"
  | "X_LARGE_V1"
  | "XX_LARGE_V1"
  | "XXX_LARGE_V1";

export type EngineConfiguration = {
  readonly autoStopDelay: Temporal.Duration;
  readonly cloudTags: { key: string; value: string }[];
  readonly description: string | null;
  readonly drainTimeLimit: Temporal.Duration;
  readonly maxConcurrency: number;
  readonly maxReplicas: number;
  readonly minReplicas: number;
  readonly queueTimeLimit: Temporal.Duration;
  readonly runTimeLimit: Temporal.Duration;
  readonly size: EngineSize;
};

export type EngineProperties = {
  readonly activeReplicas: number;
  readonly additionalEngineStateInfo: "NONE";
  readonly configuration: EngineConfiguration;
  readonly id: string;
  readonly instanceFamily: unknown;
  readonly name: string;
  readonly state:
    | "DELETING"
    | "DISABLED"
    | "DISABLING"
    | "ENABLED"
    | "ENABLING"
    | "INVALID";
  readonly statusChangedAt: Date;
  readonly queriedAt: Date | null;
};

export type EngineMethods = {
  update(
    properties: Partial<EngineConfiguration>,
  ): Promise<Result<Engine, unknown>>;
};

export type EngineInterface = EngineProperties & EngineMethods;
