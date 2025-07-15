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

import { takeLatest } from "redux-saga/effects";

import { statsEvent } from "#oss/telemetry/events/stats";
import { APP_INIT_COMPLETE } from "#oss/actions/app";

import { sourcesEvent } from "#oss/telemetry/events/sources";
import { SOURCES_LIST_LOAD_SUCCESS } from "#oss/actions/resources/sources";

import { supportKeysEvent } from "#oss/telemetry/events/supportKeys";

export function* telemetry() {
  if (statsEvent) {
    yield takeLatest(APP_INIT_COMPLETE, statsEvent);
  }

  if (sourcesEvent) {
    yield takeLatest(SOURCES_LIST_LOAD_SUCCESS, sourcesEvent);
  }

  if (supportKeysEvent) {
    yield takeLatest(APP_INIT_COMPLETE, supportKeysEvent);
  }
}
