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
package com.dremio.dac.service.catalog;

import com.dremio.service.scheduler.Schedule;
import java.time.LocalTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Methods for building schedule for catalog maintenance tasks. */
public final class CatalogMaintenanceScheduleBuilder {
  private static final String LOCAL_TASK_LEADER_NAME_FORMAT = "catalog_maintenance_%s";

  private CatalogMaintenanceScheduleBuilder() {}

  public static Schedule makeSchedule(long everySeconds, String nameSuffix) {
    return Schedule.Builder.everySeconds(everySeconds)
        .asClusteredSingleton(String.format(LOCAL_TASK_LEADER_NAME_FORMAT, nameSuffix))
        .releaseOwnershipAfter(1, TimeUnit.DAYS)
        .build();
  }

  public static Schedule makeDailySchedule(long randomSeed, String nameSuffix) {
    return Schedule.Builder.everyDays(1, generateRandomTime(randomSeed))
        .asClusteredSingleton(String.format(LOCAL_TASK_LEADER_NAME_FORMAT, nameSuffix))
        .releaseOwnershipAfter(1, TimeUnit.DAYS)
        .build();
  }

  private static LocalTime generateRandomTime(long randomSeed) {
    double fraction = new Random(randomSeed).nextDouble();
    double time = fraction * 24;
    int hour = (int) time;
    double minuteTime = 60 * (time - hour);
    int minute = (int) minuteTime;
    int second = (int) (60 * (minuteTime - minute));
    return LocalTime.of(hour, minute, second);
  }
}
