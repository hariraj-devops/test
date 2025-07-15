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
import type { SonarV3Config } from "../../../../_internal/types/Config.ts";
import type { SignalParam } from "../../../../_internal/types/Params.ts";
import type { RetrieveByPath } from "../BaseCatalogReference.ts";
import { catalogReferenceFromProperties } from "./catalogReferenceFromProperties.ts";
import { catalogReferenceEntityToProperties } from "./catalogReferenceEntityToProperties.ts";
import type { CatalogReference } from "../index.ts";

export const catalogChildren =
  (
    catalogReference: CatalogReference,
    config: SonarV3Config,
    retrieveByPath: RetrieveByPath,
  ) =>
  (additionalParams: Record<string, any> = {}) => {
    const getPage = (params: { nextPageToken?: string } & SignalParam = {}) => {
      const searchParams = new URLSearchParams();
      searchParams.set("maxChildren", "100");
      if (params.nextPageToken) {
        searchParams.set("pageToken", params.nextPageToken);
      }
      for (const param in additionalParams) {
        searchParams.set(param, additionalParams[param]);
      }
      return config
        .sonarV3Request(
          `catalog/by-path/${catalogReference.path.map(encodeURIComponent).join("/")}?${searchParams.toString()}`,
          { signal: params.signal },
        )
        .then((res) => res.json())
        .then((response: { children: unknown[]; nextPageToken?: string }) => {
          return Ok({
            data: response.children.map((entity: unknown) =>
              catalogReferenceFromProperties(
                catalogReferenceEntityToProperties(entity),
                config,
                retrieveByPath,
              ),
            ),
            nextPageToken: response.nextPageToken,
          });
        })
        .catch((e) => Err(e));
    };
    return {
      async *data({ signal }: SignalParam = {}) {
        const firstPage = (await getPage({ signal })).unwrap();
        yield* firstPage.data;
        let nextPageToken = firstPage.nextPageToken;
        while (nextPageToken && !signal?.aborted) {
          const nextPage = (await getPage({ nextPageToken, signal })).unwrap();
          yield* nextPage.data;
          nextPageToken = nextPage.nextPageToken;
        }
      },
      getPage,
    };
  };
