//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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

//@ts-ignore
import { getApiContext } from "dremio-ui-common/contexts/ApiContext.js";
import { APIV3Call } from "#oss/core/APICall";

export const getCatalogContentUrl = (include: boolean) =>
  new APIV3Call()
    .paths(`catalog`)
    .param({ include: "permissions" })
    .param(include ? { include: "datasetCount" } : {})
    .toString();

export const getCatalogContent = (include: boolean): Promise<any> =>
  getApiContext()
    .fetch(getCatalogContentUrl(include))
    .then((res: any) => res.json());
