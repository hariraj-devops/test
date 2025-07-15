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

import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.options.ReflectionUserOptions;
import com.dremio.dac.service.reflection.ReflectionServiceHelper;
import com.dremio.exec.server.options.ProjectOptionManager;
import com.dremio.options.OptionValue;
import com.dremio.service.reflection.ReflectionService;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/** API for setting low-level development options. Not meant to be a permanent API. */
@RestResource
@Secured
@RolesAllowed({"admin"})
@Path("/development_options")
public class DevelopmentOptionsResource {
  private ReflectionServiceHelper reflectionServiceHelper;
  private ProjectOptionManager projectOptionManager;
  private ReflectionService reflectionService;

  @Inject
  public DevelopmentOptionsResource(
      ReflectionService service,
      ReflectionServiceHelper reflectionServiceHelper,
      ProjectOptionManager projectOptionManager) {
    this.reflectionServiceHelper = reflectionServiceHelper;
    this.projectOptionManager = projectOptionManager;
    this.reflectionService = service;
  }

  @GET
  @Path("/acceleration/enabled")
  @Produces(MediaType.APPLICATION_JSON)
  public String isGlobalAccelerationEnabled() {
    return Boolean.toString(
        projectOptionManager.getOption(ReflectionUserOptions.REFLECTION_ENABLE_SUBSTITUTION));
  }

  @GET
  @Path("/acceleration/cacheinitialized")
  @Produces(MediaType.APPLICATION_JSON)
  public String isMaterializationCacheInitialized() {
    return Boolean.toString(reflectionService.getCacheViewerProvider().get().isInitialized());
  }

  @PUT
  @Path("/acceleration/enabled")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public String setAccelerationEnabled(/* Body */ String body) {
    boolean enabled = Boolean.valueOf(body);
    projectOptionManager.setOption(
        OptionValue.createBoolean(
            OptionValue.OptionType.SYSTEM,
            ReflectionUserOptions.REFLECTION_ENABLE_SUBSTITUTION.getOptionName(),
            enabled));
    return body;
  }

  @POST
  @Path("/acceleration/clearall")
  public void clearMaterializations() {
    reflectionServiceHelper.clearAllReflections();
  }

  @POST
  @Path("/acceleration/retryunavailable")
  public void retryGivenUp() {
    reflectionServiceHelper.retryUnavailableReflections();
  }

  @POST
  @Path("/acceleration/clean")
  public void clean() {
    reflectionService.clean();
  }
}
