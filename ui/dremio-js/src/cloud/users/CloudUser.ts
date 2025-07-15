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

import {
  EnterpriseUser,
  type EnterpriseUserProperties,
} from "../../enterprise/users/EnterpriseUser.ts";

export class CloudUser extends EnterpriseUser {}

export const cloudUserEntityToProperties = (entity: any) =>
  ({
    email: entity.email?.length > 0 ? entity.email : entity.name,
    familyName: entity.lastName || null,
    givenName: entity.firstName || null,
    id: entity.id,
    status: entity.active ? "ACTIVE" : "INACTIVE",
    username: entity.name,
  }) as EnterpriseUserProperties;
