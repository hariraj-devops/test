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
package com.dremio.plugins.azure;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.AzureAuthenticationType;
import com.dremio.exec.catalog.conf.AzureStorageConfProperties;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.store.dfs.CacheProperties;
import com.dremio.exec.store.dfs.FileSystemConf;
import com.dremio.exec.store.dfs.SchemaMutability;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.protostuff.Tag;
import java.util.List;
import javax.inject.Provider;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/** Abstract class to hold Azure Storage (including datalake v2) plugin conf */
public abstract class AbstractAzureStorageConf
    extends FileSystemConf<AbstractAzureStorageConf, AzureStoragePlugin> {

  /**
   * @return secret type for SharedAccess authentication
   */
  @Deprecated
  public abstract SharedAccessSecretType getSharedAccessSecretType();

  /**
   * @return Azure Key Vault/VaultURI for SharedAccess authentication
   */
  @Deprecated
  public abstract String getAccessKeyUri();

  /**
   * @return secret type for Azure Active Directory / Microsoft Entra ID authentication
   */
  @Deprecated
  public abstract AzureActiveDirectorySecretType getAzureADSecretType();

  /**
   * @return Azure Key Vault/VaultURI for Azure Active Directory / Microsoft Entra ID authentication
   */
  @Deprecated
  public abstract String getClientSecretUri();

  public static final List<String> KEY_AUTH_PROPS =
      ImmutableList.of(
          AzureStorageConfProperties.ACCOUNT,
          AzureStorageFileSystem.SECURE,
          AzureStorageFileSystem.CONTAINER_LIST,
          AzureStorageFileSystem.KEY);

  public static final List<String> AZURE_AD_PROPS =
      ImmutableList.of(
          AzureStorageConfProperties.ACCOUNT,
          AzureStorageFileSystem.SECURE,
          AzureStorageFileSystem.CONTAINER_LIST,
          AzureStorageFileSystem.CLIENT_ID,
          AzureStorageFileSystem.CLIENT_SECRET,
          AzureStorageFileSystem.TOKEN_ENDPOINT);

  public static final List<String> SAS_SIGNATURE_PROPS =
      ImmutableList.of(AzureStorageFileSystem.SAS_SIGNATURE, AzureStorageFileSystem.CONTAINER_LIST);

  /** Type of Storage */
  public enum AccountKind {
    @Tag(1)
    @DisplayMetadata(label = "WASBS (Legacy)")
    STORAGE_V1,

    @Tag(2)
    @DisplayMetadata(label = "ABFSS (Recommended)")
    STORAGE_V2;

    @JsonIgnore
    public Prototype getPrototype(boolean enableSSL) {
      if (this == AccountKind.STORAGE_V1) {
        return enableSSL ? Prototype.WASBS : Prototype.WASB;
      } else {
        return enableSSL ? Prototype.ABFSS : Prototype.ABFS;
      }
    }
  }

  @Tag(1)
  @DisplayMetadata(label = "Storage Connection Protocol (Driver)")
  public AccountKind accountKind = AccountKind.STORAGE_V2;

  @Tag(2)
  @DisplayMetadata(label = "Account Name")
  public String accountName;

  @Tag(3)
  @Secret
  @DisplayMetadata(label = "Secret Store")
  public SecretRef accessKey;

  @Tag(4)
  @DisplayMetadata(label = "Root Path")
  public String rootPath = "/";

  @Tag(5)
  @DisplayMetadata(label = "Advanced Properties")
  public List<Property> propertyList;

  @Tag(6)
  @DisplayMetadata(label = "Blob Containers & Filesystem Allowlist")
  public List<String> containers;

  @Tag(7)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Encrypt connection")
  public boolean enableSSL = true;

  @Tag(8)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable exports into the source (CTAS and DROP)")
  @JsonIgnore
  public boolean allowCreateDrop = false;

  @Tag(9)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable asynchronous access when possible")
  public boolean enableAsync = true;

  @Tag(10)
  @DisplayMetadata(label = "Application ID")
  public String clientId;

  @Tag(11)
  @DisplayMetadata(label = "OAuth 2.0 Token Endpoint")
  public String tokenEndpoint;

  @Tag(12)
  @Secret
  @DisplayMetadata(label = "Application Secret Store")
  public SecretRef clientSecret;

  @Tag(13)
  @DisplayMetadata(label = "Authentication Type")
  public AzureAuthenticationType credentialsType = AzureAuthenticationType.ACCESS_KEY;

  @Tag(14)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable local caching when possible")
  public boolean isCachingEnabled = true;

  @Tag(15)
  @NotMetadataImpacting
  @Min(value = 1, message = "Max percent of total available cache space must be between 1 and 100")
  @Max(
      value = 100,
      message = "Max percent of total available cache space must be between 1 and 100")
  @DisplayMetadata(label = "Max percent of total available cache space to use when possible")
  public int maxCacheSpacePct = 100;

  @Tag(16)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Default CTAS Format")
  public DefaultCtasFormatSelection defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;

  @Tag(17)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable partition column inference")
  public boolean isPartitionInferenceEnabled = false;

  @Override
  public AzureStoragePlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    Preconditions.checkNotNull(accountName, "Account name must be provided.");
    return new AzureStoragePlugin(this, pluginSabotContext, name, pluginIdProvider);
  }

  @Override
  public String getDefaultCtasFormat() {
    return defaultCtasFormat.getDefaultCtasFormat();
  }

  @Override
  public Path getPath() {
    return Path.of(rootPath);
  }

  @Override
  public boolean isImpersonationEnabled() {
    return false;
  }

  @Override
  public List<Property> getProperties() {
    return propertyList;
  }

  @Override
  public String getConnection() {
    return String.format(
        "%s:///", CloudFileSystemScheme.AZURE_STORAGE_FILE_SYSTEM_SCHEME.getScheme());
  }

  @Override
  public SchemaMutability getSchemaMutability() {
    return SchemaMutability.USER_TABLE;
  }

  @Override
  public boolean isPartitionInferenceEnabled() {
    return isPartitionInferenceEnabled;
  }

  @Override
  public boolean isAsyncEnabled() {
    return enableAsync;
  }

  @Override
  public CacheProperties getCacheProperties() {
    return new CacheProperties() {
      @Override
      public boolean isCachingEnabled(final OptionManager optionManager) {
        return isCachingEnabled;
      }

      @Override
      public int cacheMaxSpaceLimitPct() {
        return maxCacheSpacePct;
      }
    };
  }
}
