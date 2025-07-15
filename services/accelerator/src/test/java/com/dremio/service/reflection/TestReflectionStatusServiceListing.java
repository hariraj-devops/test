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
package com.dremio.service.reflection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.MetadataRequestOptions;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.options.OptionManager;
import com.dremio.service.DirectProvider;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.job.JobCounts;
import com.dremio.service.job.JobCountsRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.MaterializationMetrics;
import com.dremio.service.reflection.proto.MaterializationState;
import com.dremio.service.reflection.proto.ReflectionDetails;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionField;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionState;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.proto.Refresh;
import com.dremio.service.reflection.store.ExternalReflectionStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionEntriesStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.google.common.collect.FluentIterable;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.calcite.util.Pair;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class TestReflectionStatusServiceListing {

  @Mock private NamespaceService namespaceService;

  @Mock private ClusterCoordinator clusterCoordinator;

  @Mock private ReflectionGoalsStore goalsStore;

  @Mock private ReflectionEntriesStore entriesStore;

  @Mock private MaterializationStore materializationStore;

  @Mock private ExternalReflectionStore externalReflectionStore;

  @Mock private ReflectionValidator validator;

  @Mock private CatalogService catalogService;

  @Mock private JobsService jobsService;

  @Mock private Catalog entityExplorer;

  @Mock private OptionManager optionManager;

  private String datasetId;

  private ReflectionId reflectionId;

  private ReflectionStatusService statusService;

  @Before
  public void setup() {
    datasetId = UUID.randomUUID().toString();
    final List<String> dataPath = Arrays.asList("source", "folder", "dataset");
    final DatasetConfig dataset =
        new DatasetConfig()
            .setId(new EntityId(datasetId))
            .setFullPathList(dataPath)
            .setType(DatasetType.VIRTUAL_DATASET);
    final DremioTable table = mock(DremioTable.class);
    when(table.getDatasetConfig()).thenReturn(dataset);
    when(entityExplorer.getTable(datasetId)).thenReturn(table);
    when(catalogService.getCatalog(any(MetadataRequestOptions.class))).thenReturn(entityExplorer);

    reflectionId = new ReflectionId(UUID.randomUUID().toString());
    ReflectionEntry entry = new ReflectionEntry();
    entry.setState(ReflectionState.ACTIVE).setId(reflectionId);
    when(entriesStore.get(reflectionId)).thenReturn(entry);

    when(namespaceService.getDatasetById(new EntityId(datasetId))).thenReturn(Optional.of(dataset));

    statusService =
        new ReflectionStatusServiceImpl(
            () -> clusterCoordinator,
            DirectProvider.wrap(new TestReflectionStatusService.ConstantCacheViewer(true)),
            goalsStore,
            entriesStore,
            materializationStore,
            externalReflectionStore,
            validator,
            DirectProvider.wrap(catalogService),
            DirectProvider.wrap(jobsService),
            DirectProvider.wrap(optionManager),
            ReflectionUtils::new);
  }

  /** Verify that we can list reflections for the sys.reflections table */
  @Test
  public void testGetReflection() {

    final ReflectionField field = new ReflectionField();
    field.setName("myDisplayField1");
    final ReflectionField field2 = new ReflectionField();
    field2.setName("myDisplayField2");
    final ReflectionDetails details = new ReflectionDetails();
    details.setDisplayFieldList(Arrays.asList(field, field2));

    long createAtMillis = 2L;
    final ReflectionGoal goal =
        new ReflectionGoal()
            .setId(reflectionId)
            .setDatasetId(datasetId)
            .setState(ReflectionGoalState.ENABLED)
            .setCreatedAt(createAtMillis)
            .setModifiedAt(null)
            .setName("myReflection")
            .setType(ReflectionType.RAW)
            .setDetails(details);
    MaterializationMetrics metrics = new MaterializationMetrics().setFootprint(70L);
    final Materialization m1 =
        new Materialization().setId(new MaterializationId("m1")).setLastRefreshFromPds(10L);
    final Materialization m2 = new Materialization().setState(MaterializationState.FAILED);

    when(goalsStore.getAllNotDeleted()).thenReturn(Arrays.asList(goal));
    when(materializationStore.getLastMaterializationDone(reflectionId)).thenReturn(m1);
    when(materializationStore.getMetrics(m1)).thenReturn(new Pair<>(metrics, 5L));
    when(materializationStore.getLastMaterialization(reflectionId)).thenReturn(m2);
    final Refresh refresh =
        new Refresh().setMetrics(new MaterializationMetrics().setFootprint(99L));
    when(materializationStore.getRefreshesByReflectionId(reflectionId))
        .thenReturn(FluentIterable.from(Arrays.asList(refresh)));

    JobCounts.Builder jobCounts = JobCounts.newBuilder();
    jobCounts.addCount(0);
    when(jobsService.getJobCounts(any(JobCountsRequest.class))).thenReturn(jobCounts.build());

    Iterator<AccelerationListManager.ReflectionInfo> reflections = statusService.getReflections();
    AccelerationListManager.ReflectionInfo info = reflections.next();
    assertEquals("source.folder.dataset", info.dataset);
    assertEquals(datasetId, info.datasetId);
    assertEquals("myDisplayField1, myDisplayField2", info.displayColumns);
    assertEquals("myReflection", info.name);
    assertEquals("UNKNOWN", info.status);
    assertEquals("RAW", info.type);
    assertEquals(new Timestamp(createAtMillis), info.createdAt);
    assertNull(info.updatedAt);
    assertEquals(-1, info.lastRefreshDurationMillis);
    assertEquals(new Timestamp(m1.getLastRefreshFromPds()), info.lastRefreshFromTable);
    assertEquals(70L, info.currentFootprintBytes);
    assertEquals(99L, info.totalFootprintBytes);
    assertEquals(5L, info.recordCount);
    assertEquals(
        -1, info.consideredCount); // flag PlannerSettings.ENABLE_JOB_COUNT_CONSIDERED is off
    assertEquals(-1, info.matchedCount); // flag PlannerSettings.ENABLE_JOB_COUNT_MATCHED is off
    assertEquals(-1, info.acceleratedCount); // flag PlannerSettings.ENABLE_JOB_COUNT_CHOSEN is off

    assertFalse(reflections.hasNext());
  }

  /** Verify that a missing dataset won't cause listing API to break */
  @Test
  public void testGetReflectionOnMissingDataset() {

    final ReflectionGoal goal =
        new ReflectionGoal()
            .setId(reflectionId)
            .setDatasetId("foo")
            .setState(ReflectionGoalState.ENABLED)
            .setCreatedAt(0L)
            .setName("myReflection")
            .setType(ReflectionType.RAW);
    when(goalsStore.getAllNotDeleted()).thenReturn(Arrays.asList(goal));

    JobCounts.Builder jobCounts = JobCounts.newBuilder();
    jobCounts.addCount(0);
    when(jobsService.getJobCounts(any(JobCountsRequest.class))).thenReturn(jobCounts.build());

    Iterator<AccelerationListManager.ReflectionInfo> reflections = statusService.getReflections();
    assertFalse(reflections.hasNext());
  }
}
