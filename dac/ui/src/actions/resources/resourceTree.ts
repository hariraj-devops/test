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
import { RSAA } from "redux-api-middleware";
import summaryDatasetSchema from "#oss/schemas/v2/summaryDataset";
import schemaUtils from "#oss/utils/apiUtils/schemaUtils";
import { APIV2Call } from "#oss/core/APICall";
import { store } from "#oss/store/store";
import { getRefQueryParamsFromPath } from "#oss/utils/nessieUtils";
import {
  LOAD_SUMMARY_DATASET_FAILURE,
  LOAD_SUMMARY_DATASET_START,
  LOAD_SUMMARY_DATASET_SUCCESS,
} from "./dataset";

function oldFetchSummaryDataset(
  fullPath: string,
  viewId: string,
  storageName: string,
  nodeExpanded: boolean,
  currNode: object,
  versionContext?: {
    type: "BRANCH" | "TAG" | "DETACHED";
    value: string;
  },
) {
  const meta = {
    viewId,
    fullPath,
    // @ts-ignore
    errorMessage: laDeprecated(
      "Cannot provide more information about this dataset.",
    ),
    isSummaryDatasetResponse: storageName ? true : false,
    nodeExpanded,
    currNode,
  };
  const params = versionContext
    ? {
        refType: versionContext.type,
        refValue: versionContext.value,
      }
    : getRefQueryParamsFromPath(fullPath, store.getState().nessie, "/");
  const apiCall = new APIV2Call()
    .paths("datasets/summary")
    .params(params)
    .paths(fullPath);

  return {
    [RSAA]: {
      types: [
        {
          type: `${
            storageName ? `${storageName}_START` : LOAD_SUMMARY_DATASET_START
          }`,
          meta,
        },
        schemaUtils.getSuccessActionTypeWithSchema(
          `${
            storageName
              ? `${storageName}_SUCCESS`
              : LOAD_SUMMARY_DATASET_SUCCESS
          }`,
          summaryDatasetSchema,
          meta,
        ),
        {
          type: `${
            storageName
              ? `${storageName}_FAILURE`
              : LOAD_SUMMARY_DATASET_FAILURE
          }`,
          meta,
        },
      ],
      method: "GET",
      endpoint: apiCall,
    },
  };
}

/**
 * @deprecated Use loadSummaryDataseat from dataset.js
 * Temporary action until the old resource tree is removed
 */
export const oldLoadSummaryDataset =
  (
    fullPath: string | Immutable.List<string> | undefined,
    viewId: string,
    storageName: string,
    nodeExpanded: boolean,
    currNode: object,
    versionContext?: {
      type: "BRANCH" | "TAG" | "DETACHED";
      value: string;
    },
  ) =>
  (
    dispatch: (action: ReturnType<typeof oldFetchSummaryDataset>) => unknown,
  ) => {
    let joinedPath = "";

    if (fullPath) {
      if (typeof fullPath !== "string") {
        const newPath = fullPath
          .toJS()
          .map((pathPart: string) => encodeURIComponent(pathPart));
        newPath[newPath.length - 1] = `"${newPath[newPath.length - 1]}"`;
        joinedPath = newPath.join("/");
      } else {
        joinedPath = fullPath;
      }
    }

    return dispatch(
      oldFetchSummaryDataset(
        joinedPath,
        viewId,
        storageName,
        nodeExpanded,
        currNode,
        versionContext,
      ),
    );
  };
