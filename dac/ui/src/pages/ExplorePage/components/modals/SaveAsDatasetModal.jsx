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
import { connect } from "react-redux";
import { compose } from "redux";
import Immutable from "immutable";
import invariant from "invariant";

import Modal from "components/Modals/Modal";

import {
  submitSaveAsDataset,
  submitReapplyAndSaveAsDataset,
  afterSaveDataset,
  afterSaveDatasetWithMultiTab,
} from "actions/explore/dataset/save";
import { withIsMultiTabEnabled } from "#oss/components/SQLScripts/useMultiTabIsEnabled";
import { navigateToNextDataset } from "actions/explore/dataset/common";
import { getDatasetFromLocation } from "selectors/explore";
import { getViewState, getDescendantsList } from "selectors/resources";
import { NEXT_ACTIONS } from "actions/explore/nextAction";
import ApiUtils from "utils/apiUtils/apiUtils";
import FormUnsavedWarningHOC from "#oss/components/Modals/FormUnsavedWarningHOC";
import { splitFullPath } from "#oss/utils/pathUtils";
import { intl } from "#oss/utils/intl";

import SaveAsDatasetForm from "../forms/SaveAsDatasetForm";
import { isTabbableUrl } from "#oss/utils/explorePageTypeUtils";

const VIEW_ID = "SaveAsDatasetModal";

export class SaveAsDatasetModal extends Component {
  static propTypes = {
    isOpen: PropTypes.bool,
    hide: PropTypes.func,
    location: PropTypes.object,
    nextAction: PropTypes.string,

    //connected
    dependentDatasets: PropTypes.array,
    dataset: PropTypes.instanceOf(Immutable.Map),
    viewState: PropTypes.instanceOf(Immutable.Map).isRequired,
    submitSaveAsDataset: PropTypes.func.isRequired,
    submitReapplyAndSaveAsDataset: PropTypes.func.isRequired,
    afterSaveDataset: PropTypes.func.isRequired,
    afterSaveDatasetWithMultiTab: PropTypes.func.isRequried,
    navigateToNextDataset: PropTypes.func,
    isMultiTabEnabled: PropTypes.bool,
    // from FormUnsavedWarningHOC
    updateFormDirtyState: PropTypes.func,
  };

  static defaultProps = {
    dataset: Immutable.Map(),
  };

  static contextTypes = {
    username: PropTypes.string,
  };

  getMessage(nextAction) {
    const toolName = {
      [NEXT_ACTIONS.openTableau]: "Tableau",
      [NEXT_ACTIONS.openQlik]: "Qlik Sense",
    }[nextAction];
    if (toolName) {
      return `In order to view this data in ${toolName}, you need to save your current dataset.`;
    }
  }

  submit = (values) => {
    const { location, nextAction, isMultiTabEnabled } = this.props;
    invariant(
      typeof values.location === "string",
      `values.location must be of type string. Got '${typeof values.location}' instead.`,
    );
    const action =
      values.reapply === "ORIGINAL"
        ? this.props.submitReapplyAndSaveAsDataset
        : this.props.submitSaveAsDataset;

    return ApiUtils.attachFormSubmitHandlers(
      action(
        values.name,
        splitFullPath(values.location),
        location,
        isMultiTabEnabled && isTabbableUrl(location),
      ),
    ).then((response) => {
      if (isMultiTabEnabled && isTabbableUrl(location)) {
        return this.props.afterSaveDatasetWithMultiTab(response);
      } else {
        return this.props.afterSaveDataset(response, nextAction);
      }
    });
  };

  render() {
    const {
      isOpen,
      hide,
      nextAction,
      dataset,
      dependentDatasets,
      updateFormDirtyState,
    } = this.props;
    const fullPath = dataset && dataset.get("displayFullPath");
    return (
      <Modal
        size="small"
        title={intl.formatMessage({ id: "NewQuery.SaveViewAsBtn" })}
        isOpen={isOpen}
        hide={hide}
        modalHeight="600px"
      >
        <SaveAsDatasetForm
          dependentDatasets={dependentDatasets}
          onFormSubmit={this.submit}
          onCancel={hide}
          message={this.getMessage(nextAction)}
          canReapply={dataset && Immutable.Map(dataset).get("canReapply")}
          datasetType={dataset.get("datasetType")}
          fullPath={fullPath && fullPath.slice(0, -1).toJS()}
          updateFormDirtyState={updateFormDirtyState}
        />
      </Modal>
    );
  }
}

function mapStateToProps(state, props) {
  const requiredProps = {
    viewState: getViewState(state, VIEW_ID),
  };
  if (!props.isOpen) {
    return requiredProps;
  }
  return {
    ...requiredProps,
    dependentDatasets: getDescendantsList(state),
    dataset: getDatasetFromLocation(state, props.location),
  };
}

export default compose(
  withIsMultiTabEnabled,
  connect(mapStateToProps, {
    submitSaveAsDataset,
    submitReapplyAndSaveAsDataset,
    afterSaveDataset,
    afterSaveDatasetWithMultiTab,
    navigateToNextDataset,
  }),
)(FormUnsavedWarningHOC(SaveAsDatasetModal));
