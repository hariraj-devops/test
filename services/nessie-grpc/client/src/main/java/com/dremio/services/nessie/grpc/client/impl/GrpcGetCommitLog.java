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
import static com.dremio.services.nessie.grpc.ProtoUtil.toProto;

import com.dremio.services.nessie.grpc.api.TreeServiceGrpc.TreeServiceBlockingStub;
import org.projectnessie.api.v1.params.CommitLogParams;
import org.projectnessie.client.builder.BaseGetCommitLogBuilder;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.LogResponse;

final class GrpcGetCommitLog extends BaseGetCommitLogBuilder<CommitLogParams> {

  private final TreeServiceBlockingStub stub;

  public GrpcGetCommitLog(TreeServiceBlockingStub stub) {
    super(CommitLogParams::forNextPage);
    this.stub = stub;
  }

  @Override
  protected CommitLogParams params() {
    return CommitLogParams.builder()
        .filter(filter)
        .maxRecords(maxRecords)
        .fetchOption(fetchOption)
        .startHash(untilHash)
        .endHash(hashOnRef)
        .build();
  }

  @Override
  protected LogResponse get(CommitLogParams p) throws NessieNotFoundException {
    return handleNessieNotFoundEx(() -> fromProto(stub.getCommitLog(toProto(refName, p))));
  }
}
