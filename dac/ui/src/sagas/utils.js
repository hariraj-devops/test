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
import { RSAA, isRSAA } from "redux-api-middleware";
import invariant from "invariant";
import deepEqual from "deep-equal";
import { get } from "lodash/object";
import { PageTypes } from "#oss/pages/ExplorePage/pageTypes";
import { excludePageType } from "#oss/pages/ExplorePage/pageTypeUtils";
import { constructFullPath } from "utils/pathUtils";
import { EXPLORE_PAGE_LOCATION_CHANGED } from "#oss/actions/explore/dataset/data";
import { log } from "#oss/utils/logger";
import exploreUtils from "#oss/utils/explore/exploreUtils";
import { rmProjectBase } from "dremio-ui-common/utilities/projectBase.js";
import { select } from "redux-saga/effects";
import { getExploreState } from "#oss/selectors/explore";

export const LOCATION_CHANGE = "@@router/LOCATION_CHANGE";

// This check is to ignore location change to the current location
export function getLocationChangePredicate(oldLocation) {
  return (action) => {
    const { payload } = action;
    if (!payload) {
      return false;
    }
    return (
      action.type === LOCATION_CHANGE &&
      (oldLocation.pathname !== payload.pathname ||
        !deepEqual(oldLocation.query, payload.query) ||
        !deepEqual(oldLocation.state, payload.state))
    );
  };
}

// is used for data load cancellation purposes
export const getExplorePageLocationChangePredicate = (
  prevRouteState,
  action,
) => {
  if (action.type !== EXPLORE_PAGE_LOCATION_CHANGED) {
    return [false];
  }

  prevRouteState = prevRouteState || {
    location: {},
    params: {},
  };

  const { newRouteState } = action;
  const oldLocation = prevRouteState.location;
  const newLocation = newRouteState.location;
  const oldPathname = rmProjectBase(oldLocation.pathname) || "/";
  const newPathname = rmProjectBase(newLocation.pathname) || "/";

  // after saving a dataset with a new name we change url, but version of a dataset is not changed.
  // We do not want to cancel data loading in that case.
  if (get(newLocation, "state.afterDatasetSave", false)) {
    return [false];
  }
  // we should not treat navigation between data, catalog, graph tabs as location change,
  // BUT navigation to/from details (join, convert column type etc.) should be treated as page change
  const pageTypeChanged =
    (prevRouteState.params.pageType === PageTypes.details) ^
    (newRouteState.params.pageType === PageTypes.details);

  const hasScriptId = !!(
    oldLocation.query?.scriptId || newLocation.query?.scriptId
  );

  const queryChanged = hasScriptId
    ? //Tabs: Don't consider it a page change when staying on the same tab/scriptId
      oldLocation.query?.scriptId !== newLocation.query?.scriptId
    : !deepEqual(oldLocation.query, newLocation.query);

  const result = Boolean(
    excludePageType(oldPathname) !== excludePageType(newPathname) ||
      pageTypeChanged ||
      queryChanged,
  );
  // do not check state here as in getLocationChangePredicate above. For case of 'save as' state is changed to show a modal,
  // but we should not cancel data loading
  // state would look like:
  // { modal: "SaveAsDatasetModal" }

  log("vb Explore page changed result", result);

  // When a user goes from Dataset page to SQL Runner page, this flag will reset the explorePage view object
  const shouldResetExploreViewState =
    oldPathname &&
    exploreUtils.isExploreDatasetPage(oldLocation) &&
    newPathname === "/new_query" &&
    !newLocation.state;

  return [result, shouldResetExploreViewState];
};

export function getActionPredicate(actionType, entity) {
  return (action) => {
    const actionTypeList =
      actionType instanceof String ? [actionType] : actionType;
    return (
      actionTypeList.indexOf(action.type) !== -1 &&
      (!entity || (action.meta && action.meta.entity) === entity)
    );
  };
}

export function getApiCallCompletePredicate(apiAction) {
  const actionTypes = getApiActionTypes(apiAction);
  const entity = getApiActionEntity(apiAction);
  return (action) => {
    const actionEntity = action.meta && action.meta.entity;
    if (actionTypes.indexOf(action.type) !== -1 && action.error) {
      return !entity || actionEntity === entity;
    }
    return getActionPredicate(actionTypes.slice(1), entity)(action);
  };
}

export function unwrapAction(wrappedAction) {
  let result = wrappedAction;
  while (typeof result === "function") {
    result = result((action) => action);
  }
  return result;
}

export function getApiActionTypes(apiAction) {
  const callApiAction = unwrapAction(apiAction);
  invariant(isRSAA(callApiAction), "Not a valid api action");
  return callApiAction[RSAA].types.map((actionType) =>
    typeof actionType === "string" ? actionType : actionType.type,
  );
}

export function getApiActionEntity(apiAction) {
  const callApiAction = unwrapAction(apiAction);
  invariant(isRSAA(callApiAction), "Not a valid api action");
  const actionTypes = callApiAction[RSAA].types;
  const successType = actionTypes && actionTypes[1];
  return successType && successType.meta && successType.meta.entity;
}

/**
 * Checks if {@see currentSql} differs from last saved sql {@see savedSql}.
 * If current {@see currentSql} = (null|undefined) that means, that sql was not changed
 * @param {string} savedSql - a last saved sql
 * @param {string} currentSql
 */
export const isSqlChanged = (savedSql, currentSql) =>
  currentSql !== null && currentSql !== undefined && currentSql !== savedSql;

/**
 * Checks whether sql or context changed. Also if current dataset is empty, which means
 * that we are working with a new query case.
 *
 * @export
 * @param {Immutable.Map} dataset
 * @param {Immutable.List} queryContext
 * @param {string} currentSql
 * @returns {boolean} true if a new dataset version has to be created
 */
export function needsTransform(dataset, queryContext, currentSql) {
  const savedContext = dataset && dataset.get("context");
  const isContextChanged =
    savedContext &&
    queryContext &&
    constructFullPath(savedContext) !== constructFullPath(queryContext);
  return (
    isSqlChanged(dataset.get("sql"), currentSql) ||
    isContextChanged ||
    !(dataset && dataset.get("datasetVersion"))
  );
}

export function* getActiveScriptId() {
  return (yield select(getExploreState))?.view?.activeScript?.id;
}

/**
 * Checks the passed in script ID agains the current activeScriptId in {explorePage.view.activeScript}.
 *
 * Returns the passed in ID (for the actions when originally dispatched) if they differ
 *
 * @param {string} activeScriptId
 */
export function* getTabForActions(activeScriptId) {
  const id = yield getActiveScriptId();

  if (id !== activeScriptId) return activeScriptId;
  else return "";
}
