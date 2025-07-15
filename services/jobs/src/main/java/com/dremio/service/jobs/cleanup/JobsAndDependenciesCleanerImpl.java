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
import static com.dremio.service.jobs.LocalJobsService.getOldJobsCondition;
import static com.dremio.service.jobtelemetry.server.LocalJobTelemetryServer.ENABLE_PROFILE_IN_DIST_STORE;

import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.options.OptionManager;
import com.dremio.service.job.proto.ExtraJobInfo;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.jobs.ExtraJobInfoStoreCreator;
import com.dremio.service.jobs.JobsStoreCreator;
import com.dremio.service.jobs.LocalJobsService;
import com.dremio.service.jobtelemetry.JobTelemetryClient;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobsAndDependenciesCleanerImpl implements JobsAndDependenciesCleaner {
  private static final Logger logger =
      LoggerFactory.getLogger(JobsAndDependenciesCleanerImpl.class);
  private static final int DELAY_BEFORE_STARTING_CLEANUP_IN_MINUTES = 5;
  private static final long ONE_DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);

  private final Provider<LegacyKVStoreProvider> kvStoreProvider;
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<SchedulerService> schedulerService;
  private final Provider<JobTelemetryClient> jobTelemetryClientProvider;
  private Cancellable jobDependenciesCleanupTask;

  public JobsAndDependenciesCleanerImpl(
      final Provider<LegacyKVStoreProvider> kvStoreProvider,
      final Provider<OptionManager> optionManagerProvider,
      final Provider<SchedulerService> schedulerService,
      final Provider<JobTelemetryClient> jobTelemetryClientProvider) {
    this.kvStoreProvider = kvStoreProvider;
    this.optionManagerProvider = optionManagerProvider;
    this.schedulerService = schedulerService;
    this.jobTelemetryClientProvider = jobTelemetryClientProvider;
  }

  @Override
  public void start() throws Exception {
    final OptionManager optionManager = optionManagerProvider.get();
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
              Schedule.Builder.everyDays(1, startTime).withTimeZone(ZoneId.systemDefault()).build();
        }
      }
      jobDependenciesCleanupTask =
          schedulerService.get().schedule(schedule, new JobDependenciesCleanupTask());
    }
  }

  @Override
  public void close() throws Exception {
    if (jobDependenciesCleanupTask != null) {
      jobDependenciesCleanupTask.cancel(false);
      jobDependenciesCleanupTask = null;
    }
  }

  /** Removes the job details and profile */
  class JobDependenciesCleanupTask implements Runnable {
    private final List<ExternalCleaner> externalCleaners = new ArrayList<>();

    @Override
    public void run() {
      cleanup();
    }

    public void cleanup() {
      // obtain the max age values during each cleanup as the values could change.
      final OptionManager optionManager = optionManagerProvider.get();
      final long maxAgeInDays = optionManager.getOption(JOB_MAX_AGE_IN_DAYS);
      if (!optionManager.getOption(ENABLE_PROFILE_IN_DIST_STORE)) {
        externalCleaners.add(new OnlineProfileCleaner(jobTelemetryClientProvider, false));
      }
      if (maxAgeInDays != LocalJobsService.DISABLE_CLEANUP_VALUE) {
        logger.info(
            deleteOldJobsAndDependencies(
                externalCleaners, kvStoreProvider.get(), TimeUnit.DAYS.toMillis(maxAgeInDays)));
      }
    }
  }

  /**
   * Delete job details older than provided number of ms.
   *
   * <p>Exposed as static so that cleanup tasks can do this without needing to start a jobs service
   * and supporting daemon.
   *
   * @param externalCleaners defines an ordered sequence of external dependencies that also need to
   *     be cleaned up. Each {@link ExternalCleaner} are called once per job attempt. If an {@code
   *     externalCleanup} fails its successors will not be called for the job attempt being
   *     processed.
   * @param provider KVStore provider
   * @param maxMs Age of job after which it is deleted.
   * @return A result reporting how many details, the corresponding attempt ids and how many times
   *     attempt id fails to delete.
   */
  public static String deleteOldJobsAndDependencies(
      List<ExternalCleaner> externalCleaners, LegacyKVStoreProvider provider, long maxMs) {
    logger.info("Starting job cleanup task");
    long jobsDeleted = 0;
    LegacyIndexedStore<JobId, JobResult> jobStore = provider.getStore(JobsStoreCreator.class);
    LegacyIndexedStore<JobId, ExtraJobInfo> extraJobInfoStore =
        provider.getStore(ExtraJobInfoStoreCreator.class);
    final LegacyIndexedStore.LegacyFindByCondition oldJobs =
        getOldJobsCondition(0, System.currentTimeMillis() - maxMs)
            .setPageSize(LocalJobsService.MAX_NUMBER_JOBS_TO_FETCH);
    final int oldJobsCount = jobStore.getCounts(oldJobs.getCondition()).get(0);
    logger.info("Job cleanup task identified {} jobs that should be deleted", oldJobsCount);
    final ExternalCleanerRunner externalCleanerRunner = new ExternalCleanerRunner(externalCleaners);
    for (Map.Entry<JobId, JobResult> entry : jobStore.find(oldJobs)) {
      final JobResult result = entry.getValue();
      final JobId jobId = entry.getKey();
      try {
        externalCleanerRunner.run(result);
        extraJobInfoStore.delete(jobId);
        jobStore.delete(jobId);
      } catch (Exception e) {
        logger.error("Exception while deleting jobId: {}", jobId, e);
        continue;
      }
      jobsDeleted++;
    }
    if (jobsDeleted < oldJobsCount) {
      logger.error(
          "Expected {} jobs to be deleted, but only {} were actually deleted",
          oldJobsCount,
          jobsDeleted);
    }
    if (externalCleanerRunner.hasErrors()) {
      externalCleanerRunner.printLastErrors();
    }
    return buildDeleteReport(jobsDeleted, externalCleanerRunner);
  }

  private static String buildDeleteReport(
      long jobsDeleted, ExternalCleanerRunner externalCleanerRunner) {
    StringBuilder sb = new StringBuilder("Completed.");
    sb.append(" Deleted ").append(jobsDeleted).append(" jobs.");
    sb.append(externalCleanerRunner.getReport());
    return sb.toString();
  }
}
