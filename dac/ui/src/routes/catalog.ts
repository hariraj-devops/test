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

import { catalogBase } from "@inject/routes/catalogBase";
import {
  CatalogReference,
  HomeCatalogObject,
  SourceCatalogObject,
  SpaceCatalogObject,
} from "@dremio/dremio-js/oss";

const pathToString = (path: string[]) =>
  path.map((part) => (/\W/.test(part) ? `"${part}"` : part)).join(".");

export const datasetRoute = catalogBase.extend(
  (params: {
    catalogReference: CatalogReference;
    rootCatalogObject:
      | HomeCatalogObject
      | SourceCatalogObject
      | SpaceCatalogObject;
  }) => {
    switch (params.rootCatalogObject.catalogReference.type) {
      case "HOME":
        return `home/${encodeURIComponent(params.rootCatalogObject.name)}/${pathToString(params.catalogReference.path.slice(1))}`;

      case "SOURCE": {
        const rootCatalogObject =
          params.rootCatalogObject as SourceCatalogObject;

        if (rootCatalogObject.type === "NESSIE") {
          return `source/${rootCatalogObject.pathString(".")}/${pathToString(
            params.catalogReference.path.slice(1),
          )}?${new URLSearchParams({ refType: "BRANCH", refValue: "MAIN" }).toString()}`;
        }

        return `source/${params.rootCatalogObject.pathString(".")}/${pathToString(
          params.catalogReference.path.slice(1),
        )}`;
      }
      case "SPACE":
        return `space/${params.rootCatalogObject.pathString(".")}/${pathToString(params.catalogReference.path.slice(1))}`;

      default:
        return `source/${params.rootCatalogObject.pathString(".")}/${pathToString(
          params.catalogReference.path.slice(1),
        )}`;
    }
  },
);

export const folderRoute = catalogBase.extend(
  (params: {
    catalogReference: CatalogReference;
    rootCatalogObject:
      | HomeCatalogObject
      | SourceCatalogObject
      | SpaceCatalogObject;
  }) => {
    switch (params.rootCatalogObject.catalogReference.type) {
      case "HOME":
        return `home/${encodeURIComponent(params.rootCatalogObject.name)}/folder/${params.catalogReference.path.slice(1).join("/")}`;

      case "SOURCE": {
        const rootCatalogObject =
          params.rootCatalogObject as SourceCatalogObject;

        if (rootCatalogObject.type === "NESSIE") {
          return `source/${encodeURIComponent(params.rootCatalogObject.name)}/folder/${params.catalogReference.path
            .slice(1)
            .join(
              "/",
            )}?${new URLSearchParams({ refType: "BRANCH", refValue: "MAIN" }).toString()}`;
        }

        return `source/${encodeURIComponent(params.rootCatalogObject.name)}/folder/${params.catalogReference.path
          .slice(1)
          .join("/")}`;
      }
      case "SPACE":
        return `space/${encodeURIComponent(params.rootCatalogObject.name)}/folder/${params.catalogReference.path.slice(1).join(".")}`;

      default:
        return `source/${encodeURIComponent(params.rootCatalogObject.name)}/folder/${params.catalogReference.path
          .slice(1)
          .join("/")}`;
    }
  },
);

export const datasetReflectionsRoute = datasetRoute.extend(() => `reflections`);
