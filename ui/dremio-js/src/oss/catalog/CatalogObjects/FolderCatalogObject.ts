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
import { FolderCatalogReference } from "../CatalogReferences/FolderCatalogReference.ts";

export class FolderCatalogObject {
  readonly catalogReference: FolderCatalogReference;

  constructor(properties: { catalogReference: FolderCatalogReference }) {
    this.catalogReference = properties.catalogReference;
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

  pathString: typeof this.catalogReference.pathString;

  /**
   * @deprecated
   */
  static fromResource(
    properties: any,
    config: SonarV3Config,
    retrieveByPath: RetrieveByPath,
  ) {
    return new FolderCatalogObject({
      catalogReference: new FolderCatalogReference(
        {
          id: properties.id,
          path: properties.path,
        },
        config,
        retrieveByPath,
      ),
    });
  }
}
