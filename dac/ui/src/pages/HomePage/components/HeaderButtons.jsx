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
import LinkWithRef from "#oss/components/LinkWithRef/LinkWithRef";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { browserHistory } from "react-router";
import { IconButton, Popover } from "dremio-ui-lib/components";
import { ENTITY_TYPES } from "#oss/constants/Constants";
import { intl } from "#oss/utils/intl";
import HeaderButtonsMixin from "@inject/pages/HomePage/components/HeaderButtonsMixin";
import { RestrictedArea } from "#oss/components/Auth/RestrictedArea";
import localStorageUtils from "utils/storageUtils/localStorageUtils";
import HeaderButtonAddActions from "./HeaderButtonAddActions.tsx";
import { addProjectBase as wrapBackendLink } from "dremio-ui-common/utilities/projectBase.js";
import clsx from "clsx";

import * as classes from "./HeaderButtonAddActions.module.less";

@HeaderButtonsMixin
export class HeaderButtons extends Component {
  static propTypes = {
    entity: PropTypes.instanceOf(Immutable.Map),
    toggleVisibility: PropTypes.func.isRequired,
    rootEntityType: PropTypes.oneOf(Object.values(ENTITY_TYPES)),
    user: PropTypes.string,
    rightTreeVisible: PropTypes.bool,
    canUploadFile: PropTypes.bool,
    isVersionedSource: PropTypes.bool,
  };

  static defaultProps = {
    entity: Immutable.Map(),
  };

  getButtonsForEntityType(rootEntityType, entityType) {
    const { isVersionedSource } = this.props;
    if (isVersionedSource && entityType === ENTITY_TYPES.folder) {
      return this.getVersionedFolderSettingsButton();
    } else if (rootEntityType === ENTITY_TYPES.space) {
      return this.getSpaceSettingsButtons();
    } else if (rootEntityType === ENTITY_TYPES.source) {
      return this.getSourceSettingsButtons().concat(this.getSourceButtons());
    } else {
      return [];
    }
  }

  getSourceButtons() {
    const { entity, isVersionedSource } = this.props;
    const location = browserHistory.getCurrentLocation();
    const buttons = [];

    if (entity.get("isPhysicalDataset")) {
      buttons.push({
        qa: "query-folder",
        iconType: "navigation-bar/sql-runner",
        to: wrapBackendLink(entity.getIn(["links", "query"])),
      });
    } else if (
      !isVersionedSource &&
      entity.get("fileSystemFolder") &&
      (entity.getIn("permissions", "canEditFormatSettings") === true ||
        localStorageUtils.isUserAnAdmin())
    ) {
      buttons.push({
        qa: "convert-folder",
        iconType: "interface/format-folder",
        to: {
          ...location,
          state: {
            modal: "DatasetSettingsModal",
            tab: "format",
            entityName: entity.get("fullPathList").last(),
            type: entity.get("entityType"),
            entityType: entity.get("entityType"),
            entityId: entity.get("id"),
            query: { then: "query" },
            isHomePage: true,
          },
        },
      });
    }
    return buttons;
  }

  getIconAltText(iconType) {
    const messages = {
      "interface/format-folder": "Folder.FolderFormat",
      "navigation-bar/sql-runner": "Job.Query",
      "interface/settings": "Common.Settings",
    };
    const iconMessageId = messages[iconType];
    return iconMessageId ? intl.formatMessage({ id: iconMessageId }) : "Type";
  }

  renderButton = (item, index) => {
    const { qa, to, iconType, style, iconStyle, authRule } = item;
    const iconAlt = this.getIconAltText(iconType);

    let link = (
      <IconButton
        as={LinkWithRef}
        className="button-white"
        data-qa={`${qa}-button`}
        to={to ? to : "."}
        key={`${iconType}-${index}`}
        style={style}
        tooltip={iconAlt}
        tooltipPortal
        tooltipPlacement="top"
      >
        <dremio-icon name={iconType} alt={iconAlt} style={iconStyle} />
      </IconButton>
    );

    if (authRule) {
      link = (
        <RestrictedArea key={`${iconType}-${index}-${index}`} rule={authRule}>
          {link}
        </RestrictedArea>
      );
    }

    return link;
  };

  canAddInHomeSpace(rootEntityType) {
    const { canUploadFile: cloudCanUploadFile } = this.props;

    const isHome = rootEntityType === ENTITY_TYPES.home;

    // software permission for uploading a file is stored in localstorage,
    // while the permission on cloud is stored in Redux
    const canUploadFile =
      localStorageUtils.getUserPermissions()?.canUploadFile ||
      cloudCanUploadFile;

    return isHome && canUploadFile;
  }

  shouldShowAddButton(rootEntityType, entity) {
    const { isVersionedSource } = this.props;
    const showAddButton =
      [ENTITY_TYPES.home, ENTITY_TYPES.space].includes(rootEntityType) ||
      isVersionedSource;

    return showAddButton;
  }

  render() {
    const { rootEntityType, entity } = this.props;
    const location = browserHistory.getCurrentLocation();

    const buttonsForCurrentPage = this.getButtonsForEntityType(
      rootEntityType,
      entity.get("entityType"),
    );

    const shouldShowAddButton = this.shouldShowAddButton(
      rootEntityType,
      entity,
    );

    const canAddInHomeSpace = this.canAddInHomeSpace(rootEntityType);

    const hasAddMenu = shouldShowAddButton || canAddInHomeSpace;

    return (
      <>
        {hasAddMenu && (
          <>
            {rootEntityType === ENTITY_TYPES.home && canAddInHomeSpace ? (
              <Popover
                mode="click"
                role="menu"
                content={
                  <HeaderButtonAddActions canUploadFile={canAddInHomeSpace} />
                }
                placement="bottom-end"
                portal={false}
                className={clsx(
                  classes["headerButtons__popover"],
                  "drop-shadow-lg",
                )}
                dismissable
              >
                <IconButton
                  tooltip={intl.formatMessage({ id: "Common.Add" })}
                  tooltipPortal
                  tooltipPlacement="top"
                  className={classes["headerButtons__plusIcon"]}
                >
                  <dremio-icon name="interface/circle-plus"></dremio-icon>
                </IconButton>
              </Popover>
            ) : (
              <IconButton
                tooltip={intl.formatMessage({ id: "Common.NewFolder" })}
                tooltipPortal
                tooltipPlacement="top"
                onClick={() =>
                  browserHistory.push({
                    ...location,
                    state: { modal: "AddFolderModal", entity, rootEntityType },
                  })
                }
              >
                <dremio-icon name="interface/add-folder" alt="add folder" />
              </IconButton>
            )}
          </>
        )}
        {buttonsForCurrentPage.map(this.renderButton)}
      </>
    );
  }
}
