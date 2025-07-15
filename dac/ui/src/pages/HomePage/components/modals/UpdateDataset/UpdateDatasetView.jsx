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
import { FormattedMessage, injectIntl } from "react-intl";
import Immutable from "immutable";

import { Button } from "dremio-ui-lib/components";
import * as ButtonTypes from "components/Buttons/ButtonTypes";
import ModalFooter from "components/Modals/components/ModalFooter";
import ResourceTreeContainer from "components/Tree/ResourceTreeContainer";
import Message from "components/Message";
import { formLabel } from "uiTheme/radium/typography";
import { formRow } from "uiTheme/radium/forms";
import { FieldWithError, TextField } from "components/Fields";
import DependantDatasetsWarning from "components/Modals/components/DependantDatasetsWarning";
import { applyValidators, isRequired } from "utils/validation";
import { connectComplexForm } from "components/Forms/connectComplexForm";
import { ModalSize } from "components/Modals/Modal";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";

import "./UpdateDataset.less";

export const UpdateMode = {
  rename: "rename",
  move: "move",
  remove: "remove",
  removeFormat: "removeFormat",
};

function validate(values, props) {
  if (
    props.mode !== UpdateMode.remove &&
    props.mode !== UpdateMode.removeFormat
  ) {
    return applyValidators(values, [isRequired("datasetName", "Dataset name")]);
  }
}

@injectIntl
export class UpdateDatasetView extends Component {
  static propTypes = {
    initialPath: PropTypes.string,
    buttons: PropTypes.arrayOf(
      PropTypes.shape({
        key: PropTypes.string.isRequired,
        name: PropTypes.string.isRequired,
        type: PropTypes.string.isRequired,
      }),
    ),
    dependentDatasets: PropTypes.array,
    item: PropTypes.instanceOf(Immutable.Map),
    hidePath: PropTypes.bool.isRequired,
    name: PropTypes.string.isRequired,
    handleSubmit: PropTypes.func,
    fields: PropTypes.object,
    submit: PropTypes.func,
    error: PropTypes.object,
    hide: PropTypes.func,
    mode: PropTypes.oneOf(Object.values(UpdateMode)).isRequired,
    size: PropTypes.oneOf([ModalSize.small, ModalSize.smallest]).isRequired,
    getGraphLink: PropTypes.func,
    intl: PropTypes.object.isRequired,
  };

  static defaultProps = {
    name: "",
    hidePath: false,
  };

  constructor(props) {
    super(props);
    this.renderWarning = this.renderWarning.bind(this);
  }

  clickHandler(e) {
    e.stopPropagation();
  }

  getWarningTextId = (mode, count) => {
    // pluralization does not work well with formatMessage. Hence doing it manually
    switch (mode) {
      case UpdateMode.remove:
        return count === 1
          ? "Dataset.DependantDatasetsRemoveSingleWarning"
          : "Dataset.DependantDatasetsRemoveWarning";
      case UpdateMode.rename:
        return count === 1
          ? "Dataset.DependantDatasetsRenameSingleWarning"
          : "Dataset.DependantDatasetsRenameWarning";
      case UpdateMode.removeFormat:
        return count === 1
          ? "Dataset.DependantDatasetsRemoveFormatSingleWarning"
          : "Dataset.DependantDatasetsRemoveFormatWarning";
      case UpdateMode.move:
      default:
        return count === 1
          ? "Dataset.DependantDatasetsMoveSingleWarning"
          : "Dataset.DependantDatasetsMoveWarning";
    }
  };

  renderWarning() {
    const { dependentDatasets, mode, getGraphLink, intl } = this.props;

    if (dependentDatasets && dependentDatasets.length > 0) {
      const messageObj = new Immutable.Map({
        message: intl.formatMessage(
          { id: this.getWarningTextId(mode) },
          { dependentDatasetCount: dependentDatasets.length },
        ),
        moreInfo: (
          <DependantDatasetsWarning dependantDatasets={dependentDatasets} />
        ),
      });

      return (
        <Message
          messageType="warning"
          message={messageObj}
          detailsStyle={{ padding: "2px 38px 0px 38px" }}
          isDismissable={false}
          customRightButton={getGraphLink ? getGraphLink() : undefined}
        />
      );
    }
    return null;
  }

  renderErrorMessage() {
    const { error } = this.props;
    return (
      error && (
        <Message
          messageType="error"
          message={error.message}
          messageId={error.id}
          detailsStyle={{ maxHeight: 100 }}
        />
      )
    );
  }

  renderFormBody() {
    const { t } = getIntlContext();
    const { mode, item, fields } = this.props;
    if (mode === UpdateMode.remove) {
      return (
        <div className="remove-question">
          {t("Delete.Confirmation", {
            name: item.get("name"),
          })}
        </div>
      );
    }
    if (mode === UpdateMode.removeFormat) {
      return (
        <div className="remove-question">
          {t("RemoveFormat.Confirmation", {
            name: item.get("name"),
          })}
        </div>
      );
    }
    return (
      <div style={formRow}>
        <FieldWithError
          {...fields.datasetName}
          touched
          label="Name"
          errorPlacement="right"
        >
          <TextField
            {...fields.datasetName}
            name="name"
            touched
            initialFocus
            placeholder={t("Dataset.Name")}
          />
        </FieldWithError>
      </div>
    );
  }

  renderLocationBlock() {
    const { hidePath, initialPath, fields, dependentDatasets } = this.props;

    if (hidePath) return null;

    //setting the height of scrollable space selector (location) block
    const style =
      dependentDatasets && dependentDatasets.length
        ? { maxHeight: 153, minHeight: 153 }
        : { maxHeight: 248, minHeight: 248 };

    return (
      <div className="property location">
        <label style={formLabel}>
          <FormattedMessage id="Common.Location" />
        </label>
        <ResourceTreeContainer
          preselectedNodeId={initialPath}
          style={style}
          onChange={fields.selectedEntity.onChange}
          hideDatasets
          isSourcesHidden
        />
      </div>
    );
  }

  renderButtons() {
    const { handleSubmit, submit, buttons, hide } = this.props;
    return buttons.map((button, index) => {
      const onClick =
        button.key === "cancel"
          ? hide
          : handleSubmit(submit.bind(this, button.key));

      return (
        <Button
          className={button.className}
          onClick={onClick}
          variant={
            button.type === ButtonTypes.NEXT
              ? "primary"
              : button.type === ButtonTypes.PRIMARY_DANGER
                ? "primary-danger"
                : "secondary"
          }
          key={`${index}_button`}
          data-qa={button.name}
        >
          {button.name}
        </Button>
      );
    });
  }

  render() {
    const { size } = this.props;
    const updateDatasetClass = classNames(
      "update-dataset",
      { "update-dataset-small": size === ModalSize.small },
      { "update-dataset-smallest": size === ModalSize.smallest },
    );
    return (
      <div className={updateDatasetClass} onClick={this.clickHandler}>
        <div className="error-message-updateDataset">
          {this.renderErrorMessage()}
        </div>
        {this.renderWarning()}
        <div className="update-dataset-content">
          {this.renderFormBody()}
          {this.renderLocationBlock()}
        </div>
        <ModalFooter>
          <div className="dremio-button-group">{this.renderButtons()}</div>
        </ModalFooter>
      </div>
    );
  }
}
function mapStateToProps(state, props) {
  return {
    initialValues: {
      datasetName: props.name,
      selectedEntity: props.initialPath,
    },
  };
}

export default connectComplexForm(
  {
    form: "updateDataset",
    fields: ["datasetName", "selectedEntity"],
    validate,
  },
  [],
  mapStateToProps,
)(UpdateDatasetView);
