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
package com.dremio.service.reflection.descriptor;

import static com.dremio.sabot.rpc.user.UserSession.MAX_METADATA_COUNT;
import static com.dremio.service.reflection.ReflectionOptions.AUTO_REBUILD_PLAN;
import static com.dremio.service.reflection.ReflectionOptions.MATERIALIZATION_CACHE_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.ops.PlannerCatalog;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.MaterializationExpander.RebuildPlanException;
import com.dremio.exec.planner.acceleration.StrippingFactory;
import com.dremio.exec.planner.acceleration.descriptor.ExpandedMaterializationDescriptor;
import com.dremio.exec.planner.acceleration.descriptor.UnexpandedMaterializationDescriptor;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.options.OptionManager;
import com.dremio.service.DirectProvider;
import com.dremio.service.reflection.ReflectionSettings;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.descriptor.DescriptorHelper.ExpansionHelper;
import com.dremio.service.reflection.descriptor.DescriptorHelper.ReflectionPlanGeneratorProvider;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.JobDetails;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.MaterializationMetrics;
import com.dremio.service.reflection.proto.MaterializationPlan;
import com.dremio.service.reflection.proto.MaterializationPlanId;
import com.dremio.service.reflection.proto.MaterializationState;
import com.dremio.service.reflection.proto.ReflectionDetails;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.proto.Refresh;
import com.dremio.service.reflection.proto.RefreshId;
import com.dremio.service.reflection.refresh.ReflectionPlanGenerator;
import com.dremio.service.reflection.refresh.ReflectionPlanGenerator.SerializedMatchingInfo;
import com.dremio.service.reflection.store.DependenciesStore;
import com.dremio.service.reflection.store.ExternalReflectionStore;
import com.dremio.service.reflection.store.MaterializationPlanStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionEntriesStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.protostuff.ByteString;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class TestDescriptors {

  @Mock private SabotContext sabotContext;

  @Mock private CatalogService catalogService;

  @Mock private OptionManager optionManager;

  @Mock private DremioConfig dremioConfig;

  @Mock private LegacyKVStoreProvider kvStoreProvider;

  @Mock private LegacyIndexedStore<ReflectionId, ReflectionGoal> reflectionGoalStore;

  @Mock private LegacyKVStore<ReflectionId, ReflectionEntry> reflectionEntryStore;

  @Mock private LegacyIndexedStore<MaterializationId, Materialization> materializationStore;

  @Mock
  private LegacyIndexedStore<MaterializationPlanId, MaterializationPlan> materializationPlanStore;

  @Mock private LegacyIndexedStore<RefreshId, Refresh> refreshStore;

  @Mock private LegacyIndexedStore<ReflectionId, ExternalReflection> externalReflectionStore;

  @Mock private DependenciesStore dependenciesStore;

  @Mock private SqlConverter converter;

  @Mock private DescriptorHelper provider;

  @Mock private UnexpandedMaterializationDescriptor descriptor;

  @Mock private DremioMaterialization dremioMaterialization;

  @Mock private Catalog catalog;

  @Mock private ExpandedMaterializationDescriptor expandedDescriptor;

  @Mock private ReflectionSettings reflectionSettings;

  @Mock private ReflectionUtils reflectionUtils;

  private Materialization materialization;
  private ReflectionEntry entry;
  private ReflectionGoal goal;
  private ExpansionHelper expansionHelper;

  @Before
  public void setup() {
    when(sabotContext.getDremioConfig()).thenReturn(dremioConfig);
    when(sabotContext.getOptionManager()).thenReturn(optionManager);

    when(optionManager.getOption(MATERIALIZATION_CACHE_ENABLED)).thenReturn(true);
    when(optionManager.getOption(AUTO_REBUILD_PLAN)).thenReturn(true);
    when(optionManager.getOption(MAX_METADATA_COUNT.getOptionName()))
        .thenReturn(MAX_METADATA_COUNT.getDefault());

    // Test KV store objects
    ReflectionId rId = new ReflectionId("r1");
    MaterializationId mId = new MaterializationId("m1");
    materialization =
        new Materialization()
            .setId(mId)
            .setReflectionId(rId)
            .setReflectionGoalVersion("v1")
            .setState(MaterializationState.DONE)
            .setSeriesId(5L)
            .setSeriesOrdinal(0)
            .setExpiration(0L)
            .setStripVersion(StrippingFactory.LATEST_STRIP_VERSION)
            .setInitRefreshSubmit(0L);
    entry = new ReflectionEntry();
    entry.setId(rId);
    ReflectionDetails details = new ReflectionDetails();
    details.setDisplayFieldList(ImmutableList.of());
    details.setDistributionFieldList(ImmutableList.of());
    details.setPartitionFieldList(ImmutableList.of());
    details.setSortFieldList(ImmutableList.of());
    goal =
        new ReflectionGoal()
            .setId(rId)
            .setTag(materialization.getReflectionGoalVersion())
            .setType(ReflectionType.RAW)
            .setDetails(details);

    when(kvStoreProvider.getStore(ReflectionGoalsStore.StoreCreator.class))
        .thenReturn(reflectionGoalStore);
    when(reflectionGoalStore.get(rId)).thenReturn(goal);
    when(kvStoreProvider.getStore(ReflectionEntriesStore.StoreCreator.class))
        .thenReturn(reflectionEntryStore);
    when(reflectionEntryStore.get(rId)).thenReturn(entry);
    when(kvStoreProvider.getStore(MaterializationStore.MaterializationStoreCreator.class))
        .thenReturn(materializationStore);
    when(materializationStore.get(mId)).thenReturn(materialization);
    when(materializationStore.find(any(LegacyIndexedStore.LegacyFindByCondition.class)))
        .thenReturn(ImmutableList.of(Maps.immutableEntry(mId, materialization)));
    when(kvStoreProvider.getStore(MaterializationStore.RefreshStoreCreator.class))
        .thenReturn(refreshStore);
    Refresh refresh = new Refresh();
    refresh.setId(new RefreshId("refresh1"));
    MaterializationMetrics metrics = new MaterializationMetrics();
    metrics.setFootprint(0L);
    metrics.setOriginalCost(0d);
    metrics.setFootprint(0L);
    refresh.setMetrics(metrics);
    JobDetails jobDetails = new JobDetails();
    jobDetails.setOutputRecords(0L);
    refresh.setJob(jobDetails);
    when(refreshStore.find(Mockito.any(LegacyIndexedStore.LegacyFindByCondition.class)))
        .thenReturn(ImmutableMap.of(refresh.getId(), refresh).entrySet());
    when(materializationStore.find(Mockito.any(LegacyIndexedStore.LegacyFindByCondition.class)))
        .thenReturn(ImmutableMap.of(materialization.getId(), materialization).entrySet());
    when(kvStoreProvider.getStore(ExternalReflectionStore.StoreCreator.class))
        .thenReturn(externalReflectionStore);

    when(kvStoreProvider.getStore(MaterializationPlanStore.MaterializationPlanStoreCreator.class))
        .thenReturn(materializationPlanStore);

    // Setup Materialization expansion helper
    expansionHelper = Mockito.mock(ExpansionHelper.class);
    converter = Mockito.mock(SqlConverter.class);
    when(converter.getPlannerCatalog()).thenReturn(Mockito.mock(PlannerCatalog.class));
    when(expansionHelper.getConverter()).thenReturn(converter);

    when(descriptor.getMaterializationFor(any())).thenReturn(dremioMaterialization);
    when(descriptor.getMaterializationId()).thenReturn(mId.getId());
    when(descriptor.getLayoutInfo())
        .thenReturn(
            ReflectionUtils.toReflectionInfo(goal, reflectionUtils.REFLECTION_MODE_PROVIDER));
    when(descriptor.getPath()).thenReturn(ImmutableList.of());

    when(catalogService.getCatalog(any())).thenReturn(catalog);
    when(catalog.getAllRequestedTables())
        .thenReturn(ImmutableList.of(Mockito.mock(DremioTable.class)));
    when(provider.getValidMaterializations()).thenReturn(ImmutableList.of(materialization));
    when(provider.expand((Materialization) any(), any())).thenReturn(expandedDescriptor);
  }

  @Test
  public void testDescriptorUpgradeSuccess() {
    ReflectionPlanGeneratorProvider planGeneratorProvider =
        (expansionHelper,
            catalogService,
            config,
            goal,
            entry,
            materialization,
            reflectionSettings,
            materializationStore,
            dependenciesStore,
            forceFullUpdate,
            isRebuildPlan) -> {
          ReflectionPlanGenerator generator = Mockito.mock(ReflectionPlanGenerator.class);
          SerializedMatchingInfo info = Mockito.mock(SerializedMatchingInfo.class);
          when(generator.getSerializedMatchingInfo()).thenReturn(info);
          when(info.getMatchingPlanBytes()).thenReturn(ByteString.bytesDefaultValue("abc"));
          return generator;
        };

    Function<Catalog, ExpansionHelper> expansionHelperProvider = (catalog) -> expansionHelper;

    DescriptorHelperImpl dremioMaterializationProvider =
        new DescriptorHelperImpl(
            DirectProvider.wrap(catalogService),
            DirectProvider.wrap(sabotContext),
            new MaterializationStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionEntriesStore(DirectProvider.wrap(kvStoreProvider)),
            new ExternalReflectionStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionGoalsStore(DirectProvider.wrap(kvStoreProvider), null),
            new MaterializationPlanStore(DirectProvider.wrap(kvStoreProvider)),
            dependenciesStore,
            reflectionSettings,
            ReflectionUtils::getMaterializationDescriptor,
            reflectionUtils);

    UnexpandedMaterializationDescriptor descriptorWithPlan =
        dremioMaterializationProvider.getDescriptor(
            materialization, catalog, expansionHelperProvider, planGeneratorProvider);

    assertTrue(Arrays.equals(new byte[] {'a', 'b', 'c'}, descriptorWithPlan.getPlan()));
    verify(materializationPlanStore, times(1))
        .put(
            eq(MaterializationPlanStore.createMaterializationPlanId(materialization.getId())),
            any(MaterializationPlan.class));
    verify(expansionHelper, times(1)).close();
  }

  /**
   * Verifies that when a descriptor can't be upgraded, we throw an exception and we don't mark the
   * materialization as failed.
   */
  @Test
  public void testDescriptorUpgradeFailure() {
    ReflectionPlanGeneratorProvider planGeneratorProvider =
        (expansionHelper,
            catalogService,
            config,
            goal,
            entry,
            materialization,
            reflectionSettings,
            materializationStore,
            dependenciesStore,
            forceFullUpdate,
            isRebuildPlan) -> {
          ReflectionPlanGenerator generator = Mockito.mock(ReflectionPlanGenerator.class);
          when(generator.generateNormalizedPlan()).thenThrow(new RuntimeException("Boom!"));
          return generator;
        };

    Function<Catalog, ExpansionHelper> expansionHelperProvider = (catalog) -> expansionHelper;

    assertEquals(MaterializationState.DONE, materialization.getState());

    DescriptorHelperImpl dremioMaterializationProvider =
        new DescriptorHelperImpl(
            DirectProvider.wrap(catalogService),
            DirectProvider.wrap(sabotContext),
            new MaterializationStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionEntriesStore(DirectProvider.wrap(kvStoreProvider)),
            new ExternalReflectionStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionGoalsStore(DirectProvider.wrap(kvStoreProvider), null),
            new MaterializationPlanStore(DirectProvider.wrap(kvStoreProvider)),
            dependenciesStore,
            reflectionSettings,
            ReflectionUtils::getMaterializationDescriptor,
            reflectionUtils);

    assertRebuildException(
        () ->
            dremioMaterializationProvider.getDescriptor(
                materialization, catalog, expansionHelperProvider, planGeneratorProvider),
        false);
    assertEquals(MaterializationState.DONE, materialization.getState());
    assertNull(materialization.getFailure());
    verify(materializationStore, times(0)).put(any(), any());
  }

  private void assertRebuildException(ThrowingRunnable runnable, boolean sourceDown) {
    try {
      runnable.run();
    } catch (Throwable e) {
      assertEquals(RebuildPlanException.class, e.getClass());
      assertEquals(sourceDown, ReflectionUtils.isSourceDown(e));
    }
  }

  /**
   * Verifies that when a descriptor can't be upgraded because of a down source, we continue to
   * retry upgrade.
   */
  @Test
  public void testDescriptorUpgradeWithSourceDown() {
    ReflectionPlanGeneratorProvider planGeneratorProvider =
        (expansionHelper,
            catalogService,
            config,
            goal,
            entry,
            materialization,
            reflectionSettings,
            materializationStore,
            dependenciesStore,
            forceFullUpdate,
            isRebuildPlan) -> {
          ReflectionPlanGenerator generator = Mockito.mock(ReflectionPlanGenerator.class);
          when(generator.generateNormalizedPlan())
              .thenThrow(new RuntimeException(UserException.sourceInBadState().buildSilently()));
          return generator;
        };

    Function<Catalog, ExpansionHelper> expansionHelperProvider = (catalog) -> expansionHelper;

    DescriptorHelperImpl dremioMaterializationProvider =
        new DescriptorHelperImpl(
            DirectProvider.wrap(catalogService),
            DirectProvider.wrap(sabotContext),
            new MaterializationStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionEntriesStore(DirectProvider.wrap(kvStoreProvider)),
            new ExternalReflectionStore(DirectProvider.wrap(kvStoreProvider)),
            new ReflectionGoalsStore(DirectProvider.wrap(kvStoreProvider), null),
            new MaterializationPlanStore(DirectProvider.wrap(kvStoreProvider)),
            dependenciesStore,
            reflectionSettings,
            ReflectionUtils::getMaterializationDescriptor,
            reflectionUtils);

    assertEquals(MaterializationState.DONE, materialization.getState());
    assertRebuildException(
        () ->
            dremioMaterializationProvider.getDescriptor(
                materialization, catalog, expansionHelperProvider, planGeneratorProvider),
        true);
    assertEquals(MaterializationState.DONE, materialization.getState());
    verify(materializationStore, times(0)).put(any(), any());
  }
}
