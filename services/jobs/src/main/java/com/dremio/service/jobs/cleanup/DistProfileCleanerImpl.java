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
package com.dremio.service.jobs.cleanup;

import static com.dremio.exec.ExecConstants.JOB_MAX_AGE_IN_DAYS;
import static com.dremio.service.jobtelemetry.server.LocalJobTelemetryServer.ENABLE_PROFILE_IN_DIST_STORE;

import com.dremio.exec.ExecConstants;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.service.jobs.LocalJobsService;
import com.dremio.service.jobtelemetry.server.store.ProfileDistStoreConfig;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistProfileCleanerImpl implements DistProfileCleaner {
  private static final Logger logger = LoggerFactory.getLogger(DistProfileCleanerImpl.class);

  private static final int DELAY_BEFORE_STARTING_CLEANUP_IN_MINUTES = 5;
  private static final long ONE_DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<SchedulerService> schedulerService;
  private final Provider<ProfileDistStoreConfig> profileDistStoreConfigProvider;
  private Cancellable distProfileCleanupTask;

  public DistProfileCleanerImpl(
      final Provider<OptionManager> optionManagerProvider,
      final Provider<SchedulerService> schedulerService,
      final Provider<ProfileDistStoreConfig> profileDistStoreConfigProvider) {
    this.optionManagerProvider = optionManagerProvider;
    this.schedulerService = schedulerService;
    this.profileDistStoreConfigProvider = profileDistStoreConfigProvider;
  }

  @Override
  public void start() throws Exception {
    final OptionManager optionManager = optionManagerProvider.get();
    if (optionManager.getOption(ENABLE_PROFILE_IN_DIST_STORE)) {
      // job profiles
      final long jobProfilesAgeOffsetInMillis =
          optionManager.getOption(ExecConstants.DEBUG_RESULTS_MAX_AGE_IN_MILLISECONDS);
      final long maxJobProfilesAgeInDays = optionManager.getOption(JOB_MAX_AGE_IN_DAYS);
      final long maxJobProfilesAgeInMillis =
          (maxJobProfilesAgeInDays * ONE_DAY_IN_MILLIS) + jobProfilesAgeOffsetInMillis;
      final Schedule schedule;

      // Schedule job dependencies cleanup to run every day unless the max age is less than a day
      // (used for testing)
      if (maxJobProfilesAgeInDays != LocalJobsService.DISABLE_CLEANUP_VALUE) {
        if (maxJobProfilesAgeInMillis < ONE_DAY_IN_MILLIS) {
          schedule =
              Schedule.Builder.everyMillis(maxJobProfilesAgeInMillis)
                  .startingAt(
                      Instant.now()
                          .plus(DELAY_BEFORE_STARTING_CLEANUP_IN_MINUTES, ChronoUnit.MINUTES))
                  .build();
        } else {
          if (optionManager.getOption(ExecConstants.JOBS_CLEANUP_MORE_OFTEN)) {
            // schedule once after 60 mins and then every 12 hrs
            schedule =
                Schedule.Builder.everyHours(
                        (int) optionManager.getOption(ExecConstants.JOB_CLEANUP_INTERVAL_HRS))
                    .startingAt(
                        Instant.now()
                            .plus(
                                optionManager.getOption(ExecConstants.JOB_CLEANUP_START_AFTER_MINS),
                                ChronoUnit.MINUTES))
                    .build();
          } else {
            final long jobCleanupStartHour =
                optionManager.getOption(ExecConstants.JOB_CLEANUP_START_HOUR);
            final LocalTime startTime = LocalTime.of((int) jobCleanupStartHour, 0);

            // schedule every day at the user configured hour (defaults to 1 am)
            schedule =
                Schedule.Builder.everyDays(1, startTime)
                    .withTimeZone(ZoneId.systemDefault())
                    .build();
          }
        }
        distProfileCleanupTask =
            schedulerService.get().schedule(schedule, new DistProfileCleanupTask());
      }
    }
  }

  /** Removes the profiles using date partition */
  class DistProfileCleanupTask implements Runnable {
    @Override
    public void run() {
      try {
        cleanup();
      } catch (IOException e) {
        logger.error("Error cleaning up profiles ", e);
      }
    }

    public void cleanup() throws IOException {
      // obtain the max age values during each cleanup as the values could change.
      final OptionManager optionManager = optionManagerProvider.get();
      final long maxAgeInDays = optionManager.getOption(JOB_MAX_AGE_IN_DAYS);
      if (maxAgeInDays != LocalJobsService.DISABLE_CLEANUP_VALUE) {
        deleteProfiles(
            maxAgeInDays,
            profileDistStoreConfigProvider.get().getFileSystem(),
            profileDistStoreConfigProvider.get().getStoragePath());
      }
    }
  }

  public static void deleteProfiles(long maxAgeInDays, FileSystem dfs, Path profileStoreLocation) {
    List<LocalDate> datesToBeCleaned = new ArrayList<>();
    LocalDate today = LocalDate.now();
    // delete last 60 days except the days in range of maxAgeInDays
    for (long i = 60; i >= maxAgeInDays; i--) {
      datesToBeCleaned.add(today.minusDays(i));
    }

    for (LocalDate date : datesToBeCleaned) {
      try {
        dfs.delete(Path.mergePaths(profileStoreLocation, Path.of(String.valueOf(date))), true);
        logger.info("Cleaned profiles of date {}", date);
      } catch (FileNotFoundException f) {
        // expected if the dir is cleaned already in earlier cycles
        logger.debug("Error cleaning up profiles ", f);
      } catch (Throwable th) {
        logger.error("Error cleaning up profiles ", th);
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (distProfileCleanupTask != null) {
      distProfileCleanupTask.cancel(false);
      distProfileCleanupTask = null;
    }
  }
}
