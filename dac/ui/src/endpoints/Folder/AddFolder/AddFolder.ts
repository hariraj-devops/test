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

import { getApiContext } from "dremio-ui-common/contexts/ApiContext.js";
import { APIV2Call } from "#oss/core/APICall";

export type versionContext =
  | { refType: "BRANCH" | "TAG" | "DETACHED"; refValue: string }
  | Record<string, never>;
type rootType = "source" | "home" | "space";

type getAddFolderUrlProps = {
  rootType: rootType;
  rootName: string;
  fullPath: string;
  params: versionContext;
};

const getAddFolderUrl = ({
  rootType,
  rootName,
  fullPath,
  params,
}: getAddFolderUrlProps) =>
  new APIV2Call()
    .paths(`/${rootType}/${rootName}/folder/${fullPath}`)
    .params(params)
    .uncachable()
    .toString();

export const handleAddFolder = (
  rootType: rootType,
  rootName: string,
  fullPath: string,
  payload: { name: string; storageUri?: string | null },
  params: versionContext,
): Promise<any> =>
  getApiContext()
    .fetch(getAddFolderUrl({ rootType, rootName, fullPath, params }), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    })
    .then((res) => res.json())
    .catch((e) => {
      throw e;
    });
