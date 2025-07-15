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
import clsx from "clsx";

import PropTypes from "prop-types";

import forms from "uiTheme/radium/forms";
import * as classes from "#oss/uiTheme/radium/replacingRadiumPseudoClasses.module.less";

class TextArea extends PureComponent {
  static propTypes = {
    error: PropTypes.string,
    disabled: PropTypes.bool,
    style: PropTypes.object,
    initialValue: PropTypes.any,
    autofill: PropTypes.any,
    onUpdate: PropTypes.any,
    valid: PropTypes.any,
    invalid: PropTypes.any,
    dirty: PropTypes.any,
    pristine: PropTypes.any,
    active: PropTypes.any,
    touched: PropTypes.any,
    visited: PropTypes.any,
    autofilled: PropTypes.any,
    placeholder: PropTypes.any,
    className: PropTypes.string,
  };

  constructor(props) {
    super(props);
  }

  render() {
    return (
      <textarea
        {...this.props}
        className={clsx("field", classes["textAreaPsuedoClasses"], {
          [this.props.className]: this.props.className,
        })}
        style={{
          ...forms.textArea,
          ...(this.props.error ? forms.textInputError : {}),
          ...(this.props.disabled ? forms.textInputDisabled : {}),
          ...(this.props.style ? this.props.style : {}),
        }}
      />
    );
  }
}
export default TextArea;
