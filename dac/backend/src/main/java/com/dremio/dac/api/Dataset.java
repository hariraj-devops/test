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

import com.dremio.service.namespace.dataset.proto.AccelerationSettings;
import com.dremio.service.namespace.dataset.proto.RefreshMethod;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.proto.RefreshPolicyType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

/** Dataset model */
@JsonIgnoreProperties(
    value = {"fields"},
    allowGetters = true,
    ignoreUnknown = true)
public class Dataset implements CatalogEntity {
  /** Dateset Type */
  public enum DatasetType {
    VIRTUAL_DATASET,
    PHYSICAL_DATASET
  };

  private final String id;
  private final DatasetType type;
  private final List<String> path;

  private final DatasetFields fields;

  @JsonISODateTime private final Long createdAt;
  private final String tag;
  private final RefreshSettings accelerationRefreshPolicy;

  /** Null indicates unknown. */
  @JsonProperty private Boolean isMetadataExpired;

  /** Null indicates unknown, not serialized to JSON */
  @JsonProperty("lastMetadataRefreshAt")
  @JsonISODateTime
  private Long lastMetadataRefreshAtMillis;

  // for VDS
  private final String sql;
  private final List<String> sqlContext;
  private final Boolean schemaOutdated;

  // for PDS
  private final Boolean approximateStatisticsAllowed;

  // for promoted PDS
  private final FileFormat format;

  public Dataset(
      String id,
      DatasetType type,
      List<String> path,
      DatasetFields fields,
      Long createdAt,
      String tag,
      RefreshSettings accelerationRefreshPolicy,
      String sql,
      List<String> sqlContext,
      FileFormat format,
      Boolean approximateStatisticsAllowed,
      Boolean schemaOutdated) {
    this.id = id;
    this.type = type;
    this.path = path;
    this.fields = fields;
    this.createdAt = createdAt;
    this.tag = tag;
    this.accelerationRefreshPolicy = accelerationRefreshPolicy;
    this.sql = sql;
    this.sqlContext = sqlContext;
    this.format = format;
    this.approximateStatisticsAllowed = approximateStatisticsAllowed;
    this.schemaOutdated = schemaOutdated;
  }

  @JsonCreator
  private Dataset(
      @JsonProperty("id") String id,
      @JsonProperty("type") DatasetType type,
      @JsonProperty("path") List<String> path,
      @JsonProperty("createdAt") Long createdAt,
      @JsonProperty("tag") String tag,
      @JsonProperty("accelerationRefreshPolicy") RefreshSettings accelerationRefreshPolicy,
      @JsonProperty("sql") String sql,
      @JsonProperty("sqlContext") List<String> sqlContext,
      @JsonProperty("format") FileFormat format,
      @JsonProperty("approximateStatisticsAllowed") Boolean approximateStatisticsAllowed,
      @JsonProperty("schemaOutdated") Boolean schemaOutdated) {
    // we don't want to deserialize fields ever since they are immutable anyways
    this(
        id,
        type,
        path,
        null,
        createdAt,
        tag,
        accelerationRefreshPolicy,
        sql,
        sqlContext,
        format,
        approximateStatisticsAllowed,
        schemaOutdated);
  }

  /**
   * Table metadata could be out-of-date, not valid refers to that state. See {@link
   * com.dremio.exec.catalog.DatasetMetadataState}.
   */
  public void setMetadataExpired(
      boolean isMetadataExpired, @Nullable Long lastMetadataRefreshAtMillis) {
    this.isMetadataExpired = isMetadataExpired;
    this.lastMetadataRefreshAtMillis = lastMetadataRefreshAtMillis;
  }

  @Override
  public String getId() {
    return id;
  }

  public DatasetType getType() {
    return type;
  }

  public List<String> getPath() {
    return path;
  }

  public DatasetFields getFields() {
    return fields;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public String getTag() {
    return tag;
  }

  public String getSql() {
    return sql;
  }

  public List<String> getSqlContext() {
    return sqlContext;
  }

  public FileFormat getFormat() {
    return format;
  }

  public RefreshSettings getAccelerationRefreshPolicy() {
    return accelerationRefreshPolicy;
  }

  public Boolean getApproximateStatisticsAllowed() {
    return approximateStatisticsAllowed;
  }

  /** Whether metadata is "valid", i.e. up-to-date with regards to refresh schedule settings. */
  public Boolean getIsMetadataExpired() {
    return isMetadataExpired;
  }

  /** Optional time in millis for when metadata validity was last run. */
  public Long getLastMetadataRefreshAtMillis() {
    return lastMetadataRefreshAtMillis;
  }

  /** Whether schema is outdated because any underlying dataset changed. */
  public Boolean getSchemaOutdated() {
    return schemaOutdated;
  }

  /** Dataset acceleration refresh settings */
  public static class RefreshSettings {
    private final String refreshField;
    private final Long refreshPeriodMs;
    private final Long gracePeriodMs;
    private final RefreshMethod method;
    private final Boolean neverExpire;
    private final Boolean neverRefresh;
    private final RefreshPolicyType activePolicyType;
    private final String refreshSchedule;
    private final Boolean sourceRefreshOnDataChanges;

    @JsonCreator
    public RefreshSettings(
        @JsonProperty("activePolicyType") RefreshPolicyType activePolicyType,
        @JsonProperty("refreshField") String refreshField,
        @JsonProperty("refreshPeriodMs") Long refreshPeriodMs,
        @JsonProperty("refreshSchedule") String refreshSchedule,
        @JsonProperty("gracePeriodMs") Long gracePeriodMs,
        @JsonProperty("method") RefreshMethod method,
        @JsonProperty("neverExpire") Boolean neverExpire,
        @JsonProperty("neverRefresh") Boolean neverRefresh,
        @JsonProperty("sourceRefreshOnDataChanges") Boolean sourceRefreshOnDataChanges) {
      this.activePolicyType = activePolicyType;
      this.refreshField = refreshField;
      this.refreshPeriodMs = refreshPeriodMs;
      this.refreshSchedule = refreshSchedule;
      this.gracePeriodMs = gracePeriodMs;
      this.method = method;
      this.neverExpire = neverExpire;
      this.neverRefresh = neverRefresh;
      this.sourceRefreshOnDataChanges = sourceRefreshOnDataChanges;
    }

    public RefreshSettings(AccelerationSettings settings) {
      activePolicyType = settings.getRefreshPolicyType();
      refreshField = settings.getRefreshField();
      method = settings.getMethod();
      refreshPeriodMs = settings.getRefreshPeriod();
      refreshSchedule = settings.getRefreshSchedule();
      gracePeriodMs = settings.getGracePeriod();
      neverExpire = settings.getNeverExpire();
      neverRefresh = settings.getNeverRefresh();
      sourceRefreshOnDataChanges = settings.getSourceRefreshOnDataChanges();
    }

    public RefreshPolicyType getActivePolicyType() {
      return activePolicyType;
    }

    public String getRefreshField() {
      return refreshField;
    }

    public Long getRefreshPeriodMs() {
      return refreshPeriodMs;
    }

    public String getRefreshSchedule() {
      return refreshSchedule;
    }

    public Long getGracePeriodMs() {
      return gracePeriodMs;
    }

    public RefreshMethod getMethod() {
      return method;
    }

    public Boolean getNeverExpire() {
      return neverExpire;
    }

    public Boolean getNeverRefresh() {
      return neverRefresh;
    }

    public Boolean getSourceRefreshOnDataChanges() {
      return sourceRefreshOnDataChanges;
    }

    public AccelerationSettings toAccelerationSettings() {
      AccelerationSettings settings = new AccelerationSettings();

      settings.setRefreshPolicyType(getActivePolicyType());
      settings.setRefreshPeriod(getRefreshPeriodMs());
      settings.setRefreshSchedule(getRefreshSchedule());
      settings.setGracePeriod(getGracePeriodMs());
      settings.setMethod(getMethod());
      settings.setRefreshField(getRefreshField());
      settings.setNeverRefresh(getNeverRefresh());
      settings.setNeverExpire(getNeverExpire());
      settings.setSourceRefreshOnDataChanges(getSourceRefreshOnDataChanges());

      return settings;
    }
  }

  @Nullable
  @Override
  public String getNextPageToken() {
    return null;
  }
}
