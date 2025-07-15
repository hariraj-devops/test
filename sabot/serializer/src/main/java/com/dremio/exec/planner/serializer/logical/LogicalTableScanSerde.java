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
package com.dremio.exec.planner.serializer.logical;

import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.serializer.RelNodeSerde;
import com.dremio.plan.serialization.PLogicalTableScan;
import com.dremio.service.namespace.NamespaceKey;
import org.apache.calcite.rel.logical.LogicalTableScan;

/** Serde for LogicalTableScan */
public final class LogicalTableScanSerde
    implements RelNodeSerde<LogicalTableScan, PLogicalTableScan> {
  @Override
  public PLogicalTableScan serialize(LogicalTableScan scan, RelToProto s) {
    DremioTable table = scan.getTable().unwrap(DremioTable.class);
    return PLogicalTableScan.newBuilder().addAllPath(table.getPath().getPathComponents()).build();
  }

  @Override
  public LogicalTableScan deserialize(PLogicalTableScan node, RelFromProto s) {
    DremioPrepareTable table = s.tables().getTable(new NamespaceKey(node.getPathList()));
    return (LogicalTableScan) table.toRel(s.toRelContext());
  }
}
