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

export class DatasetCatalogObject {
  readonly catalogReference: DatasetCatalogObjectProperties["catalogReference"];
  readonly createdAt: DatasetCatalogObjectProperties["createdAt"];
  readonly fields: DatasetCatalogObjectProperties["fields"];
  readonly schemaOutdated: DatasetCatalogObjectProperties["schemaOutdated"];
  readonly type: DatasetCatalogObjectProperties["type"];

  constructor(properties: DatasetCatalogObjectProperties) {
    this.catalogReference = properties.catalogReference;
    this.createdAt = properties.createdAt;
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

  pathString: DatasetCatalogReference["pathString"];
}

type Field = {
  isPartitioned: boolean;
  isSorted: boolean;
  name: string;
  type: {
    name: string;
  };
};

export type DatasetEntity = {
  id: string;
  path: string[];
  type: string;
  createdAt: string;
  fields: Field[];
  schemaOutdated: boolean;
};

export const datasetEntityToProperties = (
  entity: DatasetEntity,
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
) =>
  ({
    catalogReference: new DatasetCatalogReference(
      {
        id: entity.id,
        path: entity.path,
        type: (mappedType as any)[entity.type],
      },
      retrieveByPath,
      config,
    ),
    createdAt: new Date(entity.createdAt),
    fields: entity.fields,
    schemaOutdated: entity.schemaOutdated,
    type: (mappedType as any)[entity.type],
  }) as const;

export type DatasetCatalogObjectProperties = ReturnType<
  typeof datasetEntityToProperties
>;
