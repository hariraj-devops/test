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
import moize from "moize";
import { getApiContext } from "dremio-ui-common/contexts/ApiContext.js";
import { type FeatureFlagResponse } from "./FeatureFlagResponse.type";
import { APIV2Call } from "#oss/core/APICall";

export const getFeatureFlagEnabledUrl = (flagId: string) =>
  new APIV2Call().projectScope(false).paths(`features/${flagId}`).toString();

export const getFeatureFlagEnabled = moize(
  (flagId: string): Promise<boolean> => {
    return getApiContext()
      .fetch(getFeatureFlagEnabledUrl(flagId))
      .then((res) => res.json() as unknown as FeatureFlagResponse)
      .then((res) => res.entitlement === "ENABLED");
  },
  { isPromise: true, maxSize: Infinity },
);
