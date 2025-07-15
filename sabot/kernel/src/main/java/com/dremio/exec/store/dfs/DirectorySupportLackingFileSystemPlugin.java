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
package com.dremio.exec.store.dfs;

import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import javax.inject.Provider;

public class DirectorySupportLackingFileSystemPlugin<C extends FileSystemConf<C, ?>>
    extends FileSystemPlugin<C> {

  public DirectorySupportLackingFileSystemPlugin(
      C config,
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> idProvider) {
    super(config, pluginSabotContext, name, idProvider);
  }

  @Override
  public BytesOutput provideSignature(DatasetHandle datasetHandle, DatasetMetadata metadata) {
    return BytesOutput.NONE;
  }
}
