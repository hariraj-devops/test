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
import { v4 as uuidv4 } from "uuid";
import Immutable from "immutable";

import localStorageUtils from "@inject/utils/storageUtils/localStorageUtils";
import APICall from "#oss/core/APICall";
import ApiCallMixin from "@inject/utils/apiUtils/ApiUtilsMixin";

import { DEFAULT_ERR_MSG } from "@inject/constants/errors";
import { appFetchWithoutErrorHandling } from "dremio-ui-common/utilities/appFetch.js";

/**
 * Error names from api middleware.
 * see {@link https://github.com/agraboso/redux-api-middleware}
 * {@code instanceof} does not work in babel environment for errors, so we have to use names
 */
export const ApiMiddlewareErrors = {
  InvalidRSAA: "InvalidRSAA",
  InternalError: "InternalError",
  RequestError: "RequestError",
  ApiError: "ApiError",
};

@ApiCallMixin
class ApiUtils {
  isApiError(error) {
    return (
      error instanceof Error &&
      (error.name === ApiMiddlewareErrors.InvalidRSAA ||
        error.name === ApiMiddlewareErrors.InternalError ||
        error.name === ApiMiddlewareErrors.RequestError ||
        error.name === ApiMiddlewareErrors.ApiError)
    );
  }

  /**
   * Make abortGroup object to be used by reducers/index.js to cancel API requests
   * startTime is added for logging
   * @param groupName
   * @return {{startTime: number, actionGroup: *}}
   */
  getAbortInfo(groupName) {
    return {
      actionGroup: groupName,
      startTime: Date.now(),
    };
  }

  getEntityFromResponse(entityType, response) {
    return response.payload.getIn([
      "entities",
      entityType,
      response.payload.get("result"),
    ]);
  }

  getFromResponse(response) {
    const payload = response.payload || new Map();
    const resultId = payload.get("result");
    const fullDataset = payload.getIn(["entities", "fullDataset", resultId]);
    const sqlStatement = payload.getIn([
      "entities",
      "datasetUI",
      resultId,
      "sql",
    ]);

    return [
      sqlStatement,
      fullDataset.getIn(["jobId", "id"]),
      fullDataset.getIn(["sessionId", "id"]),
      resultId,
    ];
  }

  getFromJSONResponse(response) {
    const payload = response.payload;

    return {
      dataset: payload.dataset,
      datasetPath: payload.datasetPath,
      datasetVersion: payload.datasetVersion,
      jobId: payload.jobId?.id,
      paginationUrl: payload.paginationUrl,
      sessionId: payload.sessionId?.id,
    };
  }

  parseErrorsToObject(response) {
    const errorFields = {};
    if (
      response.validationErrorMessages &&
      response.validationErrorMessages.fieldErrorMessages
    ) {
      const { fieldErrorMessages } = response.validationErrorMessages;
      for (const key in fieldErrorMessages) {
        errorFields[key] = fieldErrorMessages[key][0];
      }
    }
    return errorFields;
  }

  attachFormSubmitHandlers(promise) {
    // throws reject promise for reduxForm's handleSubmit
    return promise
      .then((action) => {
        if (action && action.error) {
          const error = action.payload;
          const { response } = error;
          const errorId = uuidv4();
          if (response) {
            return this.handleError(response);
          }
          throw { _error: { message: error.message, id: errorId } };
        }
        return action;
      })
      .catch(this.handleError);
  }

  handleError = (error) => {
    if (error.errorMessage) {
      const errorFields = this.parseErrorsToObject(error);
      const errors = {
        _error: { message: Immutable.Map(error), id: uuidv4() },
        ...errorFields,
      };
      throw errors;
    }
    if (error.meta && error.meta.validationError) {
      throw error.meta.validationError;
    }
    if (error.statusText) {
      // chris asks: how would this be possible? (fetch API rejects with TypeError)
      throw {
        _error: {
          message: "Request Error: " + error.statusText,
          id: uuidv4(),
        },
      }; // todo: loc
    }
    throw error;
  };

  prepareHeaders = () => {
    const token = localStorageUtils.getAuthToken();
    return {
      "Content-Type": "application/json",
      ...(token && { Authorization: token }),
    };
  };

  fetch = (endpoint, options = {}, version = 3) => {
    const apiVersion = this.getAPIVersion(version, options.customOptions);

    const headers = new Headers({
      ...this.prepareHeaders(),
      ...options.headers,
    }); // protect against older chrome browsers

    let url;

    if (endpoint instanceof APICall) {
      url = endpoint.toString();
    } else {
      url = endpoint.startsWith("/")
        ? `${apiVersion}${endpoint}`
        : `${apiVersion}/${endpoint}`;
    }

    // Include all options except custom options which are used to set URL
    const {
      customOptions, // eslint-disable-line @typescript-eslint/no-unused-vars
      ...fetchOptions
    } = options;

    return appFetchWithoutErrorHandling(url, {
      ...fetchOptions,
      headers,
    }).catch((e) => {
      if (!Object.prototype.isPrototypeOf.call(Response.prototype, e)) {
        return this.handleError(e);
      } else {
        throw e;
      }
    });
  };

  fetchJson(endpoint, jsonHandler, errorHandler, options = {}, version = 3) {
    return this.fetch(endpoint, options, version)
      .then((response) => {
        // handle ok response
        return response
          .json()
          .then((json) => jsonHandler(json)) // handle json from response
          .catch((e) => errorHandler(e));
      })
      .catch((error) => errorHandler(error));
  }

  /**
   * Returns headers that enables writing numbers as strings for job data
   *
   * key should match with {@see WebServer#X_DREMIO_JOB_DATA_NUMBERS_AS_STRINGS} in {@see WebServer.java}
   * @returns headers object
   * @memberof ApiUtils
   */
  getJobDataNumbersAsStringsHeader() {
    return {
      "x-dremio-job-data-number-format": "number-as-string",
    };
  }

  // error response may contain moreInfo or errorMessage field, that should be used for error message
  async getErrorMessage(prefix, response, delimiter = ":") {
    if (!response || !response.json) return prefix;
    try {
      const err = await response.json();
      const errText = (err && (err.moreInfo || err.errorMessage)) || "";
      if (prefix && prefix !== "") {
        return errText.length
          ? `${prefix}${delimiter} ${errText}`
          : `${prefix}.`;
      } else {
        return errText;
      }
    } catch {
      return DEFAULT_ERR_MSG;
    }
  }

  getThrownErrorException(e) {
    const error = e.toJS ? e.toJS() : e;
    let message;

    const firstError =
      error?.response?.payload?.response?.errorMessage ||
      error?.payload?.response?.errorMessage ||
      "";
    const secondError = error?.response?.payload?.message || "";
    const thirdError = error?.message || "";

    if (firstError) {
      message = firstError;
    } else if (secondError) {
      message = secondError;
    } else if (thirdError) {
      message = thirdError;
    } else if (typeof error === "string" && error) {
      message = error;
    } else {
      message = "Error";
    }

    return message;
  }
}

const apiUtils = new ApiUtils();

export default apiUtils;
