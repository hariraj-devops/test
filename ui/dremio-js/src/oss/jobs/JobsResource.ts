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

import type { SonarV3Config } from "../../_internal/types/Config.ts";
import { Job, jobEntityToProperties } from "./Job.ts";
import { Query } from "../../common/Query.ts";
import { Err, Ok } from "ts-results-es";
import type {
  LimitParams,
  OffsetParams,
  SignalParam,
} from "../../_internal/types/Params.ts";
import { withOffsetAsyncIter } from "../../_internal/IterationHelpers.ts";

type JobsSortable = "dur";

type JobsSortOptions =
  | {
      sort: JobsSortable;
      order: "asc" | "desc";
    }
  | {
      sort?: never;
      order?: never;
    };

export const JobsResource = (config: SonarV3Config) => {
  return {
    _list: withOffsetAsyncIter(
      (
        params: Partial<OffsetParams & LimitParams> & {
          batch_size?: number;
        } & JobsSortOptions & { filter?: string; detailed?: boolean } = {},
      ) => {
        const search = new URLSearchParams({
          ...(params.limit && { limit: String(params.limit) }),
          ...(params.offset && { offset: String(params.offset) }),
          ...(params.detailed && { detailLevel: "1" }),
          ...(params.sort && {
            order: params.order === "asc" ? "ASCENDING" : "DESCENDING",
            sort: params.sort,
          }),
          ...(params.filter && { filter: params.filter }),
        });
        return (config as any)
          .sonarV2Request(`jobs-listing/v1.0?${search.toString()}`)
          .then((res: any) => res.json())
          .then((collection: any) => {
            return {
              data: collection.jobs,
              hasNextPage: !!collection.next?.length,
            };
          });
      },
    ),
    create: (query: Query) => {
      return config
        .sonarV3Request(`sql`, {
          body: JSON.stringify({
            context: query.context,
            sql: query.sql,
          }),
          headers: {
            "Content-Type": "application/json",
          },
          keepalive: true,
          method: "POST",
        })
        .then((res) => res.json())
        .then((response) => Ok(response.id as string))
        .catch((e: unknown) => Err(e));
    },
    retrieve: (id: string, { signal }: SignalParam = {}) =>
      config
        .sonarV3Request(`job/${id}`, { signal })
        .then((res) => res.json())
        .then((properties) =>
          Ok(new Job(jobEntityToProperties(id, properties), config)),
        )
        .catch((e: unknown) => Err(e)),
  };
};
