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

import { useMemo } from "react";
import {
  useSuspenseQueries,
  useSuspenseQuery,
  type UseSuspenseQueryResult,
} from "@tanstack/react-query";
import type { CatalogObject, Problem } from "@dremio/dremio-js/oss";
import { catalogById, catalogRootQuery } from "dyn-load/queries/catalog";
import { starredResourcesQuery } from "dyn-load/queries/stars";
import { type Result } from "ts-results-es";

export const combine = (
  res: UseSuspenseQueryResult<Result<CatalogObject, Problem>, Error>[],
) => ({
  starredReferences: res.map((obj) => obj.data.unwrap().catalogReference),
});

export const useCatalogRoot = ({
  sort = "asc",
  starsOnly = false,
}: Partial<{
  sort: "asc" | "desc";
  starsOnly: boolean;
}>) => {
  const catalogReferences = useSuspenseQuery(catalogRootQuery()).data;

  const starredResources = useSuspenseQuery(starredResourcesQuery()).data;

  const { starredReferences } = useSuspenseQueries({
    queries: [...starredResources.entries()].map(([starId]) =>
      catalogById()(starId),
    ),
    combine,
  });

  return useMemo(
    () =>
      [...(starsOnly ? starredReferences : catalogReferences)].sort((a, b) => {
        if (sort === "asc") {
          return a.name.toLocaleLowerCase() > b.name.toLocaleLowerCase()
            ? 1
            : -1;
        } else {
          return a.name.toLocaleLowerCase() > b.name.toLocaleLowerCase()
            ? -1
            : 1;
        }
      }),
    [catalogReferences, sort, starsOnly, starredReferences],
  );
};
