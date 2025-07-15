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
} from "../../_internal/types/Config.ts";
import { Script, scriptEntityToProperties } from "./Script.ts";
import { Ok, Err } from "ts-results-es";
import { HttpError } from "../../common/HttpError.ts";
import { isProblem } from "../../common/Problem.ts";
import type { Query } from "../../common/Query.ts";
import type { SignalParam } from "../../_internal/types/Params.ts";
import {
  duplicateScriptNameError,
  scriptNotFoundError,
} from "./ScriptErrors.ts";

export const ScriptsResource = (config: ResourceConfig & SonarV3Config) => {
  const retrieve = (id: string, { signal }: SignalParam = {}) =>
    config
      .sonarV3Request(`scripts/${id}`, { signal })
      .then((res) => res.json())
      .then((entity) =>
        Ok(new Script(scriptEntityToProperties(entity), config)),
      )
      .catch((e: unknown) => {
        if (e instanceof HttpError && e.status === 404) {
          return Err(scriptNotFoundError(id));
        }
        return Err(e);
      });

  const store = (properties: { name: string; query: Query }) => {
    const { query, ...rest } = properties;
    return config
      .sonarV3Request("scripts", {
        body: JSON.stringify({
          ...rest,
          content: query.sql,
          context: query.context,
        }),
        headers: {
          "Content-Type": "application/json",
        },
        keepalive: true,
        method: "POST",
      })
      .then((res) => res.json())
      .then((entity) =>
        Ok(new Script(scriptEntityToProperties(entity), config)),
      )
      .catch((e: unknown) => {
        if (e instanceof HttpError && isProblem(e.body)) {
          if (e.body.detail?.includes("Cannot reuse the same script name"))
            return Err(duplicateScriptNameError(properties.name));
        }
        return Err(e);
      });
  };

  return {
    list() {
      return {
        async *data({ signal }: SignalParam = {}) {
          yield* await config
            .sonarV3Request("scripts?maxResults=1000", { signal })
            .then((res) => res.json())
            .then(
              (response) =>
                response.data.map(
                  (entity: any) =>
                    new Script(scriptEntityToProperties(entity), config),
                ) as Script[],
            );
        },
      };
    },
    retrieve,
    store,
  };
};
