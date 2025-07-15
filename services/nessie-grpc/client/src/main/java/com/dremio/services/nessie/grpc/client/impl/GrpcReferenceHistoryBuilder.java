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
package com.dremio.services.nessie.grpc.client.impl;

import static com.dremio.services.nessie.grpc.GrpcExceptionMapper.handleNessieNotFoundEx;
import static com.dremio.services.nessie.grpc.ProtoUtil.fromProto;
import static com.dremio.services.nessie.grpc.ProtoUtil.toProtoReferenceHistoryRequest;

import com.dremio.services.nessie.grpc.api.TreeServiceGrpc.TreeServiceBlockingStub;
import org.jetbrains.annotations.Nullable;
import org.projectnessie.client.api.ReferenceHistoryBuilder;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.ReferenceHistoryResponse;

public class GrpcReferenceHistoryBuilder implements ReferenceHistoryBuilder {

  private final TreeServiceBlockingStub stub;
  private String refName;
  private Integer headCommitsToScan;

  public GrpcReferenceHistoryBuilder(TreeServiceBlockingStub stub) {
    this.stub = stub;
  }

  @Override
  public ReferenceHistoryBuilder refName(String refName) {
    this.refName = refName;
    return this;
  }

  @Override
  public ReferenceHistoryBuilder headCommitsToScan(@Nullable Integer headCommitsToScan) {
    this.headCommitsToScan = headCommitsToScan;
    return this;
  }

  @Override
  public ReferenceHistoryResponse get() throws NessieNotFoundException {
    return handleNessieNotFoundEx(
        () ->
            fromProto(
                stub.referenceHistory(toProtoReferenceHistoryRequest(refName, headCommitsToScan))));
  }
}
