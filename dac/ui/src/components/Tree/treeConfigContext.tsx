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

import { sortByName } from "#oss/components/Tree/resourceTreeUtils";
import { createContext, useContext, RefObject } from "react";
import Immutable from "immutable";

export type TreeItemInfo = {
  entityId: string;
  fromTreeNode: boolean;
  getEntityUrl: () => string;
};

export type TreeLeafInfo = {
  entityId: string;
  fromTreeNode: boolean;
  fullPath: string[];
  id: string;
  type: string;
  versionContext: string;
};

type TreeConfigContextType = {
  nessiePrefix: string;
  filterTree: (tree: any) => any;
  restrictSelection: boolean;
  resourceTreeControllerRef: RefObject<any> | null;
  handleDatasetDetails: (
    dataset: Immutable.Map<string, unknown> | TreeItemInfo | TreeLeafInfo,
    event?: any,
  ) => void;
  addToEditor?: (path: string | string[]) => void;
};

export const defaultConfigContext: TreeConfigContextType = {
  nessiePrefix: "",
  filterTree: (tree) => tree.sort(sortByName("asc")),
  restrictSelection: false,
  resourceTreeControllerRef: null,
  handleDatasetDetails: (dataset, event) => {},
};

export const TreeConfigContext =
  createContext<TreeConfigContextType>(defaultConfigContext);

export const withTreeConfigContext =
  <T,>(WrappedComponent: React.ComponentClass) =>
  (props: T) => {
    return (
      <WrappedComponent
        treeConfigContext={useContext(TreeConfigContext)}
        {...props}
      />
    );
  };
