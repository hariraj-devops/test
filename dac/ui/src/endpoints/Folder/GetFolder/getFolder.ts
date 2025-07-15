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

type getFolderUrlProps = {
  rootType: rootType;
  rootName: string;
  fullPath: string;
};

const getFolderUrl = ({ rootType, rootName, fullPath }: getFolderUrlProps) =>
  new APIV2Call()
    .paths(`/${rootType}/${rootName}/folder/${fullPath}`)
    .uncachable()
    .toString();

export const getFolder = (
  rootType: rootType,
  rootName: string,
  fullPath: string,
): Promise<any> =>
  getApiContext()
    .fetch(getFolderUrl({ rootType, rootName, fullPath }), {
      method: "GET",
      headers: { "Content-Type": "application/json" },
    })
    .then((res) => res.json())
    .catch((e) => {
      throw e;
    });
