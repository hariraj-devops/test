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

import parseMilliseconds from "parse-ms";
import type { EnterpriseDatasetCatalogReference } from "../catalog/CatalogReferences/index.ts";

export class ReflectionSummary {
  createdAt: ReflectionProperties["createdAt"];
  dataset: ReflectionProperties["dataset"];
  id: ReflectionProperties["id"];
  isCanAlter: ReflectionProperties["isCanAlter"];
  isCanView: ReflectionProperties["isCanView"];
  metrics: ReflectionProperties["metrics"];
  name: ReflectionProperties["name"];
  status: ReflectionProperties["status"];
  type: ReflectionProperties["type"];
  updatedAt: ReflectionProperties["updatedAt"];
  constructor(properties: ReflectionProperties) {
    this.createdAt = properties.createdAt;
    this.dataset = properties.dataset;
    this.id = properties.id;
    this.isCanAlter = properties.isCanAlter;
    this.isCanView = properties.isCanView;
    this.metrics = properties.metrics;
    this.name = properties.name;
    this.status = properties.status;
    this.type = properties.type;
    this.updatedAt = properties.updatedAt;
  }
}

export const reflectionSummaryEntityToProperties = (entity: {
  createdAt: string;
  id: string;
  isCanAlter: boolean;
  isCanView: boolean;
  chosenCount: number;
  consideredCount: number;
  currentSizeBytes: number;
  matchedCount: number;
  status: {
    availabilityStatus: "AVAILABLE" | "EXPIRED" | "INCOMPLETE" | "NONE";
    combinedStatus:
      | "CAN_ACCELERATE"
      | "CAN_ACCELERATE_WITH_FAILURES"
      | "CANNOT_ACCELERATE_INITIALIZING"
      | "CANNOT_ACCELERATE_MANUAL"
      | "CANNOT_ACCELERATE_SCHEDULED"
      | "DISABLED"
      | "EXPIRED"
      | "FAILED"
      | "INVALID"
      | "INCOMPLETE"
      | "REFRESHING";
    configStatus: "OK" | "INVALID";
    expiresAt: string;
    failureCount: number;
    lastDataFetchAt: string;
    lastFailureMessage: string;
    lastRefreshDurationMillis: number;
    refreshMethod: "INCREMENTAL" | "FULL" | "NONE";
    refreshStatus: "GIVEN_UP" | "MANUAL" | "RUNNING" | "SCHEDULED";
  };
  isEnabled: boolean;
  outputRecords: number;
  totalSizeBytes: number;
  name: string;
  reflectionType: "RAW" | "AGGREGATION";
  updatedAt: string;
}) => ({
  createdAt: new Date(entity.createdAt),
  dataset: {} as EnterpriseDatasetCatalogReference,
  // dataset: new DatasetCatalogReference(
  //   {
  //     id: entity.datasetId,
  //     path: entity.datasetPath,
  //     type: entity.datasetType,
  //   },
  //   null as any,
  // ),
  id: entity.id,
  isCanAlter: entity.isCanAlter,
  isCanView: entity.isCanView,
  metrics: {
    chosenCount: entity.chosenCount,
    consideredCount: entity.consideredCount,
    currentSizeBytes: entity.currentSizeBytes,
    failureCount: entity.status.failureCount,
    matchedCount: entity.matchedCount,
    outputRecords: entity.outputRecords,
    totalSizeBytes: entity.totalSizeBytes,
  },
  name: entity.name,
  status: {
    availabilityStatus: entity.status.availabilityStatus,
    combinedStatus: entity.status.combinedStatus,
    configStatus: entity.status.configStatus,
    expiresAt: new Date(entity.status.expiresAt),
    isEnabled: entity.isEnabled,
    lastDataFetchAt: entity.status.lastDataFetchAt
      ? new Date(entity.status.lastDataFetchAt)
      : null,
    lastFailureMessage: entity.status.lastFailureMessage || null,
    lastRefreshDuration:
      typeof entity.status.lastRefreshDurationMillis === "number"
        ? Temporal.Duration.from(
            parseMilliseconds(entity.status.lastRefreshDurationMillis),
          )
        : null,
    refreshMethod: entity.status.refreshMethod,
    refreshStatus: entity.status.refreshStatus,
  },
  type: entity.reflectionType,
  updatedAt: new Date(entity.updatedAt),
});

type ReflectionProperties = ReturnType<
  typeof reflectionSummaryEntityToProperties
>;
