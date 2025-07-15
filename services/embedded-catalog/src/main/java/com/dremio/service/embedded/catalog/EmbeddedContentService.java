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

import static com.dremio.service.embedded.catalog.EmbeddedTreeService.MAIN;
import static com.dremio.service.embedded.catalog.EmbeddedTreeService.toProto;
import static com.dremio.services.nessie.grpc.GrpcExceptionMapper.handle;

import com.dremio.services.nessie.grpc.api.ContentServiceGrpc;
import com.dremio.services.nessie.grpc.api.ContentWithKey;
import com.dremio.services.nessie.grpc.api.MultipleContentsRequest;
import com.dremio.services.nessie.grpc.api.MultipleContentsResponse;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;

class EmbeddedContentService extends ContentServiceGrpc.ContentServiceImplBase {
  private final Supplier<EmbeddedUnversionedStore> store;

  EmbeddedContentService(Supplier<EmbeddedUnversionedStore> store) {
    this.store = store;
  }

  @Override
  public void getMultipleContents(
      MultipleContentsRequest request, StreamObserver<MultipleContentsResponse> observer) {
    handle(
        () -> {
          MultipleContentsResponse.Builder response = MultipleContentsResponse.newBuilder();
          request
              .getRequestedKeysList()
              .forEach(
                  k -> {
                    EmbeddedContentKey key = EmbeddedContentKey.of(k.getElementsList());
                    EmbeddedContent content = store.get().getValue(key);
                    if (content != null) {
                      response.addContentWithKey(
                          ContentWithKey.newBuilder()
                              .setContentKey(k)
                              .setContent(toProto(content))
                              .build());
                    }
                  });
          response.setEffectiveReference(MAIN);
          return response.build();
        },
        observer);
  }
}
