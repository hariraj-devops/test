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
import FormUtils from "utils/FormUtils/FormUtils";
import FormElement from "components/Forms/FormElement";
import FormSection from "components/Forms/FormSection";
import Checkbox from "components/Fields/Checkbox";
import Toggle from "components/Fields/Toggle";
import FormSectionConfig from "utils/FormUtils/FormSectionConfig";
import FormElementConfig from "utils/FormUtils/FormElementConfig";
import { HoverHelp, Label } from "dremio-ui-lib";

import {
  flexContainer,
  flexElementAuto,
  flexColumnContainer,
  noMargin,
  flexJustifyBetween,
} from "#oss/uiTheme/less/layout.less";
import {
  checkboxMargin,
  indentedContainer,
} from "./CheckEnabledContainer.less";

/**
 * Displays a checkbox and a container, which can be either shown/hidden or its inputs enabled/disabled based
 *   on checkbox state
 * Props:
 *   elementConfig: CheckEnabledConfig with getConfig() returning:
 *     - propName - for the checkbox
 *     - inverted - inverts visible state of checkbox
 *     - invertContainer - hides/disables container according to inverted state of checkbox
 *     - container - section type configuration object with elements and optional title, tooltip, etc.
 *     - whenNotChecked - optional "hide" value, otherwise defaults to "disable"
 */
export default class CheckEnabledContainer extends Component {
  static propTypes = {
    fields: PropTypes.object,
    mainCheckboxIsDisabled: PropTypes.bool,
    elementConfig: PropTypes.object,
  };

  setCheckboxFieldCheckedProp = (field) => {
    if (field && field.checked === undefined) {
      field.checked = false;
    }
  };

  render() {
    const { fields, elementConfig, mainCheckboxIsDisabled } = this.props;
    const elementConfigJson = elementConfig.getConfig();
    const checkField = FormUtils.getFieldByComplexPropName(
      fields,
      elementConfig.getPropName(),
    );
    this.setCheckboxFieldCheckedProp(checkField); // to avoid react controlled/uncontrolled field warning

    const enableContainer = checkField.checked;
    const isToggle = elementConfigJson.isToggle;
    const isInverted = elementConfigJson.inverted ? { inverted: true } : null;
    const isInvertedContainer = elementConfigJson.invertContainer;
    const container = elementConfig.getContainer();
    const containerIsIndented = !elementConfigJson.noIndent;
    const containerIsElement = container instanceof FormElementConfig;
    const containerIsSection = container instanceof FormSectionConfig;

    const containerDisabled =
      (enableContainer && isInvertedContainer) ||
      (!enableContainer && !isInvertedContainer) ||
      mainCheckboxIsDisabled;

    return (
      <div className={flexColumnContainer}>
        <div
          className={classNames([
            flexContainer,
            isToggle && flexJustifyBetween,
          ])}
        >
          {isToggle ? (
            <>
              <Label
                value={elementConfigJson.label}
                helpText={elementConfigJson.checkTooltip}
                className={noMargin}
              />
              <Toggle
                {...checkField}
                value={checkField.checked}
                disabled={mainCheckboxIsDisabled}
              />
            </>
          ) : (
            <>
              <Checkbox
                {...checkField}
                {...isInverted}
                disabled={mainCheckboxIsDisabled}
                className={checkboxMargin}
                label={elementConfigJson.label}
              />
              {elementConfigJson.checkTooltip && (
                <HoverHelp content={elementConfigJson.checkTooltip} />
              )}
            </>
          )}
        </div>
        {(elementConfigJson.whenNotChecked !== "hide" || enableContainer) && (
          <div
            className={classNames([
              flexElementAuto,
              containerIsIndented && indentedContainer,
            ])}
          >
            {containerIsElement && (
              <FormElement
                fields={fields}
                disabled={containerDisabled}
                elementConfig={container}
              />
            )}
            {containerIsSection && (
              <FormSection
                fields={fields}
                disabled={containerDisabled}
                style={{ marginBottom: 0 }}
                sectionConfig={container}
              />
            )}
          </div>
        )}
      </div>
    );
  }
}
