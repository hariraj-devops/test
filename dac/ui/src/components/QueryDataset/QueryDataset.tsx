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

import { IconButton, TooltipPlacement, Button } from "dremio-ui-lib/components";
import { Link } from "react-router";
import { useIntl } from "react-intl";
// @ts-ignore
import * as sqlPaths from "dremio-ui-common/paths/sqlEditor.js";
// @ts-ignore
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { expandExploreSql } from "#oss/actions/explore/ui";
import Immutable from "immutable";
import { store } from "#oss/store/store";

type VersionCtx = { type: string; value: string };

type QueryDatasetProps = {
  fullPath: Immutable.Map<any, any>;
  resourceId: string;
  shouldExpandEditor?: boolean;
  className?: string;
  tooltipPlacement?: TooltipPlacement;
  tooltipPortal?: boolean;
  isButton?: boolean;
  buttonClassName?: string;
  versionContext?: VersionCtx;
};

// Moving this to a hook for now, may want to separate the redux action out
export const useQueryDataset = ({
  fullPath,
  shouldExpandEditor,
  resourceId,
  versionContext,
}: Pick<QueryDatasetProps, "shouldExpandEditor" | "resourceId"> & {
  fullPath: string[] | undefined;
  versionContext?: VersionCtx;
}) => {
  const projectId = getSonarContext()?.getSelectedProjectId?.();
  const newQueryLink = sqlPaths.sqlEditor.link({ projectId });
  const stringifiedFullPath = JSON.stringify(fullPath);
  const versionedDataset = versionContext
    ? `&versionContext=${JSON.stringify(versionContext)}`
    : "";

  const newQueryUrlParams = `?context="${encodeURIComponent(
    resourceId,
  )}"&queryPath=${encodeURIComponent(stringifiedFullPath)}${versionedDataset}`;

  const handleClick = (e?: any) => {
    if (e) e.stopPropagation();
    if (shouldExpandEditor) {
      store.dispatch(expandExploreSql());
    }
  };

  const newLocation = {
    pathname: newQueryLink,
    search: newQueryUrlParams,
  };

  return { newLocation, handleClick };
};

// Icon
const QueryDataset = ({
  fullPath,
  className,
  shouldExpandEditor,
  resourceId,
  tooltipPlacement,
  tooltipPortal,
  isButton = false,
  buttonClassName,
  versionContext,
}: QueryDatasetProps) => {
  const { formatMessage } = useIntl();

  const { newLocation, handleClick } = useQueryDataset({
    fullPath: fullPath?.toJS?.(),
    shouldExpandEditor,
    resourceId,
    versionContext,
  });

  return isButton ? (
    <Button
      tooltip={formatMessage({ id: "Query.Dataset" })}
      variant="secondary"
      as={Link}
      onClick={handleClick}
      to={newLocation}
      className={buttonClassName}
    >
      <dremio-icon class={className} name="navigation-bar/sql-runner" />
      {formatMessage({ id: "Common.DoQuery" })}
    </Button>
  ) : (
    <IconButton
      as={Link}
      onClick={handleClick}
      to={newLocation}
      tooltip={formatMessage({ id: "Query.Dataset" })}
      tooltipPlacement={tooltipPlacement}
      tooltipPortal={tooltipPortal}
    >
      <dremio-icon class={className} name="navigation-bar/sql-runner" />
    </IconButton>
  );
};

export default QueryDataset;
