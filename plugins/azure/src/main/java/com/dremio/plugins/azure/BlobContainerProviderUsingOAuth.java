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

import com.microsoft.azure.storage.StorageCredentialsToken;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BlobContainerProvider that manages lifecycle of the client to Microsoft Azure Blob service. The
 * client is refreshed every time the AAD token expires.
 */
public class BlobContainerProviderUsingOAuth extends BaseBlobContainerProvider {
  private static final Logger logger =
      LoggerFactory.getLogger(BlobContainerProviderUsingOAuth.class);

  private final ClientCredentialsBasedTokenProvider tokenProvider;

  private volatile CloudBlobClient cloudBlobClient;

  public BlobContainerProviderUsingOAuth(
      AzureStorageFileSystem parent,
      URI connection,
      String account,
      String[] containers,
      ClientCredentialsBasedTokenProvider tokenProvider) {
    super(parent, connection, account, containers);
    this.tokenProvider = tokenProvider;
  }

  @Override
  protected CloudBlobClient getCloudBlobClient() {
    if (tokenProvider.checkAndUpdateToken()) {
      logger.debug("Storage V1 - Token is expired or is about to expire, client has been updated");
      cloudBlobClient =
          new CloudBlobClient(
              getConnection(),
              new StorageCredentialsToken(getAccount(), tokenProvider.getAccessTokenUnchecked()));
    }

    return cloudBlobClient;
  }
}
