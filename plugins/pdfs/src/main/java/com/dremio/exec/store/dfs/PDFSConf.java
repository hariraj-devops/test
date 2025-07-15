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

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.io.file.Path;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.protostuff.Tag;
import java.util.List;
import javax.inject.Provider;

/** Internally used config for PDFS. Test purposes only. */
@SourceType(value = "PDFS", configurable = false)
public class PDFSConf extends FileSystemConf<PDFSConf, FileSystemPlugin<PDFSConf>> {

  @VisibleForTesting
  public PDFSConf(String path) {
    this.path = path;
  }

  public PDFSConf() {}

  @Tag(1)
  public String path;

  @Override
  public Path getPath() {
    return Path.of(path);
  }

  @Override
  public boolean isImpersonationEnabled() {
    return false;
  }

  @Override
  public List<Property> getProperties() {
    return ImmutableList.of();
  }

  @Override
  public String getConnection() {
    return "pdfs:///";
  }

  @Override
  public SchemaMutability getSchemaMutability() {
    return SchemaMutability.SYSTEM_TABLE_AND_VIEW;
  }

  @Override
  public FileSystemPlugin<PDFSConf> newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new FileSystemPlugin<>(this, pluginSabotContext, name, pluginIdProvider);
  }

  @Override
  public boolean isInternal() {
    return true;
  }

  @Override
  public boolean isPartitionInferenceEnabled() {
    return false;
  }
}
