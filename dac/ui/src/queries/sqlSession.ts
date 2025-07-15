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

import { queryOptions, useMutation } from "@tanstack/react-query";
import { dremio } from "#oss/dremio";
import { Ok, Err, Result } from "ts-results-es";
import { queryClient } from "#oss/queryClient";

export type SqlSessionInterface = {
  currentScriptId: string;
  scriptIds: string[];
};

export const getQueryKey = (pid?: string) => [pid, "sql-session"];

export const sqlSession = (_pid?: string) =>
  queryOptions({
    queryKey: getQueryKey(),
    queryFn: ({ signal }): Promise<Result<SqlSessionInterface, unknown>> =>
      dremio
        ._sonarV2Request(`sql-runner/session`, { signal })
        .then((res) => res.json())
        .then(({ currentScriptId, scriptIds }: SqlSessionInterface) =>
          Ok({ currentScriptId, scriptIds }),
        )
        .catch((e) => Err(e)),
  });

export const useSelectTabMutation = (_pid?: string) =>
  useMutation({
    mutationFn: async (scriptId: string) => {
      return dremio
        ._sonarV2Request(`sql-runner/session/tabs/${scriptId}`, {
          keepalive: true,
          method: "PUT",
        })
        .then((res) => res.json())
        .then(({ currentScriptId, scriptIds }: SqlSessionInterface) =>
          Ok({ currentScriptId, scriptIds }),
        );
    },
    onSuccess: (sqlSession) => {
      queryClient.setQueryData(getQueryKey(), sqlSession);
    },
  });

export const useCloseTabMutation = (_pid?: string) =>
  useMutation({
    mutationFn: async (scriptId: string) => {
      return dremio
        ._sonarV2Request(`sql-runner/session/tabs/${scriptId}`, {
          keepalive: true,
          method: "DELETE",
        })
        .then(() => undefined);
    },
    onMutate: async (scriptId) => {
      await queryClient.cancelQueries({ queryKey: getQueryKey() });

      const previousSession = queryClient.getQueryData(
        getQueryKey(),
      ) as SqlSessionInterface;

      queryClient.setQueryData(getQueryKey(), (prev: SqlSessionInterface) => ({
        ...prev,
        scriptIds: prev.scriptIds.filter((x) => x !== scriptId),
      }));

      return { previousSession };
    },
    onError: (_err, _scriptId, context) => {
      queryClient.setQueryData(getQueryKey(), context?.previousSession);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: getQueryKey() });
    },
  });
