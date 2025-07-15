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
import type { RoleGrantee, UserGrantee } from "../Grantee.ts";
import type { Ownable } from "../Ownable.ts";
import {
  createScriptPatchedFields,
  saveScript,
  Script,
  scriptEntityToProperties,
  type ScriptEntity,
  type ScriptPatchableProperties,
} from "../../oss/scripts/Script.ts";
import { Err, Ok, Result } from "ts-results-es";
import type { SignalParam } from "../../_internal/types/Params.ts";

export class EnterpriseScript extends Script implements Ownable {
  readonly owner: UserGrantee;
  #config: SonarV3Config;

  constructor(properties: EnterpriseScriptProperties, config: SonarV3Config) {
    super(properties, config);
    this.owner = properties.owner;
    this.#config = config;
  }

  grants({ signal }: SignalParam = {}) {
    return this.#config
      .sonarV3Request(`scripts/${this.id}/grants`, { signal })
      .then((res) => res.json())
      .then(
        (entity: {
          roles: { granteeId: string; privileges: ScriptPrivileges[] }[];
          users: { granteeId: string; privileges: ScriptPrivileges[] }[];
        }) =>
          Ok([
            ...entity.roles.map((grant) => ({
              grantee: {
                id: grant.granteeId,
                type: "ROLE",
              } satisfies RoleGrantee as RoleGrantee,
              privileges: new Set(grant.privileges),
            })),
            ...entity.users.map((grant) => ({
              grantee: {
                id: grant.granteeId,
                type: "USER",
              } satisfies UserGrantee as UserGrantee,
              privileges: new Set(grant.privileges),
            })),
          ]),
      )
      .catch((e: unknown) => Err(e));
  }

  override async save(
    properties: ScriptPatchableProperties & { owner?: UserGrantee },
  ) {
    return saveScript(
      this.id,
      this.#config,
    )({
      ...createScriptPatchedFields(properties),
      ...(properties.owner && { owner: properties.owner.id }),
    }).then((result) =>
      result.map(
        (value: EnterpriseScriptEntity) =>
          new EnterpriseScript(
            enterpriseScriptEntityToProperties(value),
            this.#config,
          ),
      ),
    );
  }
}

export type EnterpriseScriptProperties = ReturnType<
  typeof enterpriseScriptEntityToProperties
>;

type EnterpriseScriptEntity = ScriptEntity & { owner?: string };

export const enterpriseScriptEntityToProperties = (
  entity: EnterpriseScriptEntity,
) => ({
  ...scriptEntityToProperties(entity),
  owner: {
    id: entity.owner as string,
    type: "USER",
  } satisfies UserGrantee as UserGrantee,
});

type ScriptPrivileges = "DELETE" | "MANAGE_GRANTS" | "MODIFY" | "VIEW";
