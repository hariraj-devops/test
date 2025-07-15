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
import { useState } from "react";
import PropTypes from "prop-types";
import { injectIntl } from "react-intl";
import DatasetItemLabel from "#oss/components/Dataset/DatasetItemLabel";
import { Label } from "dremio-ui-lib";
import Immutable from "immutable";
import { getIconByEntityType } from "utils/iconUtils";
import "./QueriedDataset.less";

const ShowOverlay = (datasetType) => {
  switch (datasetType) {
    case "OTHERS":
    case "Unavailable":
    case "Catalog":
    case "INVALID_DATASET_TYPE":
      return false;
    default:
      return true;
  }
};
const MAX_ITEMS = 3;
const QueriedDataset = ({ queriedDataSet, intl }) => {
  const [showMore, setShowMore] = useState(false);
  const getRenderedItems = () => {
    if (showMore) {
      return queriedDataSet;
    }
    return queriedDataSet && queriedDataSet.slice(0, MAX_ITEMS);
  };

  return (
    <div className="queriedDataset">
      <div className="queriedDataset-title">
        {intl.formatMessage({ id: "Queried_Datasets" })}
      </div>
      <div className="queriedDataset-dataWrapper">
        {getRenderedItems().map((dataset, index) => {
          const typeIcon = getIconByEntityType(
            dataset.get("datasetType"),
            !!dataset.get("versionContext"),
          );

          return (
            <div
              className="queriedDataset-dataWrapper__wrapper"
              key={`queriedDataset-${index}`}
            >
              <DatasetItemLabel
                name=" "
                typeIcon={typeIcon}
                className="queriedDataset-dataWrapper__label"
                fullPath={dataset.get("datasetPathsList")}
                shouldShowOverlay={ShowOverlay(dataset.get("datasetType"))}
                hideOverlayActionButtons
                customNode={
                  <span className="queriedDataset-dataWrapper__wrapper__dataHeader">
                    <Label
                      value={dataset.get("datasetName")}
                      className="queriedDataset-dataWrapper__wrapper__dataLabel"
                    />
                    <Label
                      value={dataset.get("datasetPath")}
                      className="queriedDataset-dataWrapper__wrapper__datasetPath margin--none"
                    />
                  </span>
                }
                versionContext={
                  dataset.get("versionContext") &&
                  JSON.parse(dataset.get("versionContext"))
                }
              />
            </div>
          );
        })}
      </div>
      {JSON.parse(JSON.stringify(queriedDataSet)).length > 3 && (
        <div
          onClick={() => setShowMore(!showMore)}
          className="queriedDataset-buttonStyle"
          tabIndex={0}
          onKeyDown={(e) => e.code === "Enter" && setShowMore(!showMore)}
        >
          <span className="queriedDataset-buttonStyle__showmoreContent">
            {showMore ? "Show less" : "Show more"}
          </span>
          <dremio-icon
            name={
              showMore ? "interface/down-chevron" : "interface/right-chevron"
            }
            class="queriedDataset-buttonStyle__showmoreIcon"
          />
        </div>
      )}
    </div>
  );
};

QueriedDataset.propTypes = {
  queriedDataSet: PropTypes.instanceOf(Immutable.List),
  intl: PropTypes.object.isRequired,
};
export default injectIntl(QueriedDataset);
