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

import { useCallback, useEffect, useRef, useState } from "react";
import clsx from "clsx";
import { intl } from "#oss/utils/intl";
import { cloneDeep, debounce } from "lodash";
import Immutable from "immutable";
import { WithRouterProps, withRouter } from "react-router";
import { useResourceSnapshot } from "smart-resource/react";

import ContainsText from "../JobsFilters/ContainsText";
import StartTimeSelect from "../JobsFilters/StartTimeSelect";
import * as IntervalTypes from "../JobsFilters/IntervalTypes";
import FilterSelectMenu from "#oss/components/Fields/FilterSelectMenu";

import { UsersForJobsResource } from "#oss/exports/resources/UsersForJobsResource";
import { JobsQueryParams } from "dremio-ui-common/types/Jobs.types";
import { formatQueryState } from "dremio-ui-common/utilities/jobs.js";
import {
  FILTER_LABEL_IDS,
  GenericFilters,
  itemsForQueryTypeFilter,
  itemsForStateFilter,
  transformToItems,
  transformToSelectedItems,
} from "./utils";
import additionalJobsControls from "@inject/shared/AdditionalJobsControls";
import { QueuesResource } from "#oss/resources/QueuesResource";
import { getPrivilegeContext } from "dremio-ui-common/contexts/PrivilegeContext.js";
import { MultiCheckboxPopover } from "dremio-ui-lib/components";
import localStorageUtils from "#oss/utils/storageUtils/localStorageUtils";

import * as classes from "./JobsPageFilters.module.less";

type JobsFilterProps = {
  query: JobsQueryParams | undefined;
  manageColumns: any[];
  setManageColumns: React.Dispatch<any>;
} & WithRouterProps;

const JobsFilters = withRouter(
  ({
    query,
    router,
    location,
    manageColumns,
    setManageColumns,
  }: JobsFilterProps) => {
    const defaultStartTime = new Date(2015, 0).getTime();
    const startTime = query?.filters?.st?.[0] || defaultStartTime; // start from 2015
    const endTime = query?.filters?.st?.[1] || new Date().getTime(); // current time
    const [users] = useResourceSnapshot(UsersForJobsResource);
    const [queues] = useResourceSnapshot(QueuesResource);
    const [usersSearch, setUsersSearch] = useState("");
    const dragToRef = useRef(-1);
    const canViewUsersListing = getPrivilegeContext().canViewUsersListing();

    // Update filters by pushing to URL
    const updateQuery = useCallback(
      (query: JobsQueryParams) => {
        router.replace({
          ...location,
          pathname: location.pathname,
          query: formatQueryState(query),
        });
      },
      [location, router],
    );

    useEffect(() => {
      if (canViewUsersListing) {
        UsersForJobsResource.fetch({
          filter: usersSearch,
        });
      }
      additionalJobsControls?.().fetchQueue?.() && QueuesResource.fetch();
    }, [usersSearch, canViewUsersListing]);

    const debounceUpdateQuery = debounce((text) => {
      if (query) {
        if (text) {
          updateQuery({
            ...query,
            filters: { ...query.filters, contains: [text] },
          });
        } else {
          delete query.filters.contains;
          updateQuery(query);
        }
      }
    }, 300);

    const handleEnterText = (text: string) => {
      debounceUpdateQuery(text);
    };

    const handleUpdateStartTime = (
      type: string,
      rangeObj: Immutable.List<any>,
    ) => {
      localStorageUtils?.setJobsDateTimeFilter(type);
      const range = rangeObj && rangeObj.toJS && rangeObj.toJS();
      const fromDate = range && range[0];
      const toDate = range && range[1];
      const fromDateTimestamp = fromDate && fromDate.toDate().getTime();
      const toDateTimestamp = toDate && toDate.toDate().getTime();

      if (query) {
        if (type === IntervalTypes.ALL_TIME_INTERVAL) {
          // if we are showing all time, clear out the time filter
          delete query.filters[GenericFilters.st];
          updateQuery(query);
        } else {
          updateQuery({
            ...query,
            filters: {
              ...query.filters,
              [GenericFilters.st]: [fromDateTimestamp, toDateTimestamp],
            },
          });
        }
      }
    };

    const addValueToFilter = (type: string, value: string) => {
      const values = query?.filters?.[type] || [];
      if (!values.includes(value) && query) {
        values.push(value);
        updateQuery({
          ...query,
          filters: { ...query.filters, [type]: values },
        } as JobsQueryParams);
      }
    };

    const removeValueFromFilter = (type: string, value: string) => {
      const values = query?.filters?.[type] || [];
      const index = values.indexOf(value);
      if (index !== -1 && query) {
        if (values.length > 1) {
          values.splice(index, 1);
          updateQuery({
            ...query,
            filters: { ...query.filters, [type]: values },
          } as JobsQueryParams);
        } else {
          delete query.filters[type];
          updateQuery(query);
        }
      }
    };

    const handleDragMove = (_: number, hoverIndex: number) => {
      dragToRef.current = hoverIndex;
    };

    const handleDragEnd = (itemIdx: number) => {
      const toIdx = dragToRef.current;
      if (itemIdx === toIdx || toIdx === -1) return;
      const cols = cloneDeep(manageColumns);

      const range =
        toIdx > itemIdx
          ? { start: itemIdx, stop: toIdx }
          : { start: toIdx, stop: itemIdx };
      for (let i = range.start; i <= range.stop; i++) {
        if (i === range.start && toIdx > itemIdx) {
          cols[i].sort = toIdx;
        } else if (i === range.stop && itemIdx > toIdx) {
          cols[i].sort = toIdx;
        } else {
          cols[i].sort = cols[i].sort + (itemIdx > toIdx ? 1 : -1);
        }
      }
      setManageColumns(cols.sort((a, b) => (a.sort > b.sort ? 1 : -1)));
    };

    const handleSelectColumn = (item: string) => {
      const cols = cloneDeep(manageColumns);
      const col = cols.find((col) => col.id === item);
      col.selected = !col.selected;
      setManageColumns(cols);
    };

    const renderFilterMenu = (filterKey: keyof typeof GenericFilters) => {
      const items =
        filterKey === GenericFilters.usr
          ? users || []
          : filterKey === GenericFilters.jst
            ? itemsForStateFilter
            : filterKey === GenericFilters.qt
              ? itemsForQueryTypeFilter
              : queues || [];
      const listLabel = intl.formatMessage({
        id: FILTER_LABEL_IDS[filterKey],
      });
      return (
        <MultiCheckboxPopover
          searchTerm={usersSearch}
          listItems={items}
          listLabel={listLabel}
          selectedtListItems={
            query?.filters?.[filterKey]?.length
              ? items.filter((item) =>
                  query.filters[filterKey].includes(item.id),
                )
              : []
          }
          onItemSelect={(id: string) => addValueToFilter(filterKey, id)}
          onItemUnselect={(id: string) => removeValueFromFilter(filterKey, id)}
          className={classes[`jobs-filters__${filterKey}`]}
          {...(filterKey === GenericFilters.usr && {
            searchPlaceholder: "Search users",
            hasSearch: true,
            onSearch: (value: React.ChangeEvent<HTMLInputElement>) =>
              setUsersSearch(value.target.value),
          })}
          ariaLabel={`${listLabel} filter`}
        />
      );
    };

    return (
      <div className={classes["jobs-filters"]}>
        <div className={classes["jobs-filters__left"]}>
          <ContainsText
            className={classes["jobs-filters__left__search"]}
            searchIconClass="ml-05"
            defaultValue={query?.filters?.contains?.[0] || ""}
            id="containsText"
            onEnterText={handleEnterText}
          />
          <StartTimeSelect
            iconStyle={styles.arrow}
            popoverFilters={clsx(
              "margin-top--quarter",
              classes["jobs-filters__left__start-time"],
            )}
            selectedToTop={false}
            onChange={handleUpdateStartTime}
            id="startTimeFilter"
            defaultType={IntervalTypes.ALL_TIME_INTERVAL}
            startTime={startTime}
            endTime={endTime}
          />
          {renderFilterMenu(GenericFilters.jst)}
          {renderFilterMenu(GenericFilters.qt)}
          {canViewUsersListing && renderFilterMenu(GenericFilters.usr)}
          {queues?.length > 0 &&
            additionalJobsControls?.().renderFilterMenu?.() &&
            renderFilterMenu(GenericFilters.qn)}
        </div>
        <FilterSelectMenu
          selectedToTop={false}
          noSearch
          selectedValues={Immutable.fromJS(
            transformToSelectedItems(manageColumns),
          )}
          onItemSelect={handleSelectColumn}
          onItemUnselect={handleSelectColumn}
          items={transformToItems(manageColumns)}
          label={intl.formatMessage({ id: "Common.Manage.Columns" })}
          name="col"
          showSelectedLabel={false}
          iconId="interface/manage-column"
          hasSpecialIcon
          iconClass={classes["jobs-filters__column-select__setting-icon"]}
          checkBoxClass="column-select-popup__check-box"
          selectClass={classes["jobs-filters__column-select"]}
          popoverFilters="margin-top--half"
          onDragMove={handleDragMove}
          onDragEnd={handleDragEnd}
          isDraggable
          hasIconFirst
          selectType="button"
          popoverContentClass={classes["column-select-popup"]}
        />
      </div>
    );
  },
);

const styles = {
  arrow: {
    color: "var(--icon--primary)",
    fontSize: 12,
    height: 24,
    width: 24,
    display: "flex",
    justifyContent: "center",
    alignItems: "center",
  },
};

export default JobsFilters;
