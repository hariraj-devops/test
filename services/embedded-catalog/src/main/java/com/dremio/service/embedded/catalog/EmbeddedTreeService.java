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

import static com.dremio.services.nessie.grpc.GrpcExceptionMapper.handle;
import static com.dremio.services.nessie.grpc.ProtoUtil.toProto;

import com.dremio.services.nessie.grpc.api.Branch;
import com.dremio.services.nessie.grpc.api.CommitRequest;
import com.dremio.services.nessie.grpc.api.CommitResponse;
import com.dremio.services.nessie.grpc.api.ContentType;
import com.dremio.services.nessie.grpc.api.Empty;
import com.dremio.services.nessie.grpc.api.EntriesRequest;
import com.dremio.services.nessie.grpc.api.EntriesResponse;
import com.dremio.services.nessie.grpc.api.Entry;
import com.dremio.services.nessie.grpc.api.GetAllReferencesRequest;
import com.dremio.services.nessie.grpc.api.GetAllReferencesResponse;
import com.dremio.services.nessie.grpc.api.GetReferenceByNameRequest;
import com.dremio.services.nessie.grpc.api.IcebergTable;
import com.dremio.services.nessie.grpc.api.Namespace;
import com.dremio.services.nessie.grpc.api.Reference;
import com.dremio.services.nessie.grpc.api.TreeServiceGrpc;
import com.google.common.base.Preconditions;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Supplier;
import org.projectnessie.error.NessieReferenceNotFoundException;

class EmbeddedTreeService extends TreeServiceGrpc.TreeServiceImplBase {
  private static final String NO_ANCESTOR_HASH = "11223344556677889900";
  private static final String DEFAULT_BRANCH_NAME = "main";
  private static final Branch MAIN_BRANCH =
      Branch.newBuilder().setName(DEFAULT_BRANCH_NAME).setHash(NO_ANCESTOR_HASH).build();
  static final Reference MAIN = Reference.newBuilder().setBranch(MAIN_BRANCH).build();

  private final Supplier<EmbeddedUnversionedStore> store;

  EmbeddedTreeService(Supplier<EmbeddedUnversionedStore> store) {
    this.store = store;
  }

  @Override
  public void getDefaultBranch(Empty request, StreamObserver<Reference> observer) {
    handle(() -> MAIN, observer);
  }

  @Override
  public void getAllReferences(
      GetAllReferencesRequest request, StreamObserver<GetAllReferencesResponse> observer) {
    handle(() -> GetAllReferencesResponse.newBuilder().addReference(MAIN).build(), observer);
  }

  @Override
  public void getReferenceByName(
      GetReferenceByNameRequest request, StreamObserver<Reference> observer) {
    handle(
        () -> {
          if (DEFAULT_BRANCH_NAME.equals(request.getNamedRef())) {
            return MAIN;
          }

          throw new NessieReferenceNotFoundException(
              "Reference not found: " + request.getNamedRef());
        },
        observer);
  }

  @Override
  public void commitMultipleOperations(
      CommitRequest request, StreamObserver<CommitResponse> observer) {
    handle(
        () -> {
          if (!DEFAULT_BRANCH_NAME.equals(request.getBranch())) {
            throw new NessieReferenceNotFoundException(
                "Invalid branch name: " + request.getBranch());
          }

          store.get().commit(request.getCommitOperations().getOperationsList());

          return CommitResponse.newBuilder().setBranch(MAIN_BRANCH).build();
        },
        observer);
  }

  @Override
  public void getEntries(EntriesRequest request, StreamObserver<EntriesResponse> observer) {
    handle(
        () -> {
          Preconditions.checkArgument(
              !request.hasFilter(), "getEntries with filter is not supported");
          Preconditions.checkArgument(
              !request.hasPageToken(), "getEntries with page token is not supported");
          Preconditions.checkArgument(
              !request.hasNamespaceDepth(), "getEntries with namespace depth is not supported");
          Preconditions.checkArgument(
              !request.hasMinKey(), "getEntries with min key is not supported");
          Preconditions.checkArgument(
              !request.hasMaxKey(), "getEntries with max key is not supported");
          Preconditions.checkArgument(
              !request.hasPrefixKey(), "getEntries with key prefix is not supported");
          EntriesResponse.Builder response = EntriesResponse.newBuilder();

          if (request.getKeysList().isEmpty()) {
            int maxRecords = request.hasMaxRecords() ? request.getMaxRecords() : Integer.MAX_VALUE;
            store.get().getEntries(maxRecords).forEach((k, v) -> addEntry(response, k, v));
          } else {
            request
                .getKeysList()
                .forEach(
                    k -> {
                      EmbeddedContentKey key = EmbeddedContentKey.of(k.getElementsList());
                      EmbeddedContent content = store.get().getValue(key);
                      addEntry(response, key, content);
                    });
          }

          return response.build();
        },
        observer);
  }

  private void addEntry(
      EntriesResponse.Builder response, EmbeddedContentKey key, EmbeddedContent content) {
    if (content != null) {
      response.addEntries(
          Entry.newBuilder()
              .setContentKey(toProto(key))
              .setType(ContentType.valueOf(content.type().name()))
              .setContentId(content.id())
              .setContent(toProto(content))
              .build());
    }
  }

  static com.dremio.services.nessie.grpc.api.ContentKey toProto(EmbeddedContentKey key) {
    return com.dremio.services.nessie.grpc.api.ContentKey.newBuilder()
        .addAllElements(key.getElements())
        .build();
  }

  static com.dremio.services.nessie.grpc.api.Content toProto(EmbeddedContent content) {
    com.dremio.services.nessie.grpc.api.Content.Builder builder =
        com.dremio.services.nessie.grpc.api.Content.newBuilder();
    switch (content.type()) {
      case NAMESPACE:
        return builder
            .setNamespace(
                Namespace.newBuilder()
                    .setId(content.id())
                    .addAllElements(Objects.requireNonNull(content.key()).getElements())
                    .build())
            .build();
      case ICEBERG_TABLE:
        return builder
            .setIceberg(
                IcebergTable.newBuilder()
                    .setId(content.id())
                    .setMetadataLocation(content.location())
                    .build())
            .build();
      default:
        throw new IllegalArgumentException("Unsupported content type: " + content.type());
    }
  }
}
