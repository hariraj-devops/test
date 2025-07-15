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

export const getUpdateFolderResourcePath = (selfLink: string): string =>
  selfLink.slice(0, selfLink.lastIndexOf("/"));

export const removePrefix = (path: string): string => {
  const prefixPattern = /^[^:]+:\/\//;
  return path.replace(prefixPattern, "");
};

export const getPrefixFromPath = (path: string): string => {
  const prefixPattern = /^[^:]+:\/\//;
  const match = path.match(prefixPattern);
  return match ? match[0] : "";
};

export const getDefaultStorageURI = (source: Record<string, any>): string =>
  source.getIn(["config", "defaultStorageUri"]);

export const isUsingCustomStorage = (
  defaultPath: string,
  entity: Record<string, any>,
) => {
  const entityStorageURI = entity.storageUri;
  const entityPath = entity.fullPathList.slice(1).join("/");
  if (defaultPath.endsWith("/")) {
    return defaultPath + entityPath !== entityStorageURI;
  } else {
    return defaultPath + "/" + entityPath !== entityStorageURI;
  }
};

export const constructDefaultStorageURI = (
  defaultPath: string,
  entity: Record<string, any>,
) => {
  const entityPath = entity.fullPathList.slice(1).join("/");
  if (defaultPath.endsWith("/")) {
    return defaultPath + entityPath;
  } else {
    return defaultPath + "/" + entityPath;
  }
};
