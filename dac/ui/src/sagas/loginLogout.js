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
import { all, call, fork, put, takeLatest } from "redux-saga/effects";
import { push } from "react-router-redux";

import { log } from "#oss/utils/logger";
import {
  LOGIN_USER_SUCCESS,
  LOGOUT_USER_SUCCESS,
  NO_USERS_ERROR,
  UNAUTHORIZED_ERROR,
} from "#oss/actions/account";
import socket from "@inject/utils/socket";
import localStorageUtils from "@inject/utils/storageUtils/localStorageUtils";
import { isAuthorized } from "@inject/sagas/utils/isAuthorized";
import { default as handleAppInitHelper } from "@inject/sagas/utils/handleAppInit";
import { appInitComplete } from "#oss/actions/app";
import { removeLastSession, setLastSession } from "#oss/utils/lastSession";
import { queryClient } from "#oss/queryClient";

//#region Route constants. Unfortunately should store these constants here (not in routes.js) to
// avoid module circular references

export const SIGNUP_PATH = "/signup";
export const LOGIN_PATH = "/login";
export const SSO_LANDING_PATH = "/login/sso/landing";

export function getLoginUrl(preserveRedirect = false) {
  if (!preserveRedirect || window.location.pathname === "/") {
    return LOGIN_PATH;
  }
  return `${LOGIN_PATH}?redirect=${encodeURIComponent(
    window.location.href.slice(window.location.origin.length),
  )}`;
}

//#endregion

export function* afterLogin() {
  yield takeLatest(LOGIN_USER_SUCCESS, handleLogin);
}

export function* afterLogout() {
  yield takeLatest(LOGOUT_USER_SUCCESS, handleLogout);
}

export function* afterAppStop() {
  yield takeLatest([NO_USERS_ERROR, UNAUTHORIZED_ERROR], handleAppStop);
}

//export for testing
export function* handleLogin({ payload }) {
  log("Add user data to local storage", payload);
  yield call([localStorageUtils, localStorageUtils.setUserData], payload);
  setLastSession({ username: payload.userName });
  yield call(handleAppInit);
  queryClient.resetQueries(["session-introspection"]);
}

// export for testing only
export function* checkAppState() {
  // We should always make this call to cover first user flow.
  const isUserValid = yield call(isAuthorized);
  log("Is user valid", isUserValid);

  if (!isUserValid) {
    log("clear user data and token as a user is invalid");
    yield call([localStorageUtils, localStorageUtils.clearUserData]);
    yield put(appInitComplete());
    return;
  }
  yield call(handleAppInit);
  yield put(appInitComplete());
}

let isAppInit = false;
//export for testing
export const resetAppInitState = () => {
  isAppInit = false;
};

export function* handleAppInit() {
  if (isAppInit) {
    log("App is already initialiazed. Nothing to do.");
    return;
  }
  yield call(handleAppInitHelper);
  isAppInit = true;
}

//export for testing
export function* handleAppStop() {
  if (socket.exists) {
    log("socket close");
    yield call([socket, socket.close]);
  }
  isAppInit = false;
}

export function* handleLogout() {
  yield call(handleAppStop);
  log("clear user data and token");
  yield call([localStorageUtils, localStorageUtils.clearUserData]);
  removeLastSession();
  log("go to login page");
  window.location.assign(getLoginUrl());
}

export default function* loginLogoutSagas() {
  yield all([fork(afterLogin), fork(afterLogout), fork(afterAppStop)]);
}
