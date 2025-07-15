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
import type {
  FunctionCatalogReference,
  HomeCatalogReference,
  SourceCatalogReference,
  SpaceCatalogReference,
} from "./CatalogReferences/index.ts";
import { catalogReferenceEntityToProperties } from "./CatalogReferences/utils/catalogReferenceEntityToProperties.ts";
import { catalogReferenceFromProperties } from "./CatalogReferences/utils/catalogReferenceFromProperties.ts";
import { baseRetrieve, baseRetrieveByPath } from "./catalogRetrieve.ts";

export const CatalogResource = (config: SonarV3Config) => {
  const retrieve = baseRetrieve(config);
  const retrieveByPath = baseRetrieveByPath(config);

  return {
    _catalogReferenceFromEntity: (entity: unknown) =>
      catalogReferenceFromProperties(
        catalogReferenceEntityToProperties(entity),
        config,
        retrieveByPath,
      ),
    list: () => {
      return {
        async *data() {
          yield* await config
            .sonarV3Request("catalog")
            .then((res) => res.json())
            .then((response: { data: unknown[] }) =>
              response.data.map(
                (entity: unknown) =>
                  catalogReferenceFromProperties(
                    catalogReferenceEntityToProperties(entity),
                    config,
                    retrieveByPath,
                  ) as
                    | FunctionCatalogReference
                    | HomeCatalogReference
                    | SourceCatalogReference
                    | SpaceCatalogReference,
              ),
            );
        },
      };
    },
    retrieve,
    retrieveByPath,
  };
};
