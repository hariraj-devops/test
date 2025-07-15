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
import Immutable from "immutable";
import { createSelector } from "reselect";

import ViewStateWrapper from "#oss/components/ViewStateWrapper";
import { constructFullPath } from "#oss/utils/pathUtils";
import { getEntity, getViewState } from "#oss/selectors/resources";
import { updateViewState } from "#oss/actions/resources";
import {
  clearDataSetAccelerationSettings,
  loadDatasetAccelerationSettings,
  updateDatasetAccelerationSettings,
} from "#oss/actions/resources/datasetAccelerationSettings";
import ApiUtils from "#oss/utils/apiUtils/apiUtils";
import { formatMessage } from "#oss/utils/locale";
import { INCREMENTAL_TYPES } from "#oss/constants/columnTypeGroups";
import { getCurrentFormatUrl } from "#oss/selectors/home";
import { loadFileFormat } from "#oss/actions/modals/addFileModal";
import { getVersionContextFromId } from "dremio-ui-common/utilities/datasetReference.js";
import AccelerationUpdatesForm from "./AccelerationUpdatesForm";
import { LIVE_REFLECTION_ENABLED } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import { getSupportFlags } from "#oss/selectors/supportFlags";

const VIEW_ID = "AccelerationUpdatesController";
const updateViewStateWrapper = (viewState) =>
  updateViewState(VIEW_ID, {
    // apply defaults
    isInProgress: false,
    isFailed: false,
    error: null,
    //----------------
    ...viewState,
  });

export class AccelerationUpdatesController extends Component {
  static propTypes = {
    entity: PropTypes.instanceOf(Immutable.Map),
    fileFormat: PropTypes.instanceOf(Immutable.Map),
    formatUrl: PropTypes.string,
    viewState: PropTypes.instanceOf(Immutable.Map),
    onCancel: PropTypes.func,
    onDone: PropTypes.func,
    loadFileFormat: PropTypes.func,
    clearDataSetAccelerationSettings: PropTypes.func,
    loadDatasetAccelerationSettings: PropTypes.func,
    updateDatasetAccelerationSettings: PropTypes.func,
    updateFormDirtyState: PropTypes.func,
    accelerationSettings: PropTypes.instanceOf(Immutable.Map),
    updateViewState: PropTypes.func.isRequired, // (viewState) => void
    isLiveReflectionsEnabled: PropTypes.bool,
  };

  state = {
    dataset: null,
  };

  UNSAFE_componentWillMount() {
    this.receiveProps(this.props, {});
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    if (!this.props.formatUrl) {
      this.loadFormat(nextProps);
    }
    this.receiveProps(nextProps, this.props);
  }

  componentDidMount() {
    this.loadFormat(this.props);
  }

  loadFormat(props) {
    const { formatUrl } = props;
    if (formatUrl) {
      props.loadFileFormat(formatUrl, VIEW_ID);
    }
  }

  receiveProps(nextProps, oldProps) {
    const { entity } = nextProps;
    if (entity !== oldProps.entity) {
      const id = entity.get("id");
      this.loadDataset(id, entity);
    }
  }

  loadDataset(id, entity) {
    const updateVS = this.props.updateViewState;
    const versionContext = getVersionContextFromId(id);

    // We fetch to the full schema using the v3 catalog api here so we can filter out types.  v2 collapses types
    // by display types instead of returning the actual type.
    updateVS({ isInProgress: true });
    return ApiUtils.fetchJson(
      `catalog/${id}`,
      (json) => {
        updateVS({ isInProgress: false });
        this.setState({ dataset: json });
        this.props.loadDatasetAccelerationSettings(
          entity.get("fullPathList"),
          VIEW_ID,
          versionContext,
        );
      },
      (error) => {
        // DX-22985: Server might return a valid error message.
        //    - In case it does, we need to extract it out of the JSON and display the message.
        //    - If not we need to display a generic error message.
        error
          .json()
          .then((json) =>
            updateVS({
              isFailed: true,
              error: { message: json.errorMessage },
            }),
          )
          .catch((jsonError) =>
            updateVS({
              // JSON parsing failed. So we are displaying a generic Error Message.
              isFailed: true,
              error: {
                message: formatMessage("Message.ApiErr.Load.Dataset", {
                  err: jsonError.statusText,
                }),
              },
            }),
          );
      },
    );
  }

  schemaToColumns(dataset) {
    const schema = (dataset && dataset.fields) || [];
    const columns = schema
      .filter((i) => INCREMENTAL_TYPES.indexOf(i.type.name) > -1)
      .map((item, index) => {
        return { name: item.name, type: item.type.name, index };
      });
    return Immutable.fromJS(columns);
  }

  submit = (form) => {
    if (
      !this.props.isLiveReflectionsEnabled ||
      form.accelerationRefreshOnDataChanges != null
    ) {
      delete form.accelerationRefreshOnDataChanges;
    }
    const fullPathList = this.props.entity.get("fullPathList");
    const versionContext = getVersionContextFromId(this.props.entity.get("id"));
    return ApiUtils.attachFormSubmitHandlers(
      this.props.updateDatasetAccelerationSettings(
        fullPathList,
        form,
        versionContext,
      ),
    ).then(() => {
      this.props.clearDataSetAccelerationSettings(fullPathList);
      this.props.onDone(null, true);
      return null;
    });
  };

  render() {
    const {
      onCancel,
      accelerationSettings,
      updateFormDirtyState,
      fileFormat,
      viewState,
      entity,
    } = this.props;
    const { dataset } = this.state;

    let fileFormatType = "";
    if (fileFormat) {
      fileFormatType = fileFormat.get("type");
    } else fileFormatType = dataset?.format?.type;

    return (
      <ViewStateWrapper viewState={viewState} hideChildrenWhenInProgress>
        {accelerationSettings && (
          <AccelerationUpdatesForm
            accelerationSettings={accelerationSettings}
            datasetFields={this.schemaToColumns(this.state.dataset)}
            entityType={entity.get("entityType")}
            fileFormatType={fileFormatType}
            entityId={entity.get("id")}
            onCancel={onCancel}
            updateFormDirtyState={updateFormDirtyState}
            entity={entity}
            submit={this.submit}
          />
        )}
      </ViewStateWrapper>
    );
  }
}

function mapStateToProps(state, ownProps) {
  const { entity } = ownProps;
  const fullPathList = entity.get("fullPathList");
  const fullPath = constructFullPath(fullPathList);
  // TODO: this is a workaround for accelerationSettings not having its own id

  const entityType = entity.get("entityType");

  // If entity type is folder, then get fileFormat. It will be used to disable incremental reflection option.
  let formatUrl, fileFormat;
  if (["folder"].indexOf(entityType) !== -1) {
    const getFullPathForFileFormat = createSelector(
      (fullPathImmutable) => fullPathImmutable,
      (path) => (path ? path.toJS() : null),
    );
    formatUrl = fullPathList
      ? getCurrentFormatUrl(getFullPathForFileFormat(fullPathList), true)
      : null;
    fileFormat = getEntity(state, formatUrl, "fileFormat");
  }

  return {
    fileFormat,
    formatUrl,
    viewState: getViewState(state, VIEW_ID),
    accelerationSettings: getEntity(
      state,
      fullPath,
      "datasetAccelerationSettings",
    ),
    isLiveReflectionsEnabled:
      getSupportFlags?.(state)?.[LIVE_REFLECTION_ENABLED],
  };
}

export default connect(mapStateToProps, {
  loadFileFormat,
  clearDataSetAccelerationSettings,
  loadDatasetAccelerationSettings,
  updateDatasetAccelerationSettings,
  updateViewState: updateViewStateWrapper,
})(AccelerationUpdatesController);
