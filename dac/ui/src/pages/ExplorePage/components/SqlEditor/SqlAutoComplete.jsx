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
import { Component, createRef } from "react";
import { FormattedMessage } from "react-intl";

import PropTypes from "prop-types";
import Immutable from "immutable";
import deepEqual from "deep-equal";
import classNames from "clsx";
import { compose } from "redux";
import { connect } from "react-redux";
import exploreUtils from "utils/explore/exploreUtils";
import { splitFullPath, constructFullPath } from "utils/pathUtils";
import DragTarget from "components/DragComponents/DragTarget";
import { Tooltip as OldTooltip } from "components/Tooltip";
import { IconButton } from "dremio-ui-lib/components";
import localStorageUtils from "#oss/utils/storageUtils/localStorageUtils";
import { getExploreState } from "#oss/selectors/explore";
import { getSupportFlags } from "#oss/selectors/supportFlags";
import { fetchSupportFlags } from "#oss/actions/supportFlags";
import config from "@inject/utils/config";
import { isEnterprise, isCommunity } from "dyn-load/utils/versionUtils";
import { SQL_DARK_THEME, SQL_LIGHT_THEME } from "#oss/utils/sql-editor";
import { ContextPicker } from "./components/ContextPicker";
import { addDatasetATSyntax } from "#oss/utils/nessieUtils";
import {
  renderExtraSQLToolbarIcons,
  EXTRA_KEYBOARD_BINDINGS_MAC,
  EXTRA_KEYBOARD_BINDINGS_WINDOWS,
  renderExtraSQLKeyboardShortcutMessages,
} from "@inject/utils/sql-editor-extra";
import SqlEditorContainer from "./SqlEditorContainer";
import { intl } from "#oss/utils/intl";

import "./SqlAutoComplete.less";

export class SqlAutoComplete extends Component {
  static propTypes = {
    onChange: PropTypes.func,
    onFunctionChange: PropTypes.func,
    toggleExtraSQLPanel: PropTypes.func,
    pageType: PropTypes.oneOf(["details", "recent"]),
    defaultValue: PropTypes.string,
    isGrayed: PropTypes.bool,
    context: PropTypes.instanceOf(Immutable.List),
    name: PropTypes.string,
    sqlSize: PropTypes.number,
    sidePanelEnabled: PropTypes.bool,
    changeQueryContext: PropTypes.func,
    style: PropTypes.object,
    dragType: PropTypes.string,
    children: PropTypes.any,
    fetchSupportFlags: PropTypes.func,
    supportFlags: PropTypes.object,
    sidebarCollapsed: PropTypes.bool,
    serverSqlErrors: PropTypes.array,
    editorWidth: PropTypes.any,
    isMultiQueryRunning: PropTypes.bool,
    hasExtraSQLPanelContent: PropTypes.bool,
    isDarkMode: PropTypes.bool,
  };

  static defaultProps = {
    sqlSize: 100,
  };

  static contextTypes = {
    location: PropTypes.object.isRequired,
    router: PropTypes.object.isRequired,
  };

  monacoEditorComponent = null;
  sqlAutoCompleteRef = null;

  constructor(props) {
    super(props);

    this.onMouseEnter = this.onMouseEnter.bind(this);
    this.onMouseLeave = this.onMouseLeave.bind(this);

    this.ref = {
      targetRef: createRef(),
    };
    this.state = {
      isAutocomplete: localStorageUtils.getSqlAutocomplete(),
      sidePanelEnabled: this.props.sidePanelEnabled,
      manuallyEnableAutocomplete: false,
      tooltipHover: false,
      os: "windows",
    };
  }

  async componentDidMount() {
    //  fetch supportFlags only if its not enterprise edition
    const isEnterpriseFlag = isEnterprise && isEnterprise();
    const isCommunityFlag = isCommunity && isCommunity();
    if (
      !(isEnterpriseFlag || isCommunityFlag) &&
      this.props.fetchSupportFlags
    ) {
      this.props.fetchSupportFlags("ui.autocomplete.allow");
      this.props.fetchSupportFlags("ui.formatter.allow");
    }
    this.getUserOperatingSystem();
  }

  shouldComponentUpdate(nextProps, nextState, nextContext) {
    return (
      nextProps.isDarkMode !== this.props.isDarkMode ||
      nextProps.defaultValue !== this.props.defaultValue ||
      nextProps.context !== this.props.context ||
      nextContext.location.query.version !==
        this.context.location.query.version ||
      nextProps.sidePanelEnabled !== this.props.sidePanelEnabled ||
      nextProps.isGrayed !== this.props.isGrayed ||
      nextProps.sqlSize !== this.props.sqlSize ||
      nextProps.supportFlags !== this.props.supportFlags ||
      nextProps.sidebarCollapsed !== this.props.sidebarCollapsed ||
      nextProps.serverSqlErrors !== this.props.serverSqlErrors ||
      nextProps.editorWidth !== this.props.editorWidth ||
      !deepEqual(nextState, this.state)
    );
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    // reset this when it is rest at the project settings level
    const isEnterpriseFlag = isEnterprise && isEnterprise();
    const isCommunityFlag = isCommunity && isCommunity();

    if (
      !(isEnterpriseFlag || isCommunityFlag) &&
      nextProps.supportFlags &&
      nextProps.supportFlags["ui.autocomplete.allow"] !== undefined &&
      !nextProps.supportFlags["ui.autocomplete.allow"]
    ) {
      this.setState({
        isAutocomplete: false,
      });
    } else if (
      (isEnterpriseFlag || isCommunityFlag) &&
      !config.allowAutoComplete
    ) {
      // if enterprise or community edition then read allowAutoComplete flag from config
      this.setState({ isAutocomplete: false });
    }
  }

  isFormatterEnabled() {
    // reset this when it is rest at the project settings level
    const isEnterpriseFlag = isEnterprise && isEnterprise();
    const isCommunityFlag = isCommunity && isCommunity();

    let formatterEnabled = false;

    if (!(isEnterpriseFlag || isCommunityFlag) && this.props.supportFlags) {
      formatterEnabled = this.props.supportFlags["ui.formatter.allow"];
    } else if (isEnterpriseFlag || isCommunityFlag) {
      formatterEnabled = config.allowFormatting;
    }

    return formatterEnabled;
  }

  handleDrop = ({ id, args }) => {
    const { isMultiQueryRunning } = this.props;

    if (!isMultiQueryRunning) {
      // because we move the cursor as we drag around, we can simply insert at the current position in the editor (default)

      // duck-type check pending drag-n-drop revamp
      if (args !== undefined) {
        this.insertFunction(id, args);
      } else if (typeof id === "string") {
        this.insertFieldName(id);
      } else {
        this.insertFullPath(id);
      }
    }
  };

  getMonacoEditorInstance() {
    return this.sqlAutoCompleteRef?.getEditorInstance?.();
  }

  getMonaco() {
    return this.sqlAutoCompleteRef.monaco;
  }

  getKeyboardShortcuts() {
    const { hasExtraSQLPanelContent } = this.props;
    const isFormatterEnabled = this.isFormatterEnabled();
    return this.state.os === "windows"
      ? {
          run: "CTRL + Shift + Enter",
          preview: "CTRL + Enter",
          comment: "CTRL + /",
          find: "CTRL + F",
          autocomplete: "CTRL + Space",
          ...(isFormatterEnabled ? { format: "CTRL + Shift + F" } : {}),
          ...(hasExtraSQLPanelContent && EXTRA_KEYBOARD_BINDINGS_WINDOWS),
        }
      : {
          run: "⌘⇧↵",
          preview: "⌘↵",
          comment: "⌘/",
          find: "⌘F",
          autocomplete: "⌃ Space",
          ...(isFormatterEnabled ? { format: "⌘⇧F" } : {}),
          ...(hasExtraSQLPanelContent && EXTRA_KEYBOARD_BINDINGS_MAC),
        };
  }

  handleDragOver = (evt) => {
    const target = this.getMonacoEditorInstance().getTargetAtClientPoint(
      evt.clientX,
      evt.clientY,
    );
    if (!target || !target.position) return; // no position if you drag over the rightmost part of the context UI
    this.getMonacoEditorInstance().setPosition(target.position);
    this.focus();
  };

  focus() {
    const { isMultiQueryRunning } = this.props;

    if (isMultiQueryRunning) {
      return;
    }

    this.getMonacoEditorInstance().focus();
  }

  handleChange = (val) => {
    this.updateCode(val);
  };

  updateContext = (value) => {
    this.props.changeQueryContext(
      Immutable.fromJS(value && splitFullPath(value)),
    );
  };

  insertFullPath(pathList, ranges) {
    const text = constructFullPath(pathList);
    this.insertAtRanges(text + addDatasetATSyntax(pathList.toJS()), ranges);
  }

  insertFieldName(name, ranges) {
    const text = exploreUtils.escapeFieldNameForSQL(name);
    this.insertAtRanges(text, ranges);
  }

  insertFunction(
    name,
    args,
    ranges = this.getMonacoEditorInstance().getSelections(),
  ) {
    const hasArgs = args && args.length;
    const text = name;

    if (!hasArgs) {
      // simple insert/replace
      this.insertAtRanges(text, ranges);
      return;
    }

    this.getMonacoEditorInstance().getModel().pushStackElement();

    const Selection = this.getMonaco().Selection;
    const nonEmptySelections = [];
    let emptySelections = [];
    ranges.forEach((range) => {
      const selection = new Selection(
        range.startLineNumber,
        range.startColumn,
        range.endLineNumber,
        range.endColumn,
      );
      if (!selection.isEmpty()) {
        nonEmptySelections.push(selection);
      } else {
        emptySelections.push(selection);
      }
    });

    // executes when highlighting text and clicking the + icon on a function
    if (nonEmptySelections.length) {
      const edits = [
        ...nonEmptySelections.map((sel) => ({
          identifier: "dremio-inject",
          range: sel.collapseToStart(),
          text: text + "(",
        })),
        ...nonEmptySelections.map((sel) => ({
          identifier: "dremio-inject",
          range: Selection.fromPositions(sel.getEndPosition()),
          text: ")",
        })),
      ];
      this.getMonacoEditorInstance().executeEdits("dremio", edits);

      // need to update emptySelections for the new insertions
      // assumes that function names are single line, and ranges don't overlap
      const nudge = text.length + 2;
      nonEmptySelections.forEach((nonEmptySel) => {
        emptySelections = emptySelections.map((otherSelection) => {
          let { startColumn, endColumn } = otherSelection;
          const { startLineNumber, endLineNumber } = otherSelection;
          if (startLineNumber === nonEmptySel.endLineNumber) {
            if (startColumn >= nonEmptySel.endColumn) {
              startColumn += nudge;
              if (endLineNumber === startLineNumber) {
                endColumn += nudge;
              }
            }
          }
          return new Selection(
            startLineNumber,
            startColumn,
            endLineNumber,
            endColumn,
          );
        });
      });
    }

    // do snippet-style insertion last so that the selection ends up with token selection
    // args comes in correct format from BE
    if (emptySelections.length) {
      // insertSnippet only works with the current selection, so move the selection to the input range
      this.getMonacoEditorInstance().setSelections(emptySelections);
      this.sqlAutoCompleteRef.insertSnippet(
        text + args,
        undefined,
        undefined,
        false,
        false,
      );
    }

    this.getMonacoEditorInstance().getModel().pushStackElement();
    this.focus();
  }

  insertAtRanges(
    text,
    ranges = this.getMonacoEditorInstance().getSelections(),
  ) {
    // getSelections() falls back to cursor location automatically
    const edits = ranges.map((range) => ({
      identifier: "dremio-inject",
      range,
      text,
    }));

    // Remove highlighted string and place cursor to the end of the new `text`
    ranges[0].selectionStartColumn =
      ranges[0].selectionStartColumn + text.length;
    ranges[0].positionColumn = ranges[0].positionColumn + text.length;

    this.getMonacoEditorInstance().executeEdits("dremio", edits, ranges);
    this.getMonacoEditorInstance().pushUndoStop();
    this.focus();
  }

  updateCode(val) {
    if (this.props.onChange) {
      this.props.onChange(val);
      this.getMonacoEditorInstance()?.pushUndoStop();
    }
  }

  handleAutocompleteClick() {
    localStorageUtils.setSqlAutocomplete(!this.state.isAutocomplete);
    this.setState((state) => {
      return { isAutocomplete: !state.isAutocomplete };
    });
  }

  renderIconButton = ({
    onClick,
    tooltip,
    className,
    source,
    id,
    dataQa,
    ...props
  }) => {
    return (
      <IconButton
        onClick={onClick}
        tooltip={tooltip}
        className={className}
        id={id || dataQa}
        data-qa={dataQa || id}
        {...props}
      >
        <dremio-icon
          name={source}
          alt=""
          style={{ width: "24px", height: "24px" }}
        ></dremio-icon>
      </IconButton>
    );
  };

  showAutocomplete() {
    const { supportFlags, isDarkMode, isGrayed } = this.props;

    //  if its enterprise read supportFlag from dremioConfig else read it from API
    const isEnterpriseFlag = isEnterprise && isEnterprise();
    const isCommunityFlag = isCommunity && isCommunity();
    const showAutoCompleteOption =
      isEnterpriseFlag || isCommunityFlag
        ? config.allowAutoComplete
        : supportFlags && supportFlags["ui.autocomplete.allow"];
    if (showAutoCompleteOption) {
      return this.renderIconButton({
        onClick: this.handleAutocompleteClick.bind(this),
        tooltip: this.state.isAutocomplete
          ? "Autocomplete enabled"
          : "Autocomplete disabled",
        source: this.state.isAutocomplete
          ? "sql-editor/sqlAutoCompleteEnabled"
          : "sql-editor/sqlAutoCompleteDisabled",
        className: isDarkMode ? "sql__darkIcon" : "sql__lightIcon",
        id: "toggle--autocomplete-icon",
        dataQa: "toggle-autocomplete-icon",
        disabled: isGrayed,
      });
    } else return null;
  }

  onMouseEnter() {
    this.setState({ tooltipHover: true });
  }

  onMouseLeave() {
    this.setState({ tooltipHover: false });
  }

  getUserOperatingSystem() {
    if (navigator.userAgent.indexOf("Mac OS X") !== -1) {
      this.setState({
        os: "mac",
      });
    }
  }

  render() {
    const {
      sidePanelEnabled,
      isGrayed,
      context,
      onFunctionChange,
      serverSqlErrors,
      hasExtraSQLPanelContent,
      toggleExtraSQLPanel,
      sqlSize,
      isDarkMode,
    } = this.props;

    const { query } = this.context.location;

    const widthSqlEditor = sidePanelEnabled
      ? styles.smallerSqlEditorWidth
      : this.props.editorWidth
        ? { width: this.props.editorWidth }
        : styles.SidebarEditorWidth;

    const keyboardShortcuts = this.getKeyboardShortcuts();
    const isFormatterEnabled = this.isFormatterEnabled();
    const iconColor = isDarkMode ? "sql__darkIcon" : "sql__lightIcon";

    return (
      <DragTarget
        dragType={this.props.dragType}
        onDrop={this.handleDrop}
        onDragOver={this.handleDragOver}
      >
        <div
          className={classNames(
            "sqlAutocomplete",
            isDarkMode ? SQL_DARK_THEME : SQL_LIGHT_THEME,
          )}
          name={this.props.name}
          style={{
            ...styles.base,
            ...widthSqlEditor,
            ...(!!isGrayed && { opacity: 0.4, pointerEvents: "none" }),
            ...(this.props.style || {}),
          }}
        >
          <div className={classNames("sqlAutocomplete__actions text-sm")}>
            {query.type !== "transform" &&
              this.props.pageType !== "details" && (
                <ContextPicker
                  value={this.props.context}
                  onChange={this.updateContext}
                  className={`sqlAutocomplete__contextText-${
                    isDarkMode ? "dark" : "light"
                  }`}
                  disabled={isGrayed}
                />
              )}
            {this.renderIconButton({
              onClick: onFunctionChange,
              className: iconColor,
              source: "sql-editor/function",
              tooltip: "Functions",
              id: "toggle-icon",
              disabled: isGrayed,
            })}
            {this.showAutocomplete()}
            <span
              className="keyboard__shortcutsIcon"
              onMouseEnter={this.onMouseEnter}
              onMouseLeave={this.onMouseLeave}
              onFocus={this.onMouseEnter}
              onBlur={this.onMouseLeave}
              ref={this.ref.targetRef}
              tabIndex={isGrayed ? -1 : 0}
              aria-label={intl.formatMessage({
                id: "KeyboardShortcuts.Shortcuts",
              })}
            >
              <dremio-icon
                name="sql-editor/keyboard"
                class={isDarkMode ? "sql__darkIcon" : "sql__lightIcon"}
              ></dremio-icon>
              <OldTooltip
                key="tooltip"
                type="info"
                placement="left-start"
                target={() =>
                  this.state.tooltipHover ? this.ref.targetRef.current : null
                }
                container={this}
                tooltipInnerClass="textWithHelp__tooltip --white keyboardShortcutTooltip"
                tooltipArrowClass="--white"
                style={{ zIndex: 360 }}
              >
                <p className="tooltip-content__heading">
                  <FormattedMessage id="KeyboardShortcuts.Shortcuts" />
                </p>
                <div className="divider" />
                <ul className="tooltip-content__list">
                  <li aria-live="polite">
                    <FormattedMessage id="Common.Run" />
                    <span>{keyboardShortcuts.run}</span>
                  </li>
                  <li aria-live="polite">
                    <FormattedMessage id="Common.Preview" />
                    <span>{keyboardShortcuts.preview}</span>
                  </li>
                  <li aria-live="polite">
                    <FormattedMessage id="KeyboardShortcuts.Comment" />
                    <span>{keyboardShortcuts.comment}</span>
                  </li>
                  <li aria-live="polite">
                    <FormattedMessage id="KeyboardShortcuts.Find" />
                    <span>{keyboardShortcuts.find}</span>
                  </li>
                  {this.state.isAutocomplete ? (
                    <li aria-live="polite">
                      <FormattedMessage id="KeyboardShortcuts.Autocomplete" />
                      <span>{keyboardShortcuts.autocomplete}</span>
                    </li>
                  ) : (
                    <></>
                  )}
                  {isFormatterEnabled ? (
                    <li aria-live="polite">
                      <FormattedMessage id="SQL.Format" />
                      <span>{keyboardShortcuts.format}</span>
                    </li>
                  ) : (
                    <></>
                  )}
                  {renderExtraSQLKeyboardShortcutMessages({
                    keyboardShortcuts: keyboardShortcuts,
                    hasExtraSQLPanelContent: hasExtraSQLPanelContent,
                  })}
                </ul>
              </OldTooltip>
            </span>
            {hasExtraSQLPanelContent &&
              renderExtraSQLToolbarIcons({
                renderIconButton: this.renderIconButton,
                toggleExtraSQLPanel: toggleExtraSQLPanel,
                isDarkMode,
                disabled: isGrayed,
              })}
          </div>
          <SqlEditorContainer
            ref={(ref) => (this.sqlAutoCompleteRef = ref)}
            defaultValue={this.props.defaultValue}
            extensionsConfig={{
              autocomplete: this.state.isAutocomplete,
              formatter: isFormatterEnabled,
            }}
            keyboardShortcutProps={{
              toggleExtraSQLPanel: hasExtraSQLPanelContent
                ? toggleExtraSQLPanel
                : null,
            }}
            onChange={this.handleChange}
            queryContext={context}
            serverSqlErrors={serverSqlErrors}
            style={{ height: sqlSize - 2 }} // account for top/bottom 1px borders
            readOnly={isGrayed}
          />
          {this.props.children}
        </div>
      </DragTarget>
    );
  }
}

const mapStateToProps = (state) => {
  const explorePageState = getExploreState(state);

  return {
    isMultiQueryRunning: explorePageState?.view.isMultiQueryRunning,
    supportFlags: getSupportFlags(state),
  };
};

const mapDispatchToProps = {
  fetchSupportFlags,
};

export default compose(
  connect(mapStateToProps, mapDispatchToProps, null, {
    forwardRef: true,
  })(SqlAutoComplete),
);

const styles = {
  base: {
    position: "relative",
    width: "100%",
  },
  SidebarEditorWidth: {
    width: "calc(100% - 25px)",
  },
  smallerSqlEditorWidth: {
    width: "calc(60% - 7px)",
  },
  tooltip: {
    padding: "5px 10px 5px 10px",
    backgroundColor: "#f2f2f2",
  },
  messageStyle: {
    position: "absolute",
    bottom: "0px",
    zIndex: 1000,
  },
  editIcon: {
    Container: {
      height: 15,
      width: 20,
      position: "relative",
      display: "flex",
      alignItems: "center",
      cursor: "pointer",
    },
  },
};
