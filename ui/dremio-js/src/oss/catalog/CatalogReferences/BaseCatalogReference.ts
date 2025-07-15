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
import type { Result } from "ts-results-es";
import type { CatalogObject } from "../CatalogObjects/index.ts";

export type RetrieveByPath = (
  path: string[],
) => Promise<Result<CatalogObject, unknown>>;

export abstract class BaseCatalogReference {
  readonly id: BaseCatalogReferenceProperties["id"];
  readonly path: BaseCatalogReferenceProperties["path"];
  abstract readonly type: string;
  abstract catalogObject(): Promise<Result<CatalogObject, unknown>>;

  constructor(properties: BaseCatalogReferenceProperties) {
    this.id = properties.id;
    this.path = properties.path;
  }

  get name() {
    return this.path.at(-1)!;
  }

  pathString = pathString(() => this.path);
}

const requiresQuotes = /\W/;

const pathString =
  (getPath: () => string[]) =>
  (SEPARATOR: string = "."): string => {
    return getPath()
      .map((part) => (requiresQuotes.test(part) ? `"${part}"` : part))
      .join(SEPARATOR);
  };

export type BaseCatalogReferenceProperties = { id: string; path: string[] };
