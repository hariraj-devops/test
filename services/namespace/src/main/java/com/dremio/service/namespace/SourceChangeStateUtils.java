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
package com.dremio.service.namespace;

import static com.dremio.service.namespace.source.proto.SourceChangeState.SOURCE_CHANGE_STATE_CREATING;
import static com.dremio.service.namespace.source.proto.SourceChangeState.SOURCE_CHANGE_STATE_DELETING;
import static com.dremio.service.namespace.source.proto.SourceChangeState.SOURCE_CHANGE_STATE_NONE;
import static com.dremio.service.namespace.source.proto.SourceChangeState.SOURCE_CHANGE_STATE_UNSPECIFIED;
import static com.dremio.service.namespace.source.proto.SourceChangeState.SOURCE_CHANGE_STATE_UPDATING;

import com.dremio.service.namespace.source.proto.SourceChangeState;

public final class SourceChangeStateUtils {

  public static final String CREATING = "CREATING";
  public static final String DELETING = "DELETING";
  public static final String UPDATING = "UPDATING";
  public static final String NONE = "NONE";
  public static final String UNSPECIFIED = "UNSPECIFIED";

  private SourceChangeStateUtils() {}

  public static String convertFromSourceChangeState(SourceChangeState sourceChangeState) {
    if (sourceChangeState == null) {
      return null;
    }

    switch (sourceChangeState) {
      case SOURCE_CHANGE_STATE_CREATING:
        return CREATING;
      case SOURCE_CHANGE_STATE_DELETING:
        return DELETING;
      case SOURCE_CHANGE_STATE_UPDATING:
        return UPDATING;
      case SOURCE_CHANGE_STATE_NONE:
        return NONE;
      default:
        return UNSPECIFIED;
    }
  }

  public static SourceChangeState convertToSourceChangeState(String state) {
    if (state == null) {
      return null;
    }

    switch (state) {
      case CREATING:
        return SOURCE_CHANGE_STATE_CREATING;
      case DELETING:
        return SOURCE_CHANGE_STATE_DELETING;
      case UPDATING:
        return SOURCE_CHANGE_STATE_UPDATING;
      case NONE:
        return SOURCE_CHANGE_STATE_NONE;
      default:
        return SOURCE_CHANGE_STATE_UNSPECIFIED;
    }
  }
}
