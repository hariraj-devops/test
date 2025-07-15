//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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

import { dremio } from "#oss/dremio";
import { SourceCatalogReference } from "@dremio/dremio-js/oss";
import { queryOptions } from "@tanstack/react-query";
import { BranchVersionReference } from "node_modules/@dremio/dremio-js/dist/src/oss/catalog/VersionReference";
import { Err, Ok } from "ts-results-es";

export const nessieDefaultBranchQuery =
  (pid?: string) => (sourceCatalogReference: SourceCatalogReference) =>
    queryOptions({
      queryKey: [pid, sourceCatalogReference.name],
      queryFn: ({ signal }) =>
        dremio
          ._request(
            `/nessie-proxy/v2/source/${sourceCatalogReference.name}/trees/-`,
            {
              signal,
            },
          )
          .then((res) => res.json())
          .then(
            (response: {
              reference: {
                hash: string;
                name: string;
                type: "BRANCH";
              };
            }) => {
              return Ok(response.reference as BranchVersionReference);
            },
          )
          .catch((e: unknown) => Err(e)),
    });

export const nessieBranchesQuery =
  (pid?: string) => (sourceCatalogReference: SourceCatalogReference) =>
    queryOptions({
      queryKey: [pid, sourceCatalogReference.name],
      queryFn: ({ signal }) =>
        dremio
          ._request(
            `/nessie-proxy/v2/source/${sourceCatalogReference.name}/trees`,
            {
              signal,
            },
          )
          .then((res) => res.json())
          .then(
            (response: {
              references: {
                hash: string;
                name: string;
                type: "BRANCH";
              }[];
              hasMore: boolean;
            }) =>
              Ok(
                response as {
                  references: BranchVersionReference[];
                  hasMore: boolean;
                },
              ),
          )
          .catch((e: unknown) => Err(e)),
    });
