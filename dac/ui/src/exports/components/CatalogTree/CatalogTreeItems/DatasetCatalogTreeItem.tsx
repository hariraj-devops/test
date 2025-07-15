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
import { FC, useContext, useMemo } from "react";
import {
  renderExpandedIcon,
  TreeItemLevelContextProvider,
  useTreeItem,
} from "../../Tree";
import type {
  DatasetCatalogObject,
  DatasetCatalogReference,
  VersionedDatasetCatalogObject,
} from "@dremio/dremio-js/oss";
import { useSuspenseQuery } from "@tanstack/react-query";
import { catalogByPath } from "@inject/queries/catalog";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { CatalogColumnDisplay } from "dremio-ui-common/catalog/CatalogColumnDisplay.js";
import { TreeConfigContext } from "#oss/components/Tree/treeConfigContext";
import { IconButton, Tooltip } from "dremio-ui-lib/components";
import { AddToQueryButton } from "./components/AddToQueryButton";
import { StarredButton } from "@inject/exports/components/CatalogTree/CatalogTreeItems/components/StarredButton";
import { LeafOpenDetailsButton } from "./components/OpenDetailsButton";
import DatasetSummaryOverlay from "#oss/components/Dataset/DatasetSummaryOverlay";
import { getVersionContextFromId } from "dremio-ui-common/utilities/datasetReference.js";
import DragSource from "#oss/components/DragComponents/DragSource";
import { CatalogObjectDisplay } from "../CatalogObjectDisplay";
import { PathPrefixContext } from "../prefixContext";

type Field = DatasetCatalogObject["fields"][number];

const { t } = getIntlContext();

const DatasetColumnCatalogTreeItem: FC<{ id: string; field: Field }> = (
  props,
) => {
  const treeItemProps = useTreeItem(props.id);
  const { addToEditor } = useContext(TreeConfigContext);

  // width of the div around the icons must always be large enough to include the "+" button
  const containerDivWidth =
    32 + (props.field.isSorted ? 32 : 0) + (props.field.isPartitioned ? 32 : 0);

  return (
    <div {...treeItemProps}>
      <DragSource
        dragType="explorePage"
        id={props.field.name}
        className="ml-105 overflow-hidden"
      >
        <CatalogColumnDisplay tableColumn={props.field} />
      </DragSource>
      <div className="flex" style={{ minWidth: containerDivWidth }}>
        {props.field.isSorted && (
          <Tooltip content={t("Catalog.Tree.Column.Sorted")} portal>
            <span className="p-05">
              <dremio-icon name="interface/sort" class="h-3 w-3 icon-primary" />
            </span>
          </Tooltip>
        )}
        {props.field.isPartitioned && (
          <Tooltip content={t("Catalog.Tree.Column.Partitioned")} portal>
            <span className="p-05">
              <dremio-icon
                name="sql-editor/partition"
                class="h-3 w-3 icon-primary"
              />
            </span>
          </Tooltip>
        )}
        <div className="ml-auto catalog-treeitem__actions">
          <IconButton
            tooltip="Add to query"
            tooltipPortal
            onClick={(e: React.MouseEvent<HTMLElement>) => {
              e.stopPropagation();
              addToEditor?.(props.field.name);
            }}
          >
            <dremio-icon name="interface/add-small" />
          </IconButton>
        </div>
      </div>
    </div>
  );
};

export const DatasetCatalogTreeItem: FC<{
  catalogReference: DatasetCatalogReference;
}> = (props) => {
  const catalogObject = useSuspenseQuery(
    catalogByPath(getSonarContext().getSelectedProjectId?.())(
      props.catalogReference.path,
    ),
  ).data.unwrap() as DatasetCatalogObject | VersionedDatasetCatalogObject;

  const pathPrefix = useContext(PathPrefixContext);

  const treeItemProps = useTreeItem(
    (pathPrefix ? `${pathPrefix}-` : "") + props.catalogReference.id,
  );

  const childrenIds = useMemo(() => {
    if (!catalogObject.fields) {
      return undefined;
    }

    return catalogObject.fields
      .reduce((accum, curr) => accum + " " + `${curr.name}`, "")
      .trimStart();
  }, [catalogObject.fields]);

  const { handleDatasetDetails } = useContext(TreeConfigContext);
  const fullPath: Immutable.List<string> = useMemo(
    () => Immutable.fromJS(catalogObject.catalogReference.path),
    [catalogObject.catalogReference.path],
  );

  return (
    <>
      <div
        aria-owns={childrenIds}
        {...treeItemProps}
        aria-label={catalogObject.name}
      >
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          {renderExpandedIcon(treeItemProps.isExpanded)}
          <DragSource
            dragType="explorePage"
            id={Immutable.fromJS(props.catalogReference.path)}
            className="overflow-hidden"
          >
            <CatalogObjectDisplay
              catalogObject={catalogObject}
              summaryOverlay={
                <DatasetSummaryOverlay
                  inheritedTitle={props.catalogReference.name}
                  fullPath={fullPath}
                  openWikiDrawer={handleDatasetDetails}
                  hideSqlEditorIcon
                  versionContext={getVersionContextFromId(
                    props.catalogReference.id,
                  )}
                />
              }
            />
          </DragSource>
        </div>
        {catalogObject.schemaOutdated && (
          <Tooltip
            content={t("Sonar.Dataset.DataGraph.OutdatedWarning")}
            style={{ width: 300 }}
            portal
          >
            <dremio-icon
              name="interface/warning"
              class="h-205 w-205 icon-warning"
            />
          </Tooltip>
        )}
        <div className="ml-auto catalog-treeitem__actions">
          <LeafOpenDetailsButton catalogReference={props.catalogReference} />
          <AddToQueryButton catalogReference={props.catalogReference} />
          <StarredButton catalogReference={props.catalogReference} />
        </div>
      </div>
      {treeItemProps.isExpanded ? (
        <TreeItemLevelContextProvider>
          {catalogObject.fields.map((field, i) => (
            <DatasetColumnCatalogTreeItem
              key={i}
              id={field.name}
              field={field}
            />
          ))}
        </TreeItemLevelContextProvider>
      ) : null}
    </>
  );
};
