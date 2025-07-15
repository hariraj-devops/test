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

import type { FunctionCatalogReference } from "../CatalogReferences/FunctionCatalogReference.ts";

export class FunctionCatalogObject {
  readonly catalogReference: FunctionCatalogReference;
  readonly createdAt: Date;
  readonly isScalar: boolean;
  readonly lastModified: Date;
  readonly returnType: string;
  // eslint-disable-next-line no-unused-private-class-members
  readonly #tag: string;
  pathString: typeof this.catalogReference.pathString;

  constructor(
    properties: any & {
      catalogReference: FunctionCatalogReference;
      tag: string;
    },
  ) {
    this.catalogReference = properties.catalogReference;
    this.createdAt = properties.createdAt;
    this.isScalar = properties.isScalar;
    this.lastModified = properties.lastModified;
    this.returnType = properties.returnType;
    this.#tag = properties.tag;
    this.pathString = properties.catalogReference.pathString.bind(
      this.catalogReference,
    );
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

  get name(): string {
    return this.catalogReference.name;
  }
}
