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
import { compose } from "redux";
import { connect } from "react-redux";
import Immutable from "immutable";

import { loadSourceListData } from "actions/resources/sources";
import { loadSearchData } from "actions/search";

import SearchDatasets from "components/DatasetList/SearchDatasets";
import TabControl from "components/Tabs/TabControl";
import { getSearchResult, getViewState } from "selectors/resources";

import ResourceTreeContainer from "../Tree/ResourceTreeContainer";
import searchComponentConfig from "@inject/components/formsForAddData/searchComponentConfig";
import { withFilterTreeArs } from "#oss/utils/datasetTreeUtils";

import "./ExistingForm.less";
import {
  TreeConfigContext,
  defaultConfigContext,
} from "../Tree/treeConfigContext";

export class ExistingForm extends Component {
  static propTypes = {
    updateName: PropTypes.func,
    sourceList: PropTypes.object.isRequired,
    loadDetailInfo: PropTypes.func,
    noNameField: PropTypes.bool,
    changeSelectedNode: PropTypes.func.isRequired,
    loadSourceListData: PropTypes.func,
    loadSearchData: PropTypes.func,
    search: PropTypes.instanceOf(Immutable.List).isRequired,
    isInProgressSearch: PropTypes.instanceOf(Immutable.Map).isRequired,
    children: PropTypes.node,
    nameDataset: PropTypes.string,
    style: PropTypes.object,

    // HOC
    filterTree: PropTypes.func,
  };

  static defaultProps = {
    sourceList: {},
  };

  constructor(props) {
    super(props);
    this.search = this.search.bind(this);
    this.updateName = this.updateName.bind(this);
    this.state = {
      // TODO: Use not hardcoded value
      activeItemId: "spaces",
      inputError: null,
    };
  }

  UNSAFE_componentWillMount() {
    this.props.loadSourceListData();
    this.props.loadSearchData("");
  }

  search(text) {
    this.props.loadSearchData(text);
  }

  updateName(e) {
    this.props.updateName(e.target.value);
    if (e.target.value) {
      this.setState({ inputError: null });
    } else {
      this.setState({ inputError: "Add Spend" });
    }
  }

  render() {
    const { filterTree } = this.props;

    const ResourceTreeComponent = (
      <TreeConfigContext.Provider
        value={{
          ...defaultConfigContext,
          filterTree,
        }}
      >
        <ResourceTreeContainer
          onChange={this.props.changeSelectedNode}
          stopAtDatasets
          showFolders
          showDataSets
          showSources
          fromModal
          style={{ flex: 1, minHeight: 0, height: 210 }}
          shouldShowOverlay={false}
        />
      </TreeConfigContext.Provider>
    );

    const enterpriseTabs = Immutable.Map({
      Browse: ResourceTreeComponent,
      Search: (
        <SearchDatasets
          searchData={this.props.search}
          changeSelectedNode={this.props.changeSelectedNode}
          isInProgress={this.props.isInProgressSearch.get("isInProgress")}
          handleSearch={this.search}
          isExpandable={false}
          style={{ flex: 1 }}
        />
      ),
    });

    const cloudTabs = Immutable.Map({
      Browse: ResourceTreeComponent,
    });

    return (
      <TabControl
        tabs={
          searchComponentConfig.showSearchComponent ? enterpriseTabs : cloudTabs
        }
        style={this.props.style}
      />
    );
  }
}

function mapStateToProps(state) {
  return {
    search: getSearchResult(state) || Immutable.List(),
    isInProgressSearch: getViewState(state, "searchDatasets"),
  };
}

export default compose(
  withFilterTreeArs,
  connect(mapStateToProps, {
    loadSourceListData,
    loadSearchData,
  }),
)(ExistingForm);
