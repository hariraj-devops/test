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
import { actionBtn, actionIcon } from "./actionButtons.less";
import { IconButton } from "dremio-ui-lib/components";

export class EditButton extends Component {
  static propTypes = {
    onClick: PropTypes.func,
    title: PropTypes.string,
    dataQa: PropTypes.string,
  };

  static defaultProps = {
    //todo loc
    title: "Edit",
  };

  render() {
    const { title, onClick, dataQa } = this.props;

    return (
      <IconButton
        className={actionBtn}
        onClick={onClick}
        tooltip={title}
        data-qa={dataQa}
      >
        <dremio-icon name="interface/edit" alt={title} class={actionIcon} />
      </IconButton>
    );
  }
}
