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
package com.dremio.dac.api;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import com.dremio.context.SupportContext;
import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.service.catalog.CatalogServiceHelper;
import com.dremio.dac.service.errors.ConflictException;
import com.dremio.dac.service.errors.ReflectionNotFound;
import com.dremio.dac.service.reflection.ReflectionServiceHelper;
import com.dremio.service.reflection.ChangeCause;
import com.dremio.service.reflection.proto.ReflectionGoal;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

/** Reflection API resource. */
@APIResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/reflection")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class ReflectionResource {
  private final ReflectionServiceHelper reflectionServiceHelper;
  private final CatalogServiceHelper catalogServiceHelper;

  @Inject
  public ReflectionResource(
      ReflectionServiceHelper reflectionServiceHelper, CatalogServiceHelper catalogServiceHelper) {
    this.reflectionServiceHelper = reflectionServiceHelper;
    this.catalogServiceHelper = catalogServiceHelper;
  }

  @GET
  @Path("/{id}")
  public Reflection getReflection(@PathParam("id") String id) {
    final Optional<ReflectionGoal> goal = reflectionServiceHelper.getReflectionById(id);

    if (!goal.isPresent()) {
      throw new ReflectionNotFound(id);
    }

    return reflectionServiceHelper.newReflection(goal.get());
  }

  @POST
  public Reflection createReflection(Reflection reflection) throws ForbiddenException {
    return createReflectionHelper(reflection, reflectionServiceHelper);
  }

  public static Reflection createReflectionHelper(
      Reflection reflection, ReflectionServiceHelper reflectionServiceHelper)
      throws ForbiddenException {
    // TODO: handle exceptions
    if (SupportContext.isSupportUser()) {
      throw new ForbiddenException("Permission denied. A support user cannot create a reflection");
    }
    final ReflectionGoal newReflection =
        reflectionServiceHelper.createReflection(reflection.toReflectionGoal());

    return reflectionServiceHelper.newReflection(newReflection);
  }

  @PUT
  @Path("/{id}")
  public Reflection editReflection(@PathParam("id") String id, Reflection reflection) {
    try {
      if (SupportContext.isSupportUser()) {
        throw new ForbiddenException("Permission denied. A support user cannot edit a reflection");
      }
      // force ids to match
      reflection.setId(id);

      final ReflectionGoal reflectionGoal =
          reflectionServiceHelper.updateReflection(
              reflection.toReflectionGoal(), ChangeCause.REST_UPDATE_BY_USER_CAUSE);
      return reflectionServiceHelper.newReflection(reflectionGoal);
    } catch (ConcurrentModificationException e) {
      throw new ConflictException(
          "The reflection seems to have been modified by another flow. Please verify the reflection definition and retry.",
          e);
    }
  }

  @DELETE
  @Path("/{id}")
  public Response deleteReflection(@PathParam("id") String id) {
    if (SupportContext.isSupportUser()) {
      throw new ForbiddenException("Permission denied. A support user cannot delete a reflection");
    }
    reflectionServiceHelper.removeReflection(id, ChangeCause.REST_DROP_BY_USER_CAUSE);
    return Response.ok().build();
  }
}
