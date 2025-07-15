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

import type { RetrieveByPath } from "../CatalogReferences/BaseCatalogReference.ts";
import { FileCatalogReference } from "../CatalogReferences/FileCatalogReference.ts";

export class FileCatalogObject {
  readonly catalogReference: FileCatalogReference;

  constructor(properties: any) {
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

  pathString: FileCatalogReference["pathString"];

  /**
   * @deprecated
   */
  static fromResource(properties: any, retrieveByPath: RetrieveByPath) {
    return new FileCatalogObject({
      catalogReference: new FileCatalogReference(
        {
          id: properties.id,
          path: properties.path,
        },
        retrieveByPath,
      ),
    });
  }
}
