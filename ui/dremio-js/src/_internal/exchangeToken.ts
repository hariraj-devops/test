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

import { HttpError } from "../common/HttpError.ts";
import type { Config } from "./types/Config.ts";
import { type Problem } from "../common/Problem.ts";

export type ExchangeToken = (subject_token: string) => Promise<string>;

export const exchangeToken =
  (endpoint: URL, config: Config): ExchangeToken =>
  (subject_token) => {
    const body = new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:token-exchange",
      scope: "dremio.all",
      subject_token,
      subject_token_type:
        "urn:ietf:params:oauth:token-type:dremio:personal-access-token",
    });

    return (config.fetch || globalThis.fetch)(endpoint.toString(), {
      body,
      headers: {
        Accept: "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
      },
      method: "POST",
    })
      .then(async (res) => {
        if (!res.ok) {
          throw await HttpError.fromResponse(res);
        }

        return res.json() as Promise<{ access_token: string }>;
      })
      .then((response) => response["access_token"])
      .catch((e) => {
        if (e instanceof HttpError) {
          if (e.status === 401) {
            config.logger?.warn(invalidSubjectToken);
            throw new Error(invalidSubjectToken.title, {
              cause: invalidSubjectToken,
            });
          }
        }
        throw e;
      });
  };

export const invalidSubjectToken = {
  title: "The provided subject_token is invalid.",
  type: "https://api.dremio.dev/problems/oauth2/invalid-subject-token",
} as const satisfies Problem;
