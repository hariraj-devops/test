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

import { queryOptions } from "@tanstack/react-query";
import { dremio } from "#oss/dremio";

export const USER_STALE_TIME = Infinity;

export const userByUsername = (username: string) =>
  queryOptions({
    queryKey: ["user", username],
    queryFn: () =>
      dremio.users.retrieveByName(username).then((result) => result.unwrap()),
    retry: false,
    staleTime: USER_STALE_TIME,
    enabled: !!username,
  });

export const userById = (id: string) =>
  queryOptions({
    queryKey: ["user", id],
    queryFn: () => dremio.users.retrieveById(id),
    retry: false,
    staleTime: USER_STALE_TIME,
    enabled: !!id,
  });
