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
import APICall from "#oss/core/APICall";
import { getApiContext } from "dremio-ui-common/contexts/ApiContext.js";

type Params = {
  datasetId: string;
  type: "raw" | "agg";
};

export const postDatasetReflectionRecommendationsUrl = (params: Params) =>
  new APICall()
    .paths(
      `/dataset/${params.datasetId}/reflection/recommendation/${params.type}`,
    )
    .toString();

export const postDatasetReflectionRecommendations = async (
  params: Params,
): Promise<void> => {
  return getApiContext()
    .fetch(postDatasetReflectionRecommendationsUrl(params), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
    })
    .then((res) => res.json());
};
