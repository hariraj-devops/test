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

import { useEffect, useState } from "react";
import { withRouter, WithRouterProps } from "react-router";
import { connect } from "react-redux";
import { compose } from "redux";
import Immutable from "immutable";
import CSS from "csstype";
import { rmProjectBase } from "dremio-ui-common/utilities/projectBase.js";
import { PageTop } from "dremio-ui-common/components/PageTop.js";
import { withEntityProps } from "dyn-load/utils/entity-utils";
import {
  HomePageTop,
  showHomePageTop,
} from "@inject/pages/HomePage/HomePageTop";
import NavCrumbs, {
  showNavCrumbs,
} from "@inject/components/NavCrumbs/NavCrumbs";
import { sourceTypesIncludeS3 } from "@inject/utils/sourceUtils";
import ProjectActivationHOC from "@inject/containers/ProjectActivationHOC";
import sessionStorageUtils from "@inject/utils/storageUtils/sessionStorageUtils";
import HomePageActivating from "@inject/pages/HomePage/HomePageActivating";
import SearchTriggerWrapper from "@inject/catalogSearch/SearchTriggerWrapper";
import { withCatalogARSFlag } from "@inject/utils/arsUtils";
import { getHomeSourceUrl, getSortedSources } from "#oss/selectors/home";
import ApiUtils from "#oss/utils/apiUtils/apiUtils";
import { loadSourceListData } from "#oss/actions/resources/sources";
import { getViewState } from "#oss/selectors/resources";
import { SonarSideNav } from "#oss/exports/components/SideNav/SonarSideNav";
import LeftTree from "#oss/pages/HomePage/components/LeftTree/LeftTree";
import { intl } from "#oss/utils/intl";
import { ErrorBoundary } from "#oss/components/ErrorBoundary/ErrorBoundary";
import QlikStateModal from "../ExplorePage/components/modals/QlikStateModal";
import { page } from "#oss/uiTheme/radium/general";
import "./HomePage.less";

type HomePageProps = {
  sources: Immutable.List<Record<string, any>>;
  sourcesViewState: Immutable.Map<string, any>;
  loadSourceListData: typeof loadSourceListData;
  children: React.ReactNode;
  isProjectInactive: boolean;
  location: WithRouterProps["location"];
  router: WithRouterProps["router"];
  homeSourceUrl: string | null;
  isArsEnabled: boolean;
};
const HomePage = (props: HomePageProps) => {
  const [sourceTypes, setSourceTypes] = useState([]);
  const {
    sources,
    sourcesViewState,
    location,
    loadSourceListData,
    children,
    isProjectInactive,
    router,
    homeSourceUrl,
    isArsEnabled,
  } = props;

  useEffect(() => {
    if (!isProjectInactive) {
      loadSourceListData();
    }
    setStateWithSourceTypesFromServer();
  }, [isProjectInactive, loadSourceListData]);

  useEffect(() => {
    if (sourcesViewState.get("invalidated")) {
      loadSourceListData();
    }
  }, [sourcesViewState, loadSourceListData]);

  useEffect(() => {
    if (isArsEnabled && homeSourceUrl) {
      const pathname =
        rmProjectBase(location.pathname, {
          projectId: router.params?.projectId,
        }) || "/";

      if (pathname === "/") {
        router.replace(homeSourceUrl);
      }
    }
  }, [isArsEnabled, homeSourceUrl, location.pathname, router]);

  const setStateWithSourceTypesFromServer = () => {
    ApiUtils.fetchJson(
      "source/type",
      (json: any) => {
        setSourceTypes(json.data);
      },
      () => {
        console.error(
          'Failed to load source types. Can not check if S3 is supported. Will not show "Add Sample Source".',
        );
      },
    );
  };

  const homePageSearchClass = showHomePageTop()
    ? " --withSearch"
    : " --withoutSearch";

  const homePageNavCrumbClass = showNavCrumbs ? " --withNavCrumbs" : "";
  const projectName = sessionStorageUtils?.getProjectName();

  return (
    <div id="home-page" style={page as CSS.Properties}>
      <div className="page-content">
        <SonarSideNav />
        {!isProjectInactive && (
          <div className={"homePageBody"}>
            <HomePageTop />
            {showNavCrumbs && (
              <PageTop>
                <NavCrumbs />
                <SearchTriggerWrapper className="ml-auto" />
              </PageTop>
            )}
            <div
              className={
                "homePageLeftTreeDiv" +
                homePageSearchClass +
                homePageNavCrumbClass
              }
            >
              <LeftTree
                homeSourceUrl={homeSourceUrl}
                sourcesViewState={sourcesViewState}
                sources={sources}
                sourceTypesIncludeS3={sourceTypesIncludeS3(sourceTypes)}
                className="col-lg-2 col-md-3"
                currentProject={projectName}
              />
              <ErrorBoundary
                title={intl.formatMessage(
                  { id: "Support.error.section" },
                  {
                    section: intl.formatMessage({
                      id: "SectionLabel.catalog",
                    }),
                  },
                )}
              >
                {children}
              </ErrorBoundary>
            </div>
          </div>
        )}
        {isProjectInactive && <HomePageActivating />}
      </div>
      <QlikStateModal />
    </div>
  );
};

const mapStateToProps = (state: Record<string, any>, props: HomePageProps) => {
  const sources = getSortedSources(state);
  const sourcesViewState = getViewState(state, "AllSources");
  const homeSourceUrl =
    sourcesViewState.get("isInProgress") == null ||
    sourcesViewState.get("isInProgress")
      ? null
      : getHomeSourceUrl(sources, props.isArsEnabled);

  return {
    sources,
    sourcesViewState,
    homeSourceUrl,
  };
};

export default compose(
  withRouter,
  withCatalogARSFlag,
  withEntityProps,
  connect(mapStateToProps, {
    loadSourceListData,
  }),
  ProjectActivationHOC,
)(HomePage);
