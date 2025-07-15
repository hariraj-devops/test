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
import { isLakehouseSourceType } from "@inject/constants/sourceTypes";
import { useLeftTreeExpandedStates } from "../../LeftTreeUtils";
import { toggleLakehouseSourcesExpanded } from "../../../../../../actions/ui/ui";
import DataPlaneSection from "../../../DataPlaneSection/DataPlaneSection";
export const LakehouseSection = ({
  sources,
  sourcesViewState,
}: {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<string, any>;
}) => {
  const dispatch = useDispatch();
  const { lakehouseSourcesExpanded } = useLeftTreeExpandedStates();
  const lakehouseSources = sources.filter((source) =>
    isLakehouseSourceType(source?.get("type")),
  );
  const showLakehouseSources = lakehouseSources.size > 0;
  if (!showLakehouseSources) return null;
  return (
    <DataPlaneSection
      isCollapsed={lakehouseSourcesExpanded}
      isCollapsible
      onToggle={() => dispatch(toggleLakehouseSourcesExpanded())}
      dataPlaneSources={lakehouseSources}
      sourcesViewState={sourcesViewState}
    />
  );
};
