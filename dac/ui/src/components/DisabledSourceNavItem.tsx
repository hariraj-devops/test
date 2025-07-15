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
import { useEffect } from "react";
import { useIntl } from "react-intl";
import { useDispatch } from "react-redux";
import { Tooltip } from "dremio-ui-lib";
import { Spinner } from "dremio-ui-lib/components";
import { loadSourceListData } from "#oss/actions/resources/sources";
import { getSource } from "#oss/exports/endpoints/Sources/getSource";
import { ENTITY_TYPES } from "#oss/constants/Constants";

const SOURCE_CHANGE_STATE_NONE = "SOURCE_CHANGE_STATE_NONE";
const SOURCE_CHANGE_STATE_CREATING = "SOURCE_CHANGE_STATE_CREATING";
const SOURCE_CHANGE_STATE_UPDATING = "SOURCE_CHANGE_STATE_UPDATING";
const SOURCE_CHANGE_STATE_DELETING = "SOURCE_CHANGE_STATE_DELETING";

export const isSourceCreating = (sourceChangeState: string) =>
  sourceChangeState === SOURCE_CHANGE_STATE_CREATING;
const isSourceUpdating = (sourceChangeState: string) =>
  sourceChangeState === SOURCE_CHANGE_STATE_UPDATING;
const isSourceDeleting = (sourceChangeState: string) =>
  sourceChangeState === SOURCE_CHANGE_STATE_DELETING;

export const DisabledSourceNavItem = ({
  name,
  sourceChangeState,
}: {
  name: string;
  sourceChangeState: string;
}) => {
  const { formatMessage } = useIntl();
  const dispatch = useDispatch();
  const sourceUpdating = isSourceUpdating(sourceChangeState);
  const sourceDeleting = isSourceDeleting(sourceChangeState);
  const disabledSourceTooltip = sourceDeleting
    ? formatMessage({ id: "Sources.Source.Tooltip.isDeleting" })
    : sourceUpdating
      ? formatMessage({ id: "Sources.Source.Tooltip.isUpdating" })
      : formatMessage({ id: "Sources.Source.Tooltip.isCreating" });

  useEffect(() => {
    const timeout = setInterval(() => {
      getSource(name)
        .then((res) => {
          if (res.sourceChangeState === SOURCE_CHANGE_STATE_NONE) {
            //@ts-ignore
            dispatch(loadSourceListData());
            clearInterval(timeout);
            return;
          }
          return;
        })
        .catch(() => {
          //@ts-ignore
          dispatch(loadSourceListData());
          clearInterval(timeout);
        });
    }, 5000);
    return () => {
      clearInterval(timeout);
    };
  }, [dispatch, name]);

  return (
    <li className="px-3 flex items-center h-4">
      <Tooltip title={disabledSourceTooltip} placement="top">
        <Spinner className="dremio-icon-label min-w-min overflow-hidden" />
      </Tooltip>
      <div className="text-disabled ml-05 not-allowed truncate">{name}</div>
    </li>
  );
};

export const isSourceDisabled = (
  sourceChangeState: string,
  entityType: string,
): boolean => {
  return (
    entityType === ENTITY_TYPES.source &&
    (isSourceCreating(sourceChangeState) ||
      isSourceUpdating(sourceChangeState) ||
      isSourceDeleting(sourceChangeState))
  );
};
