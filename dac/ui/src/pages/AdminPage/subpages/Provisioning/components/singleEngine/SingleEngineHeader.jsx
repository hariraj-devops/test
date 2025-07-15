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
import { injectIntl } from "react-intl";
import PropTypes from "prop-types";
import Immutable from "immutable";
import SettingHeader from "#oss/components/SettingHeader";
import { YARN_NODE_TAG_PROPERTY } from "#oss/pages/AdminPage/subpages/Provisioning/ClusterListView";
import {
  isYarn,
  getEntityName,
  getIsInReadOnlyState,
} from "#oss/pages/AdminPage/subpages/Provisioning/provisioningUtils";
import { StartStopButton } from "#oss/pages/AdminPage/subpages/Provisioning/components/EngineActionCell";
import { CLUSTER_STATE } from "#oss/constants/provisioningPage/provisioningConstants";
import SingleEngineHeaderMixin from "dyn-load/pages/AdminPage/subpages/Provisioning/components/singleEngine/SingleEngineHeaderMixin";

export const VIEW_ID = "EngineHeader";

@SingleEngineHeaderMixin
class SingleEngineHeader extends PureComponent {
  static propTypes = {
    engine: PropTypes.instanceOf(Immutable.Map),
    provisions: PropTypes.instanceOf(Immutable.List),
    unselectEngine: PropTypes.func,
    handleEdit: PropTypes.func,
    handleStartStop: PropTypes.func,
    intl: PropTypes.object,
  };
  state = {
    engineDetails: {},
  };
  componentDidMount() {
    this.loadData();
  }

  onStartStop = () => {
    const { engine, handleStartStop } = this.props;
    const nextState =
      engine.get("currentState") === CLUSTER_STATE.running
        ? CLUSTER_STATE.stopped
        : CLUSTER_STATE.running;
    handleStartStop(nextState, engine, VIEW_ID);
  };

  onEdit = () => {
    const { engine, handleEdit } = this.props;
    handleEdit(engine);
  };

  render() {
    const { engine } = this.props;
    const doubleCaretIcon = <div style={styles.doubleCaret}>»</div>;
    const statusIcon = this.getEngineStatus(engine, styles);
    const engineName = engine && getEntityName(engine, YARN_NODE_TAG_PROPERTY);
    const region =
      engine &&
      !isYarn(engine) &&
      engine.getIn(["awsProps", "connectionProps", "region"]);
    const isReadOnly = getIsInReadOnlyState(engine);

    //TODO enhancement: show spinner while start/stop inProgress
    const startStopButton = (
      <StartStopButton
        engine={engine}
        handleStartStop={this.onStartStop}
        isHeaderButton
      />
    );

    return (
      <SettingHeader
        endChildren={
          <div style={{ display: "flex" }}>
            {startStopButton} {this.renderButtons(this.onEdit, isReadOnly)}
          </div>
        }
      >
        <div style={styles.lefChildren}>
          <div
            className="link"
            role="link"
            onClick={this.props.unselectEngine}
            tabIndex={0}
            aria-label="Engine listing"
            onKeyDown={(e) => {
              if (e.code === "Space" || e.code === "Enter") {
                this.props.unselectEngine();
              }
            }}
          >
            {laDeprecated("Engines")}
          </div>
          {doubleCaretIcon} {statusIcon} {engineName}
          {region && <div style={styles.region}>({region})</div>}
        </div>
      </SettingHeader>
    );
  }
}

export default injectIntl(SingleEngineHeader);

const styles = {
  lefChildren: {
    display: "flex",
    color: "var(--text--primary)",
  },
  doubleCaret: {
    padding: "0 6px",
  },
  statusIcon: {
    marginRight: 5,
    width: 24,
    height: 24,
  },

  startStop: {
    marginRight: 10,
    marginTop: 5,
    height: 32,
    fontSize: 13,
    boxShadow: "none",
    border: "1px solid var(--border--neutral--solid)",
    outline: "none",
    backgroundColor: "var(--fill--secondary)",
    borderRadius: 4,
    width: 100,
  },
  edit: {
    width: 100,
    marginTop: 5,
  },
  region: {
    marginLeft: 10,
  },
};
