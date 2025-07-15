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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.common.exceptions.UserException;
import org.junit.jupiter.api.Test;

public class TestExecutorSelectionUtils {

  @Test
  public void testEmptyQueue() {
    assertThatThrownBy(() -> ExecutorSelectionUtils.throwEngineOffline(""))
        .isInstanceOf(UserException.class)
        .hasMessage(
            "There are no executors available on your cluster to run the query on. If you have a stopped engine, you must start it or change the queue destination from 'Any' to a specific named engine.");
  }

  @Test
  public void testNullQueue() {
    assertThatThrownBy(() -> ExecutorSelectionUtils.throwEngineOffline(null))
        .isInstanceOf(UserException.class)
        .hasMessage(
            "There are no executors available on your cluster to run the query on. If you have a stopped engine, you must start it or change the queue destination from 'Any' to a specific named engine.");
  }

  @Test
  public void testBlankQueue() {
    assertThatThrownBy(() -> ExecutorSelectionUtils.throwEngineOffline("       "))
        .isInstanceOf(UserException.class)
        .hasMessage(
            "There are no executors available on your cluster to run the query on. If you have a stopped engine, you must start it or change the queue destination from 'Any' to a specific named engine.");
  }

  @Test
  public void testWithQueue() {
    assertThatThrownBy(() -> ExecutorSelectionUtils.throwEngineOffline("default"))
        .isInstanceOf(UserException.class)
        .hasMessage("The default engine is not online.");
  }
}
