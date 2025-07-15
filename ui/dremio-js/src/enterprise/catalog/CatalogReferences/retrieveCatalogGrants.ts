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
import type { SonarV3Config } from "../../../_internal/types/Config.ts";
import type { Grantee } from "../../Grantee.ts";
import type { CatalogReference } from "../../../oss/catalog/CatalogReferences/index.ts";

export type CatalogGrants<A extends string> = {
  availablePrivileges: A[];
  grants: {
    grantee: Grantee;
    privileges: A[];
  }[];
};

export const retrieveCatalogGrants =
  (config: SonarV3Config) => (catalogReference: CatalogReference) => {
    return config
      .sonarV3Request(`catalog/${catalogReference.id}/grants`)
      .then((res) => res.json())
      .then((entity) =>
        Ok({
          availablePrivileges: entity.availablePrivileges,
          grants: entity.grants.map((grantEntity: any) => ({
            grantee: { id: grantEntity.id, type: grantEntity.granteeType },
            privileges: grantEntity.privileges,
          })),
        } satisfies CatalogGrants<string>),
      )
      .catch((e) => Err(e));
  };
