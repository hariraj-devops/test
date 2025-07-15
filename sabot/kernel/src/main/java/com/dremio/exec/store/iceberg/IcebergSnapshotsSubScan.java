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
package com.dremio.exec.store.iceberg;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.SubScanWithProjection;
import com.dremio.exec.planner.fragment.MinorDataReader;
import com.dremio.exec.planner.fragment.MinorDataWriter;
import com.dremio.exec.planner.fragment.SplitNormalizer;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SplitAndPartitionInfo;
import com.dremio.exec.store.SplitWork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Iceberg snapshots subscan POP */
@JsonTypeName("iceberg-snapshots-sub-scan")
public class IcebergSnapshotsSubScan extends SubScanWithProjection {
  private static final Collection<List<String>> NO_REFERENCED_TABLES = Collections.emptyList();

  private final StoragePluginId pluginId;
  private final SnapshotsScanOptions snapshotsScanOptions;
  @JsonIgnore private List<SplitAndPartitionInfo> splits;
  private final String schemeVariate;
  @Nullable private String userId;
  private final List<String> tableName;

  public IcebergSnapshotsSubScan(
      @JsonProperty("props") OpProps props,
      @JsonProperty("fullSchema") BatchSchema fullSchema,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("columns") List<SchemaPath> columns,
      @JsonProperty("snapshotsScanOptions") SnapshotsScanOptions snapshotsScanOptions,
      @JsonProperty("splitWorks") List<SplitWork> splitWorks,
      @JsonProperty("schemeVariate") String schemeVariate,
      @JsonProperty("userId") String userId,
      @JsonProperty("tableName") List<String> tableName) {
    super(props, fullSchema, NO_REFERENCED_TABLES, columns);
    this.pluginId = pluginId;
    this.snapshotsScanOptions = snapshotsScanOptions;
    this.schemeVariate = schemeVariate;
    if (splitWorks != null) {
      this.splits =
          splitWorks.stream().map(SplitWork::getSplitAndPartitionInfo).collect(Collectors.toList());
    }
    this.userId = userId;
    this.tableName = tableName;
  }

  public StoragePluginId getPluginId() {
    return pluginId;
  }

  public List<SplitAndPartitionInfo> getSplits() {
    return splits;
  }

  public SnapshotsScanOptions getSnapshotsScanOptions() {
    return snapshotsScanOptions;
  }

  public String getSchemeVariate() {
    return schemeVariate;
  }

  public String getUserId() {
    return userId;
  }

  public List<String> getTableName() {
    return tableName;
  }

  @Override
  public int getOperatorType() {
    return UserBitShared.CoreOperatorType.ICEBERG_SNAPSHOTS_SUB_SCAN_VALUE;
  }

  @Override
  public void collectMinorSpecificAttrs(MinorDataWriter writer) {
    SplitNormalizer.write(getProps(), writer, splits);
  }

  @Override
  public void populateMinorSpecificAttrs(MinorDataReader reader) throws Exception {
    splits = SplitNormalizer.read(getProps(), reader);
  }
}
