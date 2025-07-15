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
import type { SonarV3Config } from "../../../_internal/types/Config.ts";
import {
  BaseCatalogReference,
  type BaseCatalogReferenceProperties,
  type RetrieveByPath,
} from "./BaseCatalogReference.ts";
import type { SourceCatalogObject } from "../CatalogObjects/SourceCatalogObject.ts";
import { catalogChildren } from "./utils/catalogChildren.ts";
import { getWiki } from "./utils/getWiki.ts";
import {
  isBareCommitVersionReference,
  isBranchHeadVersionReference,
  isTagVersionReference,
  type VersionReference,
} from "../VersionReference.ts";

export class SourceCatalogReference extends BaseCatalogReference {
  readonly type = "SOURCE";
  #config: SonarV3Config;
  #retrieveByPath: RetrieveByPath;

  constructor(
    properties: BaseCatalogReferenceProperties,
    config: SonarV3Config,
    retrieveByPath: RetrieveByPath,
  ) {
    super(properties);
    this.#config = config;
    this.#retrieveByPath = retrieveByPath;
  }

  catalogObject() {
    return this.#retrieveByPath(this.path) as Promise<
      Result<SourceCatalogObject, unknown>
    >;
  }

  children(
    params: {
      version?: VersionReference;
      exclude?: string;
    } = {},
  ) {
    return catalogChildren(
      this,
      this.#config,
      this.#retrieveByPath,
    )({
      ...(params.version && getVersionParams(params.version)),
      ...(params.exclude && { exclude: params.exclude }),
    });
  }

  wiki() {
    return getWiki(this.#config, this)();
  }
}

const getVersionParams = (versionReference: VersionReference) => {
  const params = { versionType: "", versionValue: "" };
  if (isBranchHeadVersionReference(versionReference)) {
    params.versionType = "BRANCH";
    params.versionValue = versionReference.branch;
  } else if (isBareCommitVersionReference(versionReference)) {
    params.versionType = "COMMIT";
    params.versionValue = versionReference.hash;
  } else if (isTagVersionReference(versionReference)) {
    params.versionType = "TAG";
    params.versionValue = versionReference.tag;
  } else {
    throw new Error("Invalid versionReference");
  }

  return params;
};
