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
package com.dremio.service.embedded.catalog;

import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.service.Service;
import com.dremio.services.nessie.grpc.api.ContentServiceGrpc;
import com.dremio.services.nessie.grpc.api.TreeServiceGrpc;
import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import io.grpc.BindableService;
import java.util.List;
import java.util.function.Supplier;
import javax.inject.Provider;

/**
 * Runs a sub set of the Nessie API that is required for performing atomic swaps of Iceberg metadata
 * pointers for embedded Iceberg tables use cases (infinite splits, reflections).
 */
public class EmbeddedMetadataPointerService implements Service {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(EmbeddedMetadataPointerService.class);

  private final TreeServiceGrpc.TreeServiceImplBase treeService;
  private final ContentServiceGrpc.ContentServiceImplBase contentService;

  public EmbeddedMetadataPointerService(Provider<KVStoreProvider> kvStoreProvider) {
    Supplier<EmbeddedUnversionedStore> unversionedStore =
        Suppliers.memoize(() -> new EmbeddedUnversionedStore(kvStoreProvider));

    // Note: This TreeService used to be backed by "old Nessie data model", which did not support
    // paging over entries, references and diffs. The new, simple implementation of the backing
    // store does not support pagination either (for the lack of need).
    // Note: Accessing the commit log is not supported at all now (due to lack of use cases).
    this.treeService = new EmbeddedTreeService(unversionedStore);
    this.contentService = new EmbeddedContentService(unversionedStore);
  }

  public List<BindableService> getGrpcServices() {
    return Lists.newArrayList(treeService, contentService);
  }

  @Override
  public void start() throws Exception {
    logger.info("Started Nessie gRPC Services.");
  }

  @Override
  public void close() throws Exception {
    logger.info("Stopping Nessie gRPC Service: Nothing to do");
  }
}
