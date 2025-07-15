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

import type { Config, RequestFn } from "./types/Config.ts";
import { HttpError } from "../common/HttpError.ts";
import type { Problem } from "../common/Problem.ts";

const getHeadersFromConfig = async (
  config: Config,
): Promise<RequestInit["headers"]> => {
  if (config.credentials) {
    return {
      Authorization: `Bearer ${await config.credentials.get(config)}`,
    };
  }
  return {};
};

/**
 * @internal
 * @hidden
 */
export const createRequest = (config: Config): RequestFn => {
  const fetch = (config.fetch as typeof globalThis.fetch) || globalThis.fetch;
  return async (path, init) => {
    const start = performance.now();
    return fetch(new URL(path, config.origin), {
      ...init,
      headers: { ...(await getHeadersFromConfig(config)), ...init?.headers },
    })
      .then(async (res) => {
        config.logger?.debug(
          `${init?.method?.toUpperCase() || "GET"} ${path} (${res.status}) [${Math.round(performance.now() - start)}ms]`,
        );
        if (!res.ok) {
          throw await HttpError.fromResponse(res);
        }
        return res;
      })
      .catch((err: unknown) => {
        if (isFetchFailedError(err)) {
          throw new Error(networkError.title, {
            cause: { ...networkError, additionalDetails: err },
          });
        }
        throw err;
      });
  };
};

const networkError = {
  title:
    "A network error occurred while processing your request. Please ensure that you have a working connection to Dremio.",
  type: "https://api.dremio.dev/problems/network-error",
} as const satisfies Problem;

const isFetchFailedError = (err: unknown): boolean => {
  // Browser and Node fetch
  if (err instanceof TypeError) {
    return true;
  }

  // Bun fetch
  if (typeof err === "object" && err !== null) {
    if ("code" in err) {
      return err.code === "ConnectionRefused";
    }
  }

  return false;
};
