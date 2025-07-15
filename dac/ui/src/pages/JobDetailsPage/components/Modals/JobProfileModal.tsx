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
import { useEffect, useMemo, useRef, useState } from "react";
import { useIntl } from "react-intl";
import { useQuery } from "@tanstack/react-query";
import { IconButton } from "dremio-ui-lib";
import { supportKey } from "@inject/queries/flags";
import {
  DialogContent,
  ModalContainer,
  SectionMessage,
} from "dremio-ui-lib/components";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import Keys from "#oss/constants/Keys.json";
import { JOBS_PROFILE_ASYNC_UPDATE } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import type { JobDetails } from "#oss/exports/types/JobDetails.type";
import { JOB_STATUS } from "#oss/pages/ExplorePage/components/ExploreTable/constants";

import * as classes from "./JobProfileModal.module.less";

import clsx from "clsx";

type JobProfileModalProps = {
  isOpen: boolean;
  hide: () => void;
  profileUrl: string;
  jobDetails?: JobDetails;
};

export default function JobProfileModal({
  profileUrl,
  hide,
  isOpen,
  jobDetails,
}: JobProfileModalProps) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const urlRef = useRef(profileUrl);
  const [isOpenState, setIsOpenState] = useState(isOpen);
  const [hasTerminalProfileState, setHasTerminalProfileState] = useState(true);
  const { formatMessage } = useIntl();
  const registerEscape = () => {
    const iframe = iframeRef.current;
    if (!iframe || !iframe.contentWindow) return;
    // DX-5720: when focus is in Profile modal, `esc` doesn't close it
    try {
      iframe.contentWindow.addEventListener("keydown", (evt) => {
        if (evt.keyCode === Keys.ESCAPE) {
          // todo: keyCode deprecated, but no well-supported replacement yet
          hide();
        }
      });
    } catch (error) {
      // if the iframe content fails to load, suppress the cross-origin frame access error
    }
  };

  const readJobStateFromProfile = () => {
    if (!iframeRef.current || !iframeRef.current.contentWindow) return;

    const terminalStates = [
      JOB_STATUS.completed,
      JOB_STATUS.failed,
      JOB_STATUS.canceled,
    ];

    try {
      const globalConfig =
        iframeRef.current.contentWindow.document.getElementById(
          "globalconfig",
        )?.textContent;

      if (!globalConfig) return;

      const isJobComplete = terminalStates.includes(
        JSON.parse(globalConfig).state,
      );

      setHasTerminalProfileState(isJobComplete);
    } catch {
      // if the iframe content fails to load, suppress the cross-origin frame access error
    }
  };

  const handleLoad = () => {
    registerEscape();
    readJobStateFromProfile();
  };

  const asyncProfileEnabled = useQuery(
    supportKey(getSonarContext().getSelectedProjectId?.())(
      JOBS_PROFILE_ASYNC_UPDATE,
      false,
    ),
  ).data?.value as boolean | undefined;

  // isProfileUpdateComplete will be false for jobs that were run before the feature was introduced.
  // In order for this to be backwards compatible, the UI needs to read the job state from inside the profile.
  // If isProfileUpdateComplete is false, but the profile says the job is complete, this is a job from
  // an older version of Dremio and the warning should not appear. Otherwise, if isProfileUpdateComplete is false
  // and the status inside the profile is not a terminal state, show the notification since the profile is updating.
  const shouldShowWarning = useMemo(
    () =>
      asyncProfileEnabled &&
      jobDetails?.isComplete &&
      !jobDetails.isProfileUpdateComplete &&
      !hasTerminalProfileState,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [asyncProfileEnabled, hasTerminalProfileState],
  );

  useEffect(() => {
    setIsOpenState(isOpen);
  }, [isOpen]);

  return (
    <ModalContainer isOpen={isOpenState} close={hide} open={() => {}}>
      <DialogContent
        className={clsx(
          "dremio-dialog-content--no-overflow",
          classes["job-profile-content"],
        )}
        title={formatMessage({ id: "TopPanel.Profile" })}
        toolbar={
          <IconButton aria-label="Close" onClick={hide}>
            <dremio-icon name="interface/close-small" />
          </IconButton>
        }
        error={
          shouldShowWarning ? (
            <SectionMessage appearance="warning">
              {formatMessage({ id: "Job.Profile.Incomplete" })}
            </SectionMessage>
          ) : undefined
        }
      >
        <iframe
          id="profile_frame"
          src={urlRef.current}
          style={{ height: "100%", width: "100%", border: "none" }}
          ref={iframeRef}
          onLoad={handleLoad}
        />
      </DialogContent>
    </ModalContainer>
  );
}
