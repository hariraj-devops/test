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
import { compose } from "redux";
import PropTypes from "prop-types";
import Immutable from "immutable";
import classNames from "clsx";
import { injectIntl } from "react-intl";

import { DragAreaColumnWithMixin } from "@inject/components/DragComponents/DragAreaColumnMixin";
import { SelectView } from "#oss/components/Fields/SelectView";
import { SearchField } from "components/Fields";
import ColumnDragItem from "utils/ColumnDragItem";
import EllipsedText from "components/EllipsedText";

import { formDefault } from "uiTheme/radium/typography";
import { typeToIconType } from "#oss/constants/DataTypes";
import { FLEX_ROW_START_CENTER } from "uiTheme/radium/flexStyle";
import {
  base,
  content,
  column as columnCls,
  disabledContent,
  preventDrag as disabledColumnCls,
  columnItem as columnItemCls,
} from "#oss/uiTheme/less/DragComponents/DragAreaColumn.less";

import DragSource from "./DragSource";
import DragTarget from "./DragTarget";
import { NOT_SUPPORTED_TYPES } from "./DragColumnMenu";

export class DragAreaColumn extends Component {
  static propTypes = {
    dragItem: PropTypes.instanceOf(ColumnDragItem),
    field: PropTypes.object,
    disabled: PropTypes.bool,
    canSelectColumn: PropTypes.func,
    dragColumntableType: PropTypes.string,
    ownDragColumntableType: PropTypes.string,
    dragType: PropTypes.string.isRequired,
    dragOrigin: PropTypes.string.isRequired,
    isDragInProgress: PropTypes.bool,
    index: PropTypes.number,
    onRemoveColumn: PropTypes.func,
    onDragStart: PropTypes.func,
    onDragEnd: PropTypes.func,
    moveColumn: PropTypes.func,
    icon: PropTypes.any,
    id: PropTypes.any,
    allColumns: PropTypes.instanceOf(Immutable.List),
    className: PropTypes.string,
    preventDrag: PropTypes.any,
    intl: PropTypes.object.isRequired,
  };

  static defaultProps = {
    allColumns: Immutable.List(),
  };

  state = {
    pattern: "",
  };

  UNSAFE_componentWillReceiveProps(nextProps) {
    if (nextProps.disabled) {
      this.setState({
        pattern: "",
      });
    }
  }

  handleDrop = (data) => {
    if (this.checkDropPosibility()) {
      this.props.field.onChange(data.id);
    }
  };

  canSelectColumn(columnName) {
    if (this.props.canSelectColumn) {
      return this.props.canSelectColumn(columnName);
    }
    return true;
  }

  selectColumn = (selectedColumn, closeDD) => {
    const { columnName } = selectedColumn;
    if (!this.canSelectColumn(columnName)) {
      return;
    }
    closeDD();

    this.props.field.onChange(columnName);
  };

  moveColumn = (...args) => {
    if (this.props.moveColumn) {
      this.props.moveColumn(...args);
    }
  };

  handleDragOver = () => {
    if (this.checkDropPosibility()) {
      this.setState({ isDragAreaActive: true });
    }
  };

  handleDragOut = () => {
    this.setState({ isDragAreaActive: false });
  };

  checkDropPosibility() {
    const { field, isDragInProgress, dragItem } = this.props;
    const canSelect = this.canSelectColumn(dragItem);
    return !field.value && isDragInProgress && canSelect;
  }

  handleRemoveColumn = () => {
    this.props.onRemoveColumn(this.props.index);
  };

  handlePatternChange = (value) => {
    this.setState({
      pattern: value,
    });
  };

  filterColumns() {
    const { pattern } = this.state;
    return this.props.allColumns.filter((column) =>
      column.get("name").toLowerCase().includes(pattern.trim().toLowerCase()),
    );
  }

  renderAllColumns(closeDD) {
    const { dragOrigin, field, id, index } = this.props;
    const selectedColumnName = field.value;
    return this.filterColumns()
      .sort((a) => NOT_SUPPORTED_TYPES.has(a.get("type")))
      .map((column) => {
        const columnType = column.get("type");
        const columnName = column.get("name");
        const columnDisabled =
          !this.canSelectColumn(columnName) ||
          NOT_SUPPORTED_TYPES.has(columnType);
        const disabledStyle = columnDisabled
          ? { color: "var(--color--neutral--150)" }
          : {};
        const columnData = {
          dragColumnId: id || selectedColumnName,
          dragOrigin,
          dragColumnIndex: index,
          columnName,
          columnType,
          columnDisabled,
        };

        return (
          <div
            disabled={columnDisabled}
            data-qa={columnName}
            className={columnItemCls}
            style={formDefault}
            key={columnName}
            {...(!columnDisabled && {
              onClick: this.selectColumn.bind(this, columnData, closeDD),
            })}
          >
            <dremio-icon
              class="icon-primary"
              name={`data-types/${typeToIconType[columnType]}`}
            ></dremio-icon>
            <EllipsedText
              style={{ ...styles.name, ...disabledStyle }}
              text={columnName}
            />
          </div>
        );
      });
  }

  renderContent() {
    const {
      field,
      disabled,
      preventDrag,
      intl: { formatMessage },
    } = this.props;
    if (disabled) {
      return <div />;
    }

    if (this.checkDropPosibility()) {
      return (
        <div
          className={preventDrag ? disabledContent : content}
          style={styles.empty}
          key="custom-content"
          onClick={this.showPopover}
        >
          <span>Drag a field here</span>
        </div>
      );
    } else if (!field.value) {
      return (
        <SelectView
          key="custom-content"
          content={
            <SearchField
              style={styles.searchStyle}
              inputStyle={styles.searchInput}
              value={this.state.pattern}
              onChange={this.handlePatternChange}
              placeholder={formatMessage({ id: "Dataset.ChooseColumn" })}
            />
          }
          className={preventDrag ? disabledContent : content}
          style={styles.empty}
          listWidthSameAsAnchorEl
          listStyle={{ marginTop: 2, marginLeft: 0 }}
          hideExpandIcon
          useLayerForClickAway={false}
        >
          {({ closeDD }) => (
            <div style={styles.popover} data-qa="popover">
              {this.renderAllColumns(closeDD)}
            </div>
          )}
        </SelectView>
      );
    }

    const selectedColumn = this.props.allColumns.find(
      (c) => c.get("name") === field.value,
    );

    return (
      <div
        className={preventDrag ? disabledContent : content}
        key="custom-content"
      >
        {selectedColumn && (
          <dremio-icon
            key="custom-type"
            class="icon-primary"
            name={`data-types/${typeToIconType[selectedColumn.get("type")]}`}
          ></dremio-icon>
        )}
        <EllipsedText
          text={field.value}
          title={preventDrag ? formatMessage({ id: "Read.Only" }) : field.value}
        />
      </div>
    );
  }

  render() {
    const { field, disabled, className, preventDrag } = this.props;
    const columnName = field.value;
    const columnStyle = disabled ? { visibility: "hidden" } : null;
    const color = this.state.isDragAreaActive
      ? "var(--fill--brand)"
      : "var(--fill--primary)";
    const dragStyle = this.checkDropPosibility()
      ? { ...styles.dragStyle, backgroundColor: color }
      : {};

    return (
      <div
        className={classNames(["inner-join-column", base, className])}
        onDragLeave={this.handleDragOut}
        onDragOver={this.handleDragOver}
      >
        <DragSource
          type={this.props.dragOrigin}
          dragType={this.props.dragType}
          onDragStart={this.props.onDragStart}
          index={this.props.index}
          onDragEnd={this.props.onDragEnd}
          id={columnName}
          preventDrag={preventDrag}
        >
          <DragTarget
            onDrop={this.handleDrop}
            dragType={this.props.dragType}
            moveColumn={this.moveColumn}
            index={this.props.index}
            id={columnName}
          >
            <div style={styles.columnWrap}>
              <div
                className={classNames([
                  "inner-join-column__body",
                  preventDrag && "--disabled",
                  preventDrag ? disabledColumnCls : columnCls,
                ])}
                style={{ ...dragStyle, ...(columnStyle || {}) }}
                key="custom"
              >
                {this.renderContent()}
              </div>
              {this.props.icon
                ? this.props.icon
                : this.renderCancelGreyButtons()}
            </div>
          </DragTarget>
        </DragSource>
      </div>
    );
  }
}

export const styles = {
  fontIcon: {
    Container: {
      width: 25,
      height: 25,
      position: "relative",
      top: 1,
    },
    Icon: {
      cursor: "pointer",
    },
  },
  column: {
    ...FLEX_ROW_START_CENTER,
    height: 26,
    padding: 5,
    cursor: "pointer",
  },
  popover: {
    maxHeight: 200,
    marginLeft: 2,
  },

  type: {
    Icon: {
      width: 24,
      height: 20,
      backgroundPosition: "left center",
    },
    Container: {
      width: 24,
      height: 20,
      top: 0,
    },
  },
  columnWrap: {
    display: "flex",
    flexWrap: "nowrap",
    alignItems: "center",
    justifyContent: "center",
    minWidth: 100,
    margin: "5px 0 0",
  },
  dragStyle: {
    borderBottom: "10px dotted var(--border--neutral)",
    borderTop: "10px dotted var(--border--neutral)",
    borderLeft: "10px dotted var(--border--neutral)",
    borderRight: "1px dotted var(--border--neutral)",
    backgroundColor: "var(--fill--primary)",
    height: 30,
  },
  empty: {
    cursor: "default",
  },
  opacity: {
    opacity: 0,
  },
  searchStyle: {
    padding: "4px 4px 3px 4px",
  },
  searchInput: {
    padding: "2px 10px 2px 10px",
    fontSize: 11,
  },
};

export default compose(injectIntl, DragAreaColumnWithMixin)(DragAreaColumn);
