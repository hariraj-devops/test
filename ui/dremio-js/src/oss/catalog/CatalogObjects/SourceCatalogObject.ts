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

import "temporal-polyfill/global";
import parseMilliseconds from "parse-ms";

import type { SonarV3Config } from "../../../_internal/types/Config.ts";
import { SourceCatalogReference } from "../CatalogReferences/SourceCatalogReference.ts";
import type { RetrieveByPath } from "../CatalogReferences/BaseCatalogReference.ts";

export class SourceCatalogObject {
  readonly acceleration: SourceCatalogObjectProperties["acceleration"];
  readonly allowCrossSourceSelection: SourceCatalogObjectProperties["allowCrossSourceSelection"];
  readonly catalogReference: SourceCatalogReference;
  readonly config: SourceCatalogObjectProperties["config"];
  readonly createdAt: SourceCatalogObjectProperties["createdAt"];
  readonly disableMetadataValidityCheck: SourceCatalogObjectProperties["disableMetadataValidityCheck"];
  readonly metadataPolicy: SourceCatalogObjectProperties["metadataPolicy"];
  readonly sourceChangeState: SourceCatalogObjectProperties["sourceChangeState"];
  readonly status: SourceCatalogObjectProperties["status"];
  readonly type: SourceCatalogObjectProperties["type"];

  protected readonly tag: string;

  constructor(
    properties: SourceCatalogObjectProperties & {
      catalogReference: SourceCatalogReference;
      tag: string;
    },
  ) {
    this.acceleration = properties.acceleration;
    this.allowCrossSourceSelection = properties.allowCrossSourceSelection;
    this.catalogReference = properties.catalogReference;
    this.config = properties.config;
    this.createdAt = properties.createdAt;
    this.disableMetadataValidityCheck = properties.disableMetadataValidityCheck;
    this.metadataPolicy = properties.metadataPolicy;
    this.sourceChangeState = properties.sourceChangeState;
    this.status = properties.status;
    this.tag = properties.tag;
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

  get settled() {
    return (
      this.sourceChangeState === "NONE" ||
      this.sourceChangeState === "UNSPECIFIED"
    );
  }

  pathString: SourceCatalogReference["pathString"];
}

export const sourceEntityToProperties = (
  entity: any,
  config: SonarV3Config,
  retrieveByPath: RetrieveByPath,
) =>
  ({
    acceleration: {
      activePolicyType: entity.accelerationActivePolicyType,
      gracePeriod: Temporal.Duration.from(
        parseMilliseconds(entity.accelerationGracePeriodMs),
      ),
      neverExpire: entity.accelerationNeverExpire,
      neverRefresh: entity.accelerationNeverRefresh,
      refreshPeriod: Temporal.Duration.from(
        parseMilliseconds(entity.accelerationRefreshPeriodMs),
      ),
      refreshSchedule: entity.accelerationRefreshSchedule,
    },
    allowCrossSourceSelection: entity.allowCrossSourceSelection,
    catalogReference: new SourceCatalogReference(
      {
        id: entity.id,
        path: [entity.name],
      },
      config,
      retrieveByPath,
    ),
    config: entity.config,
    createdAt: new Date(entity.createdAt),
    disableMetadataValidityCheck: entity.disableMetadataValidityCheck,
    id: entity.id,
    metadataPolicy: {
      authTTL: Temporal.Duration.from(
        parseMilliseconds(entity.metadataPolicy.authTTLMs),
      ),
      autoPromoteDatasets: entity.metadataPolicy.autoPromoteDatasets,
      datasetExpireAfter: Temporal.Duration.from(
        parseMilliseconds(entity.metadataPolicy.datasetExpireAfterMs),
      ),
      datasetRefreshAfter: Temporal.Duration.from(
        parseMilliseconds(entity.metadataPolicy.datasetRefreshAfterMs),
      ),
      datasetUpdateMode: entity.metadataPolicy.datasetUpdateMode,
      deleteUnavailableDatasets:
        entity.metadataPolicy.deleteUnavailableDatasets,
      namesRefresh: Temporal.Duration.from(
        parseMilliseconds(entity.metadataPolicy.namesRefreshMs),
      ),
    },
    name: entity.name,
    sourceChangeState: entity.sourceChangeState,
    status: entity.state.status,
    tag: entity.tag,
    type: entity.type,
  }) as const;

export type SourceCatalogObjectProperties = ReturnType<
  typeof sourceEntityToProperties
>;
