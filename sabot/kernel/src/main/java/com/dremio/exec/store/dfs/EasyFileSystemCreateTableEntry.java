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

import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.Writer;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePluginResolver;
import com.dremio.exec.store.dfs.copyinto.SystemIcebergTablePluginAwareCreateTableEntry;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePlugin;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.service.namespace.NamespaceKey;
import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.io.IOException;

/**
 * Implements <code>CreateTableEntry</code> interface to create new tables in FileSystem storage.
 */
@JsonTypeName("filesystem")
public class EasyFileSystemCreateTableEntry
    implements CreateTableEntry, SystemIcebergTablePluginAwareCreateTableEntry {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(EasyFileSystemCreateTableEntry.class);

  private final String userName;
  private final FileSystemPlugin<?> plugin;
  private final FormatPlugin formatPlugin;
  private final String location;
  private final WriterOptions options;
  private final IcebergTableProps icebergTableProps;
  private final NamespaceKey datasetPath;
  private StoragePluginId systemIcebergTablesPluginId;
  private SystemIcebergTablesStoragePlugin systemIcebergTablesPlugin;
  private StoragePluginResolver storagePluginResolver;

  @JsonCreator
  public EasyFileSystemCreateTableEntry(
      @JsonProperty("userName") String userName,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("formatConfig") FormatPluginConfig formatConfig,
      @JsonProperty("location") String location,
      @JsonProperty("icebergTableProps") IcebergTableProps icebergTableProps,
      @JsonProperty("options") WriterOptions options,
      @JsonProperty("datasetPath") NamespaceKey datasetPath,
      @JacksonInject StoragePluginResolver storagePluginResolver) {
    this.userName = userName;
    this.plugin = storagePluginResolver.getSource(pluginId);
    this.formatPlugin = plugin.getFormatPlugin(formatConfig);
    this.location = location;
    this.options = options;
    this.icebergTableProps = icebergTableProps;
    this.datasetPath = datasetPath;
    this.storagePluginResolver = storagePluginResolver;
  }

  /**
   * Create an instance.
   *
   * @param userName Name of the user whom to impersonate while creating the table
   * @param plugin {@link FileSystemPlugin} instance
   * @param formatPlugin Reference to the {@link FormatPlugin} for output type
   * @param location Output path
   */
  public EasyFileSystemCreateTableEntry(
      String userName,
      FileSystemPlugin<?> plugin,
      FormatPlugin formatPlugin,
      String location,
      IcebergTableProps icebergTableProps,
      WriterOptions options,
      NamespaceKey datasetPath) {
    this.userName = userName;
    this.plugin = plugin;
    this.formatPlugin = formatPlugin;
    this.location = location;
    this.options = options;
    this.icebergTableProps = icebergTableProps;
    this.datasetPath = datasetPath;
  }

  @JsonProperty("pluginId")
  public StoragePluginId getId() {
    return plugin.getId();
  }

  @JsonProperty("formatConfig")
  public FormatPluginConfig getFormatConfig() {
    return formatPlugin.getConfig();
  }

  @Override
  @JsonProperty("location")
  public String getLocation() {
    return location;
  }

  @Override
  @JsonIgnore
  public FileSystemPlugin<?> getPlugin() {
    return plugin;
  }

  @Override
  public EasyFileSystemCreateTableEntry cloneWithNewLocation(String newLocation) {
    return new EasyFileSystemCreateTableEntry(
        userName, plugin, formatPlugin, newLocation, icebergTableProps, options, datasetPath);
  }

  @Override
  public EasyFileSystemCreateTableEntry cloneWithFields(WriterOptions writerOptions) {
    return new EasyFileSystemCreateTableEntry(
        userName, plugin, formatPlugin, location, icebergTableProps, writerOptions, datasetPath);
  }

  @Override
  public String getUserName() {
    return userName;
  }

  @Override
  public String getUserId() {
    return null;
  }

  @Override
  public Writer getWriter(OpProps props, PhysicalOperator child) throws IOException {
    // In a iceberg flow, schema in a icebergTableProps should be set only once and by the first
    // writer i.e parquet writer.
    // Because any writer in a plan after first ParquetWriter will have RecordWriterSchema which is
    // not valid iceberg table schema.
    if (child != null
        && child.getProps() != null
        && icebergTableProps != null
        && !icebergTableProps.isSchemaSet()) {
      BatchSchema writerSchema = child.getProps().getSchema();
      writerSchema = IcebergUtils.getWriterSchema(writerSchema, options);
      icebergTableProps.setFullSchema(writerSchema);
    }
    return formatPlugin.getWriter(child, location, plugin, options, props);
  }

  @Override
  public WriterOptions getOptions() {
    return options;
  }

  @Override
  public IcebergTableProps getIcebergTableProps() {
    return icebergTableProps;
  }

  @Override
  public NamespaceKey getDatasetPath() {
    return datasetPath;
  }

  @Override
  @JsonIgnore
  public SystemIcebergTablesStoragePlugin getSystemIcebergTablesPlugin() {
    if (systemIcebergTablesPlugin != null) {
      return systemIcebergTablesPlugin;
    }

    if (systemIcebergTablesPluginId != null && storagePluginResolver != null) {
      return storagePluginResolver.getSource(systemIcebergTablesPluginId);
    }

    return null;
  }

  @Override
  public void setSystemIcebergTablesPlugin(
      SystemIcebergTablesStoragePlugin systemIcebergTablesPlugin) {
    this.systemIcebergTablesPlugin = systemIcebergTablesPlugin;
    if (systemIcebergTablesPlugin != null) {
      this.systemIcebergTablesPluginId = systemIcebergTablesPlugin.getId();
    }
  }
}
