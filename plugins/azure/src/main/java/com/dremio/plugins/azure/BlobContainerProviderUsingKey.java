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

import com.microsoft.azure.storage.blob.CloudBlobClient;
import java.net.URI;

/**
 * BlobContainerProvider that manages lifecycle of the client to Microsoft Azure Blob service. The
 * client is refreshed every time on every lookup.
 */
public class BlobContainerProviderUsingKey extends BaseBlobContainerProvider {

  private final AzureStorageCredentials credentials;

  private volatile CloudBlobClient cloudBlobClient;

  public BlobContainerProviderUsingKey(
      AzureStorageFileSystem parent,
      URI connection,
      String account,
      String[] containers,
      AzureStorageCredentials credentials) {
    super(parent, connection, account, containers);
    this.credentials = credentials;
  }

  @Override
  protected CloudBlobClient getCloudBlobClient() {
    cloudBlobClient =
        new CloudBlobClient(getConnection(), credentials.exportToStorageCredentials());
    return cloudBlobClient;
  }
}
