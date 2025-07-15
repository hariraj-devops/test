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

import { useEffect, useRef } from "react";
import clsx from "clsx";
import Immutable from "immutable";

import { PureEntityIcon } from "../EntityIcon";
import { getIconType } from "#oss/components/DatasetSummary/datasetSummaryUtils";
import {
  getEntityTypeFromObject,
  isEntityWikiEditAllowed,
} from "dyn-load/utils/entity-utils";
import { IconButton } from "dremio-ui-lib/components";
import { Wiki } from "#oss/pages/ExplorePage/components/Wiki/Wiki";
import { ENTITY_TYPES } from "#oss/constants/Constants";
import { getVersionContextFromId } from "dremio-ui-common/utilities/datasetReference.js";
import { getExtraSummaryPanelIcon } from "dyn-load/utils/summary-utils";

import * as classes from "./CatalogDetailsPanel.module.less";

type CatalogDetailsPanelProps = {
  panelItem: Immutable.Map<any, any>;
  handleDatasetDetailsCollapse: () => void;
  handlePanelDetails: (dataset: any) => void;
  panelWidth?: number | string;
  focusOnOpen?: boolean;
};

// Temp util function to handle panel details to help keep behavior consistent
// Eventually wiki panel will be replaced with a new component that uses react-query
export const handlePanelDetails = (
  dataset: any,
  datasetDetails: any,
  setDataset: (dataset: any) => void,
) => {
  if (dataset.get("error")) {
    setDataset(datasetDetails.merge(dataset));
  } else if (dataset?.get("entityId") !== datasetDetails?.get("entityId")) {
    setDataset(dataset);
  }
};

const CatalogDetailsPanel = ({
  panelItem,
  handleDatasetDetailsCollapse,
  handlePanelDetails,
  panelWidth = 328,
  focusOnOpen,
}: CatalogDetailsPanelProps) => {
  const panelIsSource = panelItem?.get("entityType") === ENTITY_TYPES.source;
  const panelName = panelItem?.get("name") || panelItem?.get("fullPath").last();
  const versionContext = getVersionContextFromId(
    panelItem?.get("entityId") || panelItem?.get("id"),
  );
  const closeRef = useRef<HTMLElement>();

  useEffect(() => {
    if (closeRef.current && focusOnOpen) {
      (closeRef.current.firstChild as HTMLButtonElement)?.focus();
    }
  }, [panelName, focusOnOpen]);

  return (
    <aside
      aria-label={`Dataset details panel: ${panelName}`}
      className={clsx(
        "flex flex-col full-height shrink-0",
        classes["catalog-details-panel"],
      )}
      style={{
        width: panelWidth,
      }}
    >
      <div className={classes["catalog-details-panel__header"]}>
        {panelIsSource ? (
          <PureEntityIcon
            disableHoverListener
            entityType={panelItem.get("entityType")}
            sourceStatus={panelItem.getIn(["state", "status"], null)}
            sourceType={panelItem?.get("type")}
            style={{ width: 24, height: 24 }}
          />
        ) : (
          <span className="mr-05">
            <dremio-icon
              name={`entities/${getIconType(
                getEntityTypeFromObject(panelItem),
                !!versionContext,
              )}`}
              key={panelName} // <use> href doesn't always update
              style={{ width: 24, height: 24 }}
            />
          </span>
        )}
        <div className={classes["catalog-details-panel__header-content"]}>
          <div className="text-ellipsis pr-1 flex items-center">
            <div className="text-ellipsis">{panelName}</div>
            {getExtraSummaryPanelIcon(panelItem, { marginLeft: 4 })}
          </div>
          <IconButton
            tooltip="Close details panel"
            onClick={handleDatasetDetailsCollapse}
            className={classes["catalog-details-panel__action-icon"]}
            ref={closeRef}
          >
            <dremio-icon name="interface/close-big" alt="close" />
          </IconButton>
        </div>
      </div>
      <div className="catalog-details-panel__content full-height flex-1 overflow-hidden">
        <Wiki
          entityId={panelItem.get("entityId") || panelItem.get("id")}
          isEditAllowed={isEntityWikiEditAllowed(panelItem)}
          className="bottomContent"
          dataset={panelItem}
          handlePanelDetails={handlePanelDetails}
          isPanel
        />
      </div>
    </aside>
  );
};

export default CatalogDetailsPanel;
