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
import type { CredentialProvider } from "../../common/credentials/CredentialProvider.ts";
import { HttpError } from "../../common/HttpError.ts";

export const fromUsernamePassword = (
  username: string,
  password: string,
): CredentialProvider => {
  return {
    get: (config) =>
      (config.fetch || globalThis.fetch)(
        new URL("/apiv2/login", config.origin).toString(),
        {
          body: JSON.stringify({ password, userName: username }),
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json",
          },
          method: "POST",
        },
      )
        .then(async (res) => {
          if (!res.ok) {
            throw await HttpError.fromResponse(res);
          }
          return res.json();
        })
        .then((res) => res.token),
  };
};
