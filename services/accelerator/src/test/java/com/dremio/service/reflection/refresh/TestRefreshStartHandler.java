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
package com.dremio.service.reflection.refresh;

import static com.dremio.service.reflection.proto.ReflectionState.REFRESH;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.exec.store.CatalogService;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.RefreshMethod;
import com.dremio.service.reflection.ReflectionManager;
import com.dremio.service.reflection.ReflectionServiceImpl;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.WakeUpManagerWhenJobDone;
import com.dremio.service.reflection.materialization.AccelerationStoragePlugin;
import com.dremio.service.reflection.materialization.AccelerationStoragePluginConfig;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.Refresh;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.dremio.test.ClearInlineMocksRule;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import java.util.Collections;
import java.util.Random;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Test Refresh Start Handler */
public class TestRefreshStartHandler {

  @ClassRule
  public static final ClearInlineMocksRule CLEAR_INLINE_MOCKS = new ClearInlineMocksRule();

  @Test
  public void testStartRefreshForIcebergReflection() {
    try (MockedStatic<ReflectionUtils> mockedReflectionUtils =
            Mockito.mockStatic(ReflectionUtils.class);
        MockedStatic<ReflectionManager> mockedReflectionManager =
            Mockito.mockStatic(ReflectionManager.class); ) {
      ReflectionId reflectionId = new ReflectionId("r_id");

      ReflectionEntry reflectionEntry =
          new ReflectionEntry()
              .setId(reflectionId)
              .setState(REFRESH)
              .setRefreshMethod(RefreshMethod.INCREMENTAL);

      Refresh refresh = new Refresh().setBasePath("/basepath");

      JobId jobId = new JobId().setId("jobid");
      Table icebergTable = mock(Table.class);
      Snapshot snapshot = mock(Snapshot.class);
      long snapshotId = new Random().nextLong();

      com.dremio.service.reflection.refresh.Subject subject =
          new com.dremio.service.reflection.refresh.Subject();

      when(subject.materializationStore.getRefreshesByReflectionId(reflectionId))
          .thenReturn(FluentIterable.from(Collections.singletonList(refresh)));
      ReflectionGoal goal = new ReflectionGoal();
      when(subject.reflectionGoalsStore.get(reflectionId)).thenReturn(goal);

      mockedReflectionUtils
          .when(
              () ->
                  ReflectionUtils.submitRefreshJob(
                      any(JobsService.class),
                      any(DatasetConfig.class),
                      eq(reflectionEntry),
                      any(MaterializationId.class),
                      any(String.class),
                      eq(QueryType.ACCELERATOR_CREATE),
                      any(WakeUpManagerWhenJobDone.class),
                      any(OptionManager.class),
                      any(String.class)))
          .thenReturn(jobId);

      mockedReflectionUtils
          .when(() -> ReflectionUtils.getIcebergTable(eq(reflectionId), any(), any()))
          .thenReturn(icebergTable);
      mockedReflectionUtils
          .when(
              () ->
                  ReflectionUtils.getAnchorDataset(any(CatalogService.class), eq(reflectionEntry)))
          .thenReturn(subject.datasetConfig);
      mockedReflectionManager
          .when(() -> ReflectionManager.setSpanAttributes(any(), any()))
          .thenAnswer(invocation -> null);
      when(icebergTable.currentSnapshot()).thenReturn(snapshot);
      when(snapshot.snapshotId()).thenReturn(snapshotId);
      AccelerationStoragePluginConfig pluginConfig =
          Mockito.mock(AccelerationStoragePluginConfig.class);
      when(pluginConfig.getPath()).thenReturn(Path.of("."));
      when(subject.accelerationPlugin.getConfig()).thenReturn(pluginConfig);
      when(subject.catalogService.getSource(ReflectionServiceImpl.ACCELERATOR_STORAGEPLUGIN_NAME))
          .thenReturn(subject.accelerationPlugin);

      // Test
      subject.refreshStartHandler.startJob(reflectionEntry, System.currentTimeMillis());

      ArgumentCaptor<Materialization> captor = ArgumentCaptor.forClass(Materialization.class);
      verify(subject.materializationStore, times(2)).save(captor.capture());

      Materialization capturedArgument = captor.getValue();
      assertEquals(snapshotId, capturedArgument.getPreviousIcebergSnapshot().longValue());
      verify(icebergTable, times(1)).currentSnapshot();
    }
  }
}

class Subject {
  @VisibleForTesting JobsService jobsService = Mockito.mock(JobsService.class);
  @VisibleForTesting CatalogService catalogService = Mockito.mock(CatalogService.class);
  @VisibleForTesting OptionManager optionManager = Mockito.mock(OptionManager.class);
  @VisibleForTesting DatasetConfig datasetConfig = Mockito.mock(DatasetConfig.class);

  @VisibleForTesting
  ReflectionGoalsStore reflectionGoalsStore = Mockito.mock(ReflectionGoalsStore.class);

  @VisibleForTesting
  MaterializationStore materializationStore = Mockito.mock(MaterializationStore.class);

  @VisibleForTesting
  ReflectionManager.WakeUpCallback wakeUpCallback =
      Mockito.mock(ReflectionManager.WakeUpCallback.class);

  @VisibleForTesting RefreshStartHandler refreshStartHandler;

  @VisibleForTesting
  AccelerationStoragePlugin accelerationPlugin = Mockito.mock(AccelerationStoragePlugin.class);

  public Subject() {
    refreshStartHandler = newRefreshStartHandler();
  }

  @VisibleForTesting
  RefreshStartHandler newRefreshStartHandler() {
    return new RefreshStartHandler(
        catalogService,
        jobsService,
        reflectionGoalsStore,
        materializationStore,
        wakeUpCallback,
        optionManager);
  }
}
