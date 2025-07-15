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
import { statsEvents } from "dremio-ui-common/appEvents/stats/statsEvents";
import metrics from "#oss/metrics";
import sentryUtil from "#oss/utils/sentryUtil";
import { getPrivilegeContext } from "dremio-ui-common/contexts/PrivilegeContext.js";

const STORAGE_KEY = "lscse";

const setStorage = (value: string) => {
  window.localStorage.setItem(STORAGE_KEY, window.btoa(JSON.stringify(value)));
};

const getStorage = () => {
  try {
    const storage = JSON.parse(
      window.atob(window.localStorage.getItem(STORAGE_KEY)!),
    );
    if (typeof storage !== "string") {
      throw new Error("Recent storage did not parse into the expected format");
    }
    return storage;
  } catch {
    window.localStorage.removeItem(STORAGE_KEY);
    return null;
  }
};

export async function sendStats() {
  try {
    const result = await metrics.fetchJobAndUserStats(2, true);

    // Skip if only today is available
    if (!result?.jobStats || result.jobStats.length < 2) {
      return;
    }

    const cur = result.jobStats[result.jobStats.length - 2];

    const date = cur?.date;
    const last = getStorage();

    // Already done
    if (!date || (last && last === date)) {
      return;
    }

    const stats = { ...cur };
    if ("total" in cur) delete cur.total;

    statsEvents.clusterStats(stats);

    setStorage(date);
  } catch (e) {
    sentryUtil.logException("stats", e);
  }
}

export const statsEvent = async () => {
  try {
    const isAdmin = (
      getPrivilegeContext() as { isAdmin: () => boolean }
    ).isAdmin();

    if (!isAdmin) return;

    sendStats();
  } catch (e) {
    sentryUtil.logException("stats saga", e);
  }
};
