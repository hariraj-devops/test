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

import static com.dremio.services.nessie.grpc.GrpcExceptionMapper.handle;
import static com.dremio.services.nessie.grpc.ProtoUtil.fromProto;
import static com.dremio.services.nessie.grpc.ProtoUtil.toProto;

import com.dremio.services.nessie.grpc.api.TreeServiceGrpc.TreeServiceBlockingStub;
import org.projectnessie.client.api.TransplantCommitsBuilder;
import org.projectnessie.client.builder.BaseTransplantCommitsBuilder;
import org.projectnessie.error.NessieConflictException;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.MergeResponse;

final class GrpcTransplantCommits extends BaseTransplantCommitsBuilder {

  private final TreeServiceBlockingStub stub;

  public GrpcTransplantCommits(TreeServiceBlockingStub stub) {
    this.stub = stub;
  }

  @Override
  public TransplantCommitsBuilder commitMeta(CommitMeta commitMeta) {
    throw new UnsupportedOperationException(
        "Setting CommitMeta overrides is not supported for transplants.");
  }

  @Override
  public MergeResponse transplant() throws NessieNotFoundException, NessieConflictException {
    return handle(
        () ->
            fromProto(
                stub.transplantCommitsIntoBranch(
                    toProto(
                        branchName,
                        hash,
                        message,
                        fromRefName,
                        hashesToTransplant,
                        keepIndividualCommits,
                        dryRun,
                        returnConflictAsResult,
                        fetchAdditionalInfo,
                        defaultMergeMode,
                        mergeModes == null ? null : mergeModes.values()))));
  }
}
