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

import type { components } from "../../../apiTypes/v2/endpoints/user.ts";
type UserEntity = components["schemas"]["User"];

export class User {
  readonly email: UserProperties["email"];
  readonly id: UserProperties["id"];
  readonly familyName: UserProperties["familyName"];
  readonly givenName: UserProperties["givenName"];
  readonly status: UserProperties["status"];
  readonly username: UserProperties["username"];

  constructor(properties: UserProperties) {
    this.email = properties.email;
    this.id = properties.id;
    this.familyName = properties.familyName;
    this.givenName = properties.givenName;
    this.status = properties.status;
    this.username = properties.username;
  }

  get displayName(): string {
    if (this.givenName || this.familyName) {
      return [this.givenName, this.familyName].filter(Boolean).join(" ");
    }

    if (this.username) {
      return this.username;
    }

    if (this.email) {
      return this.email;
    }

    return this.id;
  }

  get initials(): string {
    const givenNameTrimmed = this.givenName?.trim();
    const familyNameTrimmed = this.familyName?.trim();

    if (givenNameTrimmed && familyNameTrimmed) {
      return givenNameTrimmed.charAt(0) + familyNameTrimmed.charAt(0);
    }

    return this.displayName.slice(0, 2);
  }
}

export const userEntityToProperties = (entity: UserEntity) => ({
  email: entity.email,
  familyName: entity.lastName || null,
  givenName: entity.firstName || null,
  id: entity.id,
  status: entity.active ? ("ACTIVE" as const) : ("INACTIVE" as const),
  username: entity.name,
});

export type UserProperties = ReturnType<typeof userEntityToProperties>;
