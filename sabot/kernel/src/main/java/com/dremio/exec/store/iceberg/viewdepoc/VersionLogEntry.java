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
package com.dremio.exec.store.iceberg.viewdepoc;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

public class VersionLogEntry implements HistoryEntry {
  private final long timestampMillis;
  private final int versionId;

  public VersionLogEntry(long timestampMillis, int versionId) {
    this.timestampMillis = timestampMillis;
    this.versionId = versionId;
  }

  @Override
  public long timestampMillis() {
    return timestampMillis;
  }

  @Override
  public int versionId() {
    return versionId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof VersionLogEntry)) {
      return false;
    }
    VersionLogEntry that = (VersionLogEntry) other;
    return timestampMillis == that.timestampMillis && versionId == that.versionId;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(timestampMillis, versionId);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("timestampMillis", timestampMillis)
        .add("versionId", versionId)
        .toString();
  }
}
