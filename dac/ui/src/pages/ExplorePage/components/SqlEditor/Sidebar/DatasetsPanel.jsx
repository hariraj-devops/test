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
import { connect } from "react-redux";
import Immutable from "immutable";
import { injectIntl } from "react-intl";

import PropTypes from "prop-types";
import { getViewState } from "selectors/resources";
import ResourceTreeContainer from "components/Tree/ResourceTreeContainer";
import exploreUtils from "#oss/utils/explore/exploreUtils";
import { compose } from "redux";
import { withCatalogARSFlag } from "@inject/utils/arsUtils";
import { getHomeSource, getSortedSources } from "#oss/selectors/home";
import { clearResourceTree } from "#oss/actions/resources/tree";
import { TreeConfigContext } from "#oss/components/Tree/treeConfigContext";
import { defaultConfigContext } from "#oss/components/Tree/treeConfigContext";

export class DatasetsPanel extends Component {
  static propTypes = {
    dataset: PropTypes.instanceOf(Immutable.Map),
    height: PropTypes.number,
    isVisible: PropTypes.bool.isRequired,
    search: PropTypes.object,
    dragType: PropTypes.string,
    addFuncToSqlEditor: PropTypes.func,
    loadSearchData: PropTypes.func,
    addFullPathToSqlEditor: PropTypes.func,
    sidebarCollapsed: PropTypes.bool,
    handleSidebarCollapse: PropTypes.func,
    viewState: PropTypes.instanceOf(Immutable.Map),
    intl: PropTypes.object.isRequired,
    insertFullPathAtCursor: PropTypes.func,
    location: PropTypes.object,
    isArsEnabled: PropTypes.bool,
    isArsLoading: PropTypes.bool,
    homeSource: PropTypes.any,
    clearResourceTree: PropTypes.func,
    isSourcesLoading: PropTypes.bool,
    handleDatasetDetails: PropTypes.func,
  };

  static contextTypes = {
    routeParams: PropTypes.object,
  };

  constructor(props) {
    super(props);
    /**
     * used for display headertabs in Datasetspanel
     * @type {Array}
     */
    this.state = {
      isCollapsable: true,
    };
  }

  // Workaround for double-fetch bug that has existed for a while when exiting->entering sql runner page
  componentWillUnmount() {
    this.props.clearResourceTree();
  }

  isDataLoading() {
    const { viewState } = this.props;
    return Boolean(
      viewState &&
        viewState.has("isInProgress") &&
        viewState.get("isInProgress"),
    );
  }

  renderContent = () => {
    const {
      dragType,
      insertFullPathAtCursor,
      location,
      handleSidebarCollapse,
      sidebarCollapsed,
      homeSource,
      isArsLoading,
      isSourcesLoading,
      handleDatasetDetails,
    } = this.props;

    if (isArsLoading || isSourcesLoading) return null; // Don't show tree until spaces filtered out and homeSource fetched
    return (
      <TreeConfigContext.Provider
        value={{
          ...defaultConfigContext,
          handleDatasetDetails: handleDatasetDetails,
          addToEditor: (path) => {
            insertFullPathAtCursor(path);
          },
        }}
      >
        <ResourceTreeContainer
          preselectedNodeId={
            // May want to eventually decouple the homeSource expansion from the preselectedNodeId
            homeSource?.get("name") || this.context.routeParams.resourceId
          }
          insertFullPathAtCursor={insertFullPathAtCursor}
          dragType={dragType}
          isSqlEditorTab={exploreUtils.isSqlEditorTab(location)}
          sidebarCollapsed={sidebarCollapsed}
          handleSidebarCollapse={handleSidebarCollapse}
          isCollapsable={this.state.isCollapsable}
          datasetsPanel={true}
          browser
          isExpandable
          shouldShowOverlay
          shouldAllowAdd
        />
      </TreeConfigContext.Provider>
    );
  };

  render() {
    return <>{this.renderContent()}</>;
  }
}

const mapStateToProps = (state, { isArsEnabled }) => ({
  isSourcesLoading:
    getViewState(state, "AllSources")?.get("isInProgress") ?? true,
  ...(isArsEnabled && {
    homeSource: getHomeSource(getSortedSources(state)),
  }),
});

export default compose(
  withCatalogARSFlag,
  connect(mapStateToProps, { clearResourceTree }),
  injectIntl,
)(DatasetsPanel);
