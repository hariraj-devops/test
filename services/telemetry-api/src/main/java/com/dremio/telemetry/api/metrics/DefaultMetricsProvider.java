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

import java.util.concurrent.ConcurrentHashMap;

/**
 * The default metrics provider. Simply uses the current metrics system to create counters and
 * timers.
 */
class DefaultMetricsProvider implements MetricsProvider {
  private final ConcurrentHashMap<String, SimpleCounter> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, SimpleTimer> timers = new ConcurrentHashMap<>();

  @Override
  public SimpleCounter counter(String metricName) {
    return counters.computeIfAbsent(metricName, name -> SimpleCounter.of(name));
  }

  @Override
  public SimpleTimer timer(String metricName) {
    return timers.computeIfAbsent(metricName, name -> SimpleTimer.of(name));
  }
}
