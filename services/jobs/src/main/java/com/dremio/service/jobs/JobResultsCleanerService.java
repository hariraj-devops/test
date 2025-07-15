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
package com.dremio.service.jobs;

import static com.dremio.service.jobs.LocalJobsService.DISABLE_CLEANUP_VALUE;
import static com.dremio.service.jobs.LocalJobsService.MAX_NUMBER_JOBS_TO_FETCH;
import static com.dremio.service.jobs.LocalJobsService.getOldJobsConditionHelper;

import com.dremio.common.AutoCloseables;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.options.OptionManager;
import com.dremio.options.Options;
import com.dremio.service.Service;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.Schedule.ClusteredSingletonBuilder;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.telemetry.api.metrics.CounterWithOutcome;
import com.dremio.telemetry.api.metrics.SimpleDistributionSummary;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Options
public class JobResultsCleanerService implements Service {

  private static final Logger logger = LoggerFactory.getLogger(JobResultsCleanerService.class);

  private static final String LOCAL_TASK_LEADER_NAME = "jobresultsclean";

  private static long JOB_RESULTS_PREV_CUTOFF_TIME = 0;

  private static final CounterWithOutcome JOB_RESULT_CLEANUP_COUNTER =
      CounterWithOutcome.of("jobs_service.job_result_cleanup");

  private static final SimpleDistributionSummary JOB_RESULT_CLEANUP_LOGIN_TIMER =
      SimpleDistributionSummary.of("jobs_service.job_result_cleanup.durations");

  private static final long ONE_DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);

  private static final long ONE_HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1);

  private final Provider<JobResultsStore> jobResultsStoreProvider;
  private final Provider<LegacyKVStoreProvider> kvStoreProvider;
  private final Provider<OptionManager> optionManager;
  private final Provider<SchedulerService> schedulerService;

  private LegacyIndexedStore<JobId, JobResult> store;
  private Cancellable jobResultsCleanupTask;
  private volatile JobResultsStore jobResultsStore;
  private boolean resultsCleanedQueryEnabled;

  @Inject
  public JobResultsCleanerService(
      Provider<JobResultsStore> jobResultsStoreProvider,
      Provider<LegacyKVStoreProvider> kvStoreProvider,
      Provider<OptionManager> optionManager,
      Provider<SchedulerService> schedulerService) {
    this.jobResultsStoreProvider = jobResultsStoreProvider;
    this.kvStoreProvider = kvStoreProvider;
    this.optionManager = optionManager;
    this.schedulerService = schedulerService;
  }

  @Override
  public void start() throws Exception {
    this.store = kvStoreProvider.get().getStore(JobsStoreCreator.class);
    this.resultsCleanedQueryEnabled =
        optionManager.get().getOption(ExecConstants.JOB_RESULTS_CLEANED_QUERY_ENABLED);

    // Schedule job results cleanup
    final long maxJobResultsAgeInDays =
        optionManager.get().getOption(ExecConstants.RESULTS_MAX_AGE_IN_DAYS);
    if (maxJobResultsAgeInDays == DISABLE_CLEANUP_VALUE) {
      return;
    }

    final long jobResultsCleanupStartHour =
        optionManager.get().getOption(ExecConstants.JOB_RESULTS_CLEANUP_START_HOUR);

    final long jobResultCleanerReleaseLeadershipHour =
        optionManager
            .get()
            .getOption(ExecConstants.JOB_RESULT_CLEANER_RELEASE_LEADERSHIP_MS_PROPERTY);

    final LocalTime startTime = LocalTime.of((int) jobResultsCleanupStartHour, 0);
    // schedule every day at the user configured hour (defaults to midnight)
    ClusteredSingletonBuilder scheduleBuilder =
        Schedule.Builder.everyDays(1, startTime)
            .withTimeZone(ZoneId.systemDefault())
            .asClusteredSingleton(LOCAL_TASK_LEADER_NAME)
            .releaseOwnershipAfter(jobResultCleanerReleaseLeadershipHour, TimeUnit.MILLISECONDS);
    jobResultsCleanupTask =
        schedulerService.get().schedule(buildSchedule(scheduleBuilder), this::run);
  }

  @Override
  public void close() throws Exception {
    if (jobResultsCleanupTask != null) {
      jobResultsCleanupTask.cancel(false);
      jobResultsCleanupTask = null;
    }

    AutoCloseables.close(jobResultsStore);
  }

  private void run() {
    final Stopwatch stopwatch = Stopwatch.createStarted();
    try {
      cleanup();
      JOB_RESULT_CLEANUP_COUNTER.succeeded();
    } catch (Throwable e) {
      JOB_RESULT_CLEANUP_COUNTER.errored();
      logger.warn("Exception running Job Results Cleanup ", e);
    } finally {
      final long elapsedTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      JOB_RESULT_CLEANUP_LOGIN_TIMER.recordAmount(elapsedTime);
    }
  }

  public void cleanup() {
    // obtain the max age values during each cleanup as the values could change.
    final long maxAgeInMillis =
        optionManager.get().getOption(ExecConstants.DEBUG_RESULTS_MAX_AGE_IN_MILLISECONDS);
    final long resultsMaxAgeInDays =
        optionManager.get().getOption(ExecConstants.RESULTS_MAX_AGE_IN_DAYS);
    final long jobMaxAgeInDays = optionManager.get().getOption(ExecConstants.JOB_MAX_AGE_IN_DAYS);
    final long maxAgeInDays;
    if (jobMaxAgeInDays == DISABLE_CLEANUP_VALUE) {
      maxAgeInDays = resultsMaxAgeInDays;
    } else if (jobMaxAgeInDays == 0) {
      maxAgeInDays = 0;
    } else {
      maxAgeInDays = Math.min(resultsMaxAgeInDays, jobMaxAgeInDays - 1);
    }
    long jobResultsMaxAgeInMillis = (maxAgeInDays * ONE_DAY_IN_MILLIS) + maxAgeInMillis;
    long cutOffTime = System.currentTimeMillis() - jobResultsMaxAgeInMillis;
    if (resultsMaxAgeInDays != DISABLE_CLEANUP_VALUE) {
      cleanupJobResults(cutOffTime);
    }
  }

  protected Schedule buildSchedule(ClusteredSingletonBuilder builder) {
    return builder.build();
  }

  public void cleanupJobResults(long cutOffTime) {
    if (cutOffTime < JOB_RESULTS_PREV_CUTOFF_TIME) {
      // Can happen if the RESULTS_MAX_AGE_IN_DAYS has been increased by user options.
      JOB_RESULTS_PREV_CUTOFF_TIME = cutOffTime - ONE_HOUR_IN_MILLIS;
    }
    // iterate through the job results and cleanup.
    final LegacyIndexedStore.LegacyFindByCondition condition =
        getConditionForResultsCleanup(JOB_RESULTS_PREV_CUTOFF_TIME, cutOffTime)
            .setPageSize(MAX_NUMBER_JOBS_TO_FETCH);

    final int jobResultsToBeCleaned = store.getCounts(condition.getCondition()).get(0);
    if (this.resultsCleanedQueryEnabled) {
      logger.info(
          "JobResultsCleanupTask cleaning job results up to {}, total {} jobs identified for results cleanup",
          new Date(cutOffTime),
          jobResultsToBeCleaned);
    } else {
      logger.info(
          "JobResultsCleanupTask cleaning job results from {} to {}, total {} jobs identified for results cleanup",
          new Date(JOB_RESULTS_PREV_CUTOFF_TIME),
          new Date(cutOffTime),
          jobResultsToBeCleaned);
    }

    int jobResultsCleaned = 0;

    Stopwatch start = Stopwatch.createStarted();
    for (Map.Entry<JobId, JobResult> entry : store.find(condition)) {
      logger.debug("JobResultsCleanupTask getting cleaned up for key {}", entry.getKey());

      boolean resultsCleaned = getJobResultsStore().cleanup(entry.getKey(), entry.getValue());
      if (resultsCleaned) {
        jobResultsCleaned++;
      }

      if (resultsCleanedQueryEnabled) {
        JobResult jobResult = entry.getValue().setResultsCleaned(resultsCleaned);
        store.put(entry.getKey(), jobResult);
      }
    }

    logger.debug(
        "Total time taken for Job Results Cleanup :- {}", start.elapsed(TimeUnit.MILLISECONDS));

    if (jobResultsCleaned < jobResultsToBeCleaned) {
      logger.error(
          "Expected {} job results to be cleaned, but only {} were actually cleaned",
          jobResultsToBeCleaned,
          jobResultsCleaned);
    }

    JOB_RESULTS_PREV_CUTOFF_TIME =
        cutOffTime
            - ONE_HOUR_IN_MILLIS; // Decreasing prev time by an hour to cover overlapping jobs
  }

  private LegacyIndexedStore.LegacyFindByCondition getConditionForResultsCleanup(
      long prevCutOffTime, long cutOffTime) {
    SearchTypes.SearchQuery termQueryForResultsCleaned =
        SearchQueryUtils.newTermQuery(JobIndexKeys.RESULTS_CLEANED, String.valueOf(false));
    SearchTypes.SearchQuery resultsCleanupQuery =
        SearchQueryUtils.and(termQueryForResultsCleaned, getOldJobsConditionHelper(0L, cutOffTime));
    SearchTypes.SearchQuery finalQuery =
        resultsCleanedQueryEnabled
            ? resultsCleanupQuery
            : getOldJobsConditionHelper(prevCutOffTime, cutOffTime);
    return new LegacyIndexedStore.LegacyFindByCondition().setCondition(finalQuery);
  }

  @VisibleForTesting
  JobResultsStore getJobResultsStore() {
    if (this.jobResultsStore == null) {
      // Lazy initialization to allow the late setup on pre-warmed coordinators in the cloud
      // use-cases.
      this.jobResultsStore = jobResultsStoreProvider.get();
    }
    return this.jobResultsStore;
  }
}
