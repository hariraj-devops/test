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
import { connect } from "react-redux";
import Immutable from "immutable";
import PropTypes from "prop-types";
import { loadSummaryDataset } from "actions/resources/dataset";
import { getViewState } from "selectors/resources";
import { getSummaryDataset } from "selectors/datasets";
import { stopPropagation } from "#oss/utils/reactEventUtils";

import ViewStateWrapper from "components/ViewStateWrapper";
import ColumnMenuItem from "components/DragComponents/ColumnMenuItem";

import { formDescription } from "uiTheme/radium/typography";
import {
  FLEX_COL_START,
  FLEX_NOWRAP_ROW_BETWEEN_CENTER,
} from "uiTheme/radium/flexStyle";

import "./DatasetOverlayContent.less";

const VIEW_ID = "SummaryDataset";

export class DatasetOverlayContent extends PureComponent {
  static propTypes = {
    fullPath: PropTypes.instanceOf(Immutable.List),
    summaryDataset: PropTypes.instanceOf(Immutable.Map),
    loadSummaryDataset: PropTypes.func,
    placement: PropTypes.string,
    onMouseEnter: PropTypes.func,
    onMouseLeave: PropTypes.func,
    toggleIsDragInProgress: PropTypes.func,
    onClose: PropTypes.func,
    showFullPath: PropTypes.bool,
    viewState: PropTypes.instanceOf(Immutable.Map),
    typeIcon: PropTypes.string.isRequired,
    dragType: PropTypes.string,
    iconStyles: PropTypes.object,
    onRef: PropTypes.func,
    shouldAllowAdd: PropTypes.bool,
    addtoEditor: PropTypes.func,
    isStarredLimitReached: PropTypes.bool,
  };

  static defaultProps = {
    iconStyles: {},
    dragType: "",
  };

  static contextTypes = {
    username: PropTypes.string,
    location: PropTypes.object,
  };

  componentDidMount() {
    this.props.loadSummaryDataset(this.props.fullPath, VIEW_ID);
  }

  renderColumn() {
    const { summaryDataset, shouldAllowAdd, addtoEditor } = this.props;
    return summaryDataset.get("fields") && summaryDataset.get("fields").size ? (
      <div>
        {summaryDataset.get("fields").map((item, i) => {
          return (
            <ColumnMenuItem
              key={i}
              item={item}
              dragType={this.props.dragType}
              handleDragStart={this.props.toggleIsDragInProgress}
              onDragEnd={this.props.toggleIsDragInProgress}
              index={i}
              fullPath={this.props.fullPath}
              preventDrag={!this.props.dragType}
              nativeDragData={{
                type: "columnName",
                data: {
                  name: item.get("name"),
                },
              }}
              shouldAllowAdd={shouldAllowAdd}
              addtoEditor={addtoEditor}
            />
          );
        })}
      </div>
    ) : null;
  }

  render() {
    const {
      summaryDataset,
      onMouseEnter,
      onMouseLeave,
      placement,
      viewState,
      onClose,
      onRef,
    } = this.props;
    const position = placement === "right" ? placement : "";

    return (
      <div
        data-qa="dataset-detail-popup"
        ref={onRef}
        style={styles.base}
        className={`dataset-label-overlay ${position}`}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        onClick={stopPropagation}
      >
        <ViewStateWrapper
          viewState={viewState}
          onDismissError={onClose}
          overlayStyle={{
            opacity: "0.7",
            backgroundColor: "var(--fill--popover)",
          }}
        >
          {summaryDataset.size > 0 && <div>{this.renderColumn()}</div>}
        </ViewStateWrapper>
      </div>
    );
  }
}

function mapStateToProps(state, props) {
  const fullPath = props.fullPath.join(",");
  return {
    summaryDataset: getSummaryDataset(state, fullPath),
    viewState: getViewState(state, VIEW_ID),
  };
}

export default connect(mapStateToProps, { loadSummaryDataset })(
  DatasetOverlayContent,
);

const styles = {
  base: {
    display: "flex",
    flexDirection: "column",
  },
  header: {
    padding: 5,
    height: 45,
    width: 210,
    borderRight: "1px solid var(--border--neutral)",
    borderTop: "1px solid var(--border--neutral)",
    borderLeft: "1px solid var(--border--neutral)",
    borderRadius: "2px 2px 0 0",
    backgroundColor: "var(--fill--tertiary)",
    ...FLEX_NOWRAP_ROW_BETWEEN_CENTER,
  },
  attributesWrap: {
    padding: "5px 10px",
    backgroundColor: "white",
    width: 210,
    minWidth: 200,
    maxWidth: 550,
    maxHeight: 400,
    overflowY: "auto",
    borderRadius: "0 0 2px 2px",
    borderRight: "1px solid var(--border--neutral)",
    borderBottom: "1px solid var(--border--neutral)",
    borderLeft: "1px solid var(--border--neutral)",
    boxShadow: "0 0 5px 0 rgba(0, 0, 0, 0.05)",
    ...FLEX_COL_START,
  },
  attribute: {
    flexShrink: 0,
    display: "flex",
    marginBottom: 4,
  },
  attributeLabel: {
    ...formDescription,
    width: 90,
  },
  breadCrumbs: {
    overflow: "auto",
    display: "flex",
    flexDirection: "row",
    justifyContent: "flex-start",
    alignItems: "center",
  },
};
