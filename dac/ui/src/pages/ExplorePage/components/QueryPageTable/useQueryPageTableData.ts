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

import { useSelector } from "react-redux";
import type { JobSummary } from "#oss/exports/types/JobSummary.type";
import { getExploreState } from "#oss/selectors/explore";
import { getAllJobDetails, getJobSummaries } from "#oss/selectors/exploreJobs";
import { type QueryStatusType } from "../SqlEditor/SqlQueryTabs/utils";

const viewSelector = (state: unknown) => {
  const { view }: { view: Record<string, unknown> } = getExploreState(state);

  return {
    allJobDetails: getAllJobDetails(state),
    isMultiQueryRunning: view.isMultiQueryRunning as boolean,
    jobSummaries: getJobSummaries(state),
    queryFilter: (view.queryFilter as string).toLocaleLowerCase(),
    queryStatuses: view.queryStatuses as QueryStatusType[],
  };
};

const getFilteredPendingQueries = (
  pendingQueries: { index: number; sql: string }[],
  queryFilter?: string,
) => {
  if (!queryFilter) {
    return [...pendingQueries];
  }

  return pendingQueries.filter((pendingQuery) =>
    pendingQuery.sql.toLocaleLowerCase().includes(queryFilter),
  );
};

const getFilteredSummaries = (
  jobSummaries: JobSummary[],
  queryFilter?: string,
) => {
  if (!queryFilter) {
    // used to render cached jobs in asc order
    return [...jobSummaries].sort((a, b) => a.startTime - b.startTime);
  }

  return jobSummaries.filter(
    (summary) =>
      summary.id.toLocaleLowerCase().includes(queryFilter) ||
      summary.description.toLocaleLowerCase().includes(queryFilter),
  );
};

/**
 * @returns An object with arrays for queries and pending queries to render
 * the <QueryTable> on the Query Page
 */
export const useQueryPageTableData = () => {
  const {
    allJobDetails,
    isMultiQueryRunning,
    jobSummaries,
    queryFilter,
    queryStatuses,
  } = useSelector(viewSelector);

  // Need to account for two kinds of pending queries
  // 1) Queries that have not been submitted (do not have a jobId)
  // 2) Queries that have been submitted but have not been polled (have a jobId but no summary)
  const pendingQueries = queryStatuses.reduce(
    (acc, cur, index) =>
      !cur.cancelled && (!cur.jobId || !jobSummaries[cur.jobId])
        ? [...acc, { index, sql: cur.sqlStatement }]
        : acc,
    [] as { index: number; sql: string }[],
  );

  return {
    isMultiQueryRunning,
    pendingQueries: isMultiQueryRunning
      ? getFilteredPendingQueries(pendingQueries, queryFilter)
      : [],
    queries: getFilteredSummaries(
      Object.values(jobSummaries) as JobSummary[],
      queryFilter,
    ).map((jobSummary) => {
      const curJobDetails = allJobDetails[jobSummary.id];

      return {
        accelerated: jobSummary.accelerated,
        duration: curJobDetails?.duration,
        isComplete: curJobDetails?.isComplete,
        jobId: jobSummary.id,
        numAttempts: curJobDetails?.attemptDetails.length,
        queryType: curJobDetails?.queryType,
        spilled: jobSummary.spilled,
        sql: jobSummary.description,
        startTime: jobSummary.startTime,
        status: jobSummary.state,
      };
    }),
    queryStatuses,
  };
};
