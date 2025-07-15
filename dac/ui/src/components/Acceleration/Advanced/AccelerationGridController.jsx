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
import { connect } from "react-redux";
import Immutable from "immutable";
import PropTypes from "prop-types";
import { get } from "lodash/object";

import "#oss/uiTheme/less/commonModifiers.less";
import "#oss/uiTheme/less/Acceleration/Acceleration.less";

import AccelerationGridControllerMixin from "dyn-load/components/Acceleration/Advanced/AccelerationGridControllerMixin";

import AccelerationGrid from "components/Acceleration/Advanced/AccelerationGrid";
import AccelerationGridSubCell from "components/Acceleration/Advanced/AccelerationGridSubCell";
import CellPopover from "components/Acceleration/Advanced/CellPopover";
import {
  cellType,
  fieldTypes,
  granularityValue,
} from "#oss/constants/AccelerationConstants";
import { TIMESTAMP } from "#oss/constants/DataTypes";
import {
  findAllMeasureTypes,
  getDefaultMeasureTypes,
} from "utils/accelerationUtils";
import { getSupportFlags } from "#oss/selectors/supportFlags";
import { ALLOW_REFLECTION_PARTITION_TRANFORMS } from "#oss/exports/endpoints/SupportFlags/supportFlagConstants";
import {
  PartitionTransformations,
  ReflectionDataType,
} from "dremio-ui-common/sonar/reflections/ReflectionDataTypes.js";

// AccelerationGridController is used for behavior definition cells on Raw/Aggregation
// Aggregation:
//   User can enable one or both of 'Dimension' or 'Measure' per field
//   If user didn't enable 'Dimension' field but enables sort/partition/distribution field, we should enable 'Dimension'.
//   If user disabled 'Dimension' field, we should disable sort/partition/distribution fields.
// Raw:
//   If user doesn't enable 'Display' field but enables sort/partition/distribution field, we should enable 'Display' field.
//   If user disabled 'Display' field, we should disable sort/partition/distribution fields.
// -------------------------------------------------------------------------
// In Aggregate mode: at least one dimension + measure per layout is required
// In Raw mode: at least one display per layout is required

@AccelerationGridControllerMixin
export class AccelerationGridController extends Component {
  static propTypes = {
    dataset: PropTypes.instanceOf(Immutable.Map).isRequired,
    reflections: PropTypes.instanceOf(Immutable.Map).isRequired,
    layoutFields: PropTypes.array,
    activeTab: PropTypes.string,
    canAlter: PropTypes.any,
    allowPartitionTransform: PropTypes.bool,
    loadingRecommendations: PropTypes.bool,
    isRecommendation: PropTypes.bool,
    isSubmitting: PropTypes.bool,
  };

  state = {
    filter: "",
    currentCell: {
      labelCell: "",
      columnIndex: null,
      rowIndex: null,
    },
    anchorEl: null,
  };

  componentWillUnmount() {
    if (this.props.isRecommendation) {
      document.body.removeEventListener("click", this.trackHandleCloseClick);
    }
  }

  onFilterChange = (filter) => {
    this.setState({
      filter,
    });
  };

  getRowByIndex = (rowIndex) => {
    const allColumns = this.props.dataset.get("fields");
    const datasetSchema = this.filterFieldList(allColumns).toJS();
    return datasetSchema.find((row, i) => i === rowIndex);
  };

  filterFieldList = (allColumns) => {
    const { filter } = this.state;
    return allColumns.filter((item) =>
      item.get("name").toLowerCase().includes(filter.trim().toLowerCase()),
    );
  };

  findCurrentIndexInFieldsList = (currentColumn, field, currentColumnIndex) => {
    const { columnIndex } = this.state.currentCell;
    const colIndex =
      currentColumnIndex || currentColumnIndex === 0
        ? currentColumnIndex
        : columnIndex;

    if (
      !this.props.layoutFields[colIndex] ||
      !this.props.layoutFields[colIndex][field]
    ) {
      return -1;
    }

    return this.props.layoutFields[colIndex][field].findIndex((col) => {
      if (
        col.name &&
        (col.name.value?.name ?? col.name.value) === currentColumn.name
      ) {
        return true;
      }
    });
  };

  findCurrentColumnInLayouts = (field, rowIndex, columnIndex) => {
    const { layoutFields, dataset } = this.props;
    const allColumns = dataset.get("fields");
    const datasetSchema = this.filterFieldList(allColumns).toJS();
    const currentColumn = datasetSchema.find((column, i) => i === rowIndex);

    return (
      layoutFields[columnIndex] &&
      layoutFields[columnIndex][field].find(
        (col) => (col.name.value.name ?? col.name.value) === currentColumn.name,
      )
    );
  };

  removeFieldByIndex = (currentColumn, field, columnIndex) => {
    const index = this.findCurrentIndexInFieldsList(
      currentColumn,
      field,
      columnIndex,
    );
    if (index !== -1) {
      this.props.layoutFields[columnIndex][field].removeField(index);
    }
  };

  addFieldByIndex = (currentColumn, field, columnIndex) => {
    const index = this.findCurrentIndexInFieldsList(
      currentColumn,
      field,
      columnIndex,
    );
    if (index === -1) {
      this.props.layoutFields[columnIndex][field].addField(currentColumn);
    }
  };

  trackHandleCloseClick = () => {
    // Workaround --> popover clickAwayListener isn't caught in new ModalContainer <dialog> element
    if (this.state.anchorEl) {
      this.handleRequestClose();
    }
  };

  handleRequestClose = () => {
    this.setState({
      anchorEl: null,
      currentCell: {
        labelCell: "",
        field: "",
        columnIndex: null,
        rowIndex: null,
      },
    });
    if (this.props.isRecommendation) {
      document.body.removeEventListener("click", this.trackHandleCloseClick);
    }
  };

  toggleField = (field, columnIndex, rowIndex) => {
    const { layoutFields } = this.props;
    const currentRow = this.getRowByIndex(rowIndex);
    const isFieldSelected = this.findCurrentColumnInLayouts(
      field,
      rowIndex,
      columnIndex,
    );
    if (isFieldSelected) {
      this.removeFieldByIndex(currentRow, field, columnIndex);
    } else {
      layoutFields[columnIndex][field].addField(currentRow);
    }
  };

  findPartitionIndex = (updatedRow, columnIndex) => {
    const { layoutFields } = this.props;

    const allPartitions = layoutFields[columnIndex][fieldTypes.partition];

    for (const [index, partition] of allPartitions.entries()) {
      const curPartitionName =
        partition.name.value.name ?? partition.name.value;

      if (curPartitionName === (updatedRow.name.name ?? updatedRow.name)) {
        return index;
      }
    }

    return -1;
  };

  updatePartitionTransformationField = (updatedRow, columnIndex) => {
    const { layoutFields } = this.props;

    const partitionIndex = this.findPartitionIndex(updatedRow, columnIndex);

    if (partitionIndex !== -1) {
      layoutFields[columnIndex][fieldTypes.partition].removeField(
        partitionIndex,
      );
    }

    layoutFields[columnIndex][fieldTypes.partition].addField(updatedRow);
  };

  applyPartitionRecommendations = (columnIndex, partitionRecommendations) => {
    const { layoutFields } = this.props;

    const partitionFields = layoutFields[columnIndex][fieldTypes.partition];

    partitionFields.forEach(() => partitionFields.removeField());

    partitionRecommendations.forEach((partitionRecommendation) => {
      partitionFields.addField({ name: { ...partitionRecommendation } });
      this.applyRecommendationConstraints(columnIndex, partitionRecommendation);
    });
  };

  getFieldTypeByFieldName = (fieldName) => {
    const { dataset } = this.props;
    const allFieldTypes = dataset ? dataset.toJS().fields : [];
    const fieldTypeDefinition = allFieldTypes.find(
      (field) => field.name === fieldName,
    );
    return get(fieldTypeDefinition, "type.name", "");
  };

  isFieldTypeTimestamp = (fieldName) => {
    return this.getFieldTypeByFieldName(fieldName) === TIMESTAMP;
  };

  // If a Dimension field's granularity is changed from Original -> Date we need to check if
  // a partition transformation exists and reset it to IDENTITY if it was set to HOUR.
  resetPartitionTransformationIfNeeded = (newValue, columnIndex, rowIndex) => {
    const { layoutFields } = this.props;

    const granularity = this.getColumnGranularity(rowIndex, columnIndex);

    if (
      granularity.alt === "Original" &&
      newValue === ReflectionDataType.DATE
    ) {
      const currentRow = this.getRowByIndex(rowIndex);

      const partitionIndex = this.findPartitionIndex(currentRow, columnIndex);

      if (partitionIndex !== -1) {
        const partition =
          layoutFields[columnIndex][fieldTypes.partition][partitionIndex];

        const partitionType = partition.name.value.transform?.type;

        if (partitionType === PartitionTransformations.HOUR) {
          layoutFields[columnIndex][fieldTypes.partition].removeField(
            partitionIndex,
          );

          layoutFields[columnIndex][fieldTypes.partition].addField(currentRow);
        }
      }
    }
  };

  setFieldGranularity = (newValue, columnIndex, rowIndex) => {
    const { allowPartitionTransform } = this.props;

    if (allowPartitionTransform) {
      this.resetPartitionTransformationIfNeeded(
        newValue,
        columnIndex,
        rowIndex,
      );
    }

    const selectedField = this.findCurrentColumnInLayouts(
      fieldTypes.dimension,
      rowIndex,
      columnIndex,
    );
    if (selectedField.granularity) {
      selectedField.granularity.onChange(newValue);
    }
  };

  setFieldMeasureType = (newValue, columnIndex, rowIndex) => {
    const selectedField = this.findCurrentColumnInLayouts(
      fieldTypes.measure,
      rowIndex,
      columnIndex,
    );
    if (selectedField.measureTypeList) {
      selectedField.measureTypeList.onChange(newValue);
    }
    this.setState({
      currentCell: { ...this.state.currentCell, measureTypeList: newValue },
    });
  };

  findCurrentCellValue = (currentCell) => {
    // if the cell is dimension and its type is timestamp, return granularity, else return null
    if (!currentCell || currentCell.labelCell !== cellType.dimension)
      return null;

    const currentColumn = this.findCurrentColumnInLayouts(
      fieldTypes.dimension,
      currentCell.rowIndex,
      currentCell.columnIndex,
    );
    if (currentColumn && this.isFieldTypeTimestamp(currentColumn.name.value)) {
      return get(currentColumn, "granularity.value", granularityValue.date);
    }
    return null;
  };

  findCurrentCellAllMeasureTypes = (currentCell) => {
    if (this.props.activeTab === "raw") return null;
    const currentColumn =
      currentCell &&
      this.findCurrentColumnInLayouts(
        fieldTypes.measure,
        currentCell.rowIndex,
        currentCell.columnIndex,
      );
    const fieldName = get(currentColumn, "name.value");
    return fieldName
      ? findAllMeasureTypes(this.getFieldTypeByFieldName(fieldName))
      : null;
  };

  findCurrentCellDefaultMeasureTypes = (currentCell) => {
    const currentColumn =
      currentCell &&
      this.findCurrentColumnInLayouts(
        fieldTypes.measure,
        currentCell.rowIndex,
        currentCell.columnIndex,
      );
    const fieldName = get(currentColumn, "name.value");
    return fieldName
      ? getDefaultMeasureTypes(this.getFieldTypeByFieldName(fieldName))
      : null;
  };

  getMeasureFieldSubValue = (fieldType, measureTypeList) => {
    const measureTypeListValue = get(measureTypeList, "value");
    const subValue = { text: " ", alt: "Default measures" }; //start with default
    if (measureTypeListValue && measureTypeListValue.length) {
      // compare default measures for a type with the actual measureTypeList array entries
      const defaultMeasureTypes = getDefaultMeasureTypes(fieldType);
      if (
        measureTypeListValue.length !== defaultMeasureTypes.length ||
        // assuming arrays don't have duplicates reduce works here at least as well as lodash .difference
        !measureTypeListValue.reduce((arraysAreEqual, type) => {
          return arraysAreEqual && defaultMeasureTypes.includes(type);
        }, true)
      ) {
        subValue.text = " ";
        subValue.alt = "Custom measures";
      }
    }
    return subValue;
  };

  toggleSortField = (field, columnIndex, rowIndex) => {
    const { layoutFields } = this.props;
    const currentRow = this.getRowByIndex(rowIndex);

    if (
      field &&
      !this.findCurrentColumnInLayouts(field, rowIndex, columnIndex)
    ) {
      layoutFields[columnIndex][field].addField(currentRow);
    } else {
      this.removeFieldByIndex(currentRow, "sortFields", columnIndex);
    }
  };

  handleOnSelectMenuItem = (fieldType, newValue) => {
    const { columnIndex, rowIndex } = this.state.currentCell;
    if (fieldType === cellType.dimension) {
      this.setFieldGranularity(newValue, columnIndex, rowIndex);
      this.handleRequestClose();
    } else if (fieldType === cellType.measure) {
      this.setFieldMeasureType(newValue, columnIndex, rowIndex);
    }
  };

  handleOnSortCheckboxItem(columnIndex, rowIndex) {
    this.toggleSortField(fieldTypes.sort, columnIndex, rowIndex);
    this.applyConstraintsNext(fieldTypes.sort, columnIndex, rowIndex);
    this.handleRequestClose();
  }

  handleClickSubValue = (e, currentCell) => {
    const currentCellValue = this.findCurrentCellValue(currentCell);
    if (currentCellValue) {
      currentCell.value = currentCellValue;
    }

    const currentCellAllMeasureTypes =
      this.findCurrentCellAllMeasureTypes(currentCell);
    if (currentCellAllMeasureTypes) {
      const currentCellField = this.findCurrentColumnInLayouts(
        fieldTypes.measure,
        currentCell.rowIndex,
        currentCell.columnIndex,
      );
      const currentMeasureTypeList = get(
        currentCellField,
        "measureTypeList.value",
      );
      currentCell.measureTypeAll = currentCellAllMeasureTypes;
      currentCell.measureTypeList =
        currentMeasureTypeList && currentMeasureTypeList.length
          ? currentMeasureTypeList
          : this.findCurrentCellDefaultMeasureTypes(currentCell);
    }

    this.setState({
      currentCell,
      anchorEl: e.currentTarget,
    });
    if (this.props.isRecommendation) {
      document.body.addEventListener("click", this.trackHandleCloseClick);
    }
    e.stopPropagation();
  };

  handleOnCheckboxItem = (field, columnIndex, rowIndex) => {
    this.toggleField(field, columnIndex, rowIndex);
    this.applyConstraintsNext(field, columnIndex, rowIndex);
  };

  handleOnPartitionMenuItem = (updatedRow, columnIndex) => {
    this.updatePartitionTransformationField(updatedRow, columnIndex);
  };

  applyConstraintsNext = (field, columnIndex, rowIndex) => {
    //addField and removeField work async so constraints should calculated on next run
    setTimeout(() => this.applyConstraints(field, columnIndex, rowIndex), 0);
  };

  applyConstraints = (field, columnIndex, rowIndex) => {
    const { activeTab } = this.props;
    if (activeTab !== "raw") {
      this.applyAggregationConstraints(field, columnIndex, rowIndex);
    } else {
      this.applyRawConstraints(field, columnIndex, rowIndex);
    }
  };

  applyAggregationConstraints = (field, columnIndex, rowIndex) => {
    const currentRow = this.getRowByIndex(rowIndex);
    const dimensionSelected = this.findCurrentColumnInLayouts(
      fieldTypes.dimension,
      rowIndex,
      columnIndex,
    );
    const fieldSelected =
      field && this.findCurrentColumnInLayouts(field, rowIndex, columnIndex);

    if (field === fieldTypes.dimension && !dimensionSelected) {
      this.removeFieldByIndex(currentRow, fieldTypes.sort, columnIndex);
      this.removeFieldByIndex(currentRow, fieldTypes.partition, columnIndex);
      this.removeFieldByIndex(currentRow, fieldTypes.distribution, columnIndex);
    }
    if (
      [fieldTypes.partition, fieldTypes.distribution, fieldTypes.sort].includes(
        field,
      ) &&
      fieldSelected
    ) {
      this.addFieldByIndex(currentRow, fieldTypes.dimension, columnIndex);
    }
  };

  applyRawConstraints = (field, columnIndex, rowIndex) => {
    const { layoutFields } = this.props;
    const currentRow = this.getRowByIndex(rowIndex);
    const displaySelected = this.findCurrentColumnInLayouts(
      fieldTypes.display,
      rowIndex,
      columnIndex,
    );
    const displayDependentOn = [
      fieldTypes.sort,
      fieldTypes.partition,
      fieldTypes.distribution,
    ];

    if (displayDependentOn.includes(field) && !displaySelected) {
      layoutFields[columnIndex].displayFields.addField(currentRow);
    }
    if (field === "displayFields" && !displaySelected) {
      for (const subField of displayDependentOn) {
        this.removeFieldByIndex(currentRow, subField, columnIndex);
      }
    }
  };

  applyRecommendationConstraints = (columnIndex, partitionRecommendation) => {
    const { activeTab } = this.props;

    this.addFieldByIndex(
      { name: partitionRecommendation.name },
      activeTab === "raw" ? fieldTypes.display : fieldTypes.dimension,
      columnIndex,
    );
  };

  // all Dimension fields have DATE granularity by default, but it should only be read for TIMESTAMP columns
  getColumnGranularity = (rowIndex, columnIndex) => {
    const { activeTab } = this.props;

    const isRaw = activeTab === "raw";

    let granularity;

    if (!isRaw) {
      const currentRow = this.getRowByIndex(rowIndex);

      if (currentRow.type.name === ReflectionDataType.TIMESTAMP) {
        const currentColumn = this.findCurrentColumnInLayouts(
          fieldTypes.dimension,
          rowIndex,
          columnIndex,
        );

        granularity =
          get(currentColumn, "granularity.value") === granularityValue.normal
            ? { code: "O", alt: "Original" }
            : { code: "D", alt: "Date" }; //TODO i18n
      }
    }

    return granularity;
  };

  renderCell = (fieldType, rowIndex, columnIndex, shouldHide) => {
    const onCheckClick = () =>
      this.handleOnCheckboxItem(fieldType, columnIndex, rowIndex);
    const isChecked = !!this.findCurrentColumnInLayouts(
      fieldType,
      rowIndex,
      columnIndex,
    );
    const isLastCell =
      fieldType === fieldTypes.partition
        ? !this.shouldShowDistribution()
        : fieldType === fieldTypes.distribution;
    return (
      <AccelerationGridSubCell
        onClick={onCheckClick}
        isChecked={isChecked}
        isLastCell={isLastCell}
        hasPermission={this.checkForPermissionToAlter()}
        isRecommendation={this.props.isRecommendation || shouldHide}
      />
    );
  };

  renderDimensionCell = (rowIndex, columnIndex, shouldHide) => {
    const currentColumn = this.findCurrentColumnInLayouts(
      fieldTypes.dimension,
      rowIndex,
      columnIndex,
    );
    const granularity =
      get(currentColumn, "granularity.value") === granularityValue.normal
        ? { code: "O", alt: "Original" }
        : { code: "D", alt: "Date" }; //TODO i18n

    const subValue =
      (currentColumn &&
        this.isFieldTypeTimestamp(currentColumn.name.value) &&
        granularity.code) ||
      "";
    const onCheckClick = () =>
      this.handleOnCheckboxItem(fieldTypes.dimension, columnIndex, rowIndex);
    const onValueClick = (e) =>
      this.handleClickSubValue(e, {
        columnIndex,
        rowIndex,
        labelCell: cellType.dimension,
        field: fieldTypes.dimension,
      });
    const isChecked = !!currentColumn;

    return (
      <AccelerationGridSubCell
        onClick={onCheckClick}
        hasPermission={this.checkForPermissionToAlter()}
        isRecommendation={this.props.isRecommendation || shouldHide}
        isChecked={isChecked}
        subValue={subValue}
        subValueAltText={`${granularity.alt} granularity`}
        onValueClick={onValueClick}
      />
    );
  };

  renderSortCell = (rowIndex, columnIndex, shouldHide) => {
    const { dataset, isRecommendation } = this.props;
    const allColumns = dataset.get("fields");
    const datasetSchema = this.filterFieldList(allColumns).toJS();
    const currentColumn = datasetSchema.find((column, i) => i === rowIndex);
    const order = this.findCurrentIndexInFieldsList(
      currentColumn,
      fieldTypes.sort,
      columnIndex,
    );
    const sortFields = get(
      this.props,
      `layoutFields[${columnIndex}].sortFields`,
    );
    const haveMoreThanOneSortFields = sortFields && sortFields.length > 1;

    const onCheckClick = () =>
      this.handleOnSortCheckboxItem(columnIndex, rowIndex);
    const onValueClick = haveMoreThanOneSortFields
      ? (e) =>
          this.handleClickSubValue(e, {
            columnIndex,
            rowIndex,
            labelCell: cellType.sort,
            field: fieldTypes.sort,
          })
      : undefined;
    const isChecked = order !== -1;
    const subValue = haveMoreThanOneSortFields ? `${order + 1}` : null;
    const subValueAltText = "Sort order";

    return (
      <AccelerationGridSubCell
        onClick={onCheckClick}
        hasPermission={this.checkForPermissionToAlter()}
        isRecommendation={isRecommendation || shouldHide}
        isChecked={isChecked}
        subValue={subValue}
        subValueAltText={subValueAltText}
        onValueClick={onValueClick}
      />
    );
  };

  renderMeasureCell = (rowIndex, columnIndex, shouldHide) => {
    const currentColumn = this.findCurrentColumnInLayouts(
      fieldTypes.measure,
      rowIndex,
      columnIndex,
    );
    const currentColumnType =
      (currentColumn &&
        this.getFieldTypeByFieldName(currentColumn.name.value)) ||
      "";
    const isChecked = !!currentColumn;
    const subValue =
      currentColumn &&
      this.getMeasureFieldSubValue(
        currentColumnType,
        currentColumn.measureTypeList,
      );
    const subValueText = (subValue && subValue.text) || "";
    const subValueAltText = (subValue && subValue.alt) || "";
    const onCheckClick = () =>
      this.handleOnCheckboxItem(fieldTypes.measure, columnIndex, rowIndex);
    const onValueClick = (e) =>
      this.handleClickSubValue(e, {
        columnIndex,
        rowIndex,
        labelCell: cellType.measure,
        field: fieldTypes.measure,
      });

    return (
      <AccelerationGridSubCell
        onClick={onCheckClick}
        hasPermission={this.checkForPermissionToAlter()}
        isRecommendation={this.props.isRecommendation || shouldHide}
        isChecked={isChecked}
        subValue={subValueText}
        subValueAltText={subValueAltText}
        onValueClick={onValueClick}
      />
    );
  };

  renderPartitionCell = (rowIndex, columnIndex, shouldHide) => {
    const selectedField = this.findCurrentColumnInLayouts(
      fieldTypes.partition,
      rowIndex,
      columnIndex,
    );

    const currentRow = this.getRowByIndex(rowIndex);

    const granularity = this.getColumnGranularity(rowIndex, columnIndex);

    const onCheckClick = () =>
      this.handleOnCheckboxItem(fieldTypes.partition, columnIndex, rowIndex);

    const setPartitionTransformation = (transformType, count) => {
      const updatedRow = { ...currentRow };

      updatedRow.name = {
        name: updatedRow.name,
        transform: {
          type: transformType,
          ...(transformType === PartitionTransformations.BUCKET && {
            bucketTransform: { bucketCount: count },
          }),
          ...(transformType === PartitionTransformations.TRUNCATE && {
            truncateTransform: { truncateLength: count },
          }),
        },
      };

      this.handleOnPartitionMenuItem(updatedRow, columnIndex);
    };

    const isChecked = !!this.findCurrentColumnInLayouts(
      fieldTypes.partition,
      rowIndex,
      columnIndex,
    );

    const isLastCell = !this.shouldShowDistribution();

    return (
      <AccelerationGridSubCell
        onClick={onCheckClick}
        isChecked={isChecked}
        altText="Partition transform"
        currentRow={currentRow}
        selectedField={selectedField}
        granularity={granularity}
        isLastCell={isLastCell}
        setPartitionTransformation={setPartitionTransformation}
        hasPermission={this.checkForPermissionToAlter()}
        isRecommendation={this.props.isRecommendation || shouldHide}
      />
    );
  };

  /**
   * Render AccelerationGrid cell, whis is really a row of selected cell types from
   *   [display, dimension, measure, sort, partition, distribution]
   * @param rowIndex
   * @param columnIndex - an index of a reflection
   * @param layout - the item being rendered
   * @return {*}
   */
  renderBodyCell = (rowIndex, columnIndex, layout) => {
    const { allowPartitionTransform, canAlter } = this.props;
    const backgroundColor =
      rowIndex % 2 ? "--bgColor-advEnDark" : "--bgColor-advEnLight";
    const disabledBackgroundColor =
      rowIndex % 2 ? "--bgColor-advDisDark" : "--bgColor-advDisLight";

    const isRaw = this.props.activeTab === "raw";
    const showDistributionCell = this.shouldShowDistribution();
    const shouldHide = this.shouldHideUpdates(layout.id.value);
    return (
      <div
        className={`AccelerationGridController__cell --bColor-bottom
        ${canAlter && !shouldHide ? backgroundColor : disabledBackgroundColor}
        `}
        key={`${rowIndex}-${columnIndex}`}
        data-qa={`acceleration-cell-${rowIndex + 1}-${columnIndex + 1}`}
      >
        {isRaw &&
          this.renderCell(
            fieldTypes.display,
            rowIndex,
            columnIndex,
            shouldHide,
          )}
        {!isRaw && this.renderDimensionCell(rowIndex, columnIndex, shouldHide)}
        {!isRaw && this.renderMeasureCell(rowIndex, columnIndex, shouldHide)}
        {this.renderSortCell(rowIndex, columnIndex, shouldHide)}
        {allowPartitionTransform
          ? this.renderPartitionCell(rowIndex, columnIndex, shouldHide)
          : this.renderCell(
              fieldTypes.partition,
              rowIndex,
              columnIndex,
              shouldHide,
            )}
        {showDistributionCell &&
          this.renderCell(
            fieldTypes.distribution,
            rowIndex,
            columnIndex,
            shouldHide,
          )}
      </div>
    );
  };

  render() {
    const {
      layoutFields,
      dataset,
      reflections,
      activeTab,
      canAlter,
      loadingRecommendations,
      isRecommendation,
      isSubmitting,
    } = this.props;
    const { columnIndex } = this.state.currentCell;
    const allColumns = dataset.get("fields");
    const columns = this.filterFieldList(allColumns);
    const shouldDisable = this.shouldDisablePopover();

    return (
      <div className={"AccelerationGridController"}>
        <AccelerationGrid
          activeTab={activeTab}
          renderBodyCell={this.renderBodyCell}
          columns={columns}
          allColumns={allColumns}
          shouldShowDistribution={this.shouldShowDistribution()}
          applyPartitionRecommendations={this.applyPartitionRecommendations}
          filter={this.state.filter}
          onFilterChange={this.onFilterChange}
          layoutFields={layoutFields}
          reflections={reflections}
          hasPermission={canAlter}
          loadingRecommendations={loadingRecommendations}
          isRecommendation={isRecommendation}
          isSubmitting={isSubmitting}
        />
        <CellPopover
          currentCell={this.state.currentCell}
          anchorEl={this.state.anchorEl}
          sortFields={
            layoutFields[columnIndex] && layoutFields[columnIndex].sortFields
          }
          onRequestClose={this.handleRequestClose}
          onSelectMenuItem={this.handleOnSelectMenuItem}
          hasPermission={
            this.checkForPermissionToAlter() &&
            !isRecommendation &&
            !shouldDisable
          }
          container={
            isRecommendation
              ? document.body.querySelector(".dremio-modal-container")
              : document.body
          }
        />
      </div>
    );
  }
}

const mapStateToProps = (state) => {
  return {
    allowPartitionTransform:
      getSupportFlags(state)[ALLOW_REFLECTION_PARTITION_TRANFORMS],
  };
};

export default connect(mapStateToProps)(AccelerationGridController);
