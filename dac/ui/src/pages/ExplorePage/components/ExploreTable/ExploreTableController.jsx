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
import Immutable from "immutable";
import { connect } from "react-redux";
import PropTypes from "prop-types";
import { Spinner } from "dremio-ui-lib/components";

import exploreUtils from "utils/explore/exploreUtils";
import exploreTransforms from "utils/exploreTransforms";

import { LIST, MAP, STRUCT } from "#oss/constants/DataTypes";

import {
  getPeekData,
  getImmutableTable,
  getPaginationUrl,
  getExploreState,
  getColumnFilter,
} from "#oss/selectors/explore";
import { getViewState } from "selectors/resources";
import { accessEntity } from "actions/resources/lru";

import {
  transformHistoryCheck,
  performTransform,
} from "actions/explore/dataset/transform";

import { FULL_CELL_VIEW_ID } from "actions/explore/dataset/data";
import { isSqlChanged } from "#oss/sagas/utils";
import { ErrorBoundary } from "#oss/components/OldErrorBoundary";

import { LOAD_TRANSFORM_CARDS_VIEW_ID } from "actions/explore/recommended";

import { constructFullPath } from "utils/pathUtils";
import { PageTypes } from "#oss/pages/ExplorePage/pageTypes";
import { renderJobStatus } from "#oss/utils/jobsUtils";
import { Button } from "dremio-ui-lib/components";

import Message from "#oss/components/Message";
import apiUtils from "#oss/utils/apiUtils/apiUtils";
import DropdownForSelectedText from "./DropdownForSelectedText";
import ExploreCellLargeOverlay from "./ExploreCellLargeOverlay";
import ExploreTable from "./ExploreTable";
import "./ExploreTableController.less";

export class ExploreTableController extends PureComponent {
  static propTypes = {
    pageType: PropTypes.string,
    dataset: PropTypes.instanceOf(Immutable.Map),
    tableData: PropTypes.instanceOf(Immutable.Map).isRequired,
    paginationUrl: PropTypes.string,
    isDumbTable: PropTypes.bool,
    exploreViewState: PropTypes.instanceOf(Immutable.Map).isRequired,
    cardsViewState: PropTypes.instanceOf(Immutable.Map),
    fullCellViewState: PropTypes.instanceOf(Immutable.Map),
    currentSql: PropTypes.string,
    queryContext: PropTypes.instanceOf(Immutable.List),
    location: PropTypes.object,
    dragType: PropTypes.string,
    width: PropTypes.number,
    height: PropTypes.number,
    widthScale: PropTypes.number,
    rightTreeVisible: PropTypes.bool,
    isResizeInProgress: PropTypes.bool,
    children: PropTypes.node,
    getTableHeight: PropTypes.func,
    shouldRenderInvisibles: PropTypes.bool,
    columnFilter: PropTypes.string,
    isMultiQueryRunning: PropTypes.bool,
    // Actions
    transformHistoryCheck: PropTypes.func,
    performTransform: PropTypes.func,
    accessEntity: PropTypes.func.isRequired,
    canSelect: PropTypes.any,
    currentJobsMap: PropTypes.array,
    tabStatusArr: PropTypes.array,
    queryTabNumber: PropTypes.number,
    previousMultiSql: PropTypes.string,
    queryStatuses: PropTypes.array,
  };

  static contextTypes = {
    router: PropTypes.object,
  };

  static defaultProps = {
    dataset: Immutable.Map(),
  };

  transformPreconfirmed = false;

  constructor(props) {
    super(props);
    this.openDetailsWizard = this.openDetailsWizard.bind(this);

    this.handleCellTextSelect = this.handleCellTextSelect.bind(this);
    this.handleCellShowMore = this.handleCellShowMore.bind(this);
    this.selectAll = this.selectAll.bind(this);
    this.selectItemsOfList = this.selectItemsOfList.bind(this);

    this.state = {
      activeTextSelect: null,
      openPopover: false,
      activeCell: null,
    };
  }

  UNSAFE_componentWillReceiveProps(nextProps) {
    const { isGrayed } = this.state;
    const isContextChanged = this.isContextChanged(nextProps);

    const newIsGreyed =
      nextProps.pageType === PageTypes.default &&
      (this.isSqlChanged(nextProps) || isContextChanged);
    if (isGrayed !== newIsGreyed) {
      this.setState({ isGrayed: newIsGreyed });
    }

    const nextVersion =
      nextProps.tableData && nextProps.tableData.get("version");

    if (nextVersion && nextVersion !== this.props.tableData.get("version")) {
      this.transformPreconfirmed = false;
      this.props.accessEntity("tableData", nextVersion);
    }
  }

  componentDidUpdate(prevProps) {
    const { isMultiQueryRunning } = this.props;

    if (isMultiQueryRunning && !prevProps.isMultiQueryRunning) {
      this.hideDrop();
    }
  }

  getNextTableAfterTransform({ data, tableData }) {
    const hash = {
      DROP: () =>
        exploreTransforms.dropColumn({
          name: data.columnName,
          table: tableData,
        }),
      RENAME: () =>
        exploreTransforms.renameColumn({
          name: data.columnName,
          nextName: data.newColumnName,
          table: tableData,
        }),
      DESC: () => tableData,
      ASC: () => tableData,
      MULTIPLY: () => tableData,
    };
    return exploreTransforms.isTransformOptimistic(data.type)
      ? (hash[data.type] && hash[data.type]()) || null
      : null;
  }

  selectAll(elem, columnType, columnName, cellText, cellValue) {
    const { pathname, query, state } = this.props.location;
    const isNull = cellValue === null;
    const text = isNull ? null : cellText;
    const length = isNull ? 0 : text.length;
    const model = {
      cellText: text,
      offset: 0,
      columnName,
      length,
    };
    const position = exploreUtils.selectAll(elem);
    this.context.router.push({
      pathname,
      query,
      state: {
        ...state,
        columnName,
        columnType,
        hasSelection: true,
        selection: Immutable.fromJS(model),
      },
    });
    this.handleCellTextSelect({ ...position, columnType });
  }

  selectItemsOfList(columnText, columnName, columnType, selection) {
    const { router } = this.context;
    const { location } = this.props;
    const content = exploreUtils.getSelectionForList(
      columnText,
      columnName,
      selection,
    );
    if (!content) {
      return;
    }

    router.push({
      ...location,
      state: {
        ...location.state,
        columnName,
        columnType,
        listOfItems: content.listOfItems,
        hasSelection: true,
        selection: Immutable.fromJS(content.model),
      },
    });
    this.handleCellTextSelect({ ...content.position, columnType });
  }

  handleCellTextSelect(activeTextSelect) {
    this.setState({
      activeTextSelect,
      openPopover: !this.props.isDumbTable,
    });
  }

  handleCellShowMore(cellValue, anchor, columnType, columnName, valueUrl) {
    this.setState({
      activeCell: {
        cellValue,
        anchor,
        columnType,
        columnName,
        // for dumb table do not try to load full cell value, as server does not support this functionality
        // for that case. Lets show truncated values that was loaded.
        isTruncatedValue: Boolean(this.props.isDumbTable && valueUrl),
        valueUrl: this.props.isDumbTable ? null : valueUrl,
      },
    });
  }

  preconfirmTransform = () => {
    return new Promise((resolve) => {
      if (!this.transformPreconfirmed) {
        this.props.transformHistoryCheck(this.props.dataset, () => {
          this.transformPreconfirmed = true;
          resolve();
        });
      } else {
        resolve();
      }
    });
  };

  isSqlChanged(nextProps) {
    if (!nextProps.dataset || !this.props.dataset) {
      return false;
    }
    const datasetSql = nextProps.dataset.get("sql");
    const { currentSql } = nextProps;
    const { previousMultiSql } = this.props;

    if (!previousMultiSql) {
      return isSqlChanged(datasetSql, currentSql);
    } else {
      return currentSql !== previousMultiSql;
    }
  }

  isContextChanged(nextProps) {
    if (!nextProps.dataset || !this.props.dataset) {
      return false;
    }
    const nextContext = nextProps.dataset.get("context");
    const { queryContext } = nextProps;
    return (
      nextContext &&
      queryContext &&
      constructFullPath(nextContext) !== constructFullPath(queryContext)
    );
  }

  openDetailsWizard({ detailType, columnName, toType = null }) {
    const {
      dataset,
      queryContext,
      exploreViewState,
      queryTabNumber,
      queryStatuses,
    } = this.props;

    const callback = () => {
      const { router } = this.context;
      const { location } = this.props;
      const column = this.props.tableData
        .get("columns")
        .find((col) => col.get("name") === columnName)
        .toJS();
      const nextLocation = exploreUtils.getLocationToGoToTransformWizard({
        detailType,
        column,
        toType,
        location,
      });

      router.push(nextLocation);
    };
    this.props.performTransform({
      dataset,
      currentSql: queryStatuses[queryTabNumber - 1].sqlStatement,
      queryContext,
      viewId: exploreViewState.get("viewId"),
      callback,
      indexToModify: queryTabNumber - 1,
    });
  }

  makeTransform = (data, checkHistory = true) => {
    const {
      dataset,
      queryContext,
      exploreViewState,
      queryTabNumber,
      queryStatuses,
    } = this.props;
    const doTransform = () => {
      this.props.performTransform({
        dataset,
        currentSql: queryStatuses[queryTabNumber - 1].sqlStatement,
        queryContext,
        transformData: exploreUtils.getMappedDataForTransform(data),
        viewId: exploreViewState.get("viewId"),
        nextTable: this.getNextTableAfterTransform({
          data,
          tableData: this.props.tableData,
        }),
        indexToModify: queryTabNumber - 1,
      });
    };
    if (checkHistory) {
      this.props.transformHistoryCheck(dataset, doTransform);
    } else {
      doTransform();
    }
  };

  updateColumnName = (oldColumnName, e) => {
    const newColumnName = e.target.value;
    if (newColumnName !== oldColumnName) {
      this.makeTransform(
        { type: "RENAME", columnName: oldColumnName, newColumnName },
        false,
      );
    }
  };

  hideCellMore = () => this.setState({ activeCell: null });
  hideDrop = () =>
    this.setState({
      activeTextSelect: null,
      openPopover: false,
    });

  decorateTable = (tableData) => {
    const { location } = this.props;
    if (location.query.type === "transform") {
      const transform = exploreUtils.getTransformState(location);
      const columnType = transform.get("columnType");
      const initializeColumnTypeForExtract =
        columnType === LIST || columnType === MAP || columnType === STRUCT
          ? columnType
          : "default";
      const isDefault = initializeColumnTypeForExtract === "default";

      if (!exploreUtils.transformHasSelection(transform) && isDefault) {
        const columnName = transform.get("columnName");
        const columns = tableData.get("columns").map((column) => {
          if (column.get("name") === columnName && !column.get("status")) {
            return column
              .set("status", "TRANSFORM_ON")
              .set("color", "var(--fill--primary--selected--hover)");
          }
          return column;
        });

        return tableData.set("columns", columns);
      }
    }

    return tableData;
  };

  renderExploreCellLargeOverlay() {
    return this.state.activeCell && !this.props.location.query.transformType ? (
      <ErrorBoundary>
        <ExploreCellLargeOverlay
          {...this.state.activeCell}
          isDumbTable={this.props.isDumbTable}
          fullCellViewState={this.props.fullCellViewState}
          onSelect={this.handleCellTextSelect}
          hide={this.hideCellMore}
          openPopover={this.state.openPopover}
          selectAll={this.selectAll}
          style={{ width: "398px" }}
        />
      </ErrorBoundary>
    ) : null;
  }

  render() {
    const tableData = this.decorateTable(this.props.tableData);
    const rows = tableData.get("rows");
    const columns = exploreUtils.getFilteredColumns(
      tableData.get("columns"),
      this.props.columnFilter,
    );
    const {
      canSelect,
      tabStatusArr,
      queryTabNumber,
      currentJobsMap,
      currentSql,
      previousMultiSql,
      shouldRenderInvisibles,
      isExploreTableLoading,
    } = this.props;

    const currentTab = tabStatusArr && tabStatusArr[queryTabNumber - 1];

    const isCurrentQueryFinished =
      currentJobsMap &&
      queryTabNumber > 0 &&
      currentJobsMap[queryTabNumber - 1] &&
      !!currentJobsMap[queryTabNumber - 1].jobId;

    let jobTableContent;

    if (isExploreTableLoading) {
      // generic spinner to override the results table
      jobTableContent = (
        <div className="flex self-center justify-center">
          <Spinner className="sqlEditor__spinner" />
        </div>
      );
    } else if (currentTab) {
      jobTableContent = currentTab.error ? (
        <>
          <Message
            message={apiUtils.getThrownErrorException(currentTab.error)}
            isDismissable={false}
            messageType="error"
            style={{ padding: "0 8px" }}
          />
        </>
      ) : (
        <div className="sqlEditor__pendingTable">
          <div className="sqlEditor__pendingTable__statusMessage">
            {currentTab.renderIcon && renderJobStatus(currentTab.renderIcon)}
            {currentTab.text}
          </div>
          {currentTab.buttonFunc && (
            <Button
              variant="secondary"
              className="sqlEditor__pendingTable__button"
              onClick={currentTab.buttonFunc}
              disabled={
                currentTab.ranJob && // only disabled if cancelJob button is visible
                currentJobsMap[queryTabNumber - 1].isCancelDisabled
              }
            >
              <dremio-icon
                name={currentTab.buttonIcon}
                class={"sqlEditor__jobsTable__tab-button"}
              />
              {currentTab.buttonText}
            </Button>
          )}
        </div>
      );
    } else {
      jobTableContent = (
        <div className="sqlEditor__exploreTable">
          <ExploreTable
            pageType={this.props.pageType}
            dataset={this.props.dataset}
            rows={rows}
            columns={columns}
            paginationUrl={this.props.paginationUrl}
            exploreViewState={this.props.exploreViewState}
            cardsViewState={this.props.cardsViewState}
            isResizeInProgress={this.props.isResizeInProgress}
            widthScale={this.props.widthScale}
            openDetailsWizard={this.openDetailsWizard}
            makeTransform={this.makeTransform}
            preconfirmTransform={this.preconfirmTransform}
            width={this.props.width}
            updateColumnName={this.updateColumnName}
            height={this.props.height}
            dragType={this.props.dragType}
            rightTreeVisible={this.props.rightTreeVisible}
            onCellTextSelect={this.handleCellTextSelect}
            onCellShowMore={this.handleCellShowMore}
            selectAll={this.selectAll}
            selectItemsOfList={this.selectItemsOfList}
            isDumbTable={this.props.isDumbTable}
            getTableHeight={this.props.getTableHeight}
            isGrayed={this.state.isGrayed}
            shouldRenderInvisibles={shouldRenderInvisibles}
            canSelect={canSelect}
            isMultiSql={currentJobsMap && currentJobsMap.length > 1}
            isEdited={!!previousMultiSql && currentSql !== previousMultiSql}
            isCurrentQueryFinished={isCurrentQueryFinished}
          />
          {this.renderExploreCellLargeOverlay()}
          {this.state.activeTextSelect && (
            <DropdownForSelectedText
              dropPositions={Immutable.fromJS(this.state.activeTextSelect)}
              openPopover={this.state.openPopover}
              hideDrop={this.hideDrop}
            />
          )}
          {this.props.children}
        </div>
      );
    }

    return jobTableContent;
  }
}

function mapStateToProps(state, ownProps) {
  const location = state.routing.locationBeforeTransitions || {};
  const { dataset } = ownProps;
  const datasetVersion = dataset && dataset.get("datasetVersion");
  const paginationUrl = getPaginationUrl(state, datasetVersion);

  const exploreState = getExploreState(state);
  let explorePageProps = null;
  let queryStatuses;
  let waitingForJobResults;
  if (exploreState) {
    explorePageProps = {
      currentSql: exploreState.view.currentSql,
      queryContext: exploreState.view.queryContext,
      isResizeInProgress: exploreState.ui.get("isResizeInProgress"),
      previousMultiSql: exploreState.view.previousMultiSql,
      queryStatuses: exploreState.view.queryStatuses,
      isMultiQueryRunning: exploreState.view.isMultiQueryRunning,
      isExploreTableLoading: exploreState.view.isExploreTableLoading,
    };
    queryStatuses = exploreState.view.queryStatuses;
    waitingForJobResults = exploreState.view.waitingForJobResults;
  }

  let tableData = ownProps.tableData;
  const previewVersion = location.state && location.state.previewVersion;
  const currentDataset =
    queryStatuses && queryStatuses[ownProps.queryTabNumber - 1];

  const curDatasetVersion =
    currentDataset && queryStatuses.length === 1
      ? location.query.version
      : currentDataset && queryStatuses.length > 1
        ? currentDataset.version
        : datasetVersion;

  if (!ownProps.isDumbTable) {
    if (
      ownProps.pageType === PageTypes.default ||
      !previewVersion ||
      ownProps.exploreViewState.get("isAutoPeekFailed")
    ) {
      tableData = getImmutableTable(state, curDatasetVersion);
    } else {
      tableData = getPeekData(state, previewVersion);
    }
  }

  const jobToWaitFor = queryStatuses?.find(
    (queryStatus) => queryStatus.jobId === waitingForJobResults,
  );

  let hideResults = false;

  // should only hide the results table when on the query tab of the currently running job
  if (jobToWaitFor && currentDataset?.jobId === jobToWaitFor.jobId) {
    hideResults = true;
  }

  return {
    tableData:
      !hideResults && tableData
        ? tableData
        : Immutable.fromJS({ rows: null, columns: [] }),
    columnFilter: getColumnFilter(state, curDatasetVersion),
    paginationUrl,
    location,
    exploreViewState: ownProps.exploreViewState,
    fullCellViewState: ownProps.isDumbTable
      ? ownProps.exploreViewState
      : getViewState(state, FULL_CELL_VIEW_ID),
    cardsViewState: ownProps.isDumbTable
      ? ownProps.exploreViewState
      : getViewState(state, LOAD_TRANSFORM_CARDS_VIEW_ID),
    ...explorePageProps,
  };
}

export default connect(mapStateToProps, {
  transformHistoryCheck,
  performTransform,
  accessEntity,
})(ExploreTableController);
