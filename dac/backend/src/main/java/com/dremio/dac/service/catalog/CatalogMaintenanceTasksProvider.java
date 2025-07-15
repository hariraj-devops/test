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
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.scheduler.SchedulerService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;

/** Provides maintenance tasks to run on a schedule. */
public class CatalogMaintenanceTasksProvider {
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<SchedulerService> schedulerServiceProvider;
  private final Provider<ExecutorService> executorServiceProvider;
  private final KVStoreProvider storeProvider;
  private final Provider<NamespaceService> namespaceServiceProvider;
  private final Provider<ImmutableSet<String>> versionedSourceTypesProvider;

  public CatalogMaintenanceTasksProvider(
      Provider<OptionManager> optionManagerProvider,
      Provider<SchedulerService> schedulerServiceProvider,
      Provider<ExecutorService> executorServiceProvider,
      KVStoreProvider storeProvider,
      Provider<NamespaceService> namespaceServiceProvider,
      Provider<ImmutableSet<String>> versionedSourceTypesProvider) {
    this.optionManagerProvider = optionManagerProvider;
    this.schedulerServiceProvider = schedulerServiceProvider;
    this.executorServiceProvider = executorServiceProvider;
    this.storeProvider = storeProvider;
    this.namespaceServiceProvider = namespaceServiceProvider;
    this.versionedSourceTypesProvider = versionedSourceTypesProvider;
  }

  public ImmutableList<CatalogMaintenanceTask> get(long randomSeed) {
    return ImmutableList.of(
        new DatasetVersionsOrphanDeletionTask(
            randomSeed,
            optionManagerProvider,
            storeProvider,
            schedulerServiceProvider,
            executorServiceProvider),
        new DatasetVersionsTrimmerTask(
            randomSeed,
            optionManagerProvider,
            storeProvider,
            namespaceServiceProvider,
            schedulerServiceProvider,
            executorServiceProvider,
            versionedSourceTypesProvider));
  }
}
