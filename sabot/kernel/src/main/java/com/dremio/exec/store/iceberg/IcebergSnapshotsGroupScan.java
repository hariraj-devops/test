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

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.AbstractBase;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.PhysicalVisitor;
import com.dremio.exec.physical.base.SubScan;
import com.dremio.exec.planner.fragment.DistributionAffinity;
import com.dremio.exec.planner.fragment.ExecutionNodeMap;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.SplitWork;
import com.dremio.service.namespace.PartitionChunkMetadata;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Iceberg snapshots group scan */
public class IcebergSnapshotsGroupScan extends AbstractBase implements GroupScan<SplitWork> {
  private final SnapshotsScanOptions snapshotsScanOptions;
  private final List<SchemaPath> columns;
  private final StoragePluginId storagePluginId;
  private final Iterator<PartitionChunkMetadata> splits;
  private final int maxParallelizationWidth;
  private final String schemeVariate;
  private final String userId;
  private final List<String> tableQualifiedName;

  public IcebergSnapshotsGroupScan(
      OpProps props,
      StoragePluginId storagePluginId,
      List<SchemaPath> columns,
      SnapshotsScanOptions snapshotsScanOptions,
      Iterator<PartitionChunkMetadata> splits,
      int maxParallelizationWidth,
      String schemeVariate,
      String userId,
      List<String> tableQualifiedName) {
    super(props);
    this.snapshotsScanOptions = snapshotsScanOptions;
    this.columns = columns;
    this.storagePluginId = storagePluginId;
    this.splits = splits;
    this.maxParallelizationWidth = maxParallelizationWidth;
    this.schemeVariate = schemeVariate;
    this.userId = userId;
    this.tableQualifiedName = tableQualifiedName;
  }

  @Override
  public int getMaxParallelizationWidth() {
    return this.maxParallelizationWidth;
  }

  @Override
  public int getMinParallelizationWidth() {
    return 1; // Will carry only one split
  }

  @Override
  public DistributionAffinity getDistributionAffinity() {
    return storagePluginId
            .getCapabilities()
            .getCapability(SourceCapabilities.REQUIRES_HARD_AFFINITY)
        ? DistributionAffinity.HARD
        : DistributionAffinity.SOFT;
  }

  @Override
  public <T, X, E extends Throwable> T accept(PhysicalVisitor<T, X, E> physicalVisitor, X value)
      throws E {
    return physicalVisitor.visitGroupScan(this, value);
  }

  @Override
  public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children)
      throws ExecutionSetupException {
    return this;
  }

  @Override
  public int getOperatorType() {
    return UserBitShared.CoreOperatorType.ICEBERG_SNAPSHOTS_SUB_SCAN_VALUE;
  }

  @Override
  public Iterator<SplitWork> getSplits(ExecutionNodeMap nodeMap) {
    return SplitWork.transform(splits, nodeMap, getDistributionAffinity());
  }

  @Override
  public SubScan getSpecificScan(List<SplitWork> works) throws ExecutionSetupException {
    return new IcebergSnapshotsSubScan(
        props,
        props.getSchema(),
        storagePluginId,
        columns,
        snapshotsScanOptions,
        works,
        schemeVariate,
        userId,
        tableQualifiedName);
  }

  @Override
  public List<SchemaPath> getColumns() {
    return this.columns;
  }

  @Override
  public Iterator<PhysicalOperator> iterator() {
    return Collections.emptyIterator();
  }
}
