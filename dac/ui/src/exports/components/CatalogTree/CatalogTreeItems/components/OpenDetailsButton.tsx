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

import { getFullPathFromCatalogReference } from "#oss/components/Tree/resourceTreeUtils";
import {
  TreeConfigContext,
  TreeItemInfo,
  TreeLeafInfo,
} from "#oss/components/Tree/treeConfigContext";
import { CatalogReference } from "@dremio/dremio-js/oss";
import { IconButton } from "dremio-ui-lib/components";
import { FC, useContext, useMemo } from "react";

export const OpenDetailsButton: FC<{ catalogReference: CatalogReference }> = (
  props,
) => {
  const { handleDatasetDetails } = useContext(TreeConfigContext);
  const nodeInfo: TreeItemInfo = useMemo(
    () => ({
      getEntityUrl: () =>
        getFullPathFromCatalogReference(props.catalogReference),
      fromTreeNode: true as const,
      entityId: props.catalogReference.id,
    }),
    [props.catalogReference],
  );

  return (
    <IconButton
      tooltip="Open details"
      tooltipPortal
      onClick={(e: React.MouseEvent<HTMLElement>) => {
        e.stopPropagation();
        handleDatasetDetails(nodeInfo, e);
      }}
    >
      <dremio-icon name="interface/meta" />
    </IconButton>
  );
};

export const LeafOpenDetailsButton: FC<{
  catalogReference: CatalogReference;
}> = (props) => {
  const { handleDatasetDetails } = useContext(TreeConfigContext);
  const nodeInfo: TreeLeafInfo = {
    entityId: props.catalogReference.id,
    fromTreeNode: true,
    fullPath: props.catalogReference.path,
    id: props.catalogReference.id,
    type: props.catalogReference.type,
    versionContext: props.catalogReference.id,
  };
  return (
    <IconButton
      tooltip="Open details"
      tooltipPortal
      onClick={(e: React.MouseEvent<HTMLElement>) => {
        e.stopPropagation();
        handleDatasetDetails(nodeInfo, e);
      }}
    >
      <dremio-icon name="interface/meta" />
    </IconButton>
  );
};
