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
package com.dremio.dac.server.models;

/**
 * A POJO which represents a server configuration that is injected in a html page and used by UI
 * code
 *
 * <p><b>IMPORTANT!!!</b> if you change any field name you must change UI code respectively
 */
public class ServerData {
  private final String serverEnvironment;
  private final String serverStatus;
  private final String intercomAppId;
  private final boolean shouldEnableBugFiling;
  private final boolean shouldEnableRSOD;
  private final String supportEmailTo;
  private final String supportEmailSubjectForJobs;
  private final boolean outsideCommunicationDisabled;
  private final boolean subhourAccelerationPoliciesEnabled;
  private final boolean lowerProvisioningSettingsEnabled;
  private final boolean allowFileUploads;
  private final boolean allowSpaceManagement;
  private final String tdsMimeType;
  private final String whiteLabelUrl;
  private final String clusterId;
  private final String edition;
  private final AnalyzeTools analyzeTools;
  private final boolean crossSourceDisabled;
  private final boolean queryBundleUsersEnabled;
  private final long downloadRecordsLimit;
  private final boolean showMetadataValidityCheckbox;
  private final boolean showNewJobsPage;
  private final boolean showOldReflectionsListing;
  private final boolean allowAutoComplete;
  private final boolean allowDownload;
  private final boolean allowFormatting;
  private final boolean useNewDatasetNavigation;
  private final boolean asyncDownloadEnabled;
  private final boolean nextgenSearchUIEnabled;
  private final boolean appearancePickerEnabled;

  protected ServerData(Builder builder) {
    this.serverEnvironment = builder.serverEnvironment;
    this.serverStatus = builder.serverStatus;
    this.intercomAppId = builder.intercomAppId;
    this.shouldEnableBugFiling = builder.shouldEnableBugFiling;
    this.shouldEnableRSOD = builder.shouldEnableRSOD;
    this.supportEmailTo = builder.supportEmailTo;
    this.supportEmailSubjectForJobs = builder.supportEmailSubjectForJobs;
    this.outsideCommunicationDisabled = builder.outsideCommunicationDisabled;
    this.subhourAccelerationPoliciesEnabled = builder.subhourAccelerationPoliciesEnabled;
    this.lowerProvisioningSettingsEnabled = builder.lowerProvisioningSettingsEnabled;
    this.allowFileUploads = builder.allowFileUploads;
    this.allowSpaceManagement = builder.allowSpaceManagement;
    this.tdsMimeType = builder.tdsMimeType;
    this.whiteLabelUrl = builder.whiteLabelUrl;
    this.clusterId = builder.clusterId;
    this.edition = builder.edition;
    this.analyzeTools = builder.analyzeTools;
    this.crossSourceDisabled = builder.crossSourceDisabled;
    this.queryBundleUsersEnabled = builder.queryBundleUsersEnabled;
    this.downloadRecordsLimit = builder.downloadRecordsLimit;
    this.showMetadataValidityCheckbox = builder.showMetadataValidityCheckbox;
    this.showNewJobsPage = builder.showNewJobsPage;
    this.showOldReflectionsListing = builder.showOldReflectionsListing;
    this.allowAutoComplete = builder.allowAutoComplete;
    this.allowDownload = builder.allowDownload;
    this.allowFormatting = builder.allowFormatting;
    this.useNewDatasetNavigation = builder.useNewDatasetNavigation;
    this.asyncDownloadEnabled = builder.asyncDownloadEnabled;
    this.nextgenSearchUIEnabled = builder.nextgenSearchUIEnabled;
    this.appearancePickerEnabled = builder.appearancePickerEnabled;
  }

  public String getServerEnvironment() {
    return serverEnvironment;
  }

  public String getServerStatus() {
    return serverStatus;
  }

  public String getIntercomAppId() {
    return intercomAppId;
  }

  public boolean isShouldEnableBugFiling() {
    return shouldEnableBugFiling;
  }

  public boolean isShouldEnableRSOD() {
    return shouldEnableRSOD;
  }

  public String getSupportEmailTo() {
    return supportEmailTo;
  }

  public String getSupportEmailSubjectForJobs() {
    return supportEmailSubjectForJobs;
  }

  public boolean isOutsideCommunicationDisabled() {
    return outsideCommunicationDisabled;
  }

  public boolean isSubhourAccelerationPoliciesEnabled() {
    return subhourAccelerationPoliciesEnabled;
  }

  public boolean isLowerProvisioningSettingsEnabled() {
    return lowerProvisioningSettingsEnabled;
  }

  public boolean isAllowFileUploads() {
    return allowFileUploads;
  }

  public boolean isAllowSpaceManagement() {
    return allowSpaceManagement;
  }

  public String getTdsMimeType() {
    return tdsMimeType;
  }

  public String getWhiteLabelUrl() {
    return whiteLabelUrl;
  }

  public String getClusterId() {
    return clusterId;
  }

  public String getEdition() {
    return edition;
  }

  public AnalyzeTools getAnalyzeTools() {
    return analyzeTools;
  }

  public boolean getQueryBundleUsersEnabled() {
    return queryBundleUsersEnabled;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Builder builder) {
    return new Builder(builder);
  }

  public boolean isCrossSourceDisabled() {
    return crossSourceDisabled;
  }

  public long getDownloadRecordsLimit() {
    return downloadRecordsLimit;
  }

  public boolean isShowMetadataValidityCheckbox() {
    return showMetadataValidityCheckbox;
  }

  public boolean isShowNewJobsPage() {
    return showNewJobsPage;
  }

  public boolean isShowOldReflectionsListing() {
    return showOldReflectionsListing;
  }

  public boolean isAllowAutoComplete() {
    return allowAutoComplete;
  }

  public boolean isAllowDownload() {
    return allowDownload;
  }

  public boolean isAllowFormatting() {
    return allowFormatting;
  }

  public boolean isUseNewDatasetNavigation() {
    return useNewDatasetNavigation;
  }

  public boolean isAsyncDownloadEnabled() {
    return asyncDownloadEnabled;
  }

  public boolean isNextgenSearchUIEnabled() {
    return nextgenSearchUIEnabled;
  }

  public boolean isAppearancePickerEnabled() {
    return appearancePickerEnabled;
  }

  /** A builder for server data */
  public static class Builder {
    private String serverEnvironment;
    private String serverStatus;
    private String intercomAppId;
    private boolean shouldEnableBugFiling;
    private boolean shouldEnableRSOD;
    private String supportEmailTo;
    private String supportEmailSubjectForJobs;
    private boolean outsideCommunicationDisabled;
    private boolean subhourAccelerationPoliciesEnabled;
    private boolean lowerProvisioningSettingsEnabled;
    private boolean allowFileUploads;
    private boolean allowSpaceManagement;
    private String tdsMimeType;
    private String whiteLabelUrl;
    private String clusterId;
    private String edition;
    private AnalyzeTools analyzeTools;
    private boolean crossSourceDisabled;
    private boolean queryBundleUsersEnabled;
    private long downloadRecordsLimit;
    private boolean showMetadataValidityCheckbox;
    private boolean showNewJobsPage;
    private boolean showOldReflectionsListing;
    private boolean allowAutoComplete;
    private boolean allowDownload;
    private boolean allowFormatting;
    private boolean useNewDatasetNavigation;
    private boolean asyncDownloadEnabled;
    private boolean nextgenSearchUIEnabled;
    private boolean appearancePickerEnabled;

    protected Builder() {}

    protected Builder(Builder builder) {
      this.serverEnvironment = builder.serverEnvironment;
      this.serverStatus = builder.serverStatus;
      this.intercomAppId = builder.intercomAppId;
      this.shouldEnableBugFiling = builder.shouldEnableBugFiling;
      this.shouldEnableRSOD = builder.shouldEnableRSOD;
      this.supportEmailTo = builder.supportEmailTo;
      this.supportEmailSubjectForJobs = builder.supportEmailSubjectForJobs;
      this.outsideCommunicationDisabled = builder.outsideCommunicationDisabled;
      this.subhourAccelerationPoliciesEnabled = builder.subhourAccelerationPoliciesEnabled;
      this.lowerProvisioningSettingsEnabled = builder.lowerProvisioningSettingsEnabled;
      this.allowFileUploads = builder.allowFileUploads;
      this.allowSpaceManagement = builder.allowSpaceManagement;
      this.tdsMimeType = builder.tdsMimeType;
      this.whiteLabelUrl = builder.whiteLabelUrl;
      this.clusterId = builder.clusterId;
      this.edition = builder.edition;
      this.analyzeTools = builder.analyzeTools;
      this.crossSourceDisabled = builder.crossSourceDisabled;
      this.queryBundleUsersEnabled = builder.queryBundleUsersEnabled;
      this.downloadRecordsLimit = builder.downloadRecordsLimit;
      this.showMetadataValidityCheckbox = builder.showMetadataValidityCheckbox;
      this.showNewJobsPage = builder.showNewJobsPage;
      this.showOldReflectionsListing = builder.showOldReflectionsListing;
      this.allowAutoComplete = builder.allowAutoComplete;
      this.allowDownload = builder.allowDownload;
      this.allowFormatting = builder.allowFormatting;
      this.useNewDatasetNavigation = builder.useNewDatasetNavigation;
      this.asyncDownloadEnabled = builder.asyncDownloadEnabled;
      this.nextgenSearchUIEnabled = builder.nextgenSearchUIEnabled;
      this.appearancePickerEnabled = builder.appearancePickerEnabled;
    }

    public Builder setServerEnvironment(String serverEnvironment) {
      this.serverEnvironment = serverEnvironment;
      return this;
    }

    public Builder setServerStatus(String serverStatus) {
      this.serverStatus = serverStatus;
      return this;
    }

    public Builder setIntercomAppId(String intercomAppId) {
      this.intercomAppId = intercomAppId;
      return this;
    }

    public Builder setShouldEnableBugFiling(boolean shouldEnableBugFiling) {
      this.shouldEnableBugFiling = shouldEnableBugFiling;
      return this;
    }

    public Builder setShouldEnableRSOD(boolean shouldEnableRSOD) {
      this.shouldEnableRSOD = shouldEnableRSOD;
      return this;
    }

    public Builder setSupportEmailTo(String supportEmailTo) {
      this.supportEmailTo = supportEmailTo;
      return this;
    }

    public Builder setSupportEmailSubjectForJobs(String supportEmailSubjectForJobs) {
      this.supportEmailSubjectForJobs = supportEmailSubjectForJobs;
      return this;
    }

    public Builder setOutsideCommunicationDisabled(boolean outsideCommunicationDisabled) {
      this.outsideCommunicationDisabled = outsideCommunicationDisabled;
      return this;
    }

    public Builder setSubhourAccelerationPoliciesEnabled(
        boolean subhourAccelerationPoliciesEnabled) {
      this.subhourAccelerationPoliciesEnabled = subhourAccelerationPoliciesEnabled;
      return this;
    }

    public Builder setLowerProvisioningSettingsEnabled(boolean lowerProvisioningSettingsEnabled) {
      this.lowerProvisioningSettingsEnabled = lowerProvisioningSettingsEnabled;
      return this;
    }

    public Builder setAllowFileUploads(boolean allowFileUploads) {
      this.allowFileUploads = allowFileUploads;
      return this;
    }

    public Builder setAllowSpaceManagement(boolean allowSpaceManagement) {
      this.allowSpaceManagement = allowSpaceManagement;
      return this;
    }

    public Builder setTdsMimeType(String tdsMimeType) {
      this.tdsMimeType = tdsMimeType;
      return this;
    }

    public Builder setWhiteLabelUrl(String whiteLabelUrl) {
      this.whiteLabelUrl = whiteLabelUrl;
      return this;
    }

    public Builder setClusterId(String clusterId) {
      this.clusterId = clusterId;
      return this;
    }

    public Builder setEdition(String edition) {
      this.edition = edition;
      return this;
    }

    public Builder setAnalyzeTools(AnalyzeTools analyzeTools) {
      this.analyzeTools = analyzeTools;
      return this;
    }

    public Builder setCrossSourceDisabled(boolean crossSourceDisabled) {
      this.crossSourceDisabled = crossSourceDisabled;
      return this;
    }

    public Builder setQueryBundleUsersEnabled(boolean queryBundleUsersEnabled) {
      this.queryBundleUsersEnabled = queryBundleUsersEnabled;
      return this;
    }

    public Builder setDownloadRecordsLimit(final long downloadRecordsLimit) {
      this.downloadRecordsLimit = downloadRecordsLimit;
      return this;
    }

    public Builder setShowMetadataValidityCheckbox(boolean showMetadataValidityCheckbox) {
      this.showMetadataValidityCheckbox = showMetadataValidityCheckbox;
      return this;
    }

    public Builder setShowNewJobsPage(boolean showNewJobsPage) {
      this.showNewJobsPage = showNewJobsPage;
      return this;
    }

    public Builder setShowOldReflectionsListing(boolean showOldReflectionsListing) {
      this.showOldReflectionsListing = showOldReflectionsListing;
      return this;
    }

    public Builder setAllowAutoComplete(boolean allowAutoComplete) {
      this.allowAutoComplete = allowAutoComplete;
      return this;
    }

    public Builder setAllowDownload(boolean allowDownload) {
      this.allowDownload = allowDownload;
      return this;
    }

    public Builder setAllowFormatting(boolean allowFormatting) {
      this.allowFormatting = allowFormatting;
      return this;
    }

    public Builder setUseNewDatasetNavigation(boolean useNewDatasetNavigation) {
      this.useNewDatasetNavigation = useNewDatasetNavigation;
      return this;
    }

    public Builder setAsyncDownloadEnabled(boolean asyncDownloadEnabled) {
      this.asyncDownloadEnabled = asyncDownloadEnabled;
      return this;
    }

    public Builder setNextgenSearchUIEnabled(boolean nextgenSearchUIEnabled) {
      this.nextgenSearchUIEnabled = nextgenSearchUIEnabled;
      return this;
    }

    public Builder setAppearancePickerEnabled(boolean appearancePickerEnabled) {
      this.appearancePickerEnabled = appearancePickerEnabled;
      return this;
    }

    public ServerData build() {
      return new ServerData(this);
    }
  }
}
