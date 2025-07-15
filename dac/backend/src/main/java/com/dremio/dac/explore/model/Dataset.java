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

import com.dremio.dac.model.common.AddressableResource;
import com.dremio.dac.model.common.RootEntity;
import com.dremio.dac.model.folder.FolderName;
import com.dremio.dac.model.job.JobFilters;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.util.JSONUtil;
import com.dremio.service.jobs.JobIndexKeys;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The dataset, its history and data */
@JsonIgnoreProperties(
    value = {"links"},
    allowGetters = true)
public class Dataset implements AddressableResource {

  private final String id;

  private final DatasetVersionResourcePath versionedResourcePath;

  private final DatasetName datasetName;

  private final VirtualDatasetUI datasetConfig;

  private final String sql;

  private final DatasetResourcePath resourcePath;

  private final int jobCount;

  private final HistoryItem lastHistoryItem;

  private final List<String> tags;

  @JsonCreator
  public Dataset(
      @JsonProperty("id") String id,
      @JsonProperty("resourcePath") DatasetResourcePath resourcePath,
      @JsonProperty("versionedResourcePath") DatasetVersionResourcePath versionedResourcePath,
      @JsonProperty("datasetName") DatasetName datasetName,
      @JsonProperty("sql") String sql,
      @JsonProperty("datasetConfig") VirtualDatasetUI datasetConfig,
      @JsonProperty("lastHistoryItem") HistoryItem lastHistoryItem,
      @JsonProperty(value = "jobCount", defaultValue = "0") int jobCount,
      @JsonProperty("tags") List<String> tags) {
    this.id = id;
    this.resourcePath = resourcePath;
    this.versionedResourcePath = versionedResourcePath;
    this.datasetName = datasetName;
    this.sql = sql;
    this.datasetConfig = datasetConfig;
    this.lastHistoryItem = lastHistoryItem;
    this.jobCount = jobCount;
    this.tags = tags;
  }

  public static Dataset newInstance(
      DatasetResourcePath resourcePath,
      DatasetVersionResourcePath versionedResourcePath,
      DatasetName datasetName,
      String sql,
      VirtualDatasetUI datasetConfig,
      int jobCount,
      List<String> tags) {
    // The history item is populated only after transform
    return new Dataset(
        datasetConfig.getId(),
        resourcePath,
        versionedResourcePath,
        datasetName,
        sql,
        datasetConfig,
        null,
        jobCount,
        tags);
  }

  public static Dataset newInstance(
      RootEntity rootEntity, List<String> folderNamespace, String folderName, String id) {
    final List<FolderName> folderPath =
        folderNamespace.stream().map(name -> new FolderName(name)).collect(Collectors.toList());
    final DatasetName datasetName = new DatasetName(folderName);
    final DatasetPath datasetPath = new DatasetPath(rootEntity, folderPath, datasetName);

    final DatasetVersion datasetVersion = DatasetVersion.newVersion();
    final VirtualDatasetUI vds = new VirtualDatasetUI();
    vds.setFullPathList(datasetPath.toPathList());
    vds.setName(datasetName.getName());
    vds.setId((id == null) ? UUID.randomUUID().toString() : id);
    vds.setVersion(datasetVersion);

    // For the iceberg view in nessie, we generate a datasetVersion for it.
    final DatasetResourcePath datasetResourcePath = new DatasetResourcePath(datasetPath);
    final DatasetVersionResourcePath datasetVersionResourcePath =
        new DatasetVersionResourcePath(datasetPath, datasetVersion);

    return new Dataset(
        vds.getId(),
        datasetResourcePath,
        datasetVersionResourcePath,
        datasetName,
        null,
        vds,
        null,
        0,
        null);
  }

  public static Dataset newInstance(
      List<String> entityPath, String id, int jobCount, List<String> tags) {

    final DatasetPath datasetPath = new DatasetPath(entityPath);
    DatasetName datasetName = new DatasetName(datasetPath.getLeaf().getName());

    // For Source views in external catalog we generate a  random datasetVersion.
    final DatasetVersion datasetVersion = DatasetVersion.newVersion();
    final VirtualDatasetUI vds = new VirtualDatasetUI();
    vds.setFullPathList(datasetPath.toPathList());
    vds.setName(datasetName.getName());
    vds.setId((id == null) ? UUID.randomUUID().toString() : id);
    vds.setVersion(datasetVersion);

    final DatasetResourcePath datasetResourcePath = new DatasetResourcePath(datasetPath);
    final DatasetVersionResourcePath datasetVersionResourcePath =
        new DatasetVersionResourcePath(datasetPath, datasetVersion);

    return new Dataset(
        vds.getId(),
        datasetResourcePath,
        datasetVersionResourcePath,
        datasetName,
        null, // Unused and not displayed  so no need to pass
        vds,
        null, // Unused and not applicable to source views
        jobCount,
        tags);
  }

  public int getJobCount() {
    return jobCount;
  }

  @Override
  public DatasetResourcePath getResourcePath() {
    return resourcePath;
  }

  public DatasetName getDatasetName() {
    return datasetName;
  }

  public String getSql() {
    return sql;
  }

  public DatasetVersionResourcePath getVersionedResourcePath() {
    return versionedResourcePath;
  }

  public VirtualDatasetUI getDatasetConfig() {
    return datasetConfig;
  }

  public String getId() {
    return id;
  }

  public List<String> getTags() {
    return tags;
  }

  /**
   * @return the history item corresponding to the last transformation
   */
  public HistoryItem getLastHistoryItem() {
    return lastHistoryItem;
  }

  @Override
  public String toString() {
    return JSONUtil.toString(this);
  }

  public Map<String, String> getLinks() {
    DatasetVersion datasetVersion = datasetConfig.getVersion();
    DatasetPath datasetPath = resourcePath.getDataset();

    Map<String, String> links = new HashMap<>();
    links.put("self", datasetPath.toUrlPath());
    links.put("query", datasetPath.getQueryUrlPath());
    links.put(
        "edit",
        links.get("query")
            + "?mode=edit&version="
            + (datasetVersion == null
                ? datasetVersion
                : encodeURIComponent(datasetVersion.toString())));
    final JobFilters jobFilters =
        new JobFilters()
            .addFilter(JobIndexKeys.ALL_DATASETS, datasetPath.toString())
            .addFilter(JobIndexKeys.QUERY_TYPE, JobIndexKeys.UI, JobIndexKeys.EXTERNAL);
    links.put("jobs", jobFilters.toUrl());
    return links;
  }
}
