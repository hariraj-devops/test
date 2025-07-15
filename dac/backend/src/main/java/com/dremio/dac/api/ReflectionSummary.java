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
package com.dremio.dac.api;

import com.dremio.dac.service.reflection.ReflectionStatusUI;
import com.dremio.service.reflection.proto.ReflectionType;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.List;

/** Reflection summary model for the public REST API. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ReflectionSummary {
  private final String id;
  private final ReflectionType type;
  private final String name;
  @JsonISODateTime private final long createdAt;

  @JsonISODateTime private final long updatedAt;
  private final long currentSizeBytes;
  private final long totalSizeBytes;
  private final boolean enabled;
  private final String reflectionMode;

  private final String datasetId;
  private final Dataset.DatasetType datasetType;
  private final List<String> datasetPath;

  private final ReflectionStatusUI status;

  public ReflectionSummary(
      String id,
      ReflectionType type,
      String name,
      long createdAt,
      long updatedAt,
      long currentSizeBytes,
      long totalSizeBytes,
      boolean enabled,
      ReflectionStatusUI status,
      String datasetId,
      Dataset.DatasetType datasetType,
      List<String> datasetPath,
      String reflectionMode) {
    this.id = id;
    this.type = type;
    this.name = name;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.datasetId = datasetId;
    this.currentSizeBytes = currentSizeBytes;
    this.totalSizeBytes = totalSizeBytes;
    this.enabled = enabled;
    this.datasetType = datasetType;
    this.datasetPath = datasetPath;
    this.status = status;
    this.reflectionMode = reflectionMode;
  }

  public String getEntityType() {
    return "reflection-summary";
  }

  public String getId() {
    return id;
  }

  public ReflectionType getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public long getUpdatedAt() {
    return updatedAt;
  }

  public long getCurrentSizeBytes() {
    return currentSizeBytes;
  }

  public long getTotalSizeBytes() {
    return totalSizeBytes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getDatasetId() {
    return datasetId;
  }

  public Dataset.DatasetType getDatasetType() {
    return datasetType;
  }

  public List<String> getDatasetPath() {
    return datasetPath;
  }

  public ReflectionStatusUI getStatus() {
    return status;
  }

  public String getReflectionMode() {
    return reflectionMode;
  }
}
