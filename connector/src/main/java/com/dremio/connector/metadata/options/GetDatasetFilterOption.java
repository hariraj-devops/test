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
package com.dremio.connector.metadata.options;

import com.dremio.connector.metadata.GetDatasetOption;
import java.util.List;

/**
 * Specifies the folders for a metadata name refresh. This option is only supported by
 * Hive3StoragePlugin/AWSGlueStoragePlugin.
 */
public class GetDatasetFilterOption implements GetDatasetOption {
  private final List<List<String>> folders;

  /**
   * Creates a new RefreshFoldersOption with the given folders.
   *
   * @param folders list of folders name refresh should retrieve
   */
  public GetDatasetFilterOption(List<List<String>> folders) {
    this.folders = folders;
  }

  /**
   * Returns the list of folders.
   *
   * @return the list of folders
   */
  public List<List<String>> getFolders() {
    return folders;
  }
}
