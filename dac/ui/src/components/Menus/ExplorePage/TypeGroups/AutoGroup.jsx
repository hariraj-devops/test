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
import { createRef, PureComponent } from "react";
import PropTypes from "prop-types";
import Divider from "@mui/material/Divider";

import { AUTO_TYPES } from "#oss/constants/columnTypeGroups";
import { JSONTYPE } from "#oss/constants/DataTypes";

import ColumnMenuItem from "./../ColumnMenus/ColumnMenuItem";
import MenuItem from "./../MenuItem";

class AutoGroup extends PureComponent {
  static propTypes = {
    makeTransform: PropTypes.func.isRequired,
    columnType: PropTypes.string,
  };

  constructor(props) {
    super(props);
    this.setVisibility = this.setVisibility.bind(this);
    this.rootRef = createRef();
    this.state = {
      visibility: true,
    };
  }

  componentDidMount() {
    const { current: { children } = {} } = this.rootRef;
    if (!children) {
      return null;
    }

    const divs = Array.from(children).filter(
      (child) => child.nodeName === "DIV",
    );
    if (divs.length === 1) {
      this.setVisibility(false);
    } else {
      this.setVisibility(true);
    }
  }

  setVisibility(visibility) {
    this.setState({
      visibility,
    });
  }

  render() {
    return (
      <div
        ref={this.rootRef}
        style={{ display: this.state.visibility ? "block" : "none" }}
      >
        <Divider style={{ marginTop: 5, marginBottom: 5 }} />
        <MenuItem disabled>AUTO-DETECT</MenuItem>
        <ColumnMenuItem
          actionType={JSONTYPE}
          columnType={this.props.columnType}
          title="JSON"
          availableTypes={AUTO_TYPES}
          onClick={this.props.makeTransform}
        />
      </div>
    );
  }
}
export default AutoGroup;
