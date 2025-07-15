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
package com.dremio.telemetry.api.metrics;

/**
 * Extension point to create counters and timers that don't rely on a static method, making it ideal
 * to mock.
 */
interface MetricsProvider {
  /**
   * Creates a {@link SimpleCounter} with the provided name.
   *
   * @param name The name of the counter.
   * @return A counter.
   */
  SimpleCounter counter(String name);

  /**
   * Creates a {@link SimpleTimer} with the provided name.
   *
   * @param name The name of the timer.
   * @return A timer.
   */
  SimpleTimer timer(String name);
}
