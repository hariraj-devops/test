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
import { IndexRoute, Redirect, Route } from "react-router";

import {
  CheckUserAuthentication,
  UserIsAuthenticated,
} from "#oss/components/Auth/authWrappers";

import {
  explorePageExit,
  explorePageLocationChanged,
  startExplorePageListener,
} from "#oss/actions/explore/dataset/data";
// import Votes from '@inject/pages/AdminPage/subpages/Votes'; // To Be Removed
import SSOLandingPage from "@inject/pages/AuthenticationPage/components/SSOLandingPage";
import { resetModuleState } from "#oss/actions/modulesState";
import { exploreStateKey } from "#oss/selectors/explore";
import {
  LOGIN_PATH,
  SIGNUP_PATH,
  SSO_LANDING_PATH,
} from "#oss/sagas/loginLogout";
import { AdminPageRouting, EulaRoute } from "@inject/RouteMixin";
import SSOConsent from "@inject/pages/AuthenticationPage/components/SSOConsent";
import AuthenticationPage from "@inject/pages/AuthenticationPage/AuthenticationPage";
import additionalRoutes from "@inject/additionalRoutes";
import additionalRenderedRoutes, {
  versionedSourceRoutes,
  versionedAllSourcesRoute,
} from "@inject/additionalRequiredRoutes.tsx";
import additionalRootRoutes from "@inject/additionalRootRoutes.tsx";

import JobsPage from "./pages/JobsPage/JobsPage";
import { ReflectionJobsPage } from "./pages/ReflectionJobsPage/ReflectionJobsPage";
import SingleJobPage from "#oss/pages/JobDetailsPage/JobDetailsPage";
import routeConfig from "@inject/routesConfig";
import notFoundRoute from "@inject/NotFoundRoute";

import App from "./containers/App";

import ReloadPage from "./pages/ReloadPage";

import HomeModals from "./pages/HomePage/HomeModals";
import Home from "./pages/HomePage/subpages/Home";
import { AllSpaces } from "./pages/HomePage/subpages/AllSpaces/AllSpaces";
import AllSources from "./pages/HomePage/subpages/AllSources/AllSources";

import ExploreModals from "./pages/ExplorePage/ExploreModals";

import SignupPage from "./pages/SignupPage/SignupPage";
import ServerStatusPage from "./pages/ServerStatusPage/ServerStatusPage";
import JobDetailsModals from "./pages/JobDetailsPage/JobDetailsModals";
import * as commonPaths from "dremio-ui-common/paths/common.js";
import * as jobPaths from "dremio-ui-common/paths/jobs.js";
import * as sqlPaths from "dremio-ui-common/paths/sqlEditor.js";

import Page, { MainMasterPage } from "./components/Page";
import { nessieSourceRoutes } from "./pages/NessieHomePage/NessieRoutes";
import SonarRouteComponent from "@inject/sonar/components/SonarRouteComponent";
import { RouteLeaveComponent } from "./containers/RouteLeaveComponent";
import { JobsPageProvider } from "./exports/providers/JobsPageProvider";
import { ReflectionJobsProvider } from "./exports/providers/ReflectionJobsProvider";
import { SonarPage } from "./exports/components/SonarPage";
import { QueryPage } from "./exports/pages/QueryPage";
import { ifDev } from "./exports/utilities/ifDev";

// applies global styles to all virtualized tables in the app
import "react-virtualized/styles.css";

import ExplorePage from "./pages/ExplorePage/ExplorePageController";
import { SessionRequiredWrapper } from "./SessionRequiredWrapper";
const getExploreRoute = (routeProps, dispatch) => {
  const onEnter = () => {
    dispatch(startExplorePageListener(true));
  };

  const onLeave = () => {
    // kill explore state to make sure that explore page would not be rendered with invalid state
    // DX-16117
    dispatch(resetModuleState(exploreStateKey));
    dispatch(explorePageExit());
  };

  const onChange = (prevState, newState) => {
    dispatch(explorePageLocationChanged(newState));
  };

  return (
    <Route
      {...routeProps}
      onEnter={onEnter}
      onLeave={onLeave}
      onChange={onChange}
    />
  );
};

const JobsRouting = () => (
  <Route component={Page}>
    <Route
      path={jobPaths.jobs.fullRoute()}
      component={() => (
        <JobsPageProvider>
          {(props) => <JobsPage {...props} />}
        </JobsPageProvider>
      )}
    />
    <Route
      path={jobPaths.reflection.fullRoute()}
      component={() => (
        <ReflectionJobsProvider>
          {(props) => <ReflectionJobsPage {...props} />}
        </ReflectionJobsProvider>
      )}
    />
    <Route path={jobPaths.job.fullRoute()} component={SingleJobPage} />
  </Route>
);

export default (dispatch) => {
  const isUrlabilityDisabled = commonPaths.projectBase.fullRoute() === "/";

  return (
    <Route path="/" component={App}>
      {ifDev(<Route component={SonarPage}>{QueryPage}</Route>)}
      <Route component={RouteLeaveComponent}>
        {additionalRootRoutes()}
        <Redirect
          from={commonPaths.redirect.fullRoute()}
          to={commonPaths.redirectTo.fullRoute()}
        />
        <Route path="/reload" component={ReloadPage} />
        <Route path="/sso" component={SSOLandingPage} />
        <Route path="/oauth-consent" component={SSOConsent} />
        <Route path={SSO_LANDING_PATH} component={SSOLandingPage} />
        <Route component={Page}>
          {EulaRoute()}
          <Route component={CheckUserAuthentication}>
            <Route path={LOGIN_PATH} component={AuthenticationPage} />
            {routeConfig.enableSignUp ? (
              <Route path={SIGNUP_PATH} component={SignupPage} />
            ) : (
              <Redirect from={SIGNUP_PATH} to="/" />
            )}
            <Route path="/status" component={ServerStatusPage} />
          </Route>
        </Route>
        {additionalRenderedRoutes}
        {additionalRoutes}
        <Route component={SessionRequiredWrapper}>
          <Route
            path={
              isUrlabilityDisabled
                ? undefined
                : commonPaths.projectBase.fullRoute()
            }
            component={isUrlabilityDisabled ? undefined : SonarRouteComponent}
          >
            <Route component={CheckUserAuthentication}>
              <Route component={UserIsAuthenticated(JobDetailsModals)}>
                {JobsRouting()}
              </Route>
              {AdminPageRouting()}
              <Route component={UserIsAuthenticated(HomeModals)}>
                <Route component={Page}>
                  <IndexRoute component={Home} />
                  <Redirect
                    from={commonPaths.home.fullRoute()}
                    to={commonPaths.projectBase.fullRoute()}
                  />
                  <Route path={commonPaths.source.fullRoute()} component={Home}>
                    <Route path={commonPaths.sourceFolder.fullRoute()} />
                  </Route>
                  <Route path={commonPaths.space.fullRoute()} component={Home}>
                    <Route path={commonPaths.spaceFolder.fullRoute()} />
                  </Route>
                  <Route path={commonPaths.home.fullRoute()} component={Home}>
                    <Route path={commonPaths.resource.fullRoute()} />
                  </Route>
                  <Route
                    path={commonPaths.spacesList.fullRoute()}
                    component={AllSpaces}
                  />
                  <Route
                    path={commonPaths.allSourcesList.fullRoute()}
                    component={AllSources}
                  />
                  <Route
                    path={commonPaths.objectStorage.fullRoute()}
                    component={AllSources}
                  />
                  <Route
                    path={commonPaths.metastore.fullRoute()}
                    component={AllSources}
                  />
                  <Route
                    path={commonPaths.external.fullRoute()}
                    component={AllSources}
                  />
                  <Route
                    path={commonPaths.nessie.fullRoute()}
                    component={AllSources}
                  />
                  <Route
                    path={commonPaths.lakehouse.fullRoute()}
                    component={AllSources}
                  />
                  {versionedAllSourcesRoute}
                  {nessieSourceRoutes()}
                  {versionedSourceRoutes}
                </Route>
              </Route>
              <Route component={MainMasterPage}>
                {getExploreRoute(
                  {
                    component: UserIsAuthenticated(ExploreModals),
                    children: [
                      <Route
                        key="new_query"
                        path={sqlPaths.sqlEditor.fullRoute()}
                        component={ExplorePage}
                      />,
                      <Route
                        key="existing_dataset"
                        path={sqlPaths.existingDataset.fullRoute()}
                        component={ExplorePage}
                      />,
                    ],
                  },
                  dispatch,
                )}
              </Route>
            </Route>
          </Route>
        </Route>
        {notFoundRoute}
      </Route>
    </Route>
  );
};
