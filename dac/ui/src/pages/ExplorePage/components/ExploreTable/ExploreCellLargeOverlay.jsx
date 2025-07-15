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
import { Component, createRef, Fragment } from "react";
import { compose } from "redux";
import { connect } from "react-redux";
import PropTypes from "prop-types";
import { Overlay } from "react-overlays";
import Immutable from "immutable";
import $ from "jquery";

import {
  FLEX_COL_START,
  LINE_NOWRAP_ROW_BETWEEN_CENTER,
} from "uiTheme/radium/flexStyle";
import { fixedWidthSmall } from "uiTheme/radium/typography";
import EllipsedText from "components/EllipsedText";
import { MAP, TEXT, LIST, STRUCT } from "#oss/constants/DataTypes";
import exploreUtils from "utils/explore/exploreUtils";
import dataFormatUtils from "utils/dataFormatUtils";
import ViewStateWrapper from "components/ViewStateWrapper";
import { withLocation } from "containers/dremioLocation";
import { moduleStateHOC } from "#oss/containers/ModuleStateContainer";
import exploreFullCell, {
  moduleKey,
  getCell,
} from "#oss/reducers/modules/exploreFullCell";
import { KeyChangeTrigger } from "#oss/components/KeyChangeTrigger";

import {
  loadFullCellValue,
  clearFullCellValue,
} from "actions/explore/dataset/data";
import CellPopover from "./CellPopover";
import "./ExploreCellLargeOverlay.less";
import { IconButton } from "dremio-ui-lib/components";

const mapStateToProps = (state) => ({
  fullCell: getCell(state),
});

const mapDispatchToProps = {
  loadFullCellValue,
  clearFullCellValue,
};

export class ExploreCellLargeOverlayView extends Component {
  static propTypes = {
    // If type of column is List or map, cellValue should be an object.
    // In other cases it should be a string
    cellValue: PropTypes.any,
    anchor: PropTypes.instanceOf(Element).isRequired,
    isTruncatedValue: PropTypes.bool,
    columnType: PropTypes.string,
    columnName: PropTypes.string,
    valueUrl: PropTypes.string,
    fullCell: PropTypes.instanceOf(Immutable.Map),
    fullCellViewState: PropTypes.instanceOf(Immutable.Map),
    hide: PropTypes.func.isRequired,
    loadFullCellValue: PropTypes.func,
    clearFullCellValue: PropTypes.func,
    onSelect: PropTypes.func,
    openPopover: PropTypes.bool,
    selectAll: PropTypes.func,
    isDumbTable: PropTypes.bool,
    location: PropTypes.object,
    style: PropTypes.object,
  };

  static contextTypes = {
    router: PropTypes.object,
  };

  state = {};

  contentRef = createRef();

  loadCellData = (href) => {
    if (href) {
      this.props.loadFullCellValue({ href });
    }
  };

  componentDidMount() {
    // we require this line to prevent hover on + icon in tree, because we moveout this icon from tree block
    $(".Object-node").on("mouseenter", "div", this.onNodeMouseEnter);
    $("li").on("mouseleave", ".Object-node", this.clearCurrentPath);
  }

  componentWillUnmount() {
    if (this.props.clearFullCellValue) {
      this.props.clearFullCellValue();
    }
    this.props.hide();
    $(".Object-node").off("mouseenter", "div", this.onNodeMouseEnter);
    $("li").off("mouseleave", ".Object-node", this.clearCurrentPath);
  }

  onNodeMouseEnter = (e) => {
    e.stopPropagation();
  };

  onMouseUp = () => {
    if (!this.props.onSelect) {
      return;
    }
    const { columnType, columnName, location, isDumbTable } = this.props;
    const selection = exploreUtils.getSelectionData(window.getSelection());
    if (selection && selection.text && !isDumbTable) {
      const columnText = selection.oRange.startContainer.data;
      if (columnType !== TEXT) {
        this.handleSelectAll();
      } else {
        const data = exploreUtils.getSelection(
          columnText,
          columnName,
          selection,
        );
        this.context.router.push({
          ...location,
          state: {
            ...location.state,
            columnName,
            columnType,
            selection: Immutable.fromJS({ ...data.model, columnName }),
          },
        });
        this.props.onSelect({ ...data.position, columnType });
      }
      window.getSelection().removeAllRanges();
    }
  };

  onCurrentPathChange = (currentPath) => {
    this.setState({
      currentPath: this.getBeautyPath(currentPath),
    });
  };

  getBeautyPath(path) {
    const parts = path.split(".");
    return parts.reduce((prevPart, part) =>
      $.isNumeric(part) ? `${prevPart}[${part}]` : `${prevPart}.${part}`,
    );
  }

  getCellValue({ cellValue, fullCell, valueUrl, columnType }) {
    return valueUrl
      ? fullCell.get("value")
      : dataFormatUtils.formatValue(cellValue, columnType);
  }

  showOverlay(props) {
    const { anchor } = props;
    const cellValue = this.getCellValue(props);
    return Boolean(anchor && cellValue);
  }

  handleSelectMenuVisibleChange = (state) => {
    this.setState({
      preventHovers: state,
    });
  };

  clearCurrentPath = (e) => {
    if (this.state.preventHovers) {
      e.stopPropagation();
    } else {
      this.setState({ currentPath: "" });
    }
  };

  handleSelectAll = () => {
    const { columnType, columnName } = this.props;
    const cellValue = this.getCellValue(this.props);
    this.props.selectAll(
      this.contentRef.current,
      columnType,
      columnName,
      cellValue,
    );
  };

  renderContent() {
    const { columnType, columnName, location, hide } = this.props;
    const cellValue = this.getCellValue(this.props);

    if (columnType === MAP || columnType === LIST || columnType === STRUCT) {
      return (
        <CellPopover
          availibleActions={["extract"]}
          hide={hide}
          onCurrentPathChange={this.onCurrentPathChange}
          cellPopover={Immutable.fromJS({ columnName, columnType })}
          data={dataFormatUtils.formatValue(cellValue, columnType)}
          location={location}
          hideCellPopover={hide}
          openPopover={this.state.openPopover}
          isDumbTable={this.props.isDumbTable}
          onSelectMenuVisibleChange={this.handleSelectMenuVisibleChange}
        />
      );
    }
    const style = { minHeight: 20 };
    if (columnType === "TEXT") {
      style.whiteSpace = "pre-wrap"; // todo: have to make copy also preserve
    }
    return (
      <div
        style={{ ...styles.content, ...style }}
        onMouseUp={this.onMouseUp}
        data-qa="cell-content"
      >
        <span ref={this.contentRef}>{cellValue}</span>
      </div>
    );
  }

  render() {
    const { columnType, fullCell, anchor, valueUrl, style } = this.props;
    return (
      <Fragment>
        <KeyChangeTrigger keyValue={valueUrl} onChange={this.loadCellData} />
        <Overlay
          show={this.showOverlay(this.props)}
          target={anchor}
          flip
          onHide={this.props.hide}
          container={document.body}
          placement="top"
          rootClose={
            columnType !== MAP && columnType !== LIST && columnType !== STRUCT
          }
        >
          {({ props: overlayProps, arrowProps, placement }) => {
            const pointerStyle =
              placement === "top" ? { bottom: 0 } : { top: 0 };
            const overlayStyle =
              placement === "top" ? styles.top : styles.bottom;

            return (
              <div
                style={{
                  ...styles.overlay,
                  ...(overlayProps.style || {}),
                  ...overlayStyle,
                }}
                className={`large-overlay ${placement}`}
                ref={overlayProps.ref}
              >
                <div
                  style={{
                    ...styles.pointer,
                    ...pointerStyle,
                    ...arrowProps.style,
                  }}
                  ref={arrowProps.ref}
                >
                  <div className="pointer-bottom"></div>
                  <div className="pointer-top"></div>
                </div>
                {this.renderHeader()}
                <ViewStateWrapper viewState={fullCell} style={style}>
                  {this.renderContent()}
                </ViewStateWrapper>
              </div>
            );
          }}
        </Overlay>
      </Fragment>
    );
  }

  renderHeader() {
    const { columnType, onSelect, hide, isTruncatedValue, style } = this.props;

    return (
      <div style={{ ...styles.header, ...style }}>
        {isTruncatedValue && (
          <span style={styles.infoMessage}>
            Values are truncated for preview
          </span>
        )}
        <div style={styles.path}>
          {onSelect &&
          columnType !== MAP &&
          columnType !== LIST &&
          columnType !== STRUCT ? (
            <span onClick={this.handleSelectAll} style={{ cursor: "pointer" }}>
              {laDeprecated("Select all")}
            </span>
          ) : (
            <EllipsedText text={this.state.currentPath} />
          )}
        </div>
        <IconButton aria-label="Close" onClick={hide}>
          <dremio-icon
            name="interface/close-small"
            style={{
              inlineSize: 16,
              blockSize: 16,
            }}
          ></dremio-icon>
        </IconButton>
      </div>
    );
  }
}

export default compose(
  moduleStateHOC(moduleKey, exploreFullCell),
  withLocation,
  connect(mapStateToProps, mapDispatchToProps),
)(ExploreCellLargeOverlayView);

const styles = {
  pointer: {
    position: "absolute",
  },
  overlay: {
    maxHeight: 450,
    width: 400,
    marginLeft: -15,
    border: "1px solid var(--border--neutral)",
    boxShadow: "0px 0px 5px 0px rgba(0,0,0,0.05)",
    borderRadius: 2,
    position: "absolute",
    zIndex: 1000,
    backgroundColor: "var(--fill--primary)",
    ...FLEX_COL_START,
  },
  top: {
    marginTop: -4,
  },
  bottom: {
    marginTop: 4,
  },
  path: {
    display: "inline-block",
    flexGrow: 1,
    minWidth: 0,
    color: "var(--text--brand)",
  },
  header: {
    // todo: fix styling/element type for consistent UX
    width: "100%",
    ...LINE_NOWRAP_ROW_BETWEEN_CENTER,
    backgroundColor: "var(--fill--tertiary)",
    paddingLeft: 5,
    height: 24,
  },
  infoMessage: {
    fontStyle: "italic",
  },
  content: {
    maxHeight: 250,
    ...fixedWidthSmall,
    backgroundColor: "var(--fill--primary)",
    padding: "10px 5px",
    overflowX: "auto",
  },
};
