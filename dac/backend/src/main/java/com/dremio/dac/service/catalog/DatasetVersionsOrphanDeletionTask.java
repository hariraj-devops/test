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
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceOptions;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.SchedulerService;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules deletion of dataset version orphans. The orphans are temporary dataset versions created
 * by jobs, the task deletes the ones that are older than the jobs TTL.
 */
public class DatasetVersionsOrphanDeletionTask implements CatalogMaintenanceTask {
  private static final Logger logger =
      LoggerFactory.getLogger(DatasetVersionsOrphanDeletionTask.class);

  private final long randomSeed;
  private final Provider<OptionManager> optionManagerProvider;
  private final KVStoreProvider storeProvider;
  private final Provider<SchedulerService> schedulerServiceProvider;
  private final Provider<ExecutorService> executorServiceProvider;

  private Cancellable task;

  public DatasetVersionsOrphanDeletionTask(
      long randomSeed,
      Provider<OptionManager> optionManagerProvider,
      KVStoreProvider storeProvider,
      Provider<SchedulerService> schedulerServiceProvider,
      Provider<ExecutorService> executorServiceProvider) {
    this.randomSeed = randomSeed + "deleteOrphans".hashCode();
    this.optionManagerProvider = optionManagerProvider;
    this.storeProvider = storeProvider;
    this.schedulerServiceProvider = schedulerServiceProvider;
    this.executorServiceProvider = executorServiceProvider;
  }

  @Override
  public void start() {
    onOptionChanged();
  }

  @Override
  public void stop() {
    if (task != null) {
      logger.info("Canceling deleteOrphans task");
      try {
        task.cancel(true);
      } catch (Exception e) {
        logger.error("Error while canceling deleteOrphans", e);
      } finally {
        task = null;
      }
    }
  }

  @Override
  public void onOptionChanged() {
    if (optionManagerProvider
        .get()
        .getOption(NamespaceOptions.DATASET_VERSION_ORPHAN_DELETION_ENABLED)) {
      if (task == null) {
        var schedule =
            CatalogMaintenanceScheduleBuilder.makeDailySchedule(randomSeed, "deleteOrphans");
        logger.info("Scheduling deleteOrphans with schedule {}", schedule);
        WakeupHandler wakeupHandler = new WakeupHandler(executorServiceProvider.get(), this::run);
        task =
            schedulerServiceProvider
                .get()
                .schedule(
                    schedule,
                    () -> {
                      logger.info("Submitting run of deleteOrphans");
                      wakeupHandler.handle("Wakeup for deleteOrphans");
                    });
      }
    } else {
      if (task != null) {
        logger.info("deleteOrphans is disabled");
      }
      stop();
    }
  }

  private void run() {
    try {
      logger.info("Starting DatasetVersionMutator.deleteOrphans");
      DatasetVersionMutator.deleteOrphans(
          optionManagerProvider,
          storeProvider.getStore(DatasetVersionMutator.VersionStoreCreator.class),
          0,
          // Whether to read daysThreshold from options.
          false);
      logger.info("Finished DatasetVersionMutator.deleteOrphans");
    } catch (Exception e) {
      logger.error("Failed to run DatasetVersionMutator.deleteOrphans", e);
    }
  }
}
