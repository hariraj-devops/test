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
package com.dremio.dac.explore;

import static com.dremio.dac.server.JobsServiceTestUtils.submitJobAndGetData;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.dremio.dac.api.Dataset;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetUI;
import com.dremio.dac.explore.model.DatasetUIWithHistory;
import com.dremio.dac.explore.model.DatasetVersionResourcePath;
import com.dremio.dac.explore.model.HistoryItem;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.explore.model.NewUntitledFromParentRequest;
import com.dremio.dac.explore.model.VersionContextReq;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.proto.model.dataset.SourceVersionReference;
import com.dremio.dac.proto.model.dataset.TransformUpdateSQL;
import com.dremio.dac.proto.model.dataset.VersionContext;
import com.dremio.dac.proto.model.dataset.VersionContextType;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.server.ApiErrorModel;
import com.dremio.dac.server.BaseTestServer;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.InvalidQueryException;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for DatasetVersionResource */
public class TestDatasetVersionResource extends BaseTestServer {
  @BeforeClass
  public static void init() throws Exception {
    BaseTestServer.init();

    // setup space
    NamespaceKey key = new NamespaceKey("dsvTest");
    SpaceConfig spaceConfig = new SpaceConfig();
    spaceConfig.setName("dsvTest");
    getNamespaceService().addOrUpdateSpace(key, spaceConfig);
  }

  @AfterClass
  public static void shutdown() throws Exception {
    // setup space
    NamespaceKey key = new NamespaceKey("dsvTest");
    SpaceConfig space = getNamespaceService().getSpace(key);
    getNamespaceService().deleteSpace(key, space.getTag());
  }

  @Test
  public void testNewUntitledApiWithReferences() throws Exception {
    Dataset newVDS = createVDS(Arrays.asList("dsvTest", "testVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();

    // set references payload
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(new NewUntitledFromParentRequest(references))),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // set empty references payload
    datasetVersion = DatasetVersion.newVersion();
    target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    references = new HashMap<>();
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(new NewUntitledFromParentRequest(references))),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);
  }

  @Test
  public void testPreviewApiWithReferences() throws Exception {
    // create a VDS in the space
    Dataset newVDS = createVDS(Arrays.asList("dsvTest", "previewVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(new NewUntitledFromParentRequest(references))),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // save the derivation a new VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.previewVDS2");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // modify the sql of the new VDS by doing a transform
    DatasetVersion datasetVersion2 = DatasetVersion.newVersion();
    String dsPath = String.join(".", dswh.getDataset().getFullPath());
    List<SourceVersionReference> sourceVersionReferenceList = new ArrayList<>();
    VersionContext versionContext1 = new VersionContext(VersionContextType.BRANCH, "branch");
    VersionContext versionContext2 = new VersionContext(VersionContextType.TAG, "tag");
    VersionContext versionContext3 =
        new VersionContext(VersionContextType.COMMIT, "d0628f078890fec234b98b873f9e1f3cd140988a");
    sourceVersionReferenceList.add(new SourceVersionReference("source1", versionContext1));
    sourceVersionReferenceList.add(new SourceVersionReference("source2", versionContext2));
    sourceVersionReferenceList.add(new SourceVersionReference("source3", versionContext3));

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transformAndPreview")
            .queryParam("newVersion", datasetVersion2);

    TransformUpdateSQL transformSql = new TransformUpdateSQL();
    transformSql.setSql("SELECT \"version\" FROM dsvTest.previewVDS");
    transformSql.setReferencesList(sourceVersionReferenceList);

    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // save the transform as a third VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(initialPreviewResponse.getDataset().getDatasetVersion().getVersion())
            .path("save")
            .queryParam("as", "dsvTest.previewVDS3");

    DatasetUIWithHistory dswh2 =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // preview the last history item
    HistoryItem historyItem = dswh.getHistory().getItems().get(0);
    String dsPath2 = String.join(".", dswh2.getDataset().getFullPath());

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath2)
            .path("version")
            .path(historyItem.getDatasetVersion().getVersion())
            .path("preview")
            .queryParam("view", "explore")
            .queryParam("tipVersion", dswh2.getDataset().getDatasetVersion());

    initialPreviewResponse =
        expectSuccess(getBuilder(target).buildGet(), new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);
  }

  @Test
  public void testAsyncTransformAndPreviewApiWithReferences() throws Exception {
    // create a VDS in the space
    Dataset newVDS =
        createVDS(Arrays.asList("dsvTest", "preview_VDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(new NewUntitledFromParentRequest(references))),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // save the derivation a new VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.preview_VDS2");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // modify the sql of the new VDS by doing a transform
    DatasetVersion datasetVersion2 = DatasetVersion.newVersion();
    String dsPath = String.join(".", dswh.getDataset().getFullPath());
    List<SourceVersionReference> sourceVersionReferenceList = new ArrayList<>();
    VersionContext versionContext1 = new VersionContext(VersionContextType.BRANCH, "branch");
    VersionContext versionContext2 = new VersionContext(VersionContextType.TAG, "tag");
    VersionContext versionContext3 =
        new VersionContext(VersionContextType.COMMIT, "d0628f078890fec234b98b873f9e1f3cd140988a");
    sourceVersionReferenceList.add(new SourceVersionReference("source1", versionContext1));
    sourceVersionReferenceList.add(new SourceVersionReference("source2", versionContext2));
    sourceVersionReferenceList.add(new SourceVersionReference("source3", versionContext3));

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transform_and_preview")
            .queryParam("newVersion", datasetVersion2);

    TransformUpdateSQL transformSql = new TransformUpdateSQL();
    transformSql.setSql("SELECT \"version\" FROM dsvTest.preview_VDS");
    transformSql.setReferencesList(sourceVersionReferenceList);

    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);
  }

  @Test
  public void testTransformAndRunApiWithReferences() throws Exception {
    Dataset newVDS =
        createVDS(Arrays.asList("dsvTest", "transformAndRunVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    expectSuccess(
        getBuilder(target).buildPost(Entity.json(null)),
        new GenericType<InitialPreviewResponse>() {});

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.transformAndRunVDS2");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});
    String dsPath = String.join(".", dswh.getDataset().getFullPath());

    // set references payload
    datasetVersion = DatasetVersion.newVersion();
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transformAndRun")
            .queryParam("newVersion", datasetVersion);
    List<SourceVersionReference> sourceVersionReferenceList = new ArrayList<>();
    VersionContext versionContext1 = new VersionContext(VersionContextType.BRANCH, "branch");
    VersionContext versionContext2 = new VersionContext(VersionContextType.TAG, "tag");
    VersionContext versionContext3 =
        new VersionContext(VersionContextType.COMMIT, "d0628f078890fec234b98b873f9e1f3cd140988a");
    sourceVersionReferenceList.add(new SourceVersionReference("source1", versionContext1));
    sourceVersionReferenceList.add(new SourceVersionReference("source2", versionContext2));
    sourceVersionReferenceList.add(new SourceVersionReference("source3", versionContext3));

    // set references payload
    TransformUpdateSQL transformSql1 = new TransformUpdateSQL();
    transformSql1.setSql("SELECT \"version\" FROM dsvTest.transformAndRunVDS");
    transformSql1.setReferencesList(sourceVersionReferenceList);

    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql1)),
            new GenericType<InitialPreviewResponse>() {});

    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // set null references payload
    references = new HashMap<>();
    TransformUpdateSQL transformSql2 = new TransformUpdateSQL();
    transformSql2.setSql("SELECT \"version\" FROM dsvTest.transformAndRunVDS");
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql2)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // set empty references payload
    references = new HashMap<>();
    TransformUpdateSQL transformSql3 = new TransformUpdateSQL();
    transformSql3.setSql("SELECT \"version\" FROM dsvTest.transformAndRunVDS");
    transformSql3.setReferencesList(new ArrayList<>());
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql3)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);
  }

  @Test
  public void testAsyncTransformAndRunApiWithReferences() throws Exception {
    Dataset newVDS =
        createVDS(Arrays.asList("dsvTest", "transformAndRun_VDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    expectSuccess(
        getBuilder(target).buildPost(Entity.json(null)),
        new GenericType<InitialPreviewResponse>() {});

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.transformAndRun_VDS2");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});
    String dsPath = String.join(".", dswh.getDataset().getFullPath());

    // set references payload
    datasetVersion = DatasetVersion.newVersion();
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transform_and_run")
            .queryParam("newVersion", datasetVersion);
    List<SourceVersionReference> sourceVersionReferenceList = new ArrayList<>();
    VersionContext versionContext1 = new VersionContext(VersionContextType.BRANCH, "branch");
    VersionContext versionContext2 = new VersionContext(VersionContextType.TAG, "tag");
    VersionContext versionContext3 =
        new VersionContext(VersionContextType.COMMIT, "d0628f078890fec234b98b873f9e1f3cd140988a");
    sourceVersionReferenceList.add(new SourceVersionReference("source1", versionContext1));
    sourceVersionReferenceList.add(new SourceVersionReference("source2", versionContext2));
    sourceVersionReferenceList.add(new SourceVersionReference("source3", versionContext3));

    // set references payload
    TransformUpdateSQL transformSql1 = new TransformUpdateSQL();
    transformSql1.setSql("SELECT \"version\" FROM dsvTest.transformAndRun_VDS");
    transformSql1.setReferencesList(sourceVersionReferenceList);

    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql1)),
            new GenericType<InitialPreviewResponse>() {});

    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // set null references payload
    references = new HashMap<>();
    TransformUpdateSQL transformSql2 = new TransformUpdateSQL();
    transformSql2.setSql("SELECT \"version\" FROM dsvTest.transformAndRun_VDS");
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql2)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);

    // set empty references payload
    references = new HashMap<>();
    TransformUpdateSQL transformSql3 = new TransformUpdateSQL();
    transformSql3.setSql("SELECT \"version\" FROM dsvTest.transformAndRun_VDS");
    transformSql3.setReferencesList(new ArrayList<>());
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql3)),
            new GenericType<InitialPreviewResponse>() {});
    assertThat(initialPreviewResponse.getDataset().getReferences())
        .usingRecursiveComparison()
        .isEqualTo(references);
  }

  @Test
  public void testVersionHistory() throws Exception {
    // Test for DX-12601

    // create a VDS in the space
    Dataset newVDS = createVDS(Arrays.asList("dsvTest", "myVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<InitialPreviewResponse>() {});

    // save the derivation a new VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.myVDS2");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // modify the sql of the new VDS by doing a transform
    DatasetVersion datasetVersion2 = DatasetVersion.newVersion();
    String dsPath = String.join(".", dswh.getDataset().getFullPath());

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transformAndPreview")
            .queryParam("newVersion", datasetVersion2);

    TransformUpdateSQL transformSql = new TransformUpdateSQL();
    transformSql.setSql("SELECT \"version\" FROM dsvTest.myVDS");

    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql)),
            new GenericType<InitialPreviewResponse>() {});

    // save the transform as a third VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(initialPreviewResponse.getDataset().getDatasetVersion().getVersion())
            .path("save")
            .queryParam("as", "dsvTest.myVDS3");

    DatasetUIWithHistory dswh2 =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // preview the last history item
    HistoryItem historyItem = dswh.getHistory().getItems().get(0);
    String dsPath2 = String.join(".", dswh2.getDataset().getFullPath());

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath2)
            .path("version")
            .path(historyItem.getDatasetVersion().getVersion())
            .path("preview")
            .queryParam("view", "explore")
            .queryParam("tipVersion", dswh2.getDataset().getDatasetVersion());

    expectSuccess(getBuilder(target).buildGet(), new GenericType<InitialPreviewResponse>() {});
  }

  @Test
  public void testBrokenVDSEditOriginalSQL() throws Exception {
    Dataset parentVDS =
        createVDS(
            Arrays.asList("dsvTest", "badVDSParent"), "select version, commit_id from sys.version");
    parentVDS =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog"))
                .buildPost(Entity.json(parentVDS)),
            new GenericType<Dataset>() {});

    Dataset newVDS =
        createVDS(Arrays.asList("dsvTest", "badVDS"), "select version from dsvTest.badVDSParent");
    newVDS =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // update the parent to no longer include the version field
    Dataset updatedParentVDS =
        new Dataset(
            parentVDS.getId(),
            Dataset.DatasetType.VIRTUAL_DATASET,
            parentVDS.getPath(),
            null,
            null,
            parentVDS.getTag(),
            parentVDS.getAccelerationRefreshPolicy(),
            "select commit_id from sys.version",
            parentVDS.getSqlContext(),
            parentVDS.getFormat(),
            null,
            false);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("catalog").path(updatedParentVDS.getId()))
            .buildPut(Entity.json(updatedParentVDS)),
        new GenericType<Dataset>() {});

    // create a derivation of the VDS (this will fail since the sql is no longer valid)
    String dsPath = String.join(".", newVDS.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", dsPath)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    ApiErrorModel apiErrorModel =
        expectStatus(
            Response.Status.BAD_REQUEST,
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<ApiErrorModel<InvalidQueryException.Details>>() {});

    InvalidQueryException.Details details =
        (InvalidQueryException.Details) apiErrorModel.getDetails();

    // edit original sql is a preview call with a limit of 0
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(details.getDatasetSummary().getDatasetVersion().getVersion())
            .path("preview")
            .queryParam("view", "explore")
            .queryParam("limit", "0");

    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(getBuilder(target).buildGet(), new GenericType<InitialPreviewResponse>() {});
    assertEquals(newVDS.getSql(), initialPreviewResponse.getDataset().getSql());
  }

  @Test
  public void testRenameShouldNotBreakHistory() throws Exception {
    Dataset parentVDS =
        createVDS(Arrays.asList("dsvTest", "renameParentVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog"))
                .buildPost(Entity.json(parentVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of parentVDS
    String parentDataset = String.join(".", parentVDS.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<InitialPreviewResponse>() {});

    // save the derivation a new VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.renameVDS");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // modify the sql of the new VDS by doing a transform
    DatasetVersion datasetVersion2 = DatasetVersion.newVersion();
    String dsPath = String.join(".", dswh.getDataset().getFullPath());

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transformAndPreview")
            .queryParam("newVersion", datasetVersion2);

    TransformUpdateSQL transformSql = new TransformUpdateSQL();
    transformSql.setSql("SELECT \"version\" FROM dsvTest.renameParentVDS");

    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql)),
            new GenericType<InitialPreviewResponse>() {});

    // save the transform as a third VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(initialPreviewResponse.getDataset().getDatasetVersion().getVersion())
            .path("save")
            .queryParam("as", "dsvTest.renameVDS2");

    DatasetUIWithHistory dswh2 =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});

    // the history of the original VDS should not be broken after save as a new VDS
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(String.join(".", dswh.getDataset().getFullPath()))
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("preview")
            .queryParam("view", "explore")
            .queryParam("limit", "0");
    expectSuccess(getBuilder(target).buildGet(), new GenericType<InitialPreviewResponse>() {});

    // rename original VDS, the history of saved new VDS should not be broken
    DatasetVersionMutator mutator = l(DatasetVersionMutator.class);
    mutator.renameDataset(
        new DatasetPath(dswh.getDataset().getFullPath()),
        new DatasetPath(Arrays.asList("dsvTest", "renameVDS-new")));

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(String.join(".", dswh2.getDataset().getFullPath()))
            .path("version")
            .path(dswh2.getDataset().getDatasetVersion().getVersion())
            .path("preview")
            .queryParam("view", "explore")
            .queryParam("limit", "0");
    expectSuccess(getBuilder(target).buildGet(), new GenericType<InitialPreviewResponse>() {});

    // rename dsvTest.renameVDS2 to dsvTest.renameVDS2-new
    VirtualDatasetUI renameDataset =
        mutator.renameDataset(
            new DatasetPath(dswh2.getDataset().getFullPath()),
            new DatasetPath(Arrays.asList("dsvTest", "renameVDS2-new")));

    // edit original sql
    parentDataset = String.join(".", renameDataset.getFullPathList());
    datasetVersion = DatasetVersion.newVersion();

    target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 0);
    initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<InitialPreviewResponse>() {});

    DatasetUI datasetUI = initialPreviewResponse.getDataset();
    DatasetVersionResourcePath versionResourcePath = datasetUI.toDatasetVersionPath();
    InitialPreviewResponse reapplyResult =
        getHttpClient().getDatasetApi().reapply(versionResourcePath);
  }

  // Ensure DDL statements won't work with save
  @Test
  public void testSaveWithInvalidCreateSqlInitial() throws Exception {
    Dataset newVDS =
        createVDS(
            Arrays.asList("dsvTest", "saveInvalidVDS"),
            "CREATE TABLE \"$scratch\".\"ctas\" AS select 1");
    expectStatus(
        BAD_REQUEST,
        getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)));
  }

  @Test
  public void testSaveAfterTransformWithInvalidSql() throws Exception {
    Dataset newVDS =
        createVDS(Arrays.asList("dsvTest", "initalSaveVDS"), "select * from sys.version");
    Dataset vds =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
            new GenericType<Dataset>() {});

    // create a derivation of the VDS
    String parentDataset = String.join(".", vds.getPath());
    DatasetVersion datasetVersion = DatasetVersion.newVersion();
    WebTarget target =
        getHttpClient()
            .getAPIv2()
            .path("datasets")
            .path("new_untitled")
            .queryParam("parentDataset", parentDataset)
            .queryParam("newVersion", datasetVersion)
            .queryParam("limit", 120);
    expectSuccess(
        getBuilder(target).buildPost(Entity.json(null)),
        new GenericType<InitialPreviewResponse>() {});

    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path("tmp.UNTITLED")
            .path("version")
            .path(datasetVersion.getVersion())
            .path("save")
            .queryParam("as", "dsvTest.secondVersionVDS");
    DatasetUIWithHistory dswh =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(null)),
            new GenericType<DatasetUIWithHistory>() {});
    String dsPath = String.join(".", dswh.getDataset().getFullPath());

    datasetVersion = DatasetVersion.newVersion();
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(dswh.getDataset().getDatasetVersion().getVersion())
            .path("transformAndRun")
            .queryParam("newVersion", datasetVersion);

    // Set valid sql string
    TransformUpdateSQL transformSql1 = new TransformUpdateSQL();
    // set invalid sql string
    transformSql1.setSql("create table \"dfs_test\".t (c1 int)");
    InitialPreviewResponse initialPreviewResponse =
        expectSuccess(
            getBuilder(target).buildPost(Entity.json(transformSql1)),
            new GenericType<InitialPreviewResponse>() {});

    // save the transform as a view - should fail since it's a create statement
    target =
        getHttpClient()
            .getAPIv2()
            .path("dataset")
            .path(dsPath)
            .path("version")
            .path(initialPreviewResponse.getDataset().getDatasetVersion().getVersion())
            .path("save")
            .queryParam("as", "dsvTest.thirdVersionVDS");

    expectStatus(Response.Status.BAD_REQUEST, getBuilder(target).buildPost(Entity.json(null)));
  }

  /**
   * Views created through the Catalog API (as opposed to CREATE VIEW DDL statement) are allowed to
   * contain duplicate column names. Verify that we can still select from such a view. See DX-63350
   *
   * @throws Exception
   */
  @Test
  public void testDuplicateColumns() {
    BufferAllocator allocator =
        getRootAllocator().newChildAllocator(getClass().getName(), 0, Long.MAX_VALUE);
    Dataset newVDS =
        createVDS(
            Arrays.asList("dsvTest", "testDuplicateColumns"),
            "select n_name, n_name as n_name0 from cp.\"tpch/nation.parquet\" order by 1 asc");
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("catalog")).buildPost(Entity.json(newVDS)),
        new GenericType<Dataset>() {});

    try (final JobDataFragment data =
        submitJobAndGetData(
            l(JobsService.class),
            JobRequest.newBuilder()
                .setSqlQuery(
                    new SqlQuery("select * from dsvTest.testDuplicateColumns", DEFAULT_USERNAME))
                .build(),
            0,
            20,
            allocator)) {
      assertEquals(20, data.getReturnedRowCount());
      assertEquals("ALGERIA", data.extractValue("n_name", 0).toString());
      assertEquals("ALGERIA", data.extractValue("n_name0", 0).toString());
    } finally {
      allocator.close();
    }
  }

  private Dataset createVDS(List<String> path, String sql) {
    return new Dataset(
        null,
        Dataset.DatasetType.VIRTUAL_DATASET,
        path,
        null,
        null,
        null,
        null,
        sql,
        null,
        null,
        null,
        false);
  }
}
