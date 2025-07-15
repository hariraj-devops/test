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
import { Component, createRef } from "react";
import { connect } from "react-redux";
import Immutable from "immutable";
import PropTypes from "prop-types";

import { getSupportFlags } from "#oss/selectors/supportFlags";
import { ALLOW_REFLECTION_PARTITION_TRANFORMS } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import {
  getAggregationRecommendation,
  getRawRecommendation,
} from "#oss/selectors/reflectionRecommendations";

import AccelerationAggregation from "./AccelerationAggregation";
import AccelerationRaw from "./AccelerationRaw";
import "#oss/uiTheme/less/Acceleration/Acceleration.less";
import { tabsListener } from "dremio-ui-lib/components";

export class AccelerationAdvanced extends Component {
  static propTypes = {
    dataset: PropTypes.instanceOf(Immutable.Map).isRequired,
    reflections: PropTypes.instanceOf(Immutable.Map).isRequired,
    fields: PropTypes.object.isRequired,
    location: PropTypes.object.isRequired,
    updateDirtyState: PropTypes.func.isRequired,
    updateFormDirtyState: PropTypes.func.isRequired,
    values: PropTypes.object.isRequired,
    initialValues: PropTypes.any,
    canAlter: PropTypes.any,
    allowPartitionTransform: PropTypes.bool,
    rawRecommendation: PropTypes.object,
    aggregationRecommendation: PropTypes.object,
    loadingRecommendations: PropTypes.bool,
  };

  static getFields() {
    return [
      ...AccelerationAggregation.getFields(),
      ...AccelerationRaw.getFields(),
    ];
  }

  static validate(values) {
    return {
      ...AccelerationAggregation.validate(values),
      ...AccelerationRaw.validate(values),
    };
  }

  state = {
    activeTab: null,
  };

  initialReflections = null;

  constructor(props) {
    super(props);

    this.initialReflections = Immutable.fromJS({
      aggregationReflections: this.props.values.aggregationReflections,
      rawReflections: this.props.values.rawReflections,
    });

    this.tabsRef = createRef();
  }

  componentDidMount() {
    this.tabsRef.current?.addEventListener("keydown", (e) =>
      tabsListener(e, "horizontal"),
    );
  }

  componentWillUnmount() {
    this.tabsRef.current?.removeEventListener("keydown", (e) =>
      tabsListener(e, "horizontal"),
    );
  }

  componentDidUpdate(newProps) {
    const { updateDirtyState, values, initialValues } = this.props;
    const { updateFormDirtyState } = newProps;
    const aggregationReflections = Immutable.fromJS(
      values.aggregationReflections,
    );
    const rawReflections = Immutable.fromJS(values.rawReflections);
    this.initialReflections = Immutable.fromJS({
      aggregationReflections:
        initialValues === null
          ? values.aggregationReflections
          : initialValues.aggregationReflections,
      rawReflections:
        initialValues === null
          ? values.rawReflections
          : initialValues.rawReflections,
    });

    updateFormDirtyState(
      !this.areAdvancedReflectionsFieldsEqual(
        aggregationReflections,
        rawReflections,
      ),
    ); // ! is needed. Returned value of true means not dirty, but would mean to dirty to updateDirtyState

    // This updates the canSubmit state by updating the dirty state in <AccelerationForm />, do not remove.
    updateDirtyState(
      !this.areAdvancedReflectionsFieldsEqual(
        aggregationReflections,
        rawReflections,
      ),
    );
  }

  getActiveTab() {
    if (this.state.activeTab) return this.state.activeTab;

    const { layoutId } = this.props.location.state || {};
    if (!layoutId) return "RAW";

    const found = this.props.values.aggregationReflections.some(
      (reflection) => reflection.id === layoutId,
    );

    return found ? "AGGREGATION" : "RAW";
  }

  areAdvancedReflectionsFieldsEqual(aggregationReflections, rawReflections) {
    // tracks field's dirty state because of issue in redux-form
    // we need to check dirty state differently since currently we handle array fields at 1 level deep
    // because of fields data come in random order we need to sort them to check dirty state,
    // only exception is sortFields in this case we need to keep order
    const sortByName = (arr) => arr.sortBy((value) => value.get("name"));
    const areEnabledFieldEqual = (layoutGroup, layoutName) => {
      return (
        layoutGroup.get("enabled") ===
        this.initialReflections.getIn([layoutName, "enabled"])
      );
    };

    if (
      !areEnabledFieldEqual(aggregationReflections, "aggregationReflections") ||
      !areEnabledFieldEqual(rawReflections, "rawReflections")
    ) {
      return false;
    }

    const areLayoutListEqual = (layoutList, layoutListName) => {
      return !layoutList.some((layoutListValue, i) => {
        if (!this.initialReflections.getIn([layoutListName, i])) return true;

        const currentLayoutDetails = layoutListValue;
        const initialLayoutDetails = this.initialReflections.getIn([
          layoutListName,
          i,
        ]);

        return currentLayoutDetails.some((layoutDetails, layoutDetailsName) => {
          if (!Immutable.Iterable.isIterable(layoutDetails)) {
            return (
              layoutDetails !== initialLayoutDetails.get(layoutDetailsName)
            );
          }

          if (layoutDetailsName === "sortFields") {
            return !layoutDetails.equals(
              initialLayoutDetails.get(layoutDetailsName),
            );
          }

          return !sortByName(layoutDetails).equals(
            sortByName(initialLayoutDetails.get(layoutDetailsName)),
          );
        });
      });
    };

    return (
      areLayoutListEqual(aggregationReflections, "aggregationReflections") &&
      areLayoutListEqual(rawReflections, "rawReflections")
    );
  }

  renderTableQueries() {
    const {
      fields,
      reflections,
      dataset,
      canAlter,
      allowPartitionTransform,
      rawRecommendation,
      aggregationRecommendation,
      loadingRecommendations,
    } = this.props;

    return this.getActiveTab() === "AGGREGATION" ? (
      <AccelerationAggregation
        canAlter={canAlter}
        reflections={reflections}
        dataset={dataset}
        fields={fields}
        allowPartitionTransform={allowPartitionTransform}
        aggregationRecommendation={aggregationRecommendation}
        loadingRecommendations={loadingRecommendations}
      />
    ) : (
      <AccelerationRaw
        canAlter={canAlter}
        reflections={reflections}
        dataset={dataset}
        fields={fields}
        allowPartitionTransform={allowPartitionTransform}
        rawRecommendation={rawRecommendation}
        loadingRecommendations={loadingRecommendations}
      />
    );
  }

  render() {
    const activeTab = this.getActiveTab();
    return (
      <div className={"AccelerationAdvanced"} data-qa="acceleration-advanced">
        <div className={"AccelerationAdvanced__tabs"} ref={this.tabsRef}>
          <div
            className={`AccelerationAdvanced__tab ${
              activeTab === "RAW" ? "--bgColor-header" : "--bgColor-white"
            }`}
            data-qa="raw-queries-tab"
            key="raw"
            onClick={() => this.setState({ activeTab: "RAW" })}
            tabIndex={activeTab === "RAW" ? 0 : -1}
            onKeyDown={(e) => {
              if (e.code === "Enter" || e.code === "Space") {
                this.setState({ activeTab: "RAW" });
              }
            }}
          >
            {laDeprecated("Raw Reflections")}
          </div>
          <div
            className={`AccelerationAdvanced__tab ${
              activeTab === "AGGREGATION"
                ? "--bgColor-header"
                : "--bgColor-white"
            }`}
            data-qa="aggregation-queries-tab"
            key="aggregation"
            onClick={() => this.setState({ activeTab: "AGGREGATION" })}
            onKeyDown={(e) => {
              if (e.code === "Enter" || e.code === "Space") {
                this.setState({ activeTab: "AGGREGATION" });
              }
            }}
            tabIndex={activeTab === "AGGREGATION" ? 0 : -1}
          >
            {laDeprecated("Aggregation Reflections")}
          </div>
        </div>
        {this.renderTableQueries()}
      </div>
    );
  }
}

const mapStateToProps = (state) => {
  const location = state.routing.locationBeforeTransitions;
  return {
    location,
    allowPartitionTransform:
      getSupportFlags(state)[ALLOW_REFLECTION_PARTITION_TRANFORMS],
    rawRecommendation: getRawRecommendation(state),
    aggregationRecommendation: getAggregationRecommendation(state),
  };
};

export default connect(mapStateToProps)(AccelerationAdvanced);
