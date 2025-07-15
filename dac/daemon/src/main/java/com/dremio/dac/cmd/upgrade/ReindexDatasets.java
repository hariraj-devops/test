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
package com.dremio.dac.cmd.upgrade;

import com.dremio.dac.cmd.AdminLogger;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.api.FindByCondition;
import com.dremio.datastore.api.ImmutableFindByCondition;
import com.dremio.service.namespace.NamespaceIndexKeys;
import com.dremio.service.namespace.NamespaceStore;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.google.common.base.Stopwatch;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class ReindexDatasets extends UpgradeTask {
  // DO NOT MODIFY
  private static final String taskUUID = "e54edcfc-0f62-4e10-ae4a-676b21c87311";

  private static final long MAX_PROGRESS_UPDATE_SIZE_THRESHOLD = 1_000_000L;
  private static final long MIN_PROGRESS_UPDATE_SIZE_THRESHOLD = 50_000L;

  public ReindexDatasets() {
    super("Reindex all datasets", Collections.emptyList());
  }

  @Override
  public String getTaskUUID() {
    return taskUUID;
  }

  @Override
  public void upgrade(UpgradeContext context) throws Exception {
    var namespaceStore =
        context.getKvStoreProvider().getStore(NamespaceStore.NamespaceStoreCreator.class);
    // Define condition to retrieve all datasets
    final FindByCondition condition =
        new ImmutableFindByCondition.Builder()
            .setCondition(
                SearchQueryUtils.newTermQuery(
                    NamespaceIndexKeys.ENTITY_TYPE.getIndexFieldName(),
                    NameSpaceContainer.Type.DATASET.getNumber()))
            .build();
    Stopwatch start = Stopwatch.createStarted();

    long totalDatasets = namespaceStore.getCounts(condition.getCondition()).get(0);
    AdminLogger.log(
        "Dataset reindexing task has started. This process may take some time, depending on the "
            + "total number of datasets. Total datasets: {}",
        totalDatasets);

    final long[] numDocsReindexed = new long[] {0};
    long progressUpdateSize = getProgressUpdateSize(totalDatasets);
    namespaceStore.reindex(
        condition,
        () -> {
          long count = numDocsReindexed[0]++;
          if (count % progressUpdateSize == 0) {
            AdminLogger.log(
                "Reindexing all datasets: {}% completed",
                String.format("%.2f", (count * 100.0 / totalDatasets)));
          }
        });

    AdminLogger.log(
        "Dataset reindexing task has completed, successfully re-indexed: {} datasets",
        numDocsReindexed[0]);

    AdminLogger.log(
        "Total time taken to reindex all datasets: {} seconds", start.elapsed(TimeUnit.SECONDS));
  }

  private long getProgressUpdateSize(long totalDatasets) {
    long progressUpdateSize = totalDatasets / 10;
    return Math.min(
        Math.max(progressUpdateSize, MAX_PROGRESS_UPDATE_SIZE_THRESHOLD),
        MIN_PROGRESS_UPDATE_SIZE_THRESHOLD);
  }
}
