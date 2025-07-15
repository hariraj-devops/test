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
import { Err } from "ts-results-es";
import type { SonarV3Config } from "../../_internal/types/Config.ts";
import type { SignalParam } from "../../_internal/types/Params.ts";
import {
  ArcticCatalog,
  arcticCatalogEntityToProperties,
  type ArcticCatalogEntity,
} from "./ArcticCatalog.ts";

export const ArcticResource = (config: SonarV3Config) => {
  return {
    /**
     * @hidden
     * @internal
     */
    _createFromEntity: (entity: ArcticCatalogEntity) =>
      new ArcticCatalog(arcticCatalogEntityToProperties(entity)),
    list: () => ({
      async *data({ signal }: SignalParam = {}) {
        yield* await config
          .sonarV3Request("arctic/catalogs", { signal })
          .then((res) => res.json())
          .then(
            (response: {
              data: ArcticCatalogEntity[];
              nextPageToken: string | null;
              previousPageToken: string | null;
              totalResults: number;
            }) =>
              response.data.map(
                (entity) =>
                  new ArcticCatalog(arcticCatalogEntityToProperties(entity)),
              ),
          );
      },
    }),
    retrieve: (id: string, { signal }: SignalParam = {}) =>
      config
        .sonarV3Request(`arctic/catalogs/${id}`, { signal })
        .then((res) => res.json())
        .then(
          (entity: any) =>
            new ArcticCatalog(arcticCatalogEntityToProperties(entity)),
        )
        .catch((e: unknown) => Err(e)),
  };
};
