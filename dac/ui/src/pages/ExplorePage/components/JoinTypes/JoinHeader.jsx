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
import Immutable from "immutable";
import { connect } from "react-redux";
import { injectIntl } from "react-intl";
import { getExploreState } from "#oss/selectors/explore";
import classNames from "clsx";

import {
  RECOMMENDED_JOIN,
  CUSTOM_JOIN,
} from "#oss/constants/explorePage/joinTabs";
import { setJoinTab, clearJoinDataset } from "actions/explore/join";
import { isNotSoftware } from "dyn-load/utils/versionUtils";

import * as classes from "./JoinHeader.module.less";

export class JoinHeader extends Component {
  static propTypes = {
    viewState: PropTypes.instanceOf(Immutable.Map).isRequired,
    hasRecommendations: PropTypes.bool,
    isRecommendationsInProgress: PropTypes.bool,
    closeIconHandler: PropTypes.func.isRequired,
    closeIcon: PropTypes.bool,
    separator: PropTypes.string,
    text: PropTypes.string,
    setJoinTab: PropTypes.func,
    joinTab: PropTypes.string,
    clearJoinDataset: PropTypes.func,
    intl: PropTypes.object.isRequired,
  };

  static contextTypes = {
    router: PropTypes.object,
  };

  constructor(props) {
    super(props);
    this.tabs = [
      {
        name: "Recommended Join",
        id: RECOMMENDED_JOIN,
      },
      {
        name: "Custom Join",
        id: CUSTOM_JOIN,
      },
    ];
  }

  getCloseIcon() {
    const handler = this.props.closeIconHandler;
    const icon = this.props.closeIcon ? (
      <dremio-icon
        name="interface/close-big"
        alt={this.props.intl.formatMessage({ id: "Common.Close" })}
        style={styles.icon}
        onClick={handler}
        class={classes["join-header-tab__closeIcon"]}
      />
    ) : null;
    return icon;
  }

  getSeparator() {
    const { separator } = this.props;
    return separator ? separator : ": ";
  }

  setActiveTab(id) {
    this.props.clearJoinDataset();
    this.props.setJoinTab(id);
  }

  isActiveTab(id) {
    return id === this.props.joinTab;
  }

  renderTabs() {
    return this.tabs.map((tab) => {
      const { hasRecommendations, viewState } = this.props;

      return (
        <h5
          data-qa={tab.name}
          className={classNames("transform-tab", classes["join-header-tab"], {
            [classes["join-header-tab--disabled"]]:
              (tab.id === RECOMMENDED_JOIN && !hasRecommendations) ||
              viewState.get("isInProgress"),
            [classes["join-header-tab--hoverable"]]:
              !viewState.get("isInProgress"),
            [classes["join-header-tab--active"]]: !!this.isActiveTab(tab.id),
          })}
          key={tab.id}
          onClick={this.setActiveTab.bind(this, tab.id)}
        >
          <span>{tab.name}</span>
        </h5>
      );
    });
  }

  render() {
    const shouldHideHeader = !isNotSoftware();

    if (shouldHideHeader && this.props.closeIcon) {
      return (
        <dremio-icon
          name="interface/close-big"
          alt={this.props.intl.formatMessage({ id: "Common.Close" })}
          style={styles.iconNoHeader}
          onClick={this.props.closeIconHandler}
          class={classNames(classes["join-header-tab__closeIcon"], "ml-1")}
        />
      );
    }

    return (
      <div className="raw-wizard-header" style={styles.base}>
        <div style={styles.content}>
          {this.props.text}
          {this.getSeparator()}
          {this.renderTabs()}
        </div>
        {this.getCloseIcon()}
      </div>
    );
  }
}
JoinHeader = injectIntl(JoinHeader);

const styles = {
  base: {
    display: "flex",
    height: 38,
    justifyContent: "space-between",
  },
  content: {
    display: "flex",
    marginLeft: 0,
    alignItems: "center",
    fontSize: 15,
    fontWeight: 600,
  },
  icon: {
    float: "right",
    margin: "8px 10px 0 0",
    position: "relative",
    width: 24,
    height: 24,
    fontSize: 18,
    cursor: "pointer",
    color: "var()",
  },
  iconNoHeader: {
    position: "absolute",
    right: 16,
    marginTop: 16,
    width: 24,
    height: 24,
    cursor: "pointer",
    zIndex: 1000,
  },
};

function mapStateToProps(state) {
  return {
    joinTab: getExploreState(state).join.get("joinTab"),
  };
}
export default connect(mapStateToProps, { setJoinTab, clearJoinDataset })(
  JoinHeader,
);
