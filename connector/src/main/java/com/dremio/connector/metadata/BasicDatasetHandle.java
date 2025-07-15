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
package com.dremio.connector.metadata;

import java.util.Iterator;
import java.util.List;

/** A basic dataset handle without any additional metadata. */
public class BasicDatasetHandle implements DatasetHandle, PartitionChunkListing {
  private final EntityPath entityPath;
  private static final long RECORD_COUNT = 10000L;
  private static final long SIZE_IN_BYTES = 100000L;
  private static final List<PartitionChunk> PARTITION_CHUNKS =
      List.of(PartitionChunk.of(DatasetSplit.of(SIZE_IN_BYTES, RECORD_COUNT)));

  public BasicDatasetHandle(EntityPath entityPath) {
    this.entityPath = entityPath;
  }

  @Override
  public EntityPath getDatasetPath() {
    return entityPath;
  }

  @Override
  public Iterator<? extends PartitionChunk> iterator() {
    return PARTITION_CHUNKS.iterator();
  }
}
