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
package com.dremio.service.execselector;

import com.dremio.common.exceptions.UserException;
import org.apache.commons.lang3.StringUtils;

/** Utilities for Executor selection. */
public final class ExecutorSelectionUtils {

  public static void throwEngineOffline(String queueTag) {
    if (StringUtils.isBlank(queueTag)) {
      throw UserException.resourceError()
          .message(
              "There are no executors available on your cluster to run the query on. "
                  + "If you have a stopped engine, you must start it or change the queue destination from 'Any' to a specific named engine.")
          .buildSilently();
    }
    throw UserException.resourceError()
        .message(String.format("The %s engine is not online.", queueTag))
        .buildSilently();
  }

  private ExecutorSelectionUtils() {}
}
