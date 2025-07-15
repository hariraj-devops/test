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

import com.dremio.common.util.DremioVersionInfo;
import com.dremio.common.utils.protos.AttemptId;
import com.dremio.datastore.WarningTimer;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.ops.QueryContextCreator;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.MaterializationExpander;
import com.dremio.exec.planner.acceleration.descriptor.ExpandedMaterializationDescriptor;
import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.planner.acceleration.descriptor.UnexpandedMaterializationDescriptor;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.options.OptionValue;
import com.dremio.options.OptionValue.OptionType;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.reflection.ReflectionGoalChecker;
import com.dremio.service.reflection.ReflectionSettings;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.UserSessionUtils;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Failure;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationMetrics;
import com.dremio.service.reflection.proto.MaterializationPlan;
import com.dremio.service.reflection.proto.MaterializationState;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionState;
import com.dremio.service.reflection.refresh.ReflectionPlanGenerator;
import com.dremio.service.reflection.store.DependenciesStore;
import com.dremio.service.reflection.store.ExternalReflectionStore;
import com.dremio.service.reflection.store.MaterializationPlanStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionEntriesStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import java.util.ConcurrentModificationException;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DescriptorHelperImpl implements DescriptorHelper {

  private static final Logger logger = LoggerFactory.getLogger(DescriptorHelperImpl.class);

  private final Provider<CatalogService> catalogService;
  private final Provider<SabotContext> sabotContext;
  private final MaterializationStore materializationStore;
  private final ReflectionEntriesStore internalStore;
  private final ExternalReflectionStore externalReflectionStore;
  private final ReflectionGoalsStore userStore;
  private final MaterializationPlanStore materializationPlanStore;
  private final Function<Catalog, ExpansionHelper> expansionHelper;
  private final DependenciesStore dependenciesStore;
  private final ReflectionSettings reflectionSettings;
  private final MaterializationDescriptorFactory materializationDescriptorFactory;
  private final ReflectionUtils reflectionUtils;

  public DescriptorHelperImpl(
      Provider<CatalogService> catalogService,
      Provider<SabotContext> sabotContext,
      MaterializationStore materializationStore,
      ReflectionEntriesStore internalStore,
      ExternalReflectionStore externalReflectionStore,
      ReflectionGoalsStore userStore,
      MaterializationPlanStore materializationPlanStore,
      DependenciesStore dependenciesStore,
      ReflectionSettings reflectionSettings,
      MaterializationDescriptorFactory factory,
      ReflectionUtils reflectionUtils) {
    this.catalogService = catalogService;
    this.sabotContext = sabotContext;
    this.materializationStore = materializationStore;
    this.internalStore = internalStore;
    this.externalReflectionStore = externalReflectionStore;
    this.userStore = userStore;
    this.materializationPlanStore = materializationPlanStore;
    this.dependenciesStore = dependenciesStore;
    this.reflectionSettings = reflectionSettings;
    this.materializationDescriptorFactory = factory;

    // The input catalog is expected to be shared across expansion helpers so that different
    // materializations can share the catalog metadata during expansion and plan rebuild.
    this.expansionHelper =
        catalog -> {
          SabotContext sabotCtx = sabotContext.get();
          QueryContextCreator queryCtxCreator = sabotCtx.getQueryContextCreator();
          UserSession session = UserSessionUtils.systemSession(sabotCtx.getOptionManager());
          // Explicitly disable view updates since updating a view could depend on reflections
          session
              .getSessionOptionManager()
              .setOption(
                  OptionValue.createBoolean(
                      OptionType.SESSION, PlannerSettings.VDS_AUTO_FIX.getOptionName(), false));
          QueryContext queryCtx =
              queryCtxCreator.createNewQueryContext(
                  session,
                  new AttemptId().toQueryId(),
                  null,
                  Long.MAX_VALUE,
                  Predicates.alwaysTrue(),
                  null,
                  null,
                  catalog);
          return new ExpansionHelper(queryCtx);
        };
    this.reflectionUtils = reflectionUtils;
  }

  /**
   * @return non expired DONE materializations that have at least one refresh
   */
  @Override
  public Iterable<Materialization> getValidMaterializations() {
    final long now = System.currentTimeMillis();
    return Iterables.filter(
        materializationStore.getAllDoneWhen(now),
        new Predicate<Materialization>() {
          @Override
          public boolean apply(Materialization m) {
            ReflectionEntry entry = internalStore.get(m.getReflectionId());
            return entry != null
                && entry.getState() != ReflectionState.FAILED
                && !Iterables.isEmpty(materializationStore.getRefreshes(m));
          }
        });
  }

  /**
   * @return all materializations even regardless of status
   */
  @Override
  public Iterable<Materialization> getAllMaterializations() {
    final long now = System.currentTimeMillis();
    return Iterables.filter(
        materializationStore.getAllDoneWhen(now),
        new Predicate<Materialization>() {
          @Override
          public boolean apply(Materialization m) {
            ReflectionEntry entry = internalStore.get(m.getReflectionId());
            return entry != null && !Iterables.isEmpty(materializationStore.getRefreshes(m));
          }
        });
  }

  /**
   * @param reflectionId reflection id.
   * @return all materializations even regardless of status
   */
  @Override
  public Iterable<Materialization> getAllMaterializations(ReflectionId reflectionId) {
    final long now = System.currentTimeMillis();
    return Iterables.filter(
        materializationStore.getAllDone(reflectionId, now),
        new Predicate<Materialization>() {
          @Override
          public boolean apply(Materialization m) {
            ReflectionEntry entry = internalStore.get(m.getReflectionId());
            return entry != null && !Iterables.isEmpty(materializationStore.getRefreshes(m));
          }
        });
  }

  @Override
  public Iterable<ExternalReflection> getExternalReflections() {
    return externalReflectionStore.getExternalReflections();
  }

  @Override
  public UnexpandedMaterializationDescriptor getDescriptor(
      Materialization materialization, Catalog catalog) {
    return getDescriptor(
        materialization, catalog, expansionHelper, ReflectionPlanGeneratorProvider.DEFAULT);
  }

  @VisibleForTesting
  UnexpandedMaterializationDescriptor getDescriptor(
      Materialization materialization,
      Catalog catalog,
      Function<Catalog, ExpansionHelper> expansionHelperProvider,
      ReflectionPlanGeneratorProvider provider) {

    try (WarningTimer timer =
        new WarningTimer(
            String.format(
                "Get materialization descriptor %s/%s",
                materialization.getReflectionId(), materialization.getId()),
            TimeUnit.SECONDS.toMillis(5),
            logger)) {

      final ReflectionGoal goal = userStore.get(materialization.getReflectionId());
      if (!ReflectionGoalChecker.checkGoal(goal, materialization)) {
        Materialization update = materializationStore.get(materialization.getId());
        update.setState(MaterializationState.FAILED);
        update.setFailure(
            new Failure()
                .setMessage(
                    "Reflection definition has changed and materialization is no longer valid."));
        try {
          materializationStore.save(update);
        } catch (ConcurrentModificationException e2) {
          // ignore in case another coordinator also tries to mark the materialization as failed
        }
        // reflection goal changed and corresponding materialization is no longer valid
        throw new RuntimeException(
            "Unable to expand materialization "
                + materialization.getId().getId()
                + " as it no longer matches its reflection goal");
      }

      final ReflectionEntry entry = internalStore.get(materialization.getReflectionId());

      MaterializationMetrics metrics = materializationStore.getMetrics(materialization).left;

      MaterializationPlan plan =
          this.materializationPlanStore.getVersionedPlan(materialization.getId());
      if (plan == null) {
        try (ExpansionHelper helper = expansionHelperProvider.apply(catalog)) {
          ReflectionPlanGenerator generator =
              provider.create(
                  helper,
                  catalogService.get(),
                  sabotContext.get().getConfig(),
                  goal,
                  entry,
                  materialization,
                  reflectionSettings,
                  materializationStore,
                  dependenciesStore,
                  false,
                  true);
          generator.generateNormalizedPlan();
          plan = new MaterializationPlan();
          plan.setId(MaterializationPlanStore.createMaterializationPlanId(materialization.getId()));
          plan.setMaterializationId(materialization.getId());
          plan.setReflectionId(materialization.getReflectionId());
          plan.setVersion(DremioVersionInfo.getVersion());
          plan.setLogicalPlan(generator.getSerializedMatchingInfo().getMatchingPlanBytes());
          plan.setMatchingHash(generator.getSerializedMatchingInfo().getMatchingHash());
          plan.setHashFragment(generator.getSerializedMatchingInfo().getHashFragment());
          materializationPlanStore.save(plan);
          logger.info("Successfully rebuilt plan for {}", ReflectionUtils.getId(goal));
        } catch (ConcurrentModificationException e) {
          logger.warn(
              "Plan already rebuilt by another coordinator {}", ReflectionUtils.getId(goal));
        } catch (RuntimeException e) {
          final String failureMsg =
              String.format(
                  "Unable to rebuild plan for %s. %s", ReflectionUtils.getId(goal), e.getMessage());
          throw new MaterializationExpander.RebuildPlanException(failureMsg, e);
        }
      }
      return materializationDescriptorFactory.getMaterializationDescriptor(
          goal,
          entry,
          materialization,
          plan,
          metrics.getOriginalCost(),
          catalogService.get(),
          reflectionUtils);
    }
  }

  /**
   * Expands an external reflection into an {@link ExpandedMaterializationDescriptor} which includes
   * a {@link DremioMaterialization} inside.
   */
  @Override
  public ExpandedMaterializationDescriptor expand(
      ExternalReflection externalReflection, Catalog catalog) {
    MaterializationDescriptor descriptor;
    try {
      descriptor = ReflectionUtils.getMaterializationDescriptor(externalReflection, catalog);
    } catch (NamespaceException e) {
      throw new RuntimeException("Unable to get descriptor for " + externalReflection.getName());
    }
    if (descriptor != null) {
      final DremioMaterialization expanded = expand(descriptor, catalog);
      return new ExpandedMaterializationDescriptor(descriptor, expanded);
    }
    return null;
  }

  @Override
  public ExpandedMaterializationDescriptor expand(
      Materialization materialization, Catalog catalog) {
    final MaterializationDescriptor descriptor =
        getDescriptor(
            materialization, catalog, expansionHelper, ReflectionPlanGeneratorProvider.DEFAULT);
    final DremioMaterialization expanded = expand(descriptor, catalog);
    return new ExpandedMaterializationDescriptor(descriptor, expanded);
  }

  /**
   * Expands a descriptor into an expensive {@link DremioMaterialization} which contains the
   * expanded query trees.
   */
  private DremioMaterialization expand(MaterializationDescriptor descriptor, Catalog catalog) {
    // get a new converter for each materialization. This ensures that we
    // always index flattens from zero. This is a partial fix for flatten
    // matching. We should really do a better job in matching.
    try (ExpansionHelper helper = getExpansionHelper().apply(catalog);
        WarningTimer timer =
            new WarningTimer(
                String.format(
                    "Expand materialization descriptor %s/%s",
                    descriptor.getLayoutId(), descriptor.getMaterializationId()),
                TimeUnit.SECONDS.toMillis(5),
                logger)) {
      return descriptor.getMaterializationFor(helper.getConverter());
    }
  }

  @Override
  public Function<Catalog, ExpansionHelper> getExpansionHelper() {
    return this.expansionHelper;
  }
}
