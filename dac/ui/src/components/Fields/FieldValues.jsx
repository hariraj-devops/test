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
import { PureComponent } from "react";
import { v4 as uuidv4 } from "uuid";

import PropTypes from "prop-types";

import { typeToIconType } from "#oss/constants/DataTypes";
import EllipsedText from "components/EllipsedText";
import Meter from "components/Meter";

import {
  LINE_NOWRAP_ROW_START_CENTER,
  FLEX_COL_START,
} from "uiTheme/radium/flexStyle";
import { formDescription } from "uiTheme/radium/typography";

import dataFormatUtils from "utils/dataFormatUtils";

class FieldValues extends PureComponent {
  static propTypes = {
    options: PropTypes.arrayOf(
      PropTypes.shape({
        percent: PropTypes.number,
        value: PropTypes.any,
        type: PropTypes.any,
      }),
    ),
    optionsStyle: PropTypes.object,
  };

  static defaultProps = {
    options: [],
  };

  render() {
    const { options } = this.props;
    const maxPercent = Math.max(...options.map((option) => option.percent));
    return (
      <table className="field">
        <tbody>
          {options.map((option) => {
            const correctText = dataFormatUtils.formatValue(option.value);
            const correctTextStyle =
              option.value === undefined ||
              option.value === null ||
              option.value === ""
                ? styles.nullwrap
                : {};
            return (
              <tr key={uuidv4()}>
                <td>
                  <dremio-icon
                    class="icon-primary"
                    name={`data-types/${typeToIconType[option.type ? option.type.toUpperCase() : "TEXT"]}`}
                    style={styles.icon}
                  ></dremio-icon>
                </td>
                <td style={styles.value}>
                  <EllipsedText
                    text={correctText}
                    style={{ ...correctTextStyle }}
                  />
                </td>
                <td style={styles.progressWrap}>
                  <Meter value={option.percent} max={maxPercent} />
                </td>
                <td style={styles.percent}>
                  {`${option.percent.toPrecision(2)}%`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    );
  }
}

const styles = {
  options: {
    ...FLEX_COL_START,
    height: 250,
  },
  checkbox: {
    marginRight: -7,
    marginLeft: 15,
  },
  option: {
    ...LINE_NOWRAP_ROW_START_CENTER,
    marginTop: 16,
  },
  icon: {
    display: "block",
    height: 24,
  },
  value: {
    maxWidth: 200,
    paddingLeft: 10,
  },
  nullwrap: {
    color: "#aaa",
    fontStyle: "italic",
    width: "95%",
  },
  progressWrap: {
    width: 400,
    paddingLeft: 10,
  },
  percent: {
    ...formDescription,
    paddingLeft: 10,
  },
};
export default FieldValues;
