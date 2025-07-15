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
import { useDispatch } from "react-redux";
import { browserHistory } from "react-router";
import { useIntl } from "react-intl";
import * as commonPaths from "dremio-ui-common/paths/common.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import ViewStateWrapper from "../../../../../../components/ViewStateWrapper";
import { useLeftTreeExpandedStates } from "../../LeftTreeUtils";
import { toggleExternalSourcesExpanded } from "../../../../../../actions/ui/ui";
import FinderNav from "../../../../../../components/FinderNav";
import {
  isDatabaseType,
  isDataPlaneSourceType,
} from "@inject/constants/sourceTypes";

export const DatabaseSection = ({
  sources,
  sourcesViewState,
}: {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<string, any>;
}) => {
  const dispatch = useDispatch();
  const { formatMessage } = useIntl();
  const location = browserHistory.getCurrentLocation();
  const projectId = getSonarContext()?.getSelectedProjectId?.();
  const databases = sources.filter(
    (source) =>
      isDatabaseType(source?.get("type")) &&
      !isDataPlaneSourceType(source?.get("type")),
  );
  const { externalSourcesExpanded } = useLeftTreeExpandedStates();
  const showDatabases = databases.size > 0;
  if (!showDatabases) return null;
  return (
    <div>
      <ViewStateWrapper viewState={sourcesViewState}>
        <FinderNav
          isCollapsed={externalSourcesExpanded}
          isCollapsible
          onToggle={() => dispatch(toggleExternalSourcesExpanded())}
          location={location}
          navItems={databases}
          title={formatMessage({ id: "Source.DatabaseSources" })}
          addTooltip={formatMessage({
            id: "Source.AddDatabaseSource",
          })}
          isInProgress={sourcesViewState.get("isInProgress")}
          listHref={commonPaths.external.link({ projectId })}
        />
      </ViewStateWrapper>
    </div>
  );
};
