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

import { getDremioContext } from "dremio-ui-common/contexts/DremioContext.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";

import { Ok, Err } from "ts-results-es";

type Setting =
  | {
      id: string;
      value: string;
      type: "TEXT";
    }
  | {
      id: string;
      value: boolean;
      type: "BOOLEAN";
    }
  | {
      id: string;
      value: number;
      type: "INTEGER" | "FLOAT";
    }
  | {
      id: string;
      value: unknown;
      type: string;
    };

export type SettingsResponse = Setting[];

// Fetches support keys
export const fetchSupportKeys = ({ signal }: { signal?: AbortSignal } = {}) => {
  try {
    const dremioContext = getDremioContext(
      getSonarContext().getSelectedProjectId?.(),
    );
    return dremioContext
      ._sonarV2Request("settings", {
        signal,
        body: JSON.stringify({ includeSetSettings: true }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      })
      .then((res) => res.json())
      .then((res) => Ok(res.settings as SettingsResponse))
      .catch((e) => Err(e));
  } catch (e) {
    return Err(e);
  }
};
