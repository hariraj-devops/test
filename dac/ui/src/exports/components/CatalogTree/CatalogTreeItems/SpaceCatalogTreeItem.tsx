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
import { FC, useContext, useDeferredValue, useMemo } from "react";
import type {
  SpaceCatalogObject,
  SpaceCatalogReference,
} from "@dremio/dremio-js/oss";
import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import EntitySummaryOverlay from "#oss/components/EntitySummaryOverlay/EntitySummaryOverlay";
import { getFullPathFromCatalogReference } from "#oss/components/Tree/resourceTreeUtils";
import { TreeConfigContext } from "#oss/components/Tree/treeConfigContext";
import DragSource from "#oss/components/DragComponents/DragSource";
import {
  renderExpandedIcon,
  TreeItemLevelContextProvider,
  useTreeItem,
} from "#oss/exports/components/Tree";
import { getIdListString } from "#oss/exports/utilities/getIdListString";
import { catalogByPath, catalogReferenceChildren } from "#oss/queries/catalog";
import { CatalogObjectDisplay } from "../CatalogObjectDisplay";
import { CatalogTreeChildren } from "../CatalogTreeChildren";
import { PathPrefixContext } from "../prefixContext";
import { OpenDetailsButton } from "./components/OpenDetailsButton";
import { AddToQueryButton } from "./components/AddToQueryButton";
import { StarredButton } from "./components/StarredButton";

export const SpaceCatalogTreeItem: FC<{
  catalogReference: SpaceCatalogReference;
}> = (props) => {
  const catalogObject = useSuspenseQuery(
    catalogByPath()(props.catalogReference.path),
  ).data.unwrap() as SpaceCatalogObject;
  const treeItemProps = useTreeItem(props.catalogReference.id);

  const childrenQuery = useQuery({
    ...catalogReferenceChildren(props.catalogReference),
    enabled: treeItemProps.isExpanded,
  });

  const childrenIds = useMemo(() => {
    if (!childrenQuery.data) {
      return undefined;
    }

    return getIdListString(
      childrenQuery.data,
      props.catalogReference.name.replace(/\s+/g, ""),
    );
  }, [childrenQuery.data, props.catalogReference.name]);

  const expandedDeferred = useDeferredValue(treeItemProps.isExpanded);

  const { handleDatasetDetails } = useContext(TreeConfigContext);

  return (
    <>
      <div
        aria-owns={childrenIds}
        aria-label={catalogObject.name}
        {...treeItemProps}
      >
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          {renderExpandedIcon(
            treeItemProps.isExpanded,
            childrenQuery.isFetching && treeItemProps.isExpanded,
          )}
          <DragSource
            dragType="explorePage"
            id={Immutable.fromJS(props.catalogReference.path)}
            className="overflow-hidden"
          >
            <CatalogObjectDisplay
              catalogObject={catalogObject}
              summaryOverlay={
                <EntitySummaryOverlay
                  entity={undefined}
                  name={catalogObject.name}
                  type={catalogObject.catalogReference.type}
                  fullPath={Immutable.fromJS(
                    catalogObject.catalogReference.path,
                  )}
                  openDetailsPanel={handleDatasetDetails}
                  entityUrl={getFullPathFromCatalogReference(
                    props.catalogReference,
                  )}
                />
              }
            />
          </DragSource>
        </div>
        <div className="ml-auto catalog-treeitem__actions">
          <OpenDetailsButton catalogReference={props.catalogReference} />
          <AddToQueryButton catalogReference={props.catalogReference} />
          <StarredButton catalogReference={props.catalogReference} />
        </div>
      </div>
      {expandedDeferred && childrenQuery.data ? (
        <PathPrefixContext.Provider
          value={props.catalogReference.name.replace(/\s+/g, "")}
        >
          <TreeItemLevelContextProvider>
            <CatalogTreeChildren catalogReferences={childrenQuery.data} />
          </TreeItemLevelContextProvider>
        </PathPrefixContext.Provider>
      ) : null}
    </>
  );
};
