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

import { type Result } from "ts-results-es";
import { dremio } from "#oss/dremio";
import { queryClient } from "#oss/queryClient";
import { queryOptions, type Query } from "@tanstack/react-query";
import type {
  CatalogObject,
  CatalogReference,
  SourceCatalogObject,
  SourceCatalogReference,
  VersionReference,
} from "@dremio/dremio-js/oss";
import { getResultStaleTime } from "#oss/utils/queryUtils";

export const catalogRootQuery = () =>
  queryOptions({
    queryKey: ["catalog", "root"],
    queryFn: () => Array.fromAsync(dremio.catalog.list().data()),
    retry: false,
    staleTime: 60_000,
  });

export const catalogById = () => (id: string) =>
  queryOptions({
    queryKey: ["catalog", id] as const,
    queryFn: async () => {
      const catalogObject = await dremio.catalog.retrieve(id);

      if (catalogObject.isOk()) {
        queryClient.setQueryData(
          ["catalog", catalogObject.value.path],
          catalogObject,
        );
      }

      return catalogObject;
    },
    refetchInterval: catalogObjectRefetch,
    retry: false,
    staleTime: getResultStaleTime(300_000),
  });

export const catalogByPath = () => (path: string[]) =>
  queryOptions({
    queryKey: ["catalog", path] as const,
    queryFn: async () => {
      const catalogObject = await dremio.catalog.retrieveByPath(path);

      if (catalogObject.isOk()) {
        queryClient.setQueryData(
          ["catalog", catalogObject.value.id],
          catalogObject,
        );
      }

      return catalogObject;
    },
    refetchInterval: catalogObjectRefetch,
    retry: false,
    staleTime: getResultStaleTime(300_000),
  });

export const catalogObjectRefetch = <T extends Result<CatalogObject, unknown>>(
  query: Query<T>,
) => {
  const catalogObjectResult = query.state.data;

  if (!catalogObjectResult || catalogObjectResult.isErr()) return false;

  const catalogObject = catalogObjectResult.value;

  if (
    catalogObject.catalogReference.type === "SOURCE" &&
    !(catalogObject as SourceCatalogObject).settled
  ) {
    return 5000;
  }
  return false;
};

export const catalogReferenceChildren = (catalogReference: CatalogReference) =>
  queryOptions({
    // eslint-disable-next-line @tanstack/query/exhaustive-deps
    queryKey: ["catalog_reference_children", catalogReference.id],
    queryFn: async ({ signal }) => {
      if ("children" in catalogReference) {
        return Array.fromAsync(
          catalogReference
            .children({ exclude: "externalChildLookups" })
            .data({ signal }),
        );
      }
      throw new Error(
        "Unsupported catalogReference type: " + catalogReference.type,
      );
    },
    staleTime: 300_000,
  });

export type VersionState = {
  hash?: string;
  name?: string;
  type: "BRANCH" | "COMMIT" | "TAG";
};

export const versionedCatalogReferenceChildren = (
  catalogReference: SourceCatalogReference,
  version: VersionState,
) =>
  queryOptions({
    queryKey: ["catalog_reference_children", catalogReference.path, version],
    queryFn: async ({ signal }) => {
      let versionReference: VersionReference;

      switch (version.type) {
        case "BRANCH":
          versionReference = { branch: version.name! };
          break;
        case "COMMIT":
          versionReference = { hash: version.hash! };
          break;
        case "TAG":
          versionReference = { tag: version.name! };
          break;
        default:
          throw new Error("Unknown version type " + version.type);
      }

      return Array.fromAsync(
        catalogReference
          .children({ version: versionReference })
          .data({ signal }),
      );
    },
    staleTime: 300_000,
  });

export const catalogObjectChildren = (catalogObject: CatalogObject) =>
  queryOptions({
    queryKey: ["catalog_children", catalogObject.id],
    queryFn: async () => Array.fromAsync(catalogObject.children().data()),
  });
