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
import { addProjectBase as wrapBackendLink } from "dremio-ui-common/utilities/projectBase.js";
import { browserHistory } from "react-router";
export function checkIfUserShouldGetDeadLink() {
  return false;
}

export function getHref(entity) {
  const location = browserHistory.getCurrentLocation();
  const fileType = entity.get("fileType");
  if (entity.get("fileType") === "file") {
    if (entity.get("queryable")) {
      return wrapBackendLink(entity.getIn(["links", "query"]));
    }
    return {
      ...location,
      state: {
        modal: "DatasetSettingsModal",
        tab: "format",
        type: entity.get("entityType"),
        entityName: entity.get("fullPathList").last(),
        entityType: entity.get("entityType"),
        entityId: entity.get("id"),
        fullPath: entity.get("filePath"),
        query: { then: "query" },
        isHomePage: true,
      },
    };
  }
  if (fileType === "folder") {
    if (entity.get("queryable")) {
      return wrapBackendLink(entity.getIn(["links", "query"]));
    }
    return wrapBackendLink(entity.getIn(["links", "self"]));
  }

  // OSS doesn't have permissions, should always open with edit
  const editLink = entity.getIn(["links", "edit"]);
  return wrapBackendLink(editLink || entity.getIn(["links", "query"]));
}
