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

import com.dremio.catalog.CatalogMaintenanceTask;
import com.dremio.common.WakeupHandler;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.datasets.DatasetVersionTrimmer;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceOptions;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.google.common.collect.ImmutableSet;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules trimming of the linked lists of dataset versions. */
public class DatasetVersionsTrimmerTask implements CatalogMaintenanceTask {
  private static final Logger logger = LoggerFactory.getLogger(DatasetVersionsTrimmerTask.class);

  private final long randomSeed;
  private final Provider<OptionManager> optionManagerProvider;
  private final KVStoreProvider storeProvider;
  private final Provider<NamespaceService> namespaceServiceProvider;
  private final Provider<SchedulerService> schedulerServiceProvider;
  private final Provider<ExecutorService> executorServiceProvider;
  private final Provider<ImmutableSet<String>> versionedSourceTypesProvider;

  private Cancellable task;

  public DatasetVersionsTrimmerTask(
      long randomSeed,
      Provider<OptionManager> optionManagerProvider,
      KVStoreProvider storeProvider,
      Provider<NamespaceService> namespaceServiceProvider,
      Provider<SchedulerService> schedulerServiceProvider,
      Provider<ExecutorService> executorServiceProvider,
      Provider<ImmutableSet<String>> versionedSourceTypesProvider) {
    this.randomSeed = randomSeed + "trimHistory".hashCode();
    this.optionManagerProvider = optionManagerProvider;
    this.storeProvider = storeProvider;
    this.namespaceServiceProvider = namespaceServiceProvider;
    this.schedulerServiceProvider = schedulerServiceProvider;
    this.executorServiceProvider = executorServiceProvider;
    this.versionedSourceTypesProvider = versionedSourceTypesProvider;
  }

  @Override
  public void start() {
    onOptionChanged();
  }

  @Override
  public void stop() {
    if (task != null) {
      logger.info("Canceling DatasetVersionTrimmer task");
      try {
        task.cancel(true);
      } catch (Exception e) {
        logger.error("Error while canceling DatasetVersionTrimmer", e);
      } finally {
        task = null;
      }
    }
  }

  @Override
  public void onOptionChanged() {
    if (optionManagerProvider.get().getOption(NamespaceOptions.DATASET_VERSION_TRIMMER_ENABLED)) {
      if (task == null) {
        // Make schedule.
        Schedule schedule;
        long trimmerScheduleSeconds =
            optionManagerProvider
                .get()
                .getOption(NamespaceOptions.DATASET_VERSIONS_TRIMMER_SCHEDULE_SECONDS);
        if (trimmerScheduleSeconds != 0) {
          schedule =
              CatalogMaintenanceScheduleBuilder.makeSchedule(
                  trimmerScheduleSeconds, "DatasetVersionTrimmer");
        } else {
          schedule =
              CatalogMaintenanceScheduleBuilder.makeDailySchedule(
                  randomSeed, "DatasetVersionTrimmer");
        }

        // Schedule.
        logger.info("Scheduling DatasetVersionTrimmer with schedule {}", schedule);
        WakeupHandler wakeupHandler = new WakeupHandler(executorServiceProvider.get(), this::run);
        task =
            schedulerServiceProvider
                .get()
                .schedule(
                    schedule,
                    () -> {
                      logger.info("Submitting run of DatasetVersionTrimmer");
                      wakeupHandler.handle("Wakeup for DatasetVersionTrimmer");
                    });
      }
    } else {
      if (task != null) {
        logger.info("DatasetVersionTrimmer is disabled");
      }
      stop();
    }
  }

  private void run() {
    try {
      logger.info("Starting DatasetVersionTrimmer.trimHistory");
      DatasetVersionTrimmer.trimHistory(
          Clock.systemUTC(),
          storeProvider.getStore(DatasetVersionMutator.VersionStoreCreator.class),
          namespaceServiceProvider.get(),
          versionedSourceTypesProvider.get(),
          optionManagerProvider.get());
      logger.info("Finished DatasetVersionTrimmer.trimHistory");
    } catch (Exception e) {
      logger.error("Failed to run DatasetVersionTrimmer.trimHistory", e);
    }
  }
}
