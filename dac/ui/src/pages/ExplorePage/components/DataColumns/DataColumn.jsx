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
import { Component } from "react";
import PropTypes from "prop-types";
import classNames from "clsx";
import { Tooltip } from "@dremio/design-system/components";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { formatMessage } from "utils/locale";
import {
  typeToIconType,
  typeToFormatMessageId,
} from "#oss/constants/DataTypes";

import HighlightedColumnName from "./HighlightedColumnName";

export const columnPropTypes = {
  type: PropTypes.string, //see constants/DataTypes for the list of available types
  name: PropTypes.string,
  isPartitioned: PropTypes.bool,
  isSorted: PropTypes.bool,
};

const { t } = getIntlContext();

export class DataColumn extends Component {
  static propTypes = {
    ...columnPropTypes,
    className: PropTypes.string,
    searchTerm: PropTypes.string,
  };

  render() {
    const { type, name, className, searchTerm, isPartitioned, isSorted } =
      this.props;
    const label = `data-types/${typeToIconType[type]}`;
    const alt = typeToFormatMessageId?.[type] ?? typeToFormatMessageId["ANY"];

    return (
      <div className={classNames("flex items-center h-5 gap-05", className)}>
        <dremio-icon
          name={label}
          data-qa={label}
          alt={formatMessage(alt)}
          class="h-205 w-205 icon-primary"
        />
        <HighlightedColumnName columnName={name} searchTerm={searchTerm} />
        {isSorted && (
          <Tooltip content={t("Catalog.Tree.Column.Sorted")}>
            <dremio-icon
              name="interface/sort"
              class="h-205 w-205 icon-primary"
            />
          </Tooltip>
        )}
        {isPartitioned && (
          <Tooltip content={t("Catalog.Tree.Column.Partitioned")}>
            <dremio-icon
              name="sql-editor/partition"
              class="h-205 w-205 icon-primary"
            />
          </Tooltip>
        )}
      </div>
    );
  }
}
