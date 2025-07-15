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
import PropTypes from "prop-types";

import withFilteredSections from "@inject/pages/AdminPage/withFilteredSections";

import SettingPage from "#oss/containers/SettingPage";
import UserNavigation from "components/UserNavigation";
import { SonarSideNav } from "#oss/exports/components/SideNav/SonarSideNav";
import { getTitle } from "@inject/pages/AdminPage/navSections";
import NavCrumbs from "@inject/components/NavCrumbs/NavCrumbs";
import { PageTop } from "dremio-ui-common/components/PageTop.js";
import SearchTriggerWrapper from "@inject/catalogSearch/SearchTriggerWrapper";
import "./AdminPage.less";

class AdminPageView extends PureComponent {
  static propTypes = {
    sections: PropTypes.arrayOf(PropTypes.object),
    children: PropTypes.node,
    location: PropTypes.object.isRequired,
    style: PropTypes.object,
  };

  constructor(props) {
    super(props);
  }

  render() {
    const { location, style, children, sections } = this.props;

    const title = getTitle();

    const projectsHeaderSection =
      title !== "Settings"
        ? {
            titleObject: {
              url: "/",
              icon: "interface/circled-arrow-left",
              topTitle: "Admin.Settings.Projects",
              title,
            },
          }
        : {
            title,
          };

    return (
      <SettingPage id="admin-page" style={style}>
        <div className="page-content">
          <SonarSideNav />
          <div className="page-content-inner overflow-hidden">
            <PageTop>
              <NavCrumbs />
              <SearchTriggerWrapper className="ml-auto" />
            </PageTop>
            <div className="user-nav-container">
              <UserNavigation
                sections={sections}
                location={location}
                {...projectsHeaderSection}
              />
              <div className="main-content">{children}</div>
            </div>
          </div>
        </div>
      </SettingPage>
    );
  }
}

export default withFilteredSections(AdminPageView);
