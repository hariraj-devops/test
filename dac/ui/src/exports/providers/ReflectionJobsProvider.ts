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

import { useCallback, useEffect, useMemo, useState } from "react";
import { useResourceSnapshot } from "smart-resource1/react";

import { withRouter, WithRouterProps } from "react-router";
import { parseQueryState } from "dremio-ui-common/utilities/jobs.js";
import { JobsQueryParams } from "dremio-ui-common/types/Jobs.types";
import {
  DefaultJobQuery,
  isRunningJob,
} from "#oss/pages/JobsPage/jobs-page-utils";
import {
  PaginatedReflectionJobsResource,
  ReflectionJobsPollingResource,
} from "../resources/ReflectionJobsResource";
import { debounce, isEqual } from "lodash";
import { usePrevious } from "#oss/utils/jobsUtils";
import { useResourceSnapshotDeep } from "../utilities/useDeepResourceSnapshot";
import { useResourceStatus } from "smart-resource/react";

const debouncedFetch = debounce(
  (reflectionId: string, pagesRequested: number, query: JobsQueryParams) =>
    PaginatedReflectionJobsResource.fetch(reflectionId, pagesRequested, query),
  500,
  {
    leading: true,
  },
);

/*
 *
 * Hook for running reflection jobs in the current page
 *
 */
const useRunningJobs = (reflectionId: string, jobs: any[]) => {
  const runningJobIds = useMemo(
    () =>
      (jobs || [])
        .filter((job: any) => isRunningJob(job.state))
        .map((job: any) => job.id),
    [jobs],
  );

  const previousRunningJobIds = usePrevious(runningJobIds);

  useEffect(() => {
    if (!isEqual(runningJobIds, previousRunningJobIds)) {
      ReflectionJobsPollingResource.reset();

      if (!runningJobIds.length) {
        return;
      }
      ReflectionJobsPollingResource.start(() => [{ reflectionId }]);
    }
  }, [reflectionId, runningJobIds, previousRunningJobIds]);

  useEffect(
    () =>
      ReflectionJobsPollingResource.reset.bind(ReflectionJobsPollingResource),
    [],
  );

  const [result] = useResourceSnapshotDeep(ReflectionJobsPollingResource);
  const status = useResourceStatus(ReflectionJobsPollingResource);

  useEffect(() => {
    if (status === "initial" || status === "pending") return;

    const someJobsRunning = (result?.jobs || []).some((job: any) =>
      isRunningJob(job.state),
    );
    if (!someJobsRunning) {
      ReflectionJobsPollingResource.stop.bind(ReflectionJobsPollingResource)();
    }
  }, [result, status]);

  return (result?.jobs || []).reduce((acc: any, cur: any) => {
    acc.set(cur.id, cur);
    return acc;
  }, new Map<string, any>());
};

/*
 *
 * Hook for reflection's jobs, based on query
 *
 */
const useReflectionJobs = (
  query: JobsQueryParams | undefined,
  reflectionId: string,
) => {
  const jobs = useResourceSnapshot(PaginatedReflectionJobsResource);
  const hasMorePages = !!jobs.value?.next;
  const [pagesRequested, setPagesRequested] = useState(1);

  useEffect(() => {
    PaginatedReflectionJobsResource.reset();
  }, []);

  const getJobs = useCallback(() => {
    if (query) debouncedFetch(reflectionId, pagesRequested, query);
  }, [pagesRequested, query, reflectionId]);

  useEffect(() => {
    getJobs();
  }, [getJobs]);

  return {
    jobs: jobs.value?.jobs,
    jobsErr: jobs.error,
    loadNextPage: useCallback(() => {
      if (jobs.status !== "success" || !hasMorePages) {
        return;
      }
      setPagesRequested((x) => x + 1);
    }, [hasMorePages, jobs.status]),
    pagesRequested,
    loading: jobs.status === "pending",
  };
};

/*
 *
 * Reflection jobs page provider
 *
 */
type ReflectionJobsProviderProps = {
  children: ({
    jobs,
    jobsErr,
    query,
    loadNextPage,
    pagesRequested,
    loading,
    runningJobs,
  }: {
    jobs: any;
    jobsErr: unknown;
    query: JobsQueryParams | undefined;
    loadNextPage: () => void;
    pagesRequested: number;
    loading: boolean;
    runningJobs: any;
  }) => JSX.Element;
} & WithRouterProps;

export const ReflectionJobsProvider = withRouter(
  (props: ReflectionJobsProviderProps): JSX.Element => {
    const { router, location, params } = props;
    const { query: urlQuery = {}, pathname } = props.location;

    // Handle query precendence for URL/local storage
    useEffect(() => {
      if (!urlQuery.filters) {
        router.push({
          ...location,
          query: DefaultJobQuery,
          pathname: pathname,
        });
      }
    }, [urlQuery, router, pathname, location]);

    // Create query used for endpoint
    const curQuery = useMemo(() => {
      return urlQuery.filters ? parseQueryState(urlQuery) : undefined;
    }, [urlQuery]);

    // Get jobs
    const { jobs, jobsErr, loadNextPage, pagesRequested, loading } =
      useReflectionJobs(curQuery, params.reflectionId);

    // Get running jobs
    const runningJobs = useRunningJobs(params.reflectionId, jobs);

    return props.children({
      jobs: jobs,
      jobsErr,
      query: curQuery,
      loadNextPage,
      pagesRequested,
      loading,
      runningJobs,
    });
  },
);
