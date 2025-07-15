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
import { connect } from "react-redux";
import { useIntl } from "react-intl";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { compose } from "redux";
import { withRouter } from "react-router";

import { getLocation } from "selectors/routing";

import { showConfirmationDialog } from "actions/confirmation";
import { resetNewQuery } from "actions/explore/view";
import { parseResourceId } from "utils/pathUtils";

import SideNavAdmin from "dyn-load/components/SideNav/SideNavAdmin";
import SideNavExtra from "dyn-load/components/SideNav/SideNavExtra";

import ProjectActivationHOC from "@inject/containers/ProjectActivationHOC";
import {
  usePrivileges,
  useArcticCatalogPrivileges,
} from "@inject/utils/sideNavUtils";
import AccountMenu from "./AccountMenu";
import "#oss/components/IconFont/css/DremioIcons-old.css";
import "#oss/components/IconFont/css/DremioIcons.css";
import { Avatar } from "dremio-ui-lib/components";
import { nameToInitials } from "#oss/exports/utilities/nameToInitials";
import { isActive } from "./SideNavUtils";
import HelpMenu from "./HelpMenu";
import { TopAction } from "./components/TopAction";
import clsx from "clsx";
import * as PATHS from "../../exports/paths";
import CatalogsMenu from "@inject/components/SideNav/CatalogsMenu";
import { rmProjectBase } from "dremio-ui-common/utilities/projectBase.js";
import * as commonPaths from "dremio-ui-common/paths/common.js";
import * as jobPaths from "dremio-ui-common/paths/jobs.js";
import * as orgPaths from "dremio-ui-common/paths/organization.js";
import * as sqlPaths from "dremio-ui-common/paths/sqlEditor.js";
import * as adminPaths from "dremio-ui-common/paths/admin.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { getSessionContext } from "dremio-ui-common/contexts/SessionContext.js";
import { WalkthroughMenu } from "@inject/tutorials/components/WalkthroughMenu/WalkthroughMenu";
import { useDefaultContext } from "@inject/utils/queryContextUtils";
import { LeftNavPopover } from "#oss/exports/components/LeftNav/LeftNavPopover";
import {
  DremioUserAvatar,
  DremioUserTooltip,
} from "dremio-ui-common/components/DremioUser.js";
import { useQuery } from "@tanstack/react-query";
import { userByUsername } from "@inject/queries/users";
import { SonarNavChildren } from "@inject/components/SideNav/SonarNavChildren";

import "./SideNav.less";

const SideNav = (props) => {
  const {
    socketIsOpen,
    user,
    router,
    location,
    narwhalOnly,
    isProjectInactive,
    className,
    headerAction,
    actions = null,
    showOrganization = true,
  } = props;

  const organizationLanding =
    typeof getSessionContext().getOrganizationId === "function";
  const userName = user.get("userName");
  const userQuery = useQuery(userByUsername(userName));
  //urlability
  const loc = rmProjectBase(location.pathname) || "/";
  const projectId =
    router.params?.projectId || getSonarContext()?.getSelectedProjectId?.();
  usePrivileges(projectId);
  useArcticCatalogPrivileges();
  const intl = useIntl();
  const logoSVG = "corporate/dremio";
  const defaultContext = useDefaultContext(userName);

  const getNewQueryHref = () => {
    const resourceId = parseResourceId(location.pathname, defaultContext);
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { type, ...rest } = location.query;
    return sqlPaths.newQuery.link({
      resourceId,
      projectId,
      ...(rmProjectBase(location.pathname).startsWith("/new_query") && {
        ...rest,
        resourceId: rest.context,
      }),
    });
  };

  const skipToContent = (e) => {
    if (e.code === "Enter" || e.code === "Space") {
      const main = document.getElementById("main");
      if (main) {
        main.setAttribute("tabindex", "0");
        main.focus();
        main.setAttribute("tabindex", "-1");
      } else {
        const parent = document.body.querySelector(".sideNav")?.parentElement;
        if (parent?.children?.[1])
          parent.children[1].setAttribute("tabindex", "0");
        parent.children[1].setAttribute("aria-label", "Main");
        parent.children[1].focus();
        parent.children[1].setAttribute("tabindex", "-1");
      }
    }
  };

  const LogoAction = headerAction || (
    <TopAction
      url={commonPaths.projectBase.link({ projectId })}
      icon={logoSVG}
      alt="Logo"
      logo
      tooltip={false}
      socketIsOpen={socketIsOpen}
      tooltipProps={{ placement: "right" }}
      className="dremioLogoWithTextContainer"
      iconClassName="dremioLogoWithText"
    />
  );

  const renderUserAccount = () => {
    if (userQuery.data) {
      return (
        <LeftNavPopover
          tooltip={<DremioUserTooltip user={userQuery.data} />}
          mode="hover"
          role="menu"
          icon={<DremioUserAvatar user={userQuery.data} />}
          content={(ctx) => <AccountMenu {...ctx} />}
          portal={false}
          forceTooltipOnHover
        />
      );
      // Can be removed once v3 user API is functional
    } else if (userQuery.isError || (userQuery.isSuccess && !userQuery.data)) {
      return (
        <LeftNavPopover
          tooltip={userName}
          mode="hover"
          role="menu"
          icon={<Avatar initials={nameToInitials(userName)} />}
          content={(ctx) => <AccountMenu {...ctx} />}
          portal={false}
          forceTooltipOnHover
        />
      );
    } else {
      return (
        <LeftNavPopover
          tooltip={" "}
          mode="hover"
          role="menu"
          icon={
            <Avatar
              initials=""
              style={{
                background: "var(--fill--disabled)",
                color: "var(--text--disabled)",
              }}
            />
          }
          content={(ctx) => <AccountMenu {...ctx} />}
          portal={false}
          forceTooltipOnHover
        />
      );
    }
  };

  // display only the company logo
  if (narwhalOnly) {
    return (
      <div className="sideNav">
        <div className="sideNav__topSection">{LogoAction}</div>
      </div>
    );
  }

  return (
    <>
      <nav className={clsx("sideNav", className)}>
        <button
          tabIndex={0}
          aria-label={intl.formatMessage({ id: "SideNav.SkipToContent" })}
          onKeyPress={(e) => skipToContent(e)}
          className="skipToContent drop-shadow-lg"
        >
          {intl.formatMessage({ id: "SideNav.SkipToContent" })}
        </button>
        <ul className="sideNav__topSection">
          {LogoAction}
          {actions}
          {actions === null && !isProjectInactive && (
            <TopAction
              tooltipProps={{ placement: "right" }}
              active={isActive({ name: "/", dataset: true, loc })}
              url={commonPaths.projectBase.link({ projectId })}
              icon="navigation-bar/dataset"
              alt="SideNav.Datasets"
            />
          )}
          {actions === null && !isProjectInactive && (
            <>
              <TopAction
                tooltipProps={{ placement: "right" }}
                active={isActive({ name: PATHS.newQuery(), loc, sql: true })}
                url={getNewQueryHref()}
                icon="navigation-bar/sql-runner"
                alt="SideNav.NewQuery"
                data-qa="new-query-button"
              />
              <TopAction
                tooltipProps={{ placement: "right" }}
                active={isActive({ loc, jobs: true })}
                url={jobPaths.jobs.link({ projectId })}
                icon="navigation-bar/jobs"
                alt="SideNav.Jobs"
                data-qa="select-jobs"
              />
              {organizationLanding && (
                <>
                  <TopAction
                    tooltipProps={{ placement: "right" }}
                    active={isActive({ loc, admin: true })}
                    url={adminPaths.general.link({ projectId })}
                    icon="interface/settings"
                    alt="SideNav.AdminMenuProjectSetting"
                    data-qa="select-admin-settings"
                  />
                  <WalkthroughMenu />
                </>
              )}
            </>
          )}
        </ul>

        <ul className="sideNav__bottomSection">
          {!organizationLanding && <SideNavExtra />}
          {organizationLanding && (
            <LeftNavPopover
              tooltip={intl.formatMessage({ id: "SideNav.DremioServices" })}
              mode="hover"
              role="menu"
              icon={
                <dremio-icon
                  name="navigation-bar/go-to-catalogs"
                  alt=""
                ></dremio-icon>
              }
              content={(ctx) => <CatalogsMenu {...ctx} />}
              portal={false}
            />
          )}
          {organizationLanding &&
            (showOrganization ? (
              <TopAction
                tooltipProps={{ placement: "right" }}
                url={orgPaths.organization.link()}
                icon="navigation-bar/organization"
                alt="SideNav.Organization"
                data-qa="go-to-landing-page"
              />
            ) : null)}

          {!organizationLanding && <SideNavAdmin user={user} />}

          <LeftNavPopover
            tooltip={intl.formatMessage({ id: "SideNav.Help" })}
            mode="hover"
            role="menu"
            icon={<dremio-icon name="interface/help" alt=""></dremio-icon>}
            content={(ctx) => <HelpMenu {...ctx} />}
            portal={false}
          />
          {renderUserAccount()}
        </ul>
      </nav>
      {
        // Useful for rendering Modals that need to exist on every page once the user is logged in, for example
      }
      {SonarNavChildren && <SonarNavChildren />}
    </>
  );
};

SideNav.propTypes = {
  narwhalOnly: PropTypes.bool,
  location: PropTypes.object.isRequired,
  user: PropTypes.instanceOf(Immutable.Map),
  socketIsOpen: PropTypes.bool.isRequired,
  showConfirmationDialog: PropTypes.func,
  resetNewQuery: PropTypes.func,
  isProjectInactive: PropTypes.bool,
  router: PropTypes.shape({
    isActive: PropTypes.func,
    push: PropTypes.func,
    params: PropTypes.object,
  }),
  className: PropTypes.string,
  headerAction: PropTypes.any,
  actions: PropTypes.any,
  showOrganization: PropTypes.bool,
};

const mapStateToProps = (state) => {
  return {
    user: state.account.get("user"),
    socketIsOpen: state.serverStatus.get("socketIsOpen"),
    location: getLocation(state),
  };
};

const mapDispatchToProps = {
  showConfirmationDialog,
  resetNewQuery,
};

export default compose(
  withRouter,
  connect(mapStateToProps, mapDispatchToProps),
)(ProjectActivationHOC(SideNav));
