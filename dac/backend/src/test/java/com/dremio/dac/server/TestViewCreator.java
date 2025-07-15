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
package com.dremio.dac.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dremio.dac.model.folder.FolderModel;
import com.dremio.service.job.JobSummary;
import com.dremio.service.job.JobSummaryRequest;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.JobsProtoUtil;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.SqlQuery;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** */
public class TestViewCreator extends BaseTestServer {

  @Before
  public void setup() throws Exception {
    clearAllDataExceptUser();
  }

  @Test
  public void createQueryDrop() throws Exception {
    JobsService jobsService = l(JobsService.class);

    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(
                Entity.json(new com.dremio.dac.api.Space(null, "mySpace", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/mySpace/folder/"))
            .buildPost(Entity.json("{\"name\": \"myFolder\"}")),
        FolderModel.class);

    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(
                new SqlQuery(
                    "create view mySpace.myFolder.myView as select * from cp.nation_ctas.t1.\"0_0_0.parquet\"",
                    DEFAULT_USERNAME))
            .setQueryType(QueryType.UI_RUN)
            .build());

    final JobId job2Id =
        submitJobAndWaitUntilCompletion(
            JobRequest.newBuilder()
                .setSqlQuery(
                    new SqlQuery("select * from mySpace.myFolder.myView", DEFAULT_USERNAME))
                .setQueryType(QueryType.UI_RUN)
                .build());
    final JobSummary jobSummary =
        jobsService.getJobSummary(
            JobSummaryRequest.newBuilder().setJobId(JobsProtoUtil.toBuf(job2Id)).build());
    assertEquals(25, jobSummary.getOutputRecords());

    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(new SqlQuery("drop view mySpace.myFolder.myView", DEFAULT_USERNAME))
            .setQueryType(QueryType.UI_RUN)
            .build());

    try {
      submitJobAndWaitUntilCompletion(
          JobRequest.newBuilder()
              .setSqlQuery(new SqlQuery("select * from mySpace.myFolder.myView", DEFAULT_USERNAME))
              .setQueryType(QueryType.UI_RUN)
              .build());
      Assert.fail("query should have failed");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("not found"));
    }
  }

  @Test
  public void createQueryDDLSql() {
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(
                Entity.json(new com.dremio.dac.api.Space(null, "mySpace", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/mySpace/folder/"))
            .buildPost(Entity.json("{\"name\": \"myFolder\"}")),
        FolderModel.class);

    SqlQuery ctas = getQueryFromSQL("CREATE TABLE \"$scratch\".\"ctas\" AS select 1");
    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder().setSqlQuery(ctas).setQueryType(QueryType.UI_RUN).build());
  }
}
