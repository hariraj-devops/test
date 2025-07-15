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
package com.dremio.service.namespace.folder;

import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceInvalidStateException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.space.proto.FolderConfig;
import java.util.List;
import javax.annotation.Nullable;

/** Namespace operations for Folders. */
public interface FolderNamespaceService {
  /**
   * @param folderPath - The path of the folder.
   * @param folderConfig - The configuration
   * @param attributes - Optional attributes.
   * @throws NamespaceInvalidStateException - Throws if an invariant is invalid such as a misnamed
   *     attribute.
   * @throws NamespaceNotFoundException - Throws if this folder cannot be found.
   * @throw InvalidNamespaceNameException - Throws if the folder name is invalid.
   */
  void addOrUpdateFolder(
      NamespaceKey folderPath, FolderConfig folderConfig, NamespaceAttribute... attributes)
      throws NamespaceException;

  FolderConfig getFolder(NamespaceKey folderPath) throws NamespaceException;

  /**
   * Get the list of folders under the given root path.
   *
   * @param rootPath
   * @return
   * @throws NamespaceException
   */
  List<FolderConfig> getFolders(NamespaceKey rootPath) throws NamespaceException;

  void deleteFolder(NamespaceKey folderPath, @Nullable String version) throws NamespaceException;
}
