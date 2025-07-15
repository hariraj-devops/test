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
package com.dremio.catalog.model;

import com.dremio.service.namespace.NamespaceAttribute;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Catalog Folder Model */
@Value.Immutable
public interface CatalogFolder {

  /** Folder Id */
  @Nullable
  String id();

  /** Folder Path */
  List<String> fullPath();

  /** Folder Tag Used for Concurrency Control */
  @Nullable
  String tag();

  /** Folder's Storage Uri */
  @Nullable
  String storageUri();

  /** Folder's Version Context. Input value - if not defined will assume the default version */
  @Nullable
  VersionContext versionContext();

  /**
   * Folder's Resolved Version Context. Not applicable for input but should contain a return value
   * when the folder is created or updated to match the resolvedVersionContext(commit hash) of the
   * create or update commit operation.
   */
  @Nullable
  ResolvedVersionContext resolvedVersionContext();

  /**
   * Folder's Attributes. Please note that having NamespaceAttributes is a hack. These should be
   * replaced by CatalogAttributes in the near future. See DX-100068.
   */
  @Nullable
  Set<NamespaceAttribute> attributes();

  default String getName() {
    return fullPath().get(fullPath().size() - 1);
  }
}
