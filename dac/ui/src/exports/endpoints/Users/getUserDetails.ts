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

import { getApiContext } from "dremio-ui-common/contexts/ApiContext.js";
import moize from "moize";
import { type UserDetails } from "./UserDetails.type";
import { joinName } from "../../utilities/joinName";
import { APIV2Call } from "#oss/core/APICall";

type GetUserParams = { id: string };

export const userDetailsUrl = (params: GetUserParams) =>
  new APIV2Call().projectScope(false).paths(`user/${params.id}`).toString();

export const getUserDetails = moize(
  (params: GetUserParams): Promise<UserDetails> => {
    return getApiContext()
      .fetch(userDetailsUrl(params))
      .then((res) => res.json())
      .then((userDetails) => {
        return {
          ...userDetails,
          fullname: joinName(userDetails.firstName, userDetails.lastName),
        };
      });
  },
  { isPromise: true, isDeepEqual: true, maxSize: Infinity },
);
