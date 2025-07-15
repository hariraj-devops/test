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

import { showNavCrumbs } from "@inject/components/NavCrumbs/NavCrumbs";
import { memoOne } from "#oss/utils/memoUtils";
import { cloneDeep } from "lodash";
import { FormattedMessage } from "react-intl";
import { QueryStatusType } from "../components/SqlEditor/SqlQueryTabs/utils";
import { EXPLORE_PAGE_MIN_HEIGHT } from "../ExplorePage";
import { JOB_STATUS } from "../components/ExploreTable/constants";

type StatusObjectType = {
  renderIcon?: string;
  text?: any;
  buttonFunc?: () => void;
  buttonText?: string;
  buttonIcon?: string;
  buttonAlt?: string;
  ranJob?: boolean;
  error?: any;
};

export const assemblePendingOrRunningTabContent = (
  queryStatuses: QueryStatusType[],
  statusesArray: string[],
  newQueryStatuses: (statuses: { statuses: QueryStatusType[] }) => void,
  cancelJob: (jobId: string | undefined) => void,
  cancelPendingSql: (index: number) => void,
) => {
  const tabStatusArr = queryStatuses.map((query, index) => {
    const obj = {} as StatusObjectType;

    if (statusesArray[index] === JOB_STATUS.running) {
      obj.renderIcon = statusesArray[index];
      obj.text = <FormattedMessage id="NewQuery.Running" />;
      obj.buttonFunc = () => {
        const newStutuses = cloneDeep(queryStatuses);
        newStutuses[index].isCancelDisabled = true;
        newQueryStatuses({ statuses: newStutuses });
        cancelJob(query.jobId);
      };
      obj.buttonText = "Cancel Job";
      obj.buttonIcon = "sql-editor/stop";
      obj.ranJob = true;
    } else if (!query.cancelled && !query.jobId && !query.error) {
      obj.renderIcon = "RUNNING";
      obj.text = <FormattedMessage id="NewQuery.Waiting" />;
      obj.buttonFunc = () => cancelPendingSql(index);
      obj.buttonText = "Remove Query";
      obj.buttonIcon = "interface/delete";
    } else if (query.cancelled && !query.error) {
      obj.text = <FormattedMessage id="NewQuery.Removed" />;
    } else if (query.error) {
      obj.error = query.error;
    } else if (
      [
        JOB_STATUS.pending,
        JOB_STATUS.metadataRetrieval,
        JOB_STATUS.planning,
        JOB_STATUS.engineStart,
        JOB_STATUS.queued,
        JOB_STATUS.executionPlanning,
        JOB_STATUS.starting,
        JOB_STATUS.running,
      ].includes(statusesArray[index])
    ) {
      obj.renderIcon = "RUNNING";
      obj.text = <FormattedMessage id="NewQuery.Submitted" />;
    } else if (
      statusesArray[index] === JOB_STATUS.completed &&
      query.lazyLoad
    ) {
      obj.renderIcon = "RUNNING";
      obj.text = <FormattedMessage id="NewQuery.LoadingResults" />;
    } else {
      return;
    }

    return obj;
  });

  return tabStatusArr;
};

export const EXPLORE_HEADER_HEIGHT = 54;
const NAV_CRUMBS_HEIGHT = 40;

export const getExploreContentHeight = memoOne(
  (offsetHeight, windowHeight, headerHeight) => {
    let contentHeight =
      windowHeight <= EXPLORE_PAGE_MIN_HEIGHT
        ? EXPLORE_PAGE_MIN_HEIGHT
        : windowHeight;

    if (offsetHeight) {
      contentHeight = contentHeight - offsetHeight;
    }
    if (showNavCrumbs) {
      contentHeight = contentHeight - NAV_CRUMBS_HEIGHT;
    }
    return contentHeight - headerHeight;
  },
);
