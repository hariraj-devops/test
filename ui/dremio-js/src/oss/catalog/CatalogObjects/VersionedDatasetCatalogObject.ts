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

import type { SonarV3Config } from "../../../_internal/types/Config.ts";
import type { RetrieveByPath } from "../CatalogReferences/BaseCatalogReference.ts";
import {
  DatasetCatalogReference,
  mappedType,
} from "../CatalogReferences/DatasetCatalogReference.ts";
import type { CatalogReference } from "../CatalogReferences/index.ts";

export class VersionedDatasetCatalogObject {
  readonly catalogReference: DatasetCatalogReference;
  readonly fields: Field[];
  readonly schemaOutdated: boolean;
  readonly type: string;

  constructor(properties: any) {
    this.catalogReference = properties.catalogReference;
    this.fields = properties.fields;
    this.schemaOutdated = properties.schemaOutdated;
    this.type = properties.type;
    this.pathString = this.catalogReference.pathString.bind(
      this.catalogReference,
    );
  }

  get name() {
    return this.catalogReference.name;
  }

  /**
   * @deprecated
   */
  get id() {
    return this.catalogReference.id;
  }

  get path() {
    return this.catalogReference.path;
  }

  pathString: CatalogReference["pathString"];

  /**
   * @deprecated
   */
  static fromResource(
    properties: any,
    retrieveByPath: RetrieveByPath,
    config: SonarV3Config,
  ) {
    return new VersionedDatasetCatalogObject({
      catalogReference: new DatasetCatalogReference(
        {
          id: properties.id,
          path: properties.path,
          type: (mappedType as any)[properties.type],
        },
        retrieveByPath,
        config,
      ),
      fields: properties.fields,
      schemaOutdated: properties.schemaOutdated,
      type: (mappedType as any)[properties.type],
    });
  }
}

type Field = {
  isPartitioned: boolean;
  isSorted: boolean;
  name: string;
  type: {
    name: string;
  };
};
