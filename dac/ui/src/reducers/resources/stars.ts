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

import Immutable from "immutable";
import { LOAD_STARRED_RESOURCE_LIST_SUCCESS } from "#oss/actions/resources/stars";
import { starredResourceTreeNodeDecorator } from "#oss/components/Tree/resourceTreeUtils";

type StarsState = Immutable.Map<any, Immutable.List<any>>;

type StarsActions = {
  type: string;
  payload: any;
  meta: any;
};

function getInitialState(): StarsState {
  return Immutable.Map({
    starResourceList: Immutable.List(),
  });
}

export default function stars(
  state = getInitialState(),
  action: StarsActions,
): StarsState {
  if (action.type === LOAD_STARRED_RESOURCE_LIST_SUCCESS) {
    const { nodeExpanded } = action.meta;
    const payloadKey = !nodeExpanded ? "entities" : "resources";
    return Immutable.Map(
      starredResourceTreeNodeDecorator(state, action, payloadKey),
    );
  }
  return state;
}
