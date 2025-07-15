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
package com.dremio.dac.model.resourcetree;

import static java.lang.String.format;

import com.dremio.dac.model.spaces.HomeName;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceUtils;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.source.proto.SourceChangeState;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Container object for dataset/folder */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceTreeEntity {
  private final String id;
  private final ResourceType type;
  private final String name;
  private final List<String> fullPath;
  private final String url; // only if its a listable entity
  private List<ResourceTreeEntity> resources = null; // filled in only on expansion.
  private final ResourceType rootType; // can only be top level types i.e. SOURCE, SPACE, or HOME
  private Boolean metadataOutdated; // only for VIRTUAL_DATASET
  private SourceChangeState sourceChangeState; // only for sources

  public ResourceTreeEntity(SourceConfig sourceConfig) throws UnsupportedEncodingException {
    this.type = ResourceType.SOURCE;
    this.name = sourceConfig.getName();
    this.fullPath = Collections.singletonList(this.name);
    this.url = null; // TODO can't explore sources yet
    this.id = sourceConfig.getId().getId();
    this.rootType = ResourceType.SOURCE;
    this.sourceChangeState = sourceConfig.getSourceChangeState();
  }

  public ResourceTreeEntity(SpaceConfig spaceConfig) throws UnsupportedEncodingException {
    this.type = ResourceType.SPACE;
    this.name = spaceConfig.getName();
    this.fullPath = Collections.singletonList(this.name);
    this.url = "/resourcetree/" + new NamespaceKey(this.fullPath).toUrlEncodedString();
    this.id = spaceConfig.getId().getId();
    this.rootType = ResourceType.SPACE;
  }

  public ResourceTreeEntity(HomeConfig homeConfig) throws UnsupportedEncodingException {
    this.type = ResourceType.HOME;
    this.name = HomeName.getUserHomePath(homeConfig.getOwner()).toString();
    this.fullPath = Collections.singletonList(this.name);
    this.url = "/resourcetree/" + new NamespaceKey(this.fullPath).toUrlEncodedString();
    this.id = homeConfig.getId().getId();
    this.rootType = ResourceType.HOME;
  }

  public ResourceTreeEntity(FolderConfig folderConfig, ResourceType rootType)
      throws UnsupportedEncodingException {
    this.type = ResourceType.FOLDER;
    this.name = folderConfig.getName();
    this.fullPath = folderConfig.getFullPathList();
    this.url = "/resourcetree/" + new NamespaceKey(this.fullPath).toUrlEncodedString();
    this.id = folderConfig.getId().getId();
    this.rootType = rootType;
  }

  public ResourceTreeEntity(DatasetConfig datasetConfig, ResourceType rootType)
      throws UnsupportedEncodingException {
    // TODO File system folder datasets can further be explored.
    this.type = getResourceType(datasetConfig.getType());
    this.name = datasetConfig.getName();
    this.fullPath = datasetConfig.getFullPathList();
    this.url = null;
    this.id = datasetConfig.getId().getId();
    this.rootType = rootType;
    this.metadataOutdated = NamespaceUtils.isSchemaOutdated(datasetConfig);
  }

  @JsonCreator
  public ResourceTreeEntity(
      @JsonProperty("type") ResourceType type,
      @JsonProperty("name") String name,
      @JsonProperty("fullPath") List<String> fullPath,
      @JsonProperty("url") String url,
      @JsonProperty("resources") List<ResourceTreeEntity> resources,
      @JsonProperty("id") String id,
      @JsonProperty("rootType") ResourceType rootType,
      @JsonProperty("metadataOutdated") Boolean metadataOutdated) {
    this.type = type;
    this.name = name;
    this.fullPath = fullPath;
    this.url = url;
    this.resources = resources;
    this.id = id;
    this.rootType = rootType;
    this.metadataOutdated = metadataOutdated;
    this.sourceChangeState = null;
  }

  public ResourceType getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public List<String> getFullPath() {
    return fullPath;
  }

  public String getUrl() {
    return url;
  }

  public String getId() {
    return id;
  }

  public ResourceType getRootType() {
    return rootType;
  }

  public Boolean getMetadataOutdated() {
    return metadataOutdated;
  }

  public SourceChangeState getSourceChangeState() {
    return sourceChangeState;
  }

  public static ResourceType getResourceType(DatasetType type) {
    switch (type) {
      case VIRTUAL_DATASET:
        return ResourceType.VIRTUAL_DATASET;
      case PHYSICAL_DATASET:
        return ResourceType.PHYSICAL_DATASET;
      case PHYSICAL_DATASET_SOURCE_FILE:
        return ResourceType.PHYSICAL_DATASET_SOURCE_FILE;
      case PHYSICAL_DATASET_SOURCE_FOLDER:
        return ResourceType.PHYSICAL_DATASET_SOURCE_FOLDER;
      case PHYSICAL_DATASET_HOME_FILE:
        return ResourceType.PHYSICAL_DATASET_HOME_FILE;
      case PHYSICAL_DATASET_HOME_FOLDER:
        return ResourceType.PHYSICAL_DATASET_HOME_FOLDER;
      default:
        break;
    }
    throw new IllegalArgumentException("Invalid dataset type " + type);
  }

  @Override
  public String toString() {
    return format("%s : %s", type.toString(), new NamespaceKey(fullPath).toString());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (obj instanceof ResourceTreeEntity) {
      ResourceTreeEntity other = (ResourceTreeEntity) obj;
      return Objects.equals(fullPath, other.fullPath)
          && Objects.equals(type, other.type)
          && Objects.equals(name, other.name);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(fullPath, name, type);
  }

  /** Merging top level namespace types with dataset type */
  // check who is using this
  public enum ResourceType {
    SOURCE, // always at the top of tree
    SPACE, // always at the top of tree
    FOLDER,
    HOME, // always at the top of tree
    VIRTUAL_DATASET,
    PHYSICAL_DATASET,
    PHYSICAL_DATASET_SOURCE_FILE,
    PHYSICAL_DATASET_SOURCE_FOLDER,
    PHYSICAL_DATASET_HOME_FILE,
    PHYSICAL_DATASET_HOME_FOLDER,
    FUNCTION
  }

  public List<ResourceTreeEntity> getResources() {
    return resources;
  }

  @JsonIgnore
  public boolean isListable() {
    return (type == ResourceType.SOURCE
        || type == ResourceType.SPACE
        || type == ResourceType.HOME
        || type == ResourceType.FOLDER);
  }

  public void expand(List<ResourceTreeEntity> resourceList) {
    if (isListable()) {
      this.resources = resourceList;
    }
  }
}
