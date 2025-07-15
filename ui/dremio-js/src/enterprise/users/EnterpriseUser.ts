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

import { Err, Ok } from "ts-results-es";
import type { V3Config } from "../../_internal/types/Config.ts";
import type { SignalParam } from "../../_internal/types/Params.ts";
import { User, userEntityToProperties } from "../../oss/users/User.ts";

type UserTokensResponse = {
  data: {
    tid: string;
    uid: string;
    label: string;
    createdAt: string;
    expiresAt: string;
  }[];
};

export class EnterpriseUser extends User {
  #config: V3Config;

  constructor(properties: EnterpriseUserProperties, config: V3Config) {
    super(properties);
    this.#config = config;
  }

  tokens({ signal }: SignalParam = {}) {
    return this.#config
      .v3Request(`user/${this.id}/token`, {
        headers: { Accept: "application/json" },
        signal,
      })
      .then((res) => res.json() as Promise<UserTokensResponse>)
      .then((response) =>
        Ok(
          response.data.map((token) => ({
            createdAt: new Date(token.createdAt),
            expiresAt: new Date(token.expiresAt),
            id: token.tid,
            label: token.label,
          })),
        ),
      )
      .catch((e) => Err(e));
  }
}

export const enterpriseUserEntityToProperties = (entity: any) => ({
  ...userEntityToProperties(entity),
});

export type EnterpriseUserProperties = ReturnType<
  typeof enterpriseUserEntityToProperties
>;
