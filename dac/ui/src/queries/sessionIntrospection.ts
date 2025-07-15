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

const fromLocalStorage = () => {
  const { expires, token, userName } = JSON.parse(
    window.localStorage.getItem("user")!,
  );
  return {
    exp: new Date(expires),
    token: token as string,
    username: userName as string,
  };
};

export const fetchSessionIntrospection = async ({
  signal,
}: {
  signal?: AbortSignal;
} = {}) => {
  try {
    const session = fromLocalStorage();
    if (session.exp < new Date()) {
      return null;
    }
    return fetch("/apiv2/login", {
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${session.token}`,
      },
      signal,
    })
      .then((res) => res.json() as Promise<boolean>)
      .then((res) => {
        if (res) {
          return { exp: session.exp, username: session.username };
        }
        return null;
      })
      .catch(() => null);
  } catch (_e) {
    return null;
  }
};

export const sessionIntrospectionQuery = queryOptions({
  queryKey: ["session-introspection"],
  queryFn: fetchSessionIntrospection,
  gcTime: Infinity,
  retry: false,
  staleTime: Infinity,
});
