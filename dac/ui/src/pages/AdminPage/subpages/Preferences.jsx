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

import { useState, useEffect } from "react";
import { connect } from "react-redux";
import PropTypes from "prop-types";
import Immutable from "immutable";
import SettingHeader from "#oss/components/SettingHeader";
import ViewStateWrapper from "#oss/components/ViewStateWrapper";
import Toggle from "#oss/components/Fields/Toggle";
import { fetchSupportFlags, saveSupportFlag } from "#oss/actions/supportFlags";
import { FormattedMessage } from "react-intl";
import { getViewState } from "selectors/resources";
import { getSupportFlags } from "#oss/selectors/supportFlags";
import authorize from "@inject/containers/authorize";
import config from "@inject/utils/config";
import { isEnterprise, isCommunity } from "dyn-load/utils/versionUtils";
import { compose } from "redux";
import {
  ALLOW_DOWNLOAD,
  NEW_DATASET_NAVIGATION,
  RERUN_QUERY_ON_DOWNLOAD,
} from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import { getSupportFlag } from "#oss/exports/endpoints/SupportFlags/getSupportFlag";
import { isNotSoftware } from "dyn-load/utils/versionUtils";

import "./Preferences.less";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import { renderExtraPreferences } from "@inject/utils/preferences";

export const VIEW_ID = "SUPPORT_SETTINGS_VIEW_ID";

const Preferences = (props) => {
  const {
    dispatchFetchSupportFlags,
    viewState,
    dispatchSaveSupportFlag,
    supportFlags,
  } = props;
  const { t } = getIntlContext();
  const viewStateWithoutError = viewState.set("isFailed", false);

  const [isAutocompleteEnabled, setAutocompleteIsEnabled] = useState(
    supportFlags?.["ui.autocomplete.allow"] || config.autoComplete,
  );

  const [isDownloadEnabled, setDownloadIsEnabled] = useState(
    supportFlags?.[ALLOW_DOWNLOAD] || config.allowDownload,
  );

  const [isQueryDatasetEnabled, setIsQueryDatasetEnabled] = useState(
    supportFlags?.[NEW_DATASET_NAVIGATION] || config.useNewDatasetNavigation,
  );

  const [isRerunQueryEnabled, setIsRerunQueryEnabled] = useState(
    supportFlags?.[RERUN_QUERY_ON_DOWNLOAD] || true,
  );

  useEffect(() => {
    dispatchFetchSupportFlags("ui.autocomplete.allow").then((res) => {
      setAutocompleteIsEnabled(res.payload.value);
    });

    dispatchFetchSupportFlags(NEW_DATASET_NAVIGATION).then((res) => {
      setIsQueryDatasetEnabled(res.payload.value);
    });
  }, [dispatchFetchSupportFlags]);

  useEffect(() => {
    async function doFetch() {
      try {
        const res = await getSupportFlag(ALLOW_DOWNLOAD);
        setDownloadIsEnabled(res.value);

        const rerunQuery = await getSupportFlag(RERUN_QUERY_ON_DOWNLOAD);
        setIsRerunQueryEnabled(rerunQuery.value);
      } catch (e) {
        //
      }
    }
    doFetch();
  }, []);

  useEffect(() => {
    const isEnterpriseFlag = isEnterprise && isEnterprise();
    const isCommunityFlag = isCommunity && isCommunity();

    if (isEnterpriseFlag || isCommunityFlag) {
      return;
    }

    if (supportFlags?.["ui.autocomplete.allow"] !== undefined) {
      setAutocompleteIsEnabled(supportFlags["ui.autocomplete.allow"]);
    }

    if (supportFlags?.[ALLOW_DOWNLOAD] !== undefined) {
      setDownloadIsEnabled(supportFlags[ALLOW_DOWNLOAD]);
    }

    if (supportFlags?.[NEW_DATASET_NAVIGATION] !== undefined) {
      setIsQueryDatasetEnabled(supportFlags[NEW_DATASET_NAVIGATION]);
    }

    if (supportFlags?.[RERUN_QUERY_ON_DOWNLOAD] !== undefined) {
      setIsRerunQueryEnabled(supportFlags[RERUN_QUERY_ON_DOWNLOAD]);
    }
  }, [supportFlags]);

  const handleAutocompleteChange = () => {
    const saveObj = {
      type: "BOOLEAN",
      id: "ui.autocomplete.allow",
      value: !isAutocompleteEnabled,
    };
    dispatchSaveSupportFlag("ui.autocomplete.allow", saveObj);
    setAutocompleteIsEnabled(!isAutocompleteEnabled);
  };

  const handleDownloadChange = () => {
    const saveObj = {
      type: "BOOLEAN",
      id: ALLOW_DOWNLOAD,
      value: !isDownloadEnabled,
    };
    dispatchSaveSupportFlag(ALLOW_DOWNLOAD, saveObj);
    setDownloadIsEnabled(!isDownloadEnabled);
  };

  const handleQueryDatasetChange = () => {
    const saveObj = {
      type: "BOOLEAN",
      id: NEW_DATASET_NAVIGATION,
      value: !isQueryDatasetEnabled,
    };
    dispatchSaveSupportFlag(NEW_DATASET_NAVIGATION, saveObj);
    setIsQueryDatasetEnabled(!isQueryDatasetEnabled);
  };

  const handleRerunQueryChange = () => {
    const saveObj = {
      type: "BOOLEAN",
      id: RERUN_QUERY_ON_DOWNLOAD,
      value: !isRerunQueryEnabled,
    };
    dispatchSaveSupportFlag(RERUN_QUERY_ON_DOWNLOAD, saveObj);
    setIsRerunQueryEnabled(!isRerunQueryEnabled);
  };

  return (
    <div className="admin-preferences-settings-main">
      <SettingHeader icon="settings/preferences">
        <FormattedMessage id="Admin.Preferences.Title" />
      </SettingHeader>
      <div className="gutter-left--double">
        <div className="preferences-settings-page-description">
          <FormattedMessage id="Admin.Preferences.PageDescription" />
        </div>
        <ViewStateWrapper
          viewState={viewStateWithoutError}
          hideChildrenWhenFailed={false}
          style={{ overflow: "auto", height: "100%", flex: "1 1 auto" }}
        >
          <div className="preferences-settings-button-section">
            <div className="preferences-settings-button-name">
              <span>
                <FormattedMessage id="Admin.Preferences.AutocompleteActionName" />
              </span>
            </div>
            <div>
              <Toggle
                value={isAutocompleteEnabled}
                onChange={handleAutocompleteChange}
                data-qa="autocomplete-toggle"
              />
            </div>
          </div>
          <div className="preferences-settings-description">
            <span>
              <FormattedMessage id="Admin.Preferences.AutocompleteDescription" />
            </span>
          </div>
          <hr className="setting-body-preferences-hr" />
          <div className="preferences-settings-button-section">
            <div className="preferences-settings-button-name">
              <span>
                <FormattedMessage id="Admin.Preferences.DownloadActionName" />
              </span>
            </div>
            <div>
              <Toggle
                value={isDownloadEnabled}
                onChange={handleDownloadChange}
              />
            </div>
          </div>
          <div className="preferences-settings-description">
            <span>
              <FormattedMessage id="Admin.Preferences.DownloadDescription" />
            </span>
          </div>
          {!isNotSoftware() && (
            <>
              <hr className="setting-body-preferences-hr" />
              <div className="preferences-settings-button-section">
                <div className="preferences-settings-button-name">
                  <span>
                    <FormattedMessage id="Admin.Preferences.RerunQuery" />
                  </span>
                </div>
                <div>
                  <Toggle
                    value={!isRerunQueryEnabled}
                    onChange={handleRerunQueryChange}
                  />
                </div>
              </div>
              <div className="preferences-settings-description">
                <span>
                  <FormattedMessage id="Admin.Preferences.RerunQueryDescription" />
                </span>
              </div>
            </>
          )}
          <hr className="setting-body-preferences-hr" />
          <div className="preferences-settings-button-section">
            <div className="preferences-settings-button-name">
              <span>
                <FormattedMessage id="Admin.Preferences.QueryDatasetActionName" />
              </span>
            </div>
            <div>
              <Toggle
                value={isQueryDatasetEnabled}
                onChange={handleQueryDatasetChange}
              />
            </div>
          </div>
          <div className="preferences-settings-description">
            <span>
              <FormattedMessage id="Admin.Preferences.QueryDatasetDescription" />
            </span>
          </div>
          {renderExtraPreferences()}
        </ViewStateWrapper>
      </div>
    </div>
  );
};

const mapStateToProps = (state) => {
  return {
    viewState: getViewState(state, VIEW_ID),
    supportFlags: getSupportFlags(state),
  };
};

const mapDispatchToProps = {
  dispatchFetchSupportFlags: fetchSupportFlags,
  dispatchSaveSupportFlag: saveSupportFlag,
};

Preferences.propTypes = {
  dispatchFetchSupportFlags: PropTypes.func,
  dispatchSaveSupportFlag: PropTypes.func,
  viewState: PropTypes.instanceOf(Immutable.Map).isRequired,
  supportFlags: PropTypes.object,
};

export default compose(
  authorize("Preferences"),
  connect(mapStateToProps, mapDispatchToProps),
)(Preferences);
