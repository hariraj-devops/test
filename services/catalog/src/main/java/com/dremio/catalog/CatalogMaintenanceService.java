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
package com.dremio.catalog;

import com.dremio.options.OptionManager;
import com.dremio.service.Service;
import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs catalog periodic maintenance tasks. */
public class CatalogMaintenanceService implements Service {
  private static final Logger logger = LoggerFactory.getLogger(CatalogMaintenanceService.class);

  private static final String LOCAL_TASK_LEADER_NAME_FORMAT = "catalog_maintenance_%s";

  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<ImmutableList<CatalogMaintenanceTask>> tasksProvider;

  private final Object taskLock = new Object();
  private List<CatalogMaintenanceTask> tasks = List.of();

  public CatalogMaintenanceService(
      Provider<OptionManager> optionManagerProvider,
      Provider<ImmutableList<CatalogMaintenanceTask>> tasksProvider) {
    this.optionManagerProvider = optionManagerProvider;
    this.tasksProvider = tasksProvider;
  }

  /** Schedules periodic maintenance tasks to run on an executor service. */
  @Override
  public void start() throws Exception {
    startInternal();
  }

  protected void startInternal() {
    close();

    synchronized (taskLock) {
      tasks = tasksProvider.get();
      for (CatalogMaintenanceTask task : tasks) {
        task.start();
      }
    }

    optionManagerProvider.get().addOptionChangeListener(this::onOptionsChanged);
  }

  private void onOptionsChanged() {
    synchronized (taskLock) {
      for (CatalogMaintenanceTask task : tasks) {
        task.onOptionChanged();
      }
    }
  }

  @Override
  public void close() {
    synchronized (taskLock) {
      for (CatalogMaintenanceTask task : tasks) {
        task.stop();
      }
      tasks = List.of();
    }
  }
}
