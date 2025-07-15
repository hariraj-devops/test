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

export class Reflection {
  createdAt: ReflectionProperties["createdAt"];
  id: ReflectionProperties["id"];
  name: ReflectionProperties["name"];
  updatedAt: ReflectionProperties["updatedAt"];
  type: ReflectionProperties["type"];
  // eslint-disable-next-line no-unused-private-class-members
  #tag: ReflectionProperties["tag"];

  constructor(properties: ReflectionProperties) {
    this.createdAt = properties.createdAt;
    this.id = properties.id;
    this.name = properties.name;
    this.updatedAt = properties.updatedAt;
    this.type = properties.type;
    this.#tag = properties.tag;
  }
}

export const reflectionEntityToProperties = (entity: {
  id: string;
  type: "RAW" | "AGGREGATION";
  name: string;
  tag: string;
  createdAt: string;
  updatedAt: string;
  datasetId: string;
  currentSizeBytes: number;
  totalSizeBytes: number;
  enabled: boolean;
  status: {
    config: "OK" | "INVALID";
    refresh: "GIVEN_UP" | "MANUAL" | "RUNNING" | "SCHEDULED";
    availability: "AVAILABLE" | "EXPIRED" | "INCOMPLETE" | "NONE";
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
    failureCount: number;
    lastDataFetch: string;
    expiresAt: string;
  };
  dimensionFields: {
    name: string;
    granularity?: string;
  }[];
  measureFields: {
    name: string;
    measureTypeList: string[];
  }[];
  distributionFields: {
    name: string;
  }[];
  partitionFields: {
    name: string;
  }[];
  sortFields: { name: string }[];
  partitionDistributionStrategy: string;
  canView: boolean;
  canAlter: boolean;
  entityType: "reflection";
}) => ({
  createdAt: new Date(entity.createdAt),
  id: entity.id,
  name: entity.name,
  tag: entity.tag,
  type: entity.type,
  updatedAt: new Date(entity.updatedAt),
});

type ReflectionProperties = ReturnType<typeof reflectionEntityToProperties>;
