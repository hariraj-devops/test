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

import static java.util.Collections.emptyList;

import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;

/**
 * The pre-26.0 Dremio contained an 'Azure Datalake Gen 1' Connector. Microsoft retired Gen 1 Azure
 * in Feb 2024. This upgrade task is designed to remove any existing instances of Gen 1 Azure from a
 * user's Dremio. The SourceType keyword for Gen 1 is/was "ADL".
 */
public class DeleteGen1AzureUpgradeTask extends UpgradeTask {

  public DeleteGen1AzureUpgradeTask() {
    super(
        "Remove all instances of Azure's Gen 1 Storage Containers. Gen 1 was retired in February 2024",
        emptyList());
  }

  static final String taskUUID = "9607e09f-4704-4adc-8329-c28f3f282487";
  private static final String ANSI_RED_BACKGROUND = "\u001B[41m";
  private static final String ANSI_WHITE_TEXT = "\u001B[37m";
  private static final String ANSI_RESET = "\u001B[0m";
  private static final String ACTION_REQUIRED_MESSAGE =
      ANSI_RED_BACKGROUND
          + ANSI_WHITE_TEXT
          + "[ACTION REQUIRED]\n\n"
          + "It appears that you have a Gen 1 Azure source that Dremio could not remove. "
          + "Microsoft Azure Gen 1 sources have been retired.\n"
          + "All Gen 1 sources created by 'Azure Data Lake Storage Gen 1' must be removed.\n\n"
          + "See Microsoft's Gen 1 Official Announcement Here: https://azure.microsoft.com/en-us/updates?id=action-required-switch-to-azure-data-lake-storage-gen2-by-29-february-2024."
          + ANSI_RESET
          + "\n\n";

  /**
   * "ADL" sourceType was defined in 'AzureDataLakeConf', which was removed from dremio codebase.
   */
  private static final String AZURE_GEN1_SOURCE_TYPE = "ADL";

  @Override
  public String getTaskUUID() {
    return taskUUID;
  }

  /**
   * Delete all occurrences of Gen1 Azure Data Lake from the KV-Store
   *
   * @param context KV-Store context required for upgrade tasks
   */
  @Override
  public void upgrade(UpgradeContext context) throws Exception {
    NamespaceServiceImpl namespaceService =
        new NamespaceServiceImpl(
            context.getKvStoreProvider(),
            new CatalogStatusEventsImpl(),
            CatalogEventMessagePublisherProvider.NO_OP);

    // Will Remove any Azure Data Lake Gen1 Sources.
    namespaceService.getSources().stream()
        .filter(s -> AZURE_GEN1_SOURCE_TYPE.equals(s.getType()))
        .forEach(
            adlConfig -> {
              try {
                namespaceService.deleteSource(adlConfig.getKey(), adlConfig.getTag());
              } catch (NamespaceException e) {
                throw new RuntimeException(ACTION_REQUIRED_MESSAGE);
              }
            });
  }
}
