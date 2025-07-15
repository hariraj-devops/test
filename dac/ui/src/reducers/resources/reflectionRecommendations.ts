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

import { ReflectionRecommendations } from "#oss/components/Acceleration/ReflectionRecommendations.types";
import { SET_REFLECTION_RECOMMENDATIONS } from "#oss/actions/resources/reflectionRecommendations";

type ReflectionRecommendationsActions = {
  type: string;
  reflections: ReflectionRecommendations;
};

export default function reflectionRecommendations(
  state: ReflectionRecommendations | undefined = [],
  action: ReflectionRecommendationsActions,
): ReflectionRecommendations {
  switch (action.type) {
    case SET_REFLECTION_RECOMMENDATIONS:
      return action?.reflections.length === 0
        ? action.reflections
        : [
            ...state.filter(
              (ref) => ref?.type !== action?.reflections?.[0]?.type,
            ),
            ...action.reflections,
          ];
    default:
      return state;
  }
}
