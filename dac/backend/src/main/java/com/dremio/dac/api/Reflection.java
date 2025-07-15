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

import static com.dremio.exec.planner.physical.PlannerSettings.MANUAL_REFLECTION_MODE;

import com.dremio.dac.service.errors.ClientErrorException;
import com.dremio.dac.service.reflection.ReflectionStatusUI;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.proto.PartitionDistributionStrategy;
import com.dremio.service.reflection.proto.ReflectionDetails;
import com.dremio.service.reflection.proto.ReflectionDimensionField;
import com.dremio.service.reflection.proto.ReflectionField;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionMeasureField;
import com.dremio.service.reflection.proto.ReflectionPartitionField;
import com.dremio.service.reflection.proto.ReflectionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.List;

/** Reflection model for the public REST API. */
@JsonIgnoreProperties(
    value = {"entityType"},
    allowGetters = true,
    ignoreUnknown = true)
public class Reflection {
  private String id;
  private ReflectionType type;
  private String name;
  private String tag;
  @JsonISODateTime private Long createdAt;
  @JsonISODateTime private Long updatedAt;
  private String datasetId;
  private Long currentSizeBytes;
  private Long totalSizeBytes;
  private Boolean enabled;

  private ReflectionStatusUI status;

  private List<ReflectionDimensionField> dimensionFields;
  private List<ReflectionMeasureField> measureFields;
  private List<ReflectionField> displayFields;
  private List<ReflectionField> distributionFields;
  private List<ReflectionPartitionField> partitionFields;
  private List<ReflectionField> sortFields;

  private PartitionDistributionStrategy partitionDistributionStrategy;
  private String reflectionMode;

  public Reflection() {}

  public Reflection(ReflectionGoal goal, ReflectionUtils reflectionUtils) {
    type = goal.getType();
    dimensionFields = goal.getDetails().getDimensionFieldList();
    measureFields = goal.getDetails().getMeasureFieldList();
    displayFields = goal.getDetails().getDisplayFieldList();
    enabled = goal.getState() == ReflectionGoalState.ENABLED;
    reflectionMode = reflectionUtils.getReflectionMode(goal);
  }

  public Reflection(
      ReflectionGoal goal,
      ReflectionStatusUI status,
      long currentSize,
      long totalSize,
      String reflectionMode) {
    id = goal.getId().getId();
    name = goal.getName();
    type = goal.getType();
    tag = goal.getTag();
    createdAt = goal.getCreatedAt();
    updatedAt = goal.getModifiedAt();
    datasetId = goal.getDatasetId();
    enabled = goal.getState().equals(ReflectionGoalState.ENABLED);
    partitionDistributionStrategy = goal.getDetails().getPartitionDistributionStrategy();

    this.status = status;

    currentSizeBytes = currentSize;
    totalSizeBytes = totalSize;

    dimensionFields = goal.getDetails().getDimensionFieldList();
    measureFields = goal.getDetails().getMeasureFieldList();
    displayFields = goal.getDetails().getDisplayFieldList();
    distributionFields = goal.getDetails().getDistributionFieldList();
    partitionFields = goal.getDetails().getPartitionFieldList();
    sortFields = goal.getDetails().getSortFieldList();
    this.reflectionMode = reflectionMode;
  }

  public Reflection(
      String id,
      ReflectionType type,
      String name,
      String tag,
      Long createdAt,
      Long updatedAt,
      String datasetId,
      Long currentSizeBytes,
      Long totalSizeBytes,
      boolean enabled,
      ReflectionStatusUI status,
      List<ReflectionDimensionField> dimensionFields,
      List<ReflectionMeasureField> measureFields,
      List<ReflectionField> displayFields,
      List<ReflectionField> distributionFields,
      List<ReflectionPartitionField> partitionFields,
      List<ReflectionField> sortFields,
      PartitionDistributionStrategy partitionDistributionStrategy,
      String reflectionMode) {
    this.id = id;
    this.type = type;
    this.name = name;
    this.tag = tag;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.datasetId = datasetId;
    this.currentSizeBytes = currentSizeBytes;
    this.totalSizeBytes = totalSizeBytes;
    this.enabled = enabled;
    this.status = status;

    this.dimensionFields = dimensionFields;
    this.measureFields = measureFields;
    this.displayFields = displayFields;
    this.distributionFields = distributionFields;
    this.partitionFields = partitionFields;
    this.sortFields = sortFields;
    this.partitionDistributionStrategy = partitionDistributionStrategy;
    this.reflectionMode = reflectionMode;
  }

  public String getEntityType() {
    return "reflection";
  }

  public static Reflection newRawReflection(
      String id,
      String name,
      String tag,
      Long createdAt,
      Long updatedAt,
      String datasetId,
      Long currentSizeBytes,
      Long totalSizeBytes,
      boolean enabled,
      ReflectionStatusUI status,
      List<ReflectionField> displayFields,
      List<ReflectionField> distributionFields,
      List<ReflectionPartitionField> partitionFields,
      List<ReflectionField> sortFields,
      PartitionDistributionStrategy partitionDistributionStrategy,
      String reflectionMode) {
    return new Reflection(
        id,
        ReflectionType.RAW,
        name,
        tag,
        createdAt,
        updatedAt,
        datasetId,
        currentSizeBytes,
        totalSizeBytes,
        enabled,
        status,
        null,
        null,
        displayFields == null ? Collections.emptyList() : displayFields,
        distributionFields == null ? Collections.emptyList() : distributionFields,
        partitionFields == null ? Collections.emptyList() : partitionFields,
        sortFields == null ? Collections.emptyList() : sortFields,
        partitionDistributionStrategy,
        reflectionMode);
  }

  public static Reflection newAggReflection(
      String id,
      String name,
      String tag,
      Long createdAt,
      Long updatedAt,
      String datasetId,
      Long currentSizeBytes,
      Long totalSizeBytes,
      boolean enabled,
      ReflectionStatusUI status,
      List<ReflectionDimensionField> dimensionFields,
      List<ReflectionMeasureField> measureFields,
      List<ReflectionField> distributionFields,
      List<ReflectionPartitionField> partitionFields,
      List<ReflectionField> sortFields,
      PartitionDistributionStrategy partitionDistributionStrategy,
      String reflectionMode) {
    return new Reflection(
        id,
        ReflectionType.AGGREGATION,
        name,
        tag,
        createdAt,
        updatedAt,
        datasetId,
        currentSizeBytes,
        totalSizeBytes,
        enabled,
        status,
        dimensionFields == null ? Collections.emptyList() : dimensionFields,
        measureFields == null ? Collections.emptyList() : measureFields,
        null,
        distributionFields == null ? Collections.emptyList() : distributionFields,
        partitionFields == null ? Collections.emptyList() : partitionFields,
        sortFields == null ? Collections.emptyList() : sortFields,
        partitionDistributionStrategy,
        reflectionMode);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public ReflectionType getType() {
    return type;
  }

  public void setType(ReflectionType type) {
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getTag() {
    return tag;
  }

  public void setTag(String tag) {
    this.tag = tag;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long createdAt) {
    this.createdAt = createdAt;
  }

  public Long getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Long updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getDatasetId() {
    return datasetId;
  }

  public void setDatasetId(String datasetId) {
    this.datasetId = datasetId;
  }

  public Long getCurrentSizeBytes() {
    return currentSizeBytes;
  }

  public void setCurrentSizeBytes(Long currentSizeBytes) {
    this.currentSizeBytes = currentSizeBytes;
  }

  public Long getTotalSizeBytes() {
    return totalSizeBytes;
  }

  public void setTotalSizeBytes(Long totalSizeBytes) {
    this.totalSizeBytes = totalSizeBytes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public ReflectionStatusUI getStatus() {
    return status;
  }

  public void setStatus(ReflectionStatusUI status) {
    this.status = status;
  }

  public List<ReflectionDimensionField> getDimensionFields() {
    return dimensionFields;
  }

  public void setDimensionFields(List<ReflectionDimensionField> dimensionFields) {
    this.dimensionFields = dimensionFields;
  }

  public List<ReflectionMeasureField> getMeasureFields() {
    return measureFields;
  }

  public void setMeasureFields(List<ReflectionMeasureField> measureFields) {
    this.measureFields = measureFields;
  }

  public List<ReflectionField> getDisplayFields() {
    return displayFields;
  }

  public void setDisplayFields(List<ReflectionField> displayFields) {
    this.displayFields = displayFields;
  }

  public List<ReflectionField> getDistributionFields() {
    return distributionFields;
  }

  public void setDistributionFields(List<ReflectionField> distributionFields) {
    this.distributionFields = distributionFields;
  }

  public List<ReflectionPartitionField> getPartitionFields() {
    return partitionFields;
  }

  public void setPartitionFields(List<ReflectionPartitionField> partitionFields) {
    this.partitionFields = partitionFields;
  }

  public List<ReflectionField> getSortFields() {
    return sortFields;
  }

  public void setSortFields(List<ReflectionField> sortFields) {
    this.sortFields = sortFields;
  }

  public PartitionDistributionStrategy getPartitionDistributionStrategy() {
    return partitionDistributionStrategy;
  }

  public String getReflectionMode() {
    return reflectionMode;
  }

  public void setReflectionMode(String reflectionMode) {
    this.reflectionMode = reflectionMode;
  }

  public void setPartitionDistributionStrategy(
      PartitionDistributionStrategy partitionDistributionStrategy) {
    this.partitionDistributionStrategy = partitionDistributionStrategy;
  }

  public ReflectionGoal toReflectionGoal() {
    ReflectionGoal goal = new ReflectionGoal();
    if (id != null) {
      goal.setId(new ReflectionId(id));
    }
    goal.setType(type);
    goal.setName(name);

    if (tag != null) {
      goal.setTag(tag);
    }
    goal.setCreatedAt(createdAt);
    goal.setModifiedAt(updatedAt);
    goal.setDatasetId(datasetId);

    ReflectionDetails details = new ReflectionDetails();
    details.setDimensionFieldList(dimensionFields);
    details.setMeasureFieldList(measureFields);
    details.setDisplayFieldList(displayFields);
    details.setDistributionFieldList(distributionFields);
    details.setPartitionFieldList(partitionFields);
    details.setSortFieldList(sortFields);
    details.setPartitionDistributionStrategy(partitionDistributionStrategy);
    goal.setDetails(details);
    goal.setIsDremioManaged(
        reflectionMode != null && !MANUAL_REFLECTION_MODE.equals(reflectionMode));

    if (enabled == null) {
      throw new ClientErrorException("Reflections must have the enabled field set");
    }

    ReflectionGoalState state =
        enabled ? ReflectionGoalState.ENABLED : ReflectionGoalState.DISABLED;
    goal.setState(state);

    return goal;
  }
}
