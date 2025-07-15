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
package com.dremio.service.jobs;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUser;
import com.dremio.exec.catalog.MetadataRequestOptions;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.service.users.SystemUser;

public final class CatalogUtil {
  public static Catalog getSystemCatalogForJobs(CatalogService catalogService) {
    return catalogService.getCatalog(
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(
                SchemaConfig.newBuilder(CatalogUser.from(SystemUser.SYSTEM_USERNAME)).build())
            .setCheckValidity(false)
            .setNeverPromote(true)
            .build());
  }
}
