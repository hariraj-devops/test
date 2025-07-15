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
package com.dremio.dac.explore.model;

import static com.dremio.common.utils.PathUtils.encodeURIComponent;

import com.dremio.dac.api.JsonISODateTime;
import com.dremio.dac.model.common.RootEntity;
import com.dremio.dac.model.folder.FolderName;
import com.dremio.dac.model.job.JobFilters;
import com.dremio.dac.model.sources.SourceName;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.dac.model.spaces.SpaceName;
import com.dremio.dac.model.spaces.TempSpace;
import com.dremio.dac.util.DatasetsUtil;
import com.dremio.service.jobs.JobIndexKeys;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceUtils;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.IcebergViewAttributes;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Dataset summary for overlay */
@JsonIgnoreProperties(
    value = {"links", "apiLinks"},
    allowGetters = true)
public class DatasetSummary {
  private final List<String> fullPath;
  private final int jobCount;
  private final int descendants;
  private final List<Field> fields;
  private final DatasetType datasetType;
  private final DatasetVersion datasetVersion;
  private final Map<String, VersionContextReq> references;
  private final List<String> tags;
  private final String entityId;
  private final Boolean hasReflection;
  @JsonISODateTime private final Long createdAt;
  @JsonISODateTime private final Long lastModified;
  private final String viewSpecVersion;
  private final Boolean schemaOutdated;
  private final NameSpaceContainer.Type rootContainerType;
  private final String viewDialect;

  public DatasetSummary(
      @JsonProperty("fullPath") List<String> fullPath,
      @JsonProperty("jobCount") int jobCount,
      @JsonProperty("descendants") int descendants,
      @JsonProperty("fields") List<Field> fields,
      @JsonProperty("datasetType") DatasetType datasetType,
      @JsonProperty("datasetVersion") DatasetVersion datasetVersion,
      @JsonProperty("tags") List<String> tags,
      @JsonProperty("references") Map<String, VersionContextReq> references,
      @JsonProperty("entityId") String entityId,
      @JsonProperty("hasReflection") Boolean hasReflection,
      @JsonProperty("createdAt") Long createdAt,
      @JsonProperty("lastModified") Long lastModified,
      @JsonProperty("viewSpecVersion") String viewSpecVersion,
      @JsonProperty("schemaOutdated") Boolean schemaOutdated,
      @JsonProperty("rootContainerType") NameSpaceContainer.Type rootContainerType,
      @JsonProperty("viewDialect") String viewDialect) {
    this.fullPath = fullPath;
    this.jobCount = jobCount;
    this.descendants = descendants;
    this.fields = fields;
    this.datasetType = datasetType;
    this.datasetVersion = datasetVersion;
    this.tags = tags;
    this.references = references;
    this.entityId = entityId;
    this.hasReflection = hasReflection;
    this.createdAt = createdAt;
    this.lastModified = lastModified;
    this.viewSpecVersion = viewSpecVersion;
    this.schemaOutdated = schemaOutdated;
    this.rootContainerType = rootContainerType;
    this.viewDialect = viewDialect;
  }

  public static DatasetSummary newInstance(
      DatasetConfig datasetConfig,
      int jobCount,
      int descendants,
      Map<String, VersionContextReq> references,
      List<String> tags,
      Boolean hasReflection,
      NameSpaceContainer.Type containerType) {
    List<String> fullPath = datasetConfig.getFullPathList();

    DatasetType datasetType = datasetConfig.getType();
    List<Field> fields; // here
    DatasetVersion datasetVersion;
    String viewSpecVersion = null;
    String viewDialect = null;

    List<com.dremio.dac.model.common.Field> fieldList =
        DatasetsUtil.getFieldsFromDatasetConfig(datasetConfig);
    if (fieldList == null) {
      fields = null;
    } else {
      final Set<String> partitionedColumnsSet = DatasetsUtil.getPartitionedColumns(datasetConfig);
      final Set<String> sortedColumns = DatasetsUtil.getSortedColumns(datasetConfig);

      fields =
          Lists.transform(
              fieldList,
              new Function<com.dremio.dac.model.common.Field, Field>() {
                @Override
                public Field apply(com.dremio.dac.model.common.Field input) {
                  return new Field(
                      input.getName(),
                      input.getType().name(),
                      partitionedColumnsSet.contains(input.getName()),
                      sortedColumns.contains(input.getName()));
                }
              });
    }

    if (datasetType == DatasetType.VIRTUAL_DATASET) {
      final VirtualDataset virtualDataset = datasetConfig.getVirtualDataset();
      datasetVersion = virtualDataset.getVersion();
      IcebergViewAttributes icebergViewAttributes =
          datasetConfig.getVirtualDataset().getIcebergViewAttributes();
      viewSpecVersion =
          icebergViewAttributes != null ? icebergViewAttributes.getViewSpecVersion() : null;
      viewDialect = icebergViewAttributes != null ? icebergViewAttributes.getViewDialect() : null;
    } else {
      datasetVersion = null;
    }

    final String entityId = datasetConfig.getId() == null ? null : datasetConfig.getId().getId();
    final Long createdAt = datasetConfig.getCreatedAt();
    final Long lastModified = datasetConfig.getLastModified();

    return new DatasetSummary(
        fullPath,
        jobCount,
        descendants,
        fields,
        datasetType,
        datasetVersion,
        tags,
        references,
        entityId,
        hasReflection,
        createdAt,
        lastModified,
        viewSpecVersion,
        NamespaceUtils.isSchemaOutdated(datasetConfig),
        containerType,
        viewDialect);
  }

  public DatasetVersion getDatasetVersion() {
    return datasetVersion;
  }

  public List<String> getFullPath() {
    return fullPath;
  }

  public DatasetType getDatasetType() {
    return datasetType;
  }

  public Integer getJobCount() {
    return jobCount;
  }

  public Integer getDescendants() {
    return descendants;
  }

  public List<Field> getFields() {
    return fields;
  }

  public List<String> getTags() {
    return tags;
  }

  public Map<String, VersionContextReq> getReferences() {
    return references;
  }

  public String getEntityId() {
    return entityId;
  }

  public Boolean getHasReflection() {
    return hasReflection;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public Long getLastModified() {
    return lastModified;
  }

  public String getViewSpecVersion() {
    return viewSpecVersion;
  }

  public NameSpaceContainer.Type getRootContainerType() {
    return rootContainerType;
  }

  public String getViewDialect() {
    return viewDialect;
  }

  public Boolean getSchemaOutdated() {
    return schemaOutdated;
  }

  // links
  // TODO make this consistent with DatasetUI.createLinks. In ideal case, both methods should use
  // the same util method
  public Map<String, String> getLinks() {
    List<String> components = new ArrayList<>(fullPath);
    String leafName = components.remove(fullPath.size() - 1);
    String rootName = components.remove(0);
    DatasetPath datasetPath =
        new DatasetPath(
            getRootEntity(rootName, rootContainerType),
            components.stream().map(FolderName::new).collect(Collectors.toList()),
            new DatasetName(leafName));

    Map<String, String> links = new HashMap<>();

    links.put("self", datasetPath.toUrlPath());
    links.put("query", datasetPath.getQueryUrlPath());
    links.put("jobs", this.getJobsUrl());

    if (datasetType == DatasetType.VIRTUAL_DATASET) {
      String versionValue =
          (datasetVersion != null) ? encodeURIComponent(datasetVersion.toString()) : null;
      links.put("edit", datasetPath.getQueryUrlPath() + "?mode=edit&version=" + versionValue);
    }
    return links;
  }

  private String getJobsUrl() {
    final NamespaceKey datasetPath = new NamespaceKey(fullPath);
    final JobFilters jobFilters =
        new JobFilters()
            .addFilter(JobIndexKeys.ALL_DATASETS, datasetPath.toString())
            .addFilter(JobIndexKeys.QUERY_TYPE, JobIndexKeys.UI, JobIndexKeys.EXTERNAL);
    return jobFilters.toUrl();
  }

  private static RootEntity getRootEntity(String name, NameSpaceContainer.Type rootContainerType) {
    if (TempSpace.isTempSpace(name)) {
      return TempSpace.impl();
    } else if (NamespaceUtils.isHomeSpace(name)) {
      return new HomeName(name);
    } else if (rootContainerType == NameSpaceContainer.Type.SOURCE) {
      return new SourceName(name);
    } else if (rootContainerType == NameSpaceContainer.Type.SPACE) {
      return new SpaceName(name);
    } else {
      throw new IllegalArgumentException(
          "Unexpected rootContainerType: " + rootContainerType + " for name: " + name);
    }
  }
}
