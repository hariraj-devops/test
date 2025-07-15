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

import * as ActionTypes from "actions/jobs/jobs";
import * as JobListActionTypes from "actions/joblist/jobList";
import jobsMapper from "utils/mappers/jobsMapper";
import StateUtils from "utils/stateUtils";
import { getLoggingContext } from "dremio-ui-common/contexts/LoggingContext.js";

const logger = getLoggingContext().createLogger("reducers/jobs/jobs");

const initialState = Immutable.fromJS({
  jobs: [],
  jobList: [],
  datasetsList: [],
  dataForFilter: {},
  jobDetails: {},
  filters: {},
  orderedColumn: { columnName: null, order: "desc" },
  isInProgress: false,
  isFailed: false,
  clusterType: "NA",
  isSupport: false,
  jobExecutionDetails: [],
  jobExecutionOperatorDetails: {},
});

function checkJobExists(state, jobId) {
  return !!state.get("jobList").find((job) => job.get("id") === jobId);
}

export default function jobs(state = initialState, action) {
  // Not sure if window.location is necessary
  const tabId = action.tabId || action.meta?.tabId; //Consolidate
  if (tabId && !(window.location.search || "").includes(tabId)) {
    logger.debug(
      "tabId has changed, skipping action in parent reducer",
      action,
    );
    return state; //Handled in tabJobsReducer;
  }

  switch (action.type) {
    case "SET_JOB_LIST": {
      return state.set("jobList", Immutable.fromJS(action.jobList));
    }
    case ActionTypes.UPDATE_JOB_STATE: {
      if (!checkJobExists(state, action.jobId)) return state;
      const index = state
        .get("jobs")
        .findIndex((job) => job.get("id") === action.jobId);
      if (index !== -1) {
        const oldJob = state.getIn(["jobs", index]);
        if (!oldJob) return state;

        return state.setIn(
          ["jobs", index],
          Immutable.Map({
            // For performance, job-progress websocket message does not include these.
            // Can't just merge because jackson omits null fields
            // (there would be no way to override a null value)
            datasetPathList: oldJob.get("datasetPathList"),
            datasetType: oldJob.get("datasetType"),
            ...action.payload,
          }),
        );
      }
      return state;
    }
    case ActionTypes.UPDATE_QV_JOB_STATE: {
      if (!checkJobExists(state, action.jobId)) return state;

      const jobsListInState = state.get("jobList");

      const index = state
        .get("jobList")
        .findIndex((job) => job.get("id") === action.jobId);
      if (index !== -1) {
        const oldJob = state.getIn(["jobList", index]);
        if (!oldJob) return state;
        return state.setIn(
          ["jobList", index],
          Immutable.fromJS(action.payload),
        );
      } else if (jobsListInState.size === 0) {
        return state.set("jobList", Immutable.fromJS([action.payload]));
      }
      return state;
    }
    case ActionTypes.JOBS_DATA_REQUEST:
    case ActionTypes.SORT_JOBS_REQUEST:
      return StateUtils.request(state, ["jobs"]);

    case ActionTypes.JOBS_DATA_FAILURE:
    case ActionTypes.SORT_JOBS_FAILURE:
    case ActionTypes.REFLECTION_JOB_DETAILS_FAILURE:
      return StateUtils.failed(state, ["jobs"]).set("isFailed", true);

    case ActionTypes.JOBS_DATA_SUCCESS: {
      return StateUtils.success(
        state,
        ["jobs"],
        action.payload,
        jobsMapper.mapJobs,
      )
        .set("filters", new Immutable.Map())
        .set(
          "orderedColumn",
          new Immutable.Map({ columnName: null, order: "desc" }),
        );
    }

    case ActionTypes.SORT_JOBS_SUCCESS:
      return StateUtils.success(
        state,
        ["jobs"],
        action.payload,
        jobsMapper.mapJobs,
      ).set("orderedColumn", action.meta.config);

    case ActionTypes.JOBS_DATASET_DATA_SUCCESS:
      return StateUtils.success(
        state,
        ["datasetsList"],
        action.payload,
        jobsMapper.mapDatasetsJobs,
      );

    case ActionTypes.SET_CLUSTER_TYPE:
      return state
        .set("clusterType", action.payload.clusterType)
        .set("isSupport", action.payload.isSupport);

    case JobListActionTypes.JOBS_LIST_RESET:
      return state.set("jobList", action.payload);

    case JobListActionTypes.SAVE_JOB_RESET:
      return state.set("uniqueSavingJob", action.payload);

    case JobListActionTypes.ITEMS_FOR_FILTER_JOBS_LIST_SUCCESS:
      return state.setIn(
        ["dataForFilter", action.meta.tag],
        action.payload.items,
      );

    case JobListActionTypes.FETCH_JOB_EXECUTION_DETAILS_BY_ID_SUCCESS:
      return state.set("jobExecutionDetails", Immutable.fromJS(action.payload));
    case JobListActionTypes.FETCH_JOB_EXECUTION_OPERATOR_DETAILS_BY_ID_SUCCESS:
      return state.set(
        "jobExecutionOperatorDetails",
        Immutable.fromJS(action.payload),
      );
    case JobListActionTypes.CLEAR_JOB_PROFILE_DATA:
      return state
        .set("jobExecutionDetails", Immutable.fromJS([]))
        .set("jobExecutionOperatorDetails", Immutable.fromJS({}));
    default:
      return state;
  }
}
