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

let sent = false;

export const sourcesEvent = async ({ payload }: any) => {
  try {
    if (sent) return;

    const sources = payload.getIn(["entities", "source"]);
    if (!sources || !sources.size) return;

    const totals: any = {};
    sources.forEach((source: any) => {
      totals[source.get("type")] = totals[source.get("type")] + 1 || 1;
    });
    Object.entries(totals).forEach(([key, value]) => {
      totals[key] = String(value);
    });

    statsEvents.sourceTotals(totals);

    sent = true;
  } catch (e) {
    sentryUtil.logException("stats saga", e);
  }
};
