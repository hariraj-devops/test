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

import { memo, type FC } from "react";
import { Link } from "react-router";
import { type Column } from "leantable/react";
import { cloneDeep } from "lodash";
import { IconButton } from "dremio-ui-lib/components";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { job } from "dremio-ui-common/paths/jobs.js";
import { QueryTable } from "dremio-ui-common/sonar/components/QueryTable/QueryTable.js";
import { setQueryStatuses } from "#oss/actions/explore/view";
import { cancelJobAndShowNotification } from "#oss/actions/jobs/jobs";
import { store } from "#oss/store/store";
import { type QueryStatusType } from "../SqlEditor/SqlQueryTabs/utils";
import { useQueryPageTableData } from "./useQueryPageTableData";

const { t } = getIntlContext();

const getQueryActions =
  (
    queryStatuses: QueryStatusType[],
    isMultiQueryRunning: boolean,
  ): Column<
    | { index: number; sql: string }
    | {
        isComplete?: boolean;
        jobId: string;
        numAttempts?: number;
        sql: string;
        status: string;
      }
  >["renderCell"] =>
  (row) => {
    if (!row.data.status) {
      return (
        <div className="flex items-center icon-primary">
          <span className="h-4 w-4" />
          <IconButton
            tooltip={t("Sonar.Query.Table.Remove")}
            tooltipPortal
            onClick={() => {
              const clonedStatuses = cloneDeep(queryStatuses);
              clonedStatuses[row.data.index].cancelled = true;
              store.dispatch(setQueryStatuses({ statuses: clonedStatuses }));
            }}
          >
            <dremio-icon name="interface/delete" />
          </IconButton>
        </div>
      );
    }

    return (
      <div className="flex items-center icon-primary">
        {!row.data.isComplete ? (
          <IconButton
            tooltip={t("Sonar.Job.Action.Cancel")}
            tooltipPortal
            onClick={() => {
              store.dispatch(cancelJobAndShowNotification(row.data.jobId));
            }}
          >
            <dremio-icon name="sql-editor/stop" />
          </IconButton>
        ) : (
          // Prevents the table columns from resizing while queries are running
          isMultiQueryRunning && <span className="h-4 w-4" />
        )}
        <IconButton
          as={Link}
          tooltip={t("Sonar.Job.Action.Open")}
          tooltipPortal
          to={`${job.link({
            jobId: row.data.jobId,
            projectId: getSonarContext().getSelectedProjectId?.(),
          })}?attempts=${row.data.numAttempts || 1}`}
          target="_blank"
        >
          <dremio-icon name="interface/external-link" />
        </IconButton>
      </div>
    );
  };

export const QueryPageTable: FC<{ onQueryClick: (tabIndex: number) => void }> =
  memo((props) => {
    const { isMultiQueryRunning, pendingQueries, queries, queryStatuses } =
      useQueryPageTableData();

    return (
      <QueryTable
        onQueryClick={(i: number) => props.onQueryClick(i + 1)}
        pendingQueries={pendingQueries}
        queries={queries}
        queryActions={getQueryActions(queryStatuses, isMultiQueryRunning)}
        className="mx-1"
      />
    );
  });
