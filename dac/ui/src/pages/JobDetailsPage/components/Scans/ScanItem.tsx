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
import { useState } from "react";
import { useIntl } from "react-intl";
import { Label, Tooltip } from "dremio-ui-lib";
import { formatBytes } from "dremio-ui-common/utilities/formatBytes.js";
import { formatDuration2 } from "dremio-ui-common/utilities/formatDuration.js";
import TextWithHelp from "#oss/components/TextWithHelp";
import { ScansForFilter } from "#oss/constants/Constants";
import { getIconPath } from "#oss/utils/getIconPath";
import jobsUtils from "#oss/utils/jobsUtils";
import { getIconByEntityType } from "#oss/utils/iconUtils";

import "./Scans.less";

const renderScanTooltip = (tooltip: string | undefined, scanName: string) => {
  return tooltip ? (
    <TextWithHelp
      text={tooltip}
      helpText={scanName}
      showToolTip
      color="light"
    />
  ) : (
    scanName
  );
};

const renderIcon = (
  iconName: string,
  className?: string,
  isGradient?: boolean,
) => {
  return (
    <Tooltip title="Job.Reflection">
      {!isGradient ? (
        <dremio-icon
          name={iconName}
          alt="Reflection"
          class={className}
          data-qa={iconName}
        />
      ) : (
        <img
          src={getIconPath(iconName)}
          alt="Reflection"
          className={className}
          data-qa={iconName}
        />
      )}
    </Tooltip>
  );
};

type ScanItemProps =
  | {
      resultsCacheUsed: Immutable.Map<"cacheAge" | "cacheFileSize", number>;
      scan?: undefined;
      scansForFilter?: undefined;
    }
  | {
      resultsCacheUsed?: undefined;
      scan: Immutable.Map<string, string | number>;
      scansForFilter: typeof ScansForFilter;
    };

const ScanItem = ({
  resultsCacheUsed,
  scan,
  scansForFilter,
}: ScanItemProps) => {
  const { formatMessage } = useIntl();
  const [isScanOpen, setIsScanOpen] = useState(false);
  const collapseIconUsed = isScanOpen
    ? "interface/down-chevron"
    : "interface/right-chevron";
  const dataSetType = scan?.get("datasetType");

  return (
    <div className="scans-content">
      <div className="scans-content__header">
        <span
          data-qa="dropdown-customer"
          onClick={() => setIsScanOpen(!isScanOpen)}
          className="scans-content__expandIcon"
          tabIndex={0}
          onKeyDown={(e) => e.code === "Enter" && setIsScanOpen(!isScanOpen)}
          aria-label={`${formatMessage({ id: `Scans.${isScanOpen ? "Collapse" : "Expand"}` })}: ${scan?.get("name") ?? formatMessage({ id: "Scans.ResultCache" })}`}
        >
          {renderIcon(collapseIconUsed, "scans-content__dropdownIcon")}
        </span>
        {!!dataSetType &&
          (dataSetType === "REFLECTION" ? (
            renderIcon(
              "interface/reflection",
              "scans-content__reflectionIcon",
              true,
            )
          ) : (
            <dremio-icon
              style={{
                inlineSize: "24px",
                blockSize: "24px",
              }}
              name={getIconByEntityType(dataSetType)}
            ></dremio-icon>
          ))}
        <span className="scans-content__dataLabel">
          {renderScanTooltip(
            scan?.get("description") as string | undefined,
            (scan?.get("name") as string | undefined) ??
              formatMessage({ id: "Scans.ResultCache" }),
          )}
        </span>
      </div>
      {isScanOpen && (
        <div className="gutter-left">
          {!!resultsCacheUsed && (
            <>
              <div className="scans-content__dataWrapper">
                <Label
                  value={formatMessage({ id: "Scans.ResultCache.Age" })}
                  className="scans-content__dataLabelHeader"
                />
                <span className="scans-content__dataContent">
                  {formatDuration2(resultsCacheUsed.get("cacheAge") * 1000)}
                </span>
              </div>
              <div className="scans-content__dataWrapper">
                <Label
                  value={formatMessage({ id: "Scans.ResultCache.FileSize" })}
                  className="scans-content__dataLabelHeader"
                />
                <span className="scans-content__dataContent">
                  {formatBytes(resultsCacheUsed.get("cacheFileSize"))}
                </span>
              </div>
            </>
          )}
          {!!scan &&
            scansForFilter.map((item, scanIndex) => {
              const labelValue =
                item.key === "nrScannedRows"
                  ? jobsUtils.getFormattedNumber(scan.get(item.key))
                  : scan.get(item.key);
              return (
                <div
                  key={`scansForFilter-${scanIndex}`}
                  className="scans-content__dataWrapper"
                >
                  <Label
                    value={formatMessage({ id: item.label })}
                    className="scans-content__dataLabelHeader"
                  />
                  <span className="scans-content__dataContent">
                    {labelValue}
                  </span>
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
};

export default ScanItem;
