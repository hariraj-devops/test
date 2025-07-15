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

import sentryUtil from "#oss/utils/sentryUtil";
import { statsEvents } from "dremio-ui-common/appEvents/stats/statsEvents";
import { getPrivilegeContext } from "dremio-ui-common/contexts/PrivilegeContext.js";
import { fetchSupportKeys, SettingsResponse } from "#oss/queries/support";

const getOverriddenSettings = (settings: SettingsResponse) => {
  const settingsMap: Record<string, string> = {};
  // reverse: Overridden come first, followed by defaults
  settings.reverse().forEach((setting) => {
    let value = setting.value;
    if (setting.type === "TEXT") {
      value = "**";
    }
    settingsMap[setting.id] = String(value);
  });

  return settingsMap;
};

let sent = false;

export const supportKeysEvent = async () => {
  try {
    if (sent) return;

    const isAdmin = (
      getPrivilegeContext() as { isAdmin: () => boolean }
    ).isAdmin();

    if (!isAdmin) return;

    const res = await fetchSupportKeys();

    if (res.isErr() || !res.value.length) return;

    const settings = getOverriddenSettings(res.value);

    statsEvents.supportKeys(settings);

    sent = true;
  } catch (e) {
    sentryUtil.logException("supportKeys saga", e);
  }
};
