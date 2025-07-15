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
package com.dremio.dac.cluster;

import static com.google.common.base.Suppliers.memoize;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.support.SupportService;
import java.util.function.Supplier;
import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

@APIResource
@PermitAll
@Path("/cluster")
@Produces(APPLICATION_JSON)
public class ClusterIdResource {
  private final Supplier<ClusterIdResponse> idSupplier;

  @Inject
  public ClusterIdResource(SupportService supportService) {
    idSupplier = memoize(() -> new ClusterIdResponse(supportService.getClusterId().getIdentity()));
  }

  @GET
  @Path("/id")
  public ClusterIdResponse id() {
    return idSupplier.get();
  }
}
