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

import { ENTITY_TYPES } from "#oss/constants/Constants";
import { manageSourceRule, manageSpaceRule } from "#oss/utils/authUtils";
import { isCME } from "dyn-load/utils/versionUtils";
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
import { browserHistory } from "react-router";
export default function (input) {
  Object.assign(input.prototype, {
    getSpaceSettingsButtons() {
      const location = browserHistory.getCurrentLocation();
      const { entity } = this.props;
      const buttons = [];

      if (entity.get("entityType") === ENTITY_TYPES.space) {
        let showSettingsButton = true;
        if (isCME && !isCME() && entity.get("permissions")) {
          showSettingsButton = entity.getIn([
            "permissions",
            "canEditAccessControlList",
          ]);
        }
        if (showSettingsButton) {
          buttons.push({
            qa: "settings",
            iconType: "interface/settings",
            to: {
              ...location,
              state: { modal: "SpaceModal", entityId: entity.get("id") },
            },
            authRule: manageSpaceRule,
          });
        }
      }

      return buttons;
    },

    getSourceSettingsButtons() {
      const location = browserHistory.getCurrentLocation();
      const { entity, isVersionedSource } = this.props;
      const buttons = [];

      if (entity.get("entityType") === "source") {
        let showSettingsButton = true;
        if (isCME && !isCME() && entity.get("permissions")) {
          showSettingsButton =
            entity.getIn(["permissions", "canEditAccessControlList"]) ||
            (isVersionedSource &&
              (entity.getIn(["permissions", "canAlter"]) ||
                entity.getIn(["permissions", "canEdit"])));
        }
        if (showSettingsButton) {
          buttons.push({
            qa: "settings",
            iconType: "interface/settings",
            to: {
              ...location,
              state: {
                modal: "EditSourceModal",
                query: { name: entity.get("name"), type: entity.get("type") },
              },
            },
            authRule: manageSourceRule,
          });
        }
      }

      return buttons;
    },
    getVersionedFolderSettingsButton() {
      return [];
    },
  });
}
