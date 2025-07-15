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
import { useContext, useDeferredValue, useMemo, useRef, type FC } from "react";
import { useSelector } from "react-redux";
import {
  renderExpandedIcon,
  TreeItemLevelContextProvider,
  useFocusContext,
  useTreeItem,
} from "../../Tree";
import type {
  SourceCatalogObject,
  SourceCatalogReference,
} from "@dremio/dremio-js/oss";
import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import {
  catalogReferenceChildren,
  versionedCatalogReferenceChildren,
} from "@inject/queries/catalog";
import { catalogByPath } from "dyn-load/queries/catalog";
import { isVersionedSoftwareSource } from "dyn-load/constants/sourceTypes";
import { type VersionState } from "#oss/queries/catalog";
import { getIdListString } from "#oss/exports/utilities/getIdListString";
import { CatalogTreeChildren } from "../CatalogTreeChildren";
import { BranchPickerButton } from "./components/BranchPickerButton";
import { OpenDetailsButton } from "./components/OpenDetailsButton";
import { AddToQueryButton } from "./components/AddToQueryButton";
import { StarredButton } from "@inject/exports/components/CatalogTree/CatalogTreeItems/components/StarredButton";
import EntitySummaryOverlay from "#oss/components/EntitySummaryOverlay/EntitySummaryOverlay";
import { TreeConfigContext } from "#oss/components/Tree/treeConfigContext";
import { getFullPathFromCatalogReference } from "#oss/components/Tree/resourceTreeUtils";
import DragSource from "#oss/components/DragComponents/DragSource";
import { type NessieState } from "#oss/types/nessie";
import { CatalogObjectDisplay } from "../CatalogObjectDisplay";
import { FailedCatalogTreeItem } from "../FailedCatalogTreeItem";
import { PathPrefixContext } from "../prefixContext";

export const SourceCatalogTreeItem: FC<{
  catalogReference: SourceCatalogReference;
}> = (props) => {
  const catalogObject = useSuspenseQuery(
    catalogByPath()(props.catalogReference.path),
  ).data.unwrap() as SourceCatalogObject;

  return catalogObject.sourceChangeState !== "NONE" ? (
    <></>
  ) : catalogObject.status === "bad" ? (
    <FailedCatalogTreeItem catalogReference={props.catalogReference} />
  ) : isVersionedSoftwareSource(catalogObject.type) ? (
    <VersionedSourceItem catalogObject={catalogObject} />
  ) : (
    <StandardSourceItem catalogObject={catalogObject} />
  );
};

export const StandardSourceItem: FC<{
  catalogObject: SourceCatalogObject;
}> = (props) => {
  return (
    <SourceTreeItem
      catalogObject={props.catalogObject}
      query={catalogReferenceChildren(props.catalogObject.catalogReference)}
    />
  );
};

export const VersionedSourceItem: FC<{
  catalogObject: SourceCatalogObject;
}> = (props) => {
  const versionedState: NessieState | undefined = useSelector(
    (state: Record<string, any>) => state.nessie[props.catalogObject.name],
  );

  const version = useRef<VersionState>(
    versionedState?.reference
      ? {
          type: versionedState.hash ? "COMMIT" : versionedState.reference.type,
          name: versionedState.reference.name,
          hash: versionedState.hash ?? versionedState.reference.hash,
        }
      : {
          type: "BRANCH",
          name: "main",
        },
  );

  return (
    <SourceTreeItem
      catalogObject={props.catalogObject}
      query={versionedCatalogReferenceChildren(
        props.catalogObject.catalogReference,
        version.current,
      )}
      setVersion={(val: VersionState) => {
        version.current = val;
      }}
    />
  );
};

const SourceTreeItem: FC<{
  catalogObject: SourceCatalogObject;
  query: ReturnType<
    typeof catalogReferenceChildren | typeof versionedCatalogReferenceChildren
  >;
  setVersion?: (val: VersionState) => unknown;
}> = (props) => {
  const itemRef = useRef(null);
  const {
    childrenIds,
    childrenQuery,
    expandedDeferred,
    handleDatasetDetails,
    treeItemProps,
  } = useSourceCatalogTreeItem(
    props.catalogObject.catalogReference,
    props.query,
  );
  const focusedEl = useFocusContext();

  const hasFocus =
    focusedEl === itemRef.current ||
    (itemRef.current as unknown as HTMLElement)?.contains(
      focusedEl as HTMLElement,
    );

  return (
    <>
      <div
        aria-owns={childrenIds}
        aria-label={props.catalogObject.name}
        ref={itemRef}
        {...treeItemProps}
      >
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          {renderExpandedIcon(
            treeItemProps.isExpanded,
            childrenQuery.isFetching && treeItemProps.isExpanded,
          )}
          <DragSource
            dragType="explorePage"
            id={Immutable.fromJS(props.catalogObject.catalogReference.path)}
            className="overflow-hidden"
          >
            <CatalogObjectDisplay
              catalogObject={props.catalogObject}
              summaryOverlay={
                <EntitySummaryOverlay
                  name={props.catalogObject.name}
                  type={props.catalogObject.catalogReference.type}
                  fullPath={Immutable.fromJS(
                    props.catalogObject.catalogReference.path,
                  )}
                  openDetailsPanel={handleDatasetDetails}
                  entityUrl={getFullPathFromCatalogReference(
                    props.catalogObject.catalogReference,
                  )}
                />
              }
            />
          </DragSource>
        </div>
        {props.setVersion && (
          <BranchPickerButton
            catalogObject={props.catalogObject}
            setVersion={props.setVersion}
            tabIndex={hasFocus ? 0 : -1}
          />
        )}
        <div className="ml-auto catalog-treeitem__actions">
          <OpenDetailsButton
            catalogReference={props.catalogObject.catalogReference}
          />
          <AddToQueryButton
            catalogReference={props.catalogObject.catalogReference}
          />
          <StarredButton
            catalogReference={props.catalogObject.catalogReference}
          />
        </div>
      </div>
      {expandedDeferred && childrenQuery.data ? (
        <PathPrefixContext.Provider
          value={props.catalogObject.name.replace(/\s+/g, "")}
        >
          <TreeItemLevelContextProvider>
            <CatalogTreeChildren catalogReferences={childrenQuery.data} />
          </TreeItemLevelContextProvider>
        </PathPrefixContext.Provider>
      ) : null}
    </>
  );
};

const useSourceCatalogTreeItem = (
  catalogReference: SourceCatalogReference,
  query:
    | ReturnType<typeof catalogReferenceChildren>
    | ReturnType<typeof versionedCatalogReferenceChildren>,
) => {
  const treeItemProps = useTreeItem(catalogReference.id);

  // @ts-expect-error useQuery expects a queryKey of type string[] but can use
  // anything as long as it's serializable
  const childrenQuery = useQuery({
    ...query,
    enabled: treeItemProps.isExpanded,
  });

  return {
    childrenIds: useMemo(() => {
      if (!childrenQuery.data) {
        return undefined;
      }

      return getIdListString(
        childrenQuery.data,
        catalogReference.name.replace(/\s+/g, ""),
      );
    }, [childrenQuery.data, catalogReference.name]),
    childrenQuery,
    expandedDeferred: useDeferredValue(treeItemProps.isExpanded),
    handleDatasetDetails: useContext(TreeConfigContext).handleDatasetDetails,
    treeItemProps,
  } as const;
};
