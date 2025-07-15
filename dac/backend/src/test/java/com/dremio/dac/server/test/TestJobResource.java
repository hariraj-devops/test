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
package com.dremio.dac.server.test;

import com.dremio.dac.annotations.RestResourceUsedForTesting;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.annotations.TemporaryAccess;
import com.dremio.dac.explore.model.DownloadFormat;
import com.dremio.dac.resource.JobResource;
import com.dremio.dac.server.BufferAllocatorFactory;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.JobResourceNotFoundException;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.SessionId;
import com.dremio.service.jobs.JobNotFoundException;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.NamespaceService;
import java.io.IOException;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

/** Job test resource */
@RestResourceUsedForTesting
@Secured
@RolesAllowed({"admin", "user"})
@Path("/testjob/{jobId}")
public class TestJobResource extends JobResource {
  private final JobId jobId;

  @Inject
  public TestJobResource(
      JobsService jobsService,
      DatasetVersionMutator datasetService,
      @Context SecurityContext securityContext,
      NamespaceService namespace,
      BufferAllocatorFactory allocatorFactory,
      @PathParam("jobId") JobId jobId,
      @PathParam("sessionId") SessionId sessionId) {
    super(
        jobsService,
        datasetService,
        securityContext,
        namespace,
        allocatorFactory,
        jobId,
        sessionId);
    this.jobId = jobId;
  }

  /**
   * Export data for job id as a file
   *
   * @param downloadFormat - a format of output file. Also defines a file extension
   * @return
   * @throws IOException
   * @throws JobResourceNotFoundException
   * @throws JobNotFoundException
   */
  @Override
  @GET
  @Path("download")
  @Consumes(MediaType.APPLICATION_JSON)
  @TemporaryAccess
  public Response download(@QueryParam("downloadFormat") DownloadFormat downloadFormat)
      throws JobResourceNotFoundException, JobNotFoundException {
    return doDownload(jobId, downloadFormat);
  }

  /**
   * No-op method for testing non temporary access method
   *
   * @return ok
   */
  @GET
  @Path("test")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response testNonTemporaryAccessMethod() {
    return Response.ok().build();
  }

  @Override
  protected long getDelay() {
    return 500L;
  }
}
