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
import clsx from "clsx";
import PropTypes from "prop-types";

import { compose } from "redux";
import { connect } from "react-redux";
import { FormattedMessage } from "react-intl";
import Message from "components/Message";

import authorize from "@inject/containers/authorize";

import settingActions, { getDefinedSettings } from "actions/resources/setting";
import { addNotification } from "actions/notification";

import { getViewState } from "selectors/resources";
import Immutable from "immutable";
import { getErrorMessage } from "#oss/reducers/resources/view";

import { description } from "uiTheme/radium/forms";
import { formContext } from "uiTheme/radium/typography";

import SettingHeader from "#oss/components/SettingHeader";
import ViewStateWrapper from "#oss/components/ViewStateWrapper";
import TextField from "#oss/components/Fields/TextField";
import ApiUtils from "#oss/utils/apiUtils/apiUtils";
import SupportAccess, {
  RESERVED as SUPPORT_ACCESS_RESERVED,
} from "@inject/pages/AdminPage/subpages/SupportAccess";
import FormUnsavedRouteLeave from "#oss/components/Forms/FormUnsavedRouteLeave";
import { Button } from "dremio-ui-lib/components";
import SettingsMicroForm from "./SettingsMicroForm";
import { LABELS, LABELS_IN_SECTIONS } from "./settingsConfig";
import InternalSupportEmail, {
  RESERVED as INTERNAL_SUPPORT_RESERVED,
} from "./InternalSupportEmail";
import { clearCachedSupportFlags } from "#oss/exports/endpoints/SupportFlags/getSupportFlag";
import DDCWrapper from "@inject/pages/AdminPage/subpages/Support/DDC/DDCWrapper";
import "./Support.less";

export const VIEW_ID = "SUPPORT_SETTINGS_VIEW_ID";

export const RESERVED = new Set([
  ...(SUPPORT_ACCESS_RESERVED || []),
  ...INTERNAL_SUPPORT_RESERVED,
]);

export class Support extends PureComponent {
  static propTypes = {
    getDefinedSettings: PropTypes.func.isRequired,
    resetSetting: PropTypes.func.isRequired,
    addNotification: PropTypes.func.isRequired,
    viewState: PropTypes.instanceOf(Immutable.Map).isRequired,
    settings: PropTypes.instanceOf(Immutable.Map).isRequired,
    setChildDirtyState: PropTypes.func,
    getSetting: PropTypes.func,
  };

  UNSAFE_componentWillMount() {
    this.props.getDefinedSettings(
      [...RESERVED, ...Object.keys(LABELS_IN_SECTIONS)],
      true,
      VIEW_ID,
    );
  }

  state = {
    getSettingInProgress: false,
    tempShown: new Immutable.OrderedSet(),
    diagnosticBanner: {
      message: undefined,
      type: undefined,
    },
  };

  renderSettingsMicroForm = (settingId, props) => {
    const formKey = "settings-" + settingId;
    return (
      <SettingsMicroForm
        updateFormDirtyState={this.props.setChildDirtyState(formKey)}
        form={formKey}
        key={formKey}
        settingId={settingId}
        viewId={VIEW_ID}
        style={{ margin: "5px 0" }}
        {...props}
      />
    );
  };

  settingExists(id) {
    return (
      this.props.settings &&
      this.props.settings.some((setting) => setting.get("id") === id)
    );
  }

  getShownSettings({ includeSections = true } = {}) {
    const ret = !this.props.settings
      ? []
      : this.props.settings
          .toList()
          .toJS()
          .filter((setting) => {
            const id = setting.id;
            if (
              !includeSections &&
              Object.prototype.hasOwnProperty.call(LABELS_IN_SECTIONS, id)
            )
              return false;
            if (RESERVED.has(id)) return false;
            return true;
          });

    return ret;
  }

  addAdvanced = async (evt) => {
    // todo: replace with generic `prompt` modal
    evt.preventDefault();

    const value = evt.target.children[0].value?.trim();
    if (!value) {
      return;
    }

    const valueEle = <span style={{ wordBreak: "break-all" }}>{value}</span>;

    if (value?.startsWith("%") && value.length < 3) {
      this.props.addNotification(
        <FormattedMessage
          id="Support.Error.InvalidSearch"
          values={{ value }}
        />,
        "error",
      );
      return;
    }

    if (this.getShownSettings().some((e) => e.id === value)) {
      this.props.addNotification(
        <span>Setting “{valueEle}” already shown.</span>, // todo: loc substitution engine
        "info",
      );
      return;
    }

    evt.persist(); // need to save an event as it used in async operation
    this.setState({
      getSettingInProgress: true,
    });
    const reduxAction = await this.props.getSetting(value);
    const payload = reduxAction.payload;
    if (ApiUtils.isApiError(payload)) {
      this.props.addNotification(
        payload.status === 404 ? (
          <span>No setting “{valueEle}”.</span> // todo: loc substitution engine
        ) : (
          getErrorMessage(reduxAction).errorMessage
        ),
        "error",
      );
    } else {
      this.setState(function (state) {
        return {
          tempShown: state.tempShown.add(value),
        };
      });
      evt.target.reset();
    }

    this.setState({
      getSettingInProgress: false,
    });
  };

  resetSetting(settingId) {
    return this.props.resetSetting(settingId).then(() => {
      // need to remove the setting from our state
      this.setState(function (state) {
        return {
          tempShown: state.tempShown.delete(settingId),
        };
      });
      return null;
    });
  }

  renderMicroForm(settingId, allowReset) {
    const formKey = "settings-" + settingId;
    return (
      <SettingsMicroForm
        updateFormDirtyState={this.props.setChildDirtyState(formKey)}
        style={{ marginTop: LABELS[settingId] !== "" ? 15 : 0 }}
        form={formKey}
        key={formKey}
        settingId={settingId}
        resetSetting={allowReset && this.resetSetting.bind(this, settingId)}
        viewId={VIEW_ID}
        beforeSubmit={clearCachedSupportFlags}
      />
    );
  }

  sortSettings(settings) {
    const orderedSet = this.state.tempShown;
    const tempShownArray = orderedSet.toArray();
    settings.sort((a, b) => {
      if (orderedSet.has(a.id)) {
        if (orderedSet.has(b.id)) {
          return tempShownArray.indexOf(b.id) - tempShownArray.indexOf(a.id);
        }
        return -1;
      } else if (orderedSet.has(b.id)) {
        return 1;
      }

      if (Object.prototype.hasOwnProperty.call(LABELS, a.id)) {
        if (Object.prototype.hasOwnProperty.call(LABELS, b.id))
          return LABELS[a.id] < LABELS[b.id] ? -1 : 1;
        return -1;
      } else if (Object.prototype.hasOwnProperty.call(LABELS, b.id)) {
        return 1;
      }

      return a.id < b.id ? -1 : 1;
    });
  }

  renderOtherSettings() {
    const settings = this.getShownSettings({ includeSections: false });
    this.sortSettings(settings);
    return settings.map((setting) => this.renderMicroForm(setting.id, true));
  }

  render() {
    // SettingsMicroForm has a logic for error display. We should not duplicate it in the viewState
    const viewStateWithoutError = this.props.viewState.set("isFailed", false);
    const advancedForm = (
      <form className="flex" onSubmit={this.addAdvanced}>
        <TextField
          placeholder={laDeprecated("Support Key")}
          data-qa="support-key-search"
        />
        <Button
          type="submit"
          variant="secondary"
          data-qa="support-key-search-btn"
          pending={this.state.getSettingInProgress}
          className="mx-1"
        >
          {laDeprecated("Show")}
        </Button>
      </form>
    );

    return (
      <div className="support-settings">
        <SettingHeader icon="settings/support">
          {laDeprecated("Support Settings")}
        </SettingHeader>
        {this.state.diagnosticBanner.type && (
          <Message
            messageType={this.state.diagnosticBanner.type}
            message={this.state.diagnosticBanner.message}
          />
        )}
        <div
          className="gutter-left--double"
          style={{ overflow: "auto", height: "100%", flex: "1 1 auto" }}
        >
          <ViewStateWrapper
            viewState={viewStateWithoutError}
            hideChildrenWhenFailed={false}
          >
            {!this.props.settings.size ? null : (
              <div>
                {SupportAccess && (
                  <SupportAccess
                    renderSettings={this.renderSettingsMicroForm}
                    descriptionStyle={styles.description}
                  />
                )}
                <DDCWrapper
                  setDiagnosticBanner={(diagnosticBanner) =>
                    this.setState({ diagnosticBanner })
                  }
                />
                <InternalSupportEmail
                  renderSettings={this.renderSettingsMicroForm}
                  descriptionStyle={styles.description}
                />
              </div>
            )}
            <div style={{ padding: "10px 0" }}>
              <div style={{ display: "flex", alignItems: "center" }}>
                <h3
                  style={{
                    flex: "1 1 auto",
                    lineHeight: "28px",
                    height: "32px",
                  }}
                  className="text-semibold text-xl"
                >
                  {laDeprecated("Support Keys")}
                </h3>
                {advancedForm}
              </div>
              <p>
                {laDeprecated("Advanced settings provided by Dremio Support.")}
              </p>
              {this.renderOtherSettings()}
            </div>
          </ViewStateWrapper>
        </div>
      </div>
    );
  }
}

function mapStateToProps(state) {
  return {
    viewState: getViewState(state, VIEW_ID),
    settings: state.resources.entities.get("setting"),
  };
}

export default compose(
  authorize("Support"),
  connect(mapStateToProps, {
    // todo: find way to auto-inject PropTypes for actions
    resetSetting: settingActions.delete.dispatch,
    getSetting: settingActions.get.dispatch,
    getDefinedSettings,
    addNotification,
  }),
  FormUnsavedRouteLeave,
)(Support);

const styles = {
  description: {
    ...description,
    margin: "5px 0",
  },
};
