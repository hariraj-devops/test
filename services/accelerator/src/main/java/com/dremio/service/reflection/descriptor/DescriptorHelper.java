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

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.SabotConfig;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.descriptor.ExpandedMaterializationDescriptor;
import com.dremio.exec.planner.acceleration.descriptor.UnexpandedMaterializationDescriptor;
import com.dremio.exec.planner.observer.AbstractAttemptObserver;
import com.dremio.exec.planner.observer.AttemptObservers;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.server.MaterializationDescriptorProvider;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.reflection.ReflectionSettings;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.refresh.ReflectionPlanGenerator;
import com.dremio.service.reflection.store.DependenciesStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

/**
 * DescriptorHelper provides helpers for working with materialization descriptors. {@link
 * MaterializationCache}, {@link MaterializationDescriptorProvider} and {@link
 * com.dremio.service.reflection.refresh.RefreshDoneHandler} all depend on this interface.
 */
public interface DescriptorHelper {

  /** Returns materializations that are valid for planning */
  Iterable<Materialization> getValidMaterializations();

  /** Returns all materializations even if they are not valid for planning */
  Iterable<Materialization> getAllMaterializations();

  /** Returns all materializations even if they are not valid for planning for a given reflection */
  Iterable<Materialization> getAllMaterializations(ReflectionId reflectionId);

  /** Returns external reflections. */
  Iterable<ExternalReflection> getExternalReflections();

  /** Returns a raw or agg reflection's descriptor */
  UnexpandedMaterializationDescriptor getDescriptor(
      Materialization materialization, Catalog catalog);

  /**
   * Expands an external reflection into an {@link ExpandedMaterializationDescriptor} which includes
   * a {@link DremioMaterialization} inside.
   */
  ExpandedMaterializationDescriptor expand(ExternalReflection externalReflection, Catalog catalog);

  /**
   * Expands a non-external reflection into an {@link ExpandedMaterializationDescriptor} which
   * includes a {@link DremioMaterialization} inside.
   */
  ExpandedMaterializationDescriptor expand(Materialization materialization, Catalog catalog);

  /** Returns the mighty expansion helper than can produce query trees for reflections */
  Function<Catalog, ExpansionHelper> getExpansionHelper();

  /**
   * Materialization expansion helper that takes care of releasing the query context when closed.
   * Caller must close the helper when done using the converter
   */
  class ExpansionHelper implements AutoCloseable {

    private final QueryContext context;
    private final SqlConverter converter;

    public ExpansionHelper(QueryContext context) {
      this.context = Preconditions.checkNotNull(context, "query context required");
      converter =
          new SqlConverter(
              context.getPlannerSettings(),
              context.getOperatorTable(),
              context,
              MaterializationDescriptorProvider.EMPTY,
              context.getFunctionRegistry(),
              context.getSession(),
              AbstractAttemptObserver.NOOP,
              context.getSubstitutionProviderFactory(),
              context.getConfig(),
              context.getScanResult(),
              context.getRelMetadataQuerySupplier());
    }

    public SqlConverter getConverter() {
      return converter;
    }

    @Override
    public void close() {
      converter.dispose();
      AutoCloseables.closeNoChecked(context);
    }

    public QueryContext getContext() {
      return context;
    }
  }

  // Used for testing
  interface ReflectionPlanGeneratorProvider {

    ReflectionPlanGenerator create(
        ExpansionHelper helper,
        CatalogService catalogService,
        SabotConfig sabotConfig,
        ReflectionGoal goal,
        ReflectionEntry entry,
        Materialization materialization,
        ReflectionSettings reflectionSettings,
        MaterializationStore materializationStore,
        DependenciesStore dependenciesStore,
        boolean forceFullUpdate,
        boolean isRebuildPlan);

    ReflectionPlanGeneratorProvider DEFAULT =
        (ExpansionHelper helper,
            CatalogService catalogService,
            SabotConfig sabotConfig,
            ReflectionGoal goal,
            ReflectionEntry entry,
            Materialization materialization,
            ReflectionSettings reflectionSettings,
            MaterializationStore materializationStore,
            DependenciesStore dependenciesStore,
            boolean forceFullUpdate,
            boolean isRebuildPlan) -> {
          SqlHandlerConfig sqlHandlerConfig =
              new SqlHandlerConfig(
                  helper.getContext(), helper.getConverter(), AttemptObservers.of(), null);
          return new ReflectionPlanGenerator(
              sqlHandlerConfig,
              catalogService,
              sabotConfig,
              goal,
              entry,
              materialization,
              reflectionSettings,
              materializationStore,
              dependenciesStore,
              forceFullUpdate,
              isRebuildPlan);
        };
  }
}
