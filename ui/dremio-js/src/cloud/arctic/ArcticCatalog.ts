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

import type { UserGrantee } from "../../enterprise/Grantee.ts";

export class ArcticCatalog implements ArcticCatalogProperties {
  readonly createdAt: ArcticCatalogProperties["createdAt"];
  readonly createdBy: ArcticCatalogProperties["createdBy"];
  readonly id: ArcticCatalogProperties["id"];
  readonly modifiedAt: ArcticCatalogProperties["modifiedAt"];
  readonly modifiedBy: ArcticCatalogProperties["modifiedBy"];
  readonly name: ArcticCatalogProperties["name"];
  readonly owner: ArcticCatalogProperties["owner"];

  constructor(properties: ArcticCatalogProperties) {
    this.createdAt = properties.createdAt;
    this.createdBy = properties.createdBy;
    this.id = properties.id;
    this.modifiedAt = properties.modifiedAt;
    this.modifiedBy = properties.modifiedBy;
    this.name = properties.name;
    this.owner = properties.owner;
  }
}

export type ArcticCatalogEntity = {
  id: string;
  name: string;
  ownerId: string;
  ownerName: string;
  createdBy: string;
  modifiedBy: string;
  createdAt: string;
  modifiedAt: string;
  state: "ACTIVE" | "INACTIVE";
  nessieEndpoint: string;
};

type ArcticCatalogProperties = ReturnType<
  typeof arcticCatalogEntityToProperties
>;

export const arcticCatalogEntityToProperties = (
  entity: ArcticCatalogEntity,
) => ({
  createdAt: new Date(entity.createdAt),
  createdBy: entity.createdBy,
  id: entity.id,
  modifiedAt: new Date(entity.modifiedAt),
  modifiedBy: entity.modifiedBy,
  name: entity.name,
  owner: {
    id: entity.ownerId,
    type: "USER",
  } satisfies UserGrantee as UserGrantee,
});
