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
import { RSAA } from "redux-api-middleware";
import { push } from "react-router-redux";
import Immutable from "immutable";
import { normalize } from "normalizr";

import schemaUtils from "utils/apiUtils/schemaUtils";
import jobDetailsSchema from "schemas/jobDetails";
import { addNotification } from "actions/notification";
import tokenUtils from "@inject/utils/tokenUtils";
import { APIV2Call } from "#oss/core/APICall";

export const UPDATE_JOB_DETAILS = "UPDATE_JOB_DETAILS";

export const updateJobDetails = (jobDetails) => ({
  type: UPDATE_JOB_DETAILS,
  payload: Immutable.fromJS(normalize(jobDetails, jobDetailsSchema)),
});

export const UPDATE_JOB_STATE = "UPDATE_JOB_STATE";
export const UPDATE_QV_JOB_STATE = "UPDATE_QV_JOB_STATE";

export const updateJobState = (jobId, payload) => ({
  type: UPDATE_JOB_STATE,
  jobId,
  payload,
});

export const updateQVJobState = (jobId, payload) => ({
  type: UPDATE_QV_JOB_STATE,
  jobId,
  payload,
});

export const JOB_DETAILS_REQUEST = "JOB_DETAILS_REQUEST";
export const JOB_DETAILS_SUCCESS = "JOB_DETAILS_SUCCESS";
export const JOB_DETAILS_FAILURE = "JOB_DETAILS_FAILURE";

export function loadJobDetails(jobId, viewId) {
  const meta = { jobId, viewId };

  const apiCall = new APIV2Call().path("job").path(jobId).path("details");

  return {
    [RSAA]: {
      types: [
        { type: JOB_DETAILS_REQUEST, meta },
        schemaUtils.getSuccessActionTypeWithSchema(
          JOB_DETAILS_SUCCESS,
          jobDetailsSchema,
          meta,
        ),
        { type: JOB_DETAILS_FAILURE, meta },
      ],
      method: "GET",
      endpoint: apiCall,
    },
  };
}

export const REFLECTION_JOB_DETAILS_REQUEST = "REFLECTION_JOB_DETAILS_REQUEST";
export const REFLECTION_JOB_DETAILS_SUCCESS = "REFLECTION_JOB_DETAILS_SUCCESS";
export const REFLECTION_JOB_DETAILS_FAILURE = "REFLECTION_JOB_DETAILS_FAILURE";

export function loadReflectionJobDetails(jobId, reflectionId, viewId) {
  const meta = { jobId, viewId };

  const apiCall = new APIV2Call()
    .path("job")
    .path(jobId)
    .path("reflection")
    .path(reflectionId)
    .path("details");

  return {
    [RSAA]: {
      types: [
        { type: REFLECTION_JOB_DETAILS_REQUEST, meta },
        schemaUtils.getSuccessActionTypeWithSchema(
          REFLECTION_JOB_DETAILS_SUCCESS,
          jobDetailsSchema,
          meta,
        ),
        { type: REFLECTION_JOB_DETAILS_FAILURE, meta },
      ],
      method: "GET",
      endpoint: apiCall,
    },
  };
}

export const CANCEL_JOB_REQUEST = "CANCEL_JOB_REQUEST";
export const CANCEL_JOB_SUCCESS = "CANCEL_JOB_SUCCESS";
export const CANCEL_JOB_FAILURE = "CANCEL_JOB_FAILURE";

const cancelJob = (jobId) => {
  const apiCall = new APIV2Call().paths(`job/${jobId}/cancel`);

  return {
    [RSAA]: {
      types: [CANCEL_JOB_REQUEST, CANCEL_JOB_SUCCESS, CANCEL_JOB_FAILURE],
      method: "POST",
      endpoint: apiCall,
    },
  };
};

export const cancelJobAndShowNotification = (jobId) => (dispatch) => {
  return dispatch(cancelJob(jobId)).then((action) => {
    if (action.payload.response && action.payload.response.errorMessage) {
      return dispatch(
        addNotification(action.payload.response.errorMessage, "error"),
      );
    } else {
      return dispatch(addNotification(action.payload.message, "success"));
    }
  });
};

export const ASK_GNARLY_STARTED = "ASK_GNARLY_STARTED";
export const ASK_GNARLY_SUCCESS = "ASK_GNARLY_SUCCESS";
export const ASK_GNARLY_FAILURE = "ASK_GNARLY_FAILURE";

function fetchAskGnarly(jobId) {
  const apiCall = new APIV2Call().paths(`support/${jobId}`);

  return {
    [RSAA]: {
      types: [
        {
          type: ASK_GNARLY_STARTED,
          meta: {
            notification: {
              message: laDeprecated("Uploading data. Chat will open shortly."),
              level: "success",
            },
          },
        },
        {
          type: ASK_GNARLY_SUCCESS,
          meta: {
            notification: (payload) => {
              if (!payload.success) {
                return {
                  message:
                    payload.url ||
                    laDeprecated(
                      "Upload failed. Please check logs for details.",
                    ),
                  level: "error",
                };
              }
              return {
                message: false,
              };
            },
          },
        },
        {
          type: ASK_GNARLY_FAILURE,
          meta: {
            notification: {
              message: laDeprecated(
                "There was an error uploading. Please check logs for details.",
              ),
              level: "error",
            },
          },
        },
      ],
      method: "POST",
      endpoint: apiCall,
    },
  };
}

export function askGnarly(jobId) {
  return (dispatch) => {
    return dispatch(fetchAskGnarly(jobId));
  };
}

export function showJobProfile(profileUrl, jobDetails) {
  return (dispatch, getState) => {
    tokenUtils
      .getTempToken({
        params: {
          durationSeconds: 30,
          request: profileUrl,
        },
        requestApiVersion: 2,
      })
      .then((data) => {
        const apiCall = new APIV2Call()
          .fullpath(profileUrl)
          .param(".token", data.token ? data.token : "");
        const location = getState().routing.locationBeforeTransitions;
        return dispatch(
          push({
            ...location,
            state: {
              ...location.state,
              modal: "JobProfileModal",
              profileUrl: apiCall.toString(),
              jobDetails,
            },
          }),
        );
      })
      .catch((error) => error);
  };
}

export function showReflectionJobProfile(profileUrl, reflectionId) {
  // add the reflection id to the end of the url, but before the ? (url always has ?attemptId)
  const split = profileUrl.split("/");
  split[2] = split[2].replace("?", `/reflection/${reflectionId}?`);
  const url = split.join("/");

  return showJobProfile(url);
}

export const SET_CLUSTER_TYPE = "SET_CLUSTER_TYPE";

export const setClusterType = (value) => ({
  type: SET_CLUSTER_TYPE,
  payload: value,
});
