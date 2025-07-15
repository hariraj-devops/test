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
import { Suspense } from "react";
import PropTypes from "prop-types";
import { injectIntl } from "react-intl";
import Immutable from "immutable";
import timeUtils from "utils/timeUtils";
import { getDuration } from "utils/jobListUtils";
import { ScansForFilter } from "#oss/constants/Constants";
import jobsUtils from "#oss/utils/jobsUtils";
import FileUtils from "#oss/utils/FileUtils";
import { getQueueInfo } from "@inject/pages/JobDetailsPage/utils";

import JobDetailsErrorInfo from "../OverView/JobDetailsErrorInfo";
import { getFormatMessageIdForQueryType } from "../../utils";
import JobSummary from "../Summary/Summary";
import TotalExecutionTime from "../TotalExecutionTime/TotalExecutionTime";
import HelpSection from "../HelpSection/HelpSection";
import SQL from "../SQL/SQL";
import ReflectionsCreated from "../Reflections/ReflectionsCreated";
import QueriedDataset from "../QueriedDataset/QueriedDataset";
import Scans from "../Scans/Scans";
import Acceleration from "../Reflections/Acceleration";
import { Tooltip } from "dremio-ui-lib";
import { SectionMessage, Spinner } from "dremio-ui-lib/components";
import { SupportKeyValue } from "#oss/exports/providers/useSupportKeyValue";
import { JOBS_PROFILE_ASYNC_UPDATE } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import "./OverView.less";

const VIEW_ID = "JOB_DETAILS_VIEW_ID";

const renderErrorLog = (failureInfo) => {
  return (
    failureInfo &&
    failureInfo.size > 0 && <JobDetailsErrorInfo failureInfo={failureInfo} />
  );
};

const renderCancellationLog = (cancellationInfo) => {
  return (
    cancellationInfo && <JobDetailsErrorInfo failureInfo={cancellationInfo} />
  );
};

const OverView = (props) => {
  const {
    intl: { formatMessage },
    jobDetails,
    downloadJobFile,
    location,
  } = props;

  const attemptDetails = jobDetails.get("attemptDetails") || Immutable.List();
  const haveMultipleAttempts = attemptDetails.size > 1;
  const durationLabelId = haveMultipleAttempts
    ? "Job.TotalDuration"
    : "Job.Duration";
  const jobDuration = jobDetails.get("duration");
  const reflectionId = location?.hash?.replace("#", "");

  const renderLastAttemptDuration = () => {
    const lastAttempt = attemptDetails && attemptDetails.last();
    const totalTimeMs = lastAttempt && lastAttempt.get("totalTime");
    return jobsUtils.formatJobDuration(totalTimeMs);
  };

  const downloadJobProfile = (viewId, jobId) => {
    const path = `${jobId}${reflectionId ? `/reflection/${reflectionId}` : ""}`;
    downloadJobFile({
      url: `/support/${path}/download`,
      method: "POST",
      viewId,
    });
  };

  const jobSummaryData = [
    { label: "Job.Status", content: jobDetails.get("jobStatus") },
    {
      label: "Job.TotalMemory",
      content: FileUtils.getFormattedBytes(jobDetails.get("totalMemory")),
    },
    {
      label: "Job.CpuUsed",
      content: jobsUtils.formatJobDurationWithMS(jobDetails.get("cpuUsed")),
    },
    {
      label: "Job.QueryType",
      content: formatMessage({
        id: getFormatMessageIdForQueryType(jobDetails),
      }),
    },
    {
      label: "Job.StartTime",
      content: timeUtils.formatTime(jobDetails.get("startTime")),
    },
    ...(haveMultipleAttempts
      ? [
          {
            label: "Job.LastAttemptDuration",
            content: renderLastAttemptDuration(),
          },
        ]
      : []),
    {
      label: `${durationLabelId}`,
      content: `${jobsUtils.formatJobDurationWithMS(jobDuration)}`,
    },
    {
      label: "Job.Summary.WaitOnClient",
      content:
        jobDetails.get("waitInClient") !== undefined
          ? `${jobsUtils.formatJobDuration(jobDetails.get("waitInClient"))}`
          : "",
    },
    { label: "Common.User", content: jobDetails.get("queryUser") },
    getQueueInfo(jobDetails),
    {
      label: "Job.Summary.Input",
      content: `${FileUtils.getFormattedBytes(
        jobDetails.get("inputBytes"),
      )} / ${jobsUtils.getFormattedNumber(
        jobDetails.get("inputRecords"),
      )} Rows`,
    },
    {
      label: "Job.Summary.Output",
      content: `${FileUtils.getFormattedBytes(
        jobDetails.get("outputBytes"),
      )} / ${jobsUtils.getFormattedNumber(
        jobDetails.get("outputRecords"),
      )} Rows`,
      secondaryContent: (
        <>
          {jobDetails.get("isOutputLimited") && (
            <div className="summary__content">
              <div className="summary__contentHeader"></div>
              <div className="summary__contentValue outputLimited">
                {formatMessage({ id: "Job.Summary.OutputTruncation" })}
                <Tooltip
                  title={formatMessage(
                    { id: "Explore.Run.NewWarning" },
                    {
                      rows: jobDetails.get("outputRecords").toLocaleString(),
                    },
                  )}
                >
                  <dremio-icon
                    name="interface/information"
                    style={{
                      height: 16,
                      width: 16,
                      marginLeft: 4,
                      color: "var(--icon--primary)",
                    }}
                  ></dremio-icon>
                </Tooltip>
              </div>
            </div>
          )}
          {jobDetails.get("isProfileIncomplete") && (
            <SectionMessage appearance="warning" className="mt-1">
              {formatMessage({ id: "Job.Summary.OutputIncomplete" })}
            </SectionMessage>
          )}
        </>
      ),
    },
  ];

  const durationDetails = jobDetails.get("durationDetails");
  const jobId = jobDetails.get("id");
  const failureInfo = jobDetails.get("failureInfo");
  const cancellationInfo = jobDetails.get("cancellationInfo");
  const queryType = jobDetails.get("queryType");
  return (
    <div className="overview">
      <div className="overview__leftSidePanel">
        <JobSummary jobSummary={jobSummaryData} />
        <TotalExecutionTime
          pending={getDuration(durationDetails, "PENDING")}
          metadataRetrival={getDuration(durationDetails, "METADATA_RETRIEVAL")}
          planning={getDuration(durationDetails, "PLANNING")}
          engineStart={getDuration(durationDetails, "ENGINE_START")}
          queued={getDuration(durationDetails, "QUEUED")}
          executionPlanning={getDuration(durationDetails, "EXECUTION_PLANNING")}
          starting={getDuration(durationDetails, "STARTING")}
          running={getDuration(durationDetails, "RUNNING")}
          total={jobDuration}
        />
        <Suspense
          fallback={
            <div className="flex justify-center">
              <Spinner />
            </div>
          }
        >
          <SupportKeyValue
            supportKey={JOBS_PROFILE_ASYNC_UPDATE}
            defaultValue={false}
          >
            {(enabled) => (
              <HelpSection
                jobId={jobId}
                downloadFile={() => downloadJobProfile(VIEW_ID, jobId)}
                className="helpSectionQvlogo"
                jobDetails={jobDetails}
                asyncUpdatesEnabled={enabled}
              />
            )}
          </SupportKeyValue>
        </Suspense>
      </div>
      <div className="overview__righSidePanel">
        <div>
          {renderErrorLog(failureInfo)}
          {renderCancellationLog(cancellationInfo)}
        </div>
        <SQL
          sqlString={jobDetails.get("queryText")}
          title={formatMessage({ id: "SubmittedSQL" })}
          sqlClass="overview__sqlBody"
        />
        {queryType !== "ACCELERATOR_DROP" && (
          <>
            {jobDetails.get("reflections") && (
              <ReflectionsCreated
                reflections={jobDetails.get("reflections")}
                location={location}
                jobStartTime={jobDetails.get("startTime")}
              />
            )}
            {jobDetails.get("queriedDatasets") && (
              <QueriedDataset
                queriedDataSet={jobDetails.get("queriedDatasets")}
              />
            )}
            {jobDetails.get("scannedDatasets") && (
              <Scans
                resultsCacheUsed={jobDetails.get("resultsCacheUsed")}
                scansForFilter={ScansForFilter}
                scans={jobDetails.get("scannedDatasets")}
              />
            )}
            {(!!jobDetails.get("reflectionsUsed") ||
              !!jobDetails.get("reflectionsMatched") ||
              !!jobDetails.get("resultsCacheUsed")) && (
              <Acceleration
                reflectionsUsed={jobDetails.get("reflectionsUsed")}
                reflectionsNotUsed={jobDetails.get("reflectionsMatched")}
                isAcceleration={jobDetails.get("accelerated")}
                isAcceleratedByCache={!!jobDetails.get("resultsCacheUsed")}
                location={location}
                jobStartTime={jobDetails.get("startTime")}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
};

OverView.propTypes = {
  intl: PropTypes.object.isRequired,
  scansForFilter: PropTypes.array,
  jobSummary: PropTypes.array,
  reflectionsUsed: PropTypes.array,
  reflectionsNotUsed: PropTypes.array,
  queriedDataSet: PropTypes.array,
  duration: PropTypes.object,
  jobDetails: PropTypes.object,
  downloadJobProfile: PropTypes.func,
  downloadJobFile: PropTypes.func,
  location: PropTypes.object,
};
export default injectIntl(OverView);
