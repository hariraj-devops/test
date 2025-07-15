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
import { useIntl } from "react-intl";
import { ScansForFilter } from "#oss/constants/Constants";
import ScanItem from "./ScanItem";
import "./Scans.less";

type ScansProps = {
  resultsCacheUsed?: Immutable.Map<"cacheAge" | "cacheFileSize", number>;
  scans: Immutable.List<Immutable.Map<string, string | number>>;
  scansForFilter: typeof ScansForFilter;
};

const Scans = ({ resultsCacheUsed, scans, scansForFilter }: ScansProps) => {
  const { formatMessage } = useIntl();

  return (
    <div className="scans">
      {(scans.size > 0 || resultsCacheUsed) && (
        <div className="scans-title">{formatMessage({ id: "Scans" })}</div>
      )}

      {resultsCacheUsed && (
        <ScanItem key="results-cache" resultsCacheUsed={resultsCacheUsed} />
      )}

      {scans.map((scan, index) => {
        return (
          <ScanItem
            key={`scans-${index}`}
            scan={scan}
            scansForFilter={scansForFilter}
          />
        );
      })}
    </div>
  );
};

export default Scans;
