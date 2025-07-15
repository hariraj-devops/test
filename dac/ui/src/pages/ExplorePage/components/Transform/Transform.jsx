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
import { connect } from "react-redux";
import PropTypes from "prop-types";
import Immutable from "immutable";
import ImmutablePropTypes from "react-immutable-proptypes";

import { getViewState } from "#oss/selectors/resources";
import { getExploreState } from "#oss/selectors/explore";

import dataStoreUtils from "#oss/utils/dataStoreUtils";
import exploreUtils from "#oss/utils/explore/exploreUtils";

import {
  loadTransformCards,
  loadTransformCardPreview,
  loadTransformValuesPreview,
  LOAD_TRANSFORM_CARDS_VIEW_ID,
} from "#oss/actions/explore/recommended";
import { resetViewState } from "#oss/actions/resources";
import { loadTransformCardsWrapper, transformTypeURLMapper } from "./utils";

import TransformView from "./TransformView";

export class Transform extends PureComponent {
  static propTypes = {
    dataset: PropTypes.instanceOf(Immutable.Map),
    submit: PropTypes.func.isRequired,
    cancel: PropTypes.func,
    changeFormType: PropTypes.func.isRequired,

    // connected
    transform: ImmutablePropTypes.contains({
      transformType: PropTypes.string,
      columnType: PropTypes.string,
    }),
    sqlSize: PropTypes.number,
    cardsViewState: PropTypes.instanceOf(Immutable.Map),
    location: PropTypes.object,

    // actions
    loadTransformCards: PropTypes.func.isRequired,
    loadTransformCardPreview: PropTypes.func.isRequired,
    loadTransformValuesPreview: PropTypes.func.isRequired,
    resetViewState: PropTypes.func,
  };

  static contextTypes = {
    router: PropTypes.object,
  };

  constructor(props) {
    super(props);
    this.subTitles = dataStoreUtils.getSubtypeForTransformTab();
    this.loadTransformCardPreview = this.loadTransformCardPreview.bind(this);
    this.loadTransformValuesPreview =
      this.loadTransformValuesPreview.bind(this);
  }

  componentDidMount() {
    this.props.resetViewState(LOAD_TRANSFORM_CARDS_VIEW_ID);
    return loadTransformCardsWrapper(this.props).then((action) => {
      if (!action) {
        return;
      }

      const { transform, location } = this.props;
      if (transform.get("method") === "Values" && action.payload.values) {
        const allUnique = action.payload.values.availableValues.every(
          (item) => item.count === 1,
        );
        if (allUnique) {
          this.context.router.replace({
            ...location,
            state: {
              ...location.state,
              method: "Pattern",
            },
          });
        }
      }
      return null;
    });
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    if (!this.props.transform.equals(nextProps.transform)) {
      loadTransformCardsWrapper(nextProps);
    }
  }

  loadTransformCardPreview(index, model) {
    const { transform } = this.props;
    const columnName = transform.get("columnName");

    const selection = exploreUtils.transformHasSelection(transform)
      ? { columnName }
      : { columnName, mapPathList: model.path ? [model.path] : undefined }; // TODO does api actually use this?
    const actionType = transformTypeURLMapper(transform);
    const data = {
      selection,
      rule: model,
    };
    return this.props.loadTransformCardPreview(
      data,
      transform,
      this.props.dataset,
      actionType,
      index,
    );
  }

  loadTransformValuesPreview(values) {
    const { transform } = this.props;
    const transformType = transform.get("transformType");
    const columnName = transform.get("columnName");

    const selection = !exploreUtils.transformHasSelection(transform)
      ? { columnName }
      : transform.get("selection") && transform.get("selection").toJS();

    const data = {
      selection,
      values,
    };
    this.props.loadTransformValuesPreview(
      data,
      transform,
      this.props.dataset,
      transformType,
    );
  }

  handleTransformChange = (newTransform) => {
    const { location } = this.props;
    this.context.router.push({ ...location, state: newTransform.toJS() });
  };

  render() {
    return (
      <div>
        <TransformView
          dataset={this.props.dataset}
          transform={this.props.transform}
          onTransformChange={this.handleTransformChange}
          loadTransformValuesPreview={this.loadTransformValuesPreview}
          submit={this.props.submit}
          changeFormType={this.props.changeFormType}
          loadTransformCardPreview={this.loadTransformCardPreview}
          cancel={this.props.cancel}
          subTitles={this.subTitles}
          sqlSize={this.props.sqlSize}
          location={this.props.location}
          cardsViewState={this.props.cardsViewState}
        />
      </div>
    );
  }
}

function mapStateToProps(state) {
  const location = state.routing.locationBeforeTransitions;
  const transform = exploreUtils.getTransformState(location);
  return {
    transform,
    sqlSize: getExploreState(state).ui.get("sqlSize"),
    cardsViewState: getViewState(state, LOAD_TRANSFORM_CARDS_VIEW_ID),
    location,
  };
}

export default connect(mapStateToProps, {
  loadTransformCards,
  loadTransformCardPreview,
  loadTransformValuesPreview,
  resetViewState,
})(Transform);
