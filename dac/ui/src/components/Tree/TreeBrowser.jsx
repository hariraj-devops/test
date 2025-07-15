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
import clsx from "clsx";
import { useState, useMemo, useEffect } from "react";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { connect } from "react-redux";

import { intl } from "#oss/utils/intl";
import SubHeaderTabs from "#oss/components/SubHeaderTabs";
import { getLocation } from "#oss/selectors/routing";
import SearchDatasetsPopover from "../DatasetList/SearchDatasetsPopover";
import {
  RESOURCE_LIST_SORT_MENU,
  DATA_SCRIPT_TABS,
  starTabNames,
} from "#oss/components/Tree/resourceTreeUtils";
import SortDropDownMenu from "#oss/components/SortDropDownMenu";
import SQLScripts from "../SQLScripts/SQLScripts";
import TreeNode from "./TreeNode";
import { TabsNavigationItem } from "dremio-ui-lib";
import { useFilterTreeArs } from "#oss/utils/datasetTreeUtils";
import { getHomeSource, getSortedSources } from "#oss/selectors/home";
import { useIsArsEnabled } from "@inject/utils/arsUtils";
import { useMultiTabIsEnabled } from "../SQLScripts/useMultiTabIsEnabled";
import { isTabbableUrl } from "#oss/utils/explorePageTypeUtils";
import { showNavCrumbs } from "@inject/components/NavCrumbs/NavCrumbs";
import { CatalogTreeContainer } from "./CatalogTreeContainer";
import { IconButton, TabsWrapper } from "dremio-ui-lib/components";
import { useQuery } from "@tanstack/react-query";
import { starredResourcesQuery } from "@inject/queries/stars";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";

import * as classes from "./TreeBrowser.less";
import "./TreeBrowser.less";

export const TreeBrowser = (props) => {
  const {
    location,
    sidebarCollapsed,
    isCollapsable,
    resourceTree,
    isSqlEditorTab,
    starredItems,
    starNode,
    unstarNode,
    changeStarredTab,
    selectedStarredTab,
    homeSource: arsHomeSource,
  } = props;

  const isTabsRendered = useMultiTabIsEnabled() && isTabbableUrl(location);

  const [selectedTab, setSelectedTab] = useState(DATA_SCRIPT_TABS.Data);
  const [sort, setSort] = useState(RESOURCE_LIST_SORT_MENU[1]);

  const [collapseText, setCollapseText] = useState();
  const [starredTabsArray, setStarredTabsArray] = useState([
    intl.formatMessage({ id: "Resource.Tree.All" }),
  ]);

  const [isArsLoading, isArsEnabled] = useIsArsEnabled();

  const isNewTreeEnabled = false;

  const starredResources = useQuery({
    ...starredResourcesQuery(getSonarContext().getSelectedProjectId?.()),
    enabled: isNewTreeEnabled,
  }).data;

  useEffect(() => {
    if (isArsLoading || isArsEnabled) return;

    setStarredTabsArray([
      intl.formatMessage({ id: "Resource.Tree.All" }),
      intl.formatMessage({ id: "Resource.Tree.Starred" }) +
        ` (${isNewTreeEnabled ? (starredResources?.size ?? 0) : starredItems?.length})`,
    ]);
  }, [
    isArsEnabled,
    isNewTreeEnabled,
    starredResources?.size,
    starredItems,
    isArsLoading,
  ]);

  useEffect(() => {
    if (location && location.state && location.state.renderScriptTab) {
      setSelectedTab(DATA_SCRIPT_TABS.Scripts);
    }
  }, [location, setSelectedTab]);

  useEffect(() => {
    if (!isSqlEditorTab) {
      setSelectedTab(DATA_SCRIPT_TABS.Data);
    }
  }, [isSqlEditorTab, setSelectedTab]);

  useEffect(() => {
    setCollapseText(
      sidebarCollapsed
        ? intl.formatMessage({ id: "Explore.Left.Panel.Collapse.Text.Close" })
        : intl.formatMessage({ id: "Explore.Left.Panel.Collapse.Text.Open" }),
    );
  }, [sidebarCollapsed]);

  const treeFilterFunc = useFilterTreeArs();

  const [homeSource, sortedTree] = useMemo(() => {
    let tempResourceTree = resourceTree;
    if (isArsLoading) tempResourceTree = Immutable.fromJS([]); //Don't render while loading

    if (isArsEnabled && treeFilterFunc) {
      const sorted = treeFilterFunc(resourceTree);
      tempResourceTree = sorted;
    }

    const tempHomeSource = Array.from(tempResourceTree)[0];

    const hasHomeNode =
      tempHomeSource &&
      (tempHomeSource.get("type") === "HOME" ||
        (arsHomeSource &&
          tempHomeSource.get("name") === arsHomeSource.get("name")));

    // Remove home item for new copied list
    const tempOtherSources = hasHomeNode
      ? Array.from(tempResourceTree).splice(1)
      : Array.from(tempResourceTree);

    const tempSortedTree = tempOtherSources.sort(sort.compare);

    return hasHomeNode
      ? [tempHomeSource, tempSortedTree]
      : [undefined, tempSortedTree];
  }, [
    resourceTree,
    sort,
    isArsEnabled,
    isArsLoading,
    treeFilterFunc,
    arsHomeSource,
  ]);

  const renderSubHeadingTabs = () => {
    return (
      selectedTab === DATA_SCRIPT_TABS.Data && (
        <div className="TreeBrowser__subHeading">
          <SubHeaderTabs
            onClickFunc={changeStarredTab}
            tabArray={starredTabsArray}
            selectedTab={selectedStarredTab}
          />
          <SortDropDownMenu
            menuList={RESOURCE_LIST_SORT_MENU}
            sortValue={sort}
            setSortValue={setSort}
          />
        </div>
      )
    );
  };

  const renderHome = () => {
    if (homeSource) {
      return (
        <TreeNode
          node={homeSource}
          key={0}
          isStarredLimitReached={starredItems.length === 25}
          {...props}
        />
      );
    }
  };

  const renderItems = () => {
    if (sortedTree.length > 0) {
      return sortedTree.map((currNode, index) => {
        return (
          <TreeNode
            node={currNode}
            key={index}
            isStarredLimitReached={starredItems.length === 25}
            isSqlEditorTab={isSqlEditorTab}
            selectedStarredTab={selectedStarredTab}
            {...props}
          />
        );
      });
    } else if (
      homeSource === undefined &&
      sortedTree.length === 0 &&
      selectedStarredTab === starTabNames.starred
    ) {
      return (
        <span className="TreeBrowser--empty">
          {intl.formatMessage({ id: "Resource.Tree.No.Stars" })}
        </span>
      );
    }
  };

  const renderTabs = () => {
    return props.isSqlEditorTab ? (
      <TabsWrapper className="flex items-center">
        <TabsNavigationItem
          name="Data"
          activeTab={selectedTab}
          onClick={() => setSelectedTab(DATA_SCRIPT_TABS.Data)}
        >
          {intl.formatMessage({ id: "Dataset.Data" })}
        </TabsNavigationItem>
        <TabsNavigationItem
          name="Scripts"
          activeTab={selectedTab}
          onClick={() => setSelectedTab(DATA_SCRIPT_TABS.Scripts)}
        >
          {intl.formatMessage({ id: "Common.Scripts" })}
        </TabsNavigationItem>
      </TabsWrapper>
    ) : (
      <div className="TreeBrowser-tab">Data</div>
    );
  };

  const renderCollapseIcon = () => {
    return (
      <IconButton
        onClick={props.handleSidebarCollapse}
        tooltip={collapseText || "collapse"}
        tooltipPortal
        className={classes["collapseButton"]}
      >
        <dremio-icon
          alt={intl.formatMessage({
            id: "Explore.Left.Panel.Collapse.Alt",
          })}
          name={
            sidebarCollapsed ? "scripts/CollapseRight" : "scripts/CollapseLeft"
          }
        ></dremio-icon>
      </IconButton>
    );
  };

  const renderTabsContent = () => {
    if (selectedTab === DATA_SCRIPT_TABS.Data) {
      return (
        <>
          {renderSubHeadingTabs()}
          <SearchDatasetsPopover
            changeSelectedNode={() => {}}
            dragType={props.dragType}
            addtoEditor={props.addtoEditor}
            shouldAllowAdd
            isStarredLimitReached={
              isNewTreeEnabled
                ? starredResources?.size === 25
                : starredItems.length === 25
            }
            starNode={starNode}
            starredItems={
              isNewTreeEnabled
                ? Array.from(starredResources ?? new Set())
                : starredItems
            }
            unstarNode={unstarNode}
          />
          <CatalogTreeContainer
            sort={sort.dir}
            starsOnly={selectedStarredTab === starTabNames.starred}
            oldRenderHome={renderHome}
            oldRenderItems={renderItems}
          />
        </>
      );
    } else if (selectedTab === DATA_SCRIPT_TABS.Scripts) {
      return <SQLScripts />;
    }
  };

  return (
    <div
      className={clsx("TreeBrowser", {
        "--withTabs": isTabsRendered,
        "--withBreadcrumbs": showNavCrumbs,
      })}
    >
      <div
        className={`TreeBrowser-heading ${!isSqlEditorTab ? "--dataset" : ""} ${
          sidebarCollapsed ? "--collapsed" : ""
        }`}
      >
        {!sidebarCollapsed && renderTabs()}
        {isCollapsable && renderCollapseIcon()}
      </div>
      {!sidebarCollapsed && renderTabsContent()}
    </div>
  );
};

TreeBrowser.propTypes = {
  resourceTree: PropTypes.instanceOf(Immutable.List),
  isNodeExpanded: PropTypes.func,
  selectedNodeId: PropTypes.string,
  addtoEditor: PropTypes.func,
  dragType: PropTypes.string,
  isSqlEditorTab: PropTypes.bool,
  location: PropTypes.object,
  handleSidebarCollapse: PropTypes.func,
  sidebarCollapsed: PropTypes.bool,
  isCollapsable: PropTypes.bool,
  formatIdFromNode: PropTypes.func,
  isDatasetsDisabled: PropTypes.bool,
  isSourcesHidden: PropTypes.bool,
  shouldAllowAdd: PropTypes.bool,
  shouldShowOverlay: PropTypes.bool,
  handleSelectedNodeChange: PropTypes.func,
  isNodeExpandable: PropTypes.func,
  isExpandable: PropTypes.bool,
  starredItems: PropTypes.array,
  starNode: PropTypes.func,
  unstarNode: PropTypes.func,
  changeStarredTab: PropTypes.func,
  selectedStarredTab: PropTypes.string,
  homeSource: PropTypes.object,
};

TreeBrowser.defaultProps = {
  resourceTree: Immutable.List(),
};

TreeBrowser.contextTypes = {
  loggedInUser: PropTypes.object,
};

const mapStateToProps = (state) => ({
  homeSource: getHomeSource(getSortedSources(state)),
  location: getLocation(state),
});

export default connect(mapStateToProps)(TreeBrowser);
