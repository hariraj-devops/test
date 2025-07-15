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
import {
  CatalogWiki,
  catalogWikiEntityToProperties,
} from "../../CatalogWiki.ts";
import type { CatalogReference } from "../index.ts";

export const getWiki =
  (config: SonarV3Config, catalogReference: CatalogReference) => () =>
    config
      .sonarV3Request(`catalog/${catalogReference.id}/collaboration/wiki`)
      .then((res) => res.json())
      .then((entity) =>
        Ok(
          new CatalogWiki(
            catalogWikiEntityToProperties(catalogReference, entity),
            config,
          ),
        ),
      )
      .catch((e: unknown) => Err(e));
