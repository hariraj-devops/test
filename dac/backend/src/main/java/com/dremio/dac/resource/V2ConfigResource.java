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
package com.dremio.dac.resource;

import com.dremio.services.nessie.proxy.ProxyV2ConfigResource;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.error.ErrorCode;
import org.projectnessie.error.ImmutableNessieError;
import org.projectnessie.error.NessieForbiddenException;
import org.projectnessie.model.UpdateRepositoryConfigRequest;
import org.projectnessie.model.UpdateRepositoryConfigResponse;

/**
 * Nessie-specific extension of {@link ProxyV2ConfigResource}. Disables certain API calls that are
 * not needed in the NaaS proxy.
 */
public class V2ConfigResource extends ProxyV2ConfigResource {

  public V2ConfigResource(NessieApiV2 api) {
    super(api);
  }

  @Override
  public UpdateRepositoryConfigResponse updateRepositoryConfig(
      UpdateRepositoryConfigRequest request) {
    throw new NessieForbiddenException(
        ImmutableNessieError.builder()
            .errorCode(ErrorCode.FORBIDDEN)
            .status(ErrorCode.FORBIDDEN.httpStatus())
            .message("updateRepositoryConfig is not supported.")
            .reason("Forbidden")
            .build());
  }
}
