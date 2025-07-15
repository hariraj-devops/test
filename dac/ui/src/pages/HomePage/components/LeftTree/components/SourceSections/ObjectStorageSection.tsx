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
import { useIntl } from "react-intl";
import * as commonPaths from "dremio-ui-common/paths/common.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { isObjectStorageSourceType } from "@inject/constants/sourceTypes";
import { spacesSourcesListSpinnerStyleFinderNav } from "../../../../../../pages/HomePage/HomePageConstants";
import { useLeftTreeExpandedStates } from "../../LeftTreeUtils";
import { toggleObjectStorageSourcesExpanded } from "../../../../../../actions/ui/ui";
import ViewStateWrapper from "../../../../../../components/ViewStateWrapper";
import FinderNav from "../../../../../../components/FinderNav";

export const ObjectStorageSection = ({
  sources,
  sourcesViewState,
}: {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<string, any>;
}) => {
  const dispatch = useDispatch();
  const { formatMessage } = useIntl();
  const projectId = getSonarContext()?.getSelectedProjectId?.();
  const objectStorageSources = sources.filter((source) =>
    isObjectStorageSourceType(source?.get("type")),
  );
  const { objectStorageSourcesExpanded } = useLeftTreeExpandedStates();
  const showObjectStorageSources = objectStorageSources.size > 0;
  if (!showObjectStorageSources) return null;
  return (
    <div>
      <ViewStateWrapper
        viewState={sourcesViewState}
        spinnerStyle={spacesSourcesListSpinnerStyleFinderNav}
      >
        <FinderNav
          isCollapsed={objectStorageSourcesExpanded}
          isCollapsible
          onToggle={() => dispatch(toggleObjectStorageSourcesExpanded())}
          navItems={objectStorageSources}
          title={formatMessage({ id: "Source.Object.Storage" })}
          addTooltip={formatMessage({
            id: "Source.Add.Object.Storage",
          })}
          isInProgress={sourcesViewState.get("isInProgress")}
          listHref={commonPaths.objectStorage.link({ projectId })}
        />
      </ViewStateWrapper>
    </div>
  );
};
