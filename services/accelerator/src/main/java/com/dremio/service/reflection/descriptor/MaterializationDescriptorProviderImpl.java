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

import static com.dremio.dac.options.ReflectionUserOptions.REFLECTION_ENABLE_SUBSTITUTION;
import static com.dremio.service.reflection.ReflectionOptions.MATERIALIZATION_CACHE_ENABLED;
import static com.google.common.base.Predicates.notNull;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.acceleration.descriptor.ExpandedMaterializationDescriptor;
import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.MaterializationDescriptorProvider;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.sys.SystemTable;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.exec.store.sys.accel.AccelerationListManager.MaterializationInfo;
import com.dremio.options.OptionManager;
import com.dremio.service.accelerator.AccelerationMaterializationUtils;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.reflection.ReflectionOptions;
import com.dremio.service.reflection.ReflectionStatusService;
import com.dremio.service.reflection.ReflectionUtils;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.ReflectionField;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.vector.types.pojo.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of MaterializationDescriptorProvider that provides materialization descriptors to
 * the planner for substitution. This implementation depends on {@link DescriptorHelper} and {@link
 * MaterializationCache}. When {@link MaterializationCache} is enabled, then expanded
 * materialization descriptors are provided. Expanded means that the descriptor has already been
 * de-serialized into query trees so that we can skip this step at the time of planning. Otherwise,
 * unexpanded descriptors are returned and planning would take a huge performance hit to expand each
 * descriptor on use.
 */
public class MaterializationDescriptorProviderImpl implements MaterializationDescriptorProvider {

  private static final Logger logger =
      LoggerFactory.getLogger(MaterializationDescriptorProviderImpl.class);

  private static final Comparator<MaterializationDescriptor> JOB_START_COMPARATOR =
      Comparator.comparingLong(MaterializationDescriptor::getJobStart);

  private final MaterializationCache materializationCache;
  private final DescriptorHelper provider;
  private final OptionManager optionManager;
  private final Provider<CatalogService> catalogService;
  private final Provider<SabotContext> sabotContext;
  private final MaterializationStore materializationStore;
  private final ReflectionGoalsStore userStore;
  private final ReflectionStatusService reflectionStatusService;

  public MaterializationDescriptorProviderImpl(
      MaterializationCache materializationCache,
      DescriptorHelper provider,
      OptionManager optionManager,
      Provider<CatalogService> catalogService,
      Provider<SabotContext> sabotContext,
      MaterializationStore materializationStore,
      ReflectionGoalsStore userStore,
      ReflectionStatusService reflectionStatusService) {
    this.materializationCache = materializationCache;
    this.provider = provider;
    this.optionManager = optionManager;
    this.catalogService = catalogService;
    this.sabotContext = sabotContext;
    this.materializationStore = materializationStore;
    this.userStore = userStore;
    this.reflectionStatusService = reflectionStatusService;
  }

  @Override
  public List<MaterializationDescriptor> get() {

    if (!isSubstitutionEnabled()) {
      return Collections.emptyList();
    }

    final long currentTime = System.currentTimeMillis();
    FluentIterable<MaterializationDescriptor> descriptors;
    if (isCacheEnabled()) {
      descriptors =
          FluentIterable.from(materializationCache.getAll())
              .filter(
                  new Predicate<>() {
                    @Override
                    public boolean apply(MaterializationDescriptor descriptor) {
                      return descriptor.getExpirationTimestamp() > currentTime;
                    }
                  });
    } else {
      final Catalog catalog =
          CatalogUtil.getSystemCatalogForMaterializationCache(catalogService.get());
      descriptors =
          FluentIterable.from(provider.getValidMaterializations())
              .transform(
                  new Function<>() {
                    @Override
                    public MaterializationDescriptor apply(Materialization m) {
                      try {
                        // we don't need to expand here, but we do so to be able to update
                        // reflections when we fail to expand
                        // their materializations
                        return provider.expand(m, catalog);
                      } catch (Exception e) {
                        logger.warn("couldn't expand materialization {}", m.getId().getId(), e);
                        return null;
                      }
                    }
                  });

      Iterable<MaterializationDescriptor> externalDescriptors =
          FluentIterable.from(provider.getExternalReflections())
              .transform(
                  new Function<>() {
                    @Nullable
                    @Override
                    public MaterializationDescriptor apply(ExternalReflection externalReflection) {
                      try {
                        return ReflectionUtils.getMaterializationDescriptor(
                            externalReflection, catalog);
                      } catch (Exception e) {
                        logger.debug(
                            "failed to get MaterializationDescriptor for external reflection {}",
                            externalReflection.getName());
                        return null;
                      }
                    }
                  });

      descriptors = descriptors.append(externalDescriptors).filter(notNull());
    }

    if (Iterables.isEmpty(descriptors)) {
      return Collections.emptyList();
    }

    // group the materializations by reflectionId and keep one by reflection
    final ImmutableListMultimap<ReflectionId, MaterializationDescriptor> descriptorMap =
        descriptors.index(
            new Function<>() {
              @Override
              public ReflectionId apply(MaterializationDescriptor m) {
                return new ReflectionId(m.getLayoutId());
              }
            });
    // for each reflection, get latest materialization
    final Ordering<MaterializationDescriptor> ordering = Ordering.from(JOB_START_COMPARATOR);
    return FluentIterable.from(descriptorMap.keySet())
        .transform(reflectionId -> ordering.max(descriptorMap.get(reflectionId)))
        .toList();
  }

  /**
   * Checks if default raw reflection has been enabled/disabled on the VDS. Uses the same caching
   * catalog from planning so table metadata should already be in cache.
   */
  private boolean isDefaultReflectionEnabled(DremioTable viewTable) {
    DatasetConfig datasetConfig = viewTable.getDatasetConfig();
    if (!datasetConfig.getType().equals(DatasetType.VIRTUAL_DATASET)
        || datasetConfig.getVirtualDataset() == null) {
      return false;
    }
    return Optional.ofNullable(datasetConfig.getVirtualDataset().getDefaultReflectionEnabled())
        .orElse(true);
  }

  @Override
  public java.util.Optional<MaterializationDescriptor> getDefaultRawMaterialization(
      ViewTable viewTable) {

    if (isSubstitutionEnabled()) {
      try {
        final BatchSchema batchSchema = viewTable.getSchema();
        final List<String> vdsFields =
            batchSchema == null
                ? new ArrayList<>()
                : batchSchema.getFields().stream()
                    .map(Field::getName)
                    .sorted()
                    .collect(Collectors.toList());

        for (ReflectionGoal goal :
            userStore.getByDatasetId(viewTable.getDatasetConfig().getId().getId())) {
          if (goal.getType() == ReflectionType.RAW) {
            List<String> displayFields =
                goal.getDetails().getDisplayFieldList().stream()
                    .map(ReflectionField::getName)
                    .sorted()
                    .collect(Collectors.toList());
            if (displayFields.equals(vdsFields)) {
              final long currentTime = System.currentTimeMillis();

              Materialization m = materializationStore.getLastMaterializationDone(goal.getId());
              if (m == null || Iterables.isEmpty(materializationStore.getRefreshes(m))) {
                continue;
              }
              ExpandedMaterializationDescriptor descriptor = null;
              // First try to get the descriptor from the materialization cache
              if (isCacheEnabled()) {
                descriptor =
                    (ExpandedMaterializationDescriptor) materializationCache.get(m.getId());
                // Check that materialization has not expired and all partitions are online
                // (deprecated)
                if (descriptor != null && descriptor.getExpirationTimestamp() < currentTime) {
                  descriptor = null;
                }
              }
              // If not found because cache is disabled or not online yet, then expand it on the
              // fly
              if (descriptor == null) {
                try {
                  descriptor =
                      provider.expand(
                          m,
                          CatalogUtil.getSystemCatalogForMaterializationCache(
                              catalogService.get()));
                } catch (Exception e) {
                  logger.warn("Couldn't expand materialization {}", m.getId().getId(), e);
                  continue; // There may be other DRRs for the same view that are available
                }
              }
              if (descriptor != null) {
                // We expect the feature to be enabled in the majority of cases. So we want to
                // wait until
                // we know that there are default reflections available for the dataset. This way
                // we avoid
                // hitting the namspace for every dataset in the tree, even if the dataset doesn't
                // have any
                // reflections
                if (!isDefaultReflectionEnabled(viewTable)) {
                  return java.util.Optional.empty();
                }
                if (!(descriptor.getMaterialization().getIncrementalUpdateSettings().isIncremental()
                        && descriptor.getMaterialization().hasAgg())
                    || optionManager.getOption(
                        ReflectionOptions.ENABLE_INCREMENTAL_DEFAULT_RAW_REFLECTIONS_WITH_AGGS)) {
                  // Do not apply default reflections for incremental refresh if there is an agg
                  // in the query plan
                  // unless we have the support key enabled.
                  return java.util.Optional.of(descriptor);
                }
              }
            }
          }
        }
      } catch (Exception ex) {
        if (ex.getCause() instanceof NamespaceNotFoundException) {
          logger.debug(
              "Error while expanding view with path {}: {}",
              viewTable.getPath().getSchemaPath(),
              ex.getMessage());
        } else {
          Throwables.propagate(ex);
        }
      }
    }
    return java.util.Optional.empty();
  }

  @Override
  public boolean hasDefaultRawReflection(ViewTable viewTable) {
    final BatchSchema batchSchema = viewTable.getSchema();
    final List<String> vdsFields =
        Optional.ofNullable(batchSchema)
            .map(
                schema ->
                    schema.getFields().stream()
                        .map(Field::getName)
                        .sorted()
                        .collect(Collectors.toList()))
            .orElseGet(ArrayList::new);

    for (ReflectionGoal goal :
        userStore.getByDatasetId(viewTable.getDatasetConfig().getId().getId())) {
      if (goal.getState() == ReflectionGoalState.ENABLED && goal.getType() == ReflectionType.RAW) {
        List<String> displayFields =
            goal.getDetails().getDisplayFieldList().stream()
                .map(ReflectionField::getName)
                .sorted()
                .collect(Collectors.toList());
        if (displayFields.equals(vdsFields)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public boolean isMaterializationCacheInitialized() {
    return materializationCache.isInitialized();
  }

  private boolean isSubstitutionEnabled() {
    return optionManager.getOption(REFLECTION_ENABLE_SUBSTITUTION);
  }

  private boolean isCacheEnabled() {
    return optionManager.getOption(MATERIALIZATION_CACHE_ENABLED);
  }

  @Override
  public List<String> dumpMaterializationStateToJson() {
    List<String> dump = new ArrayList<>();
    final ObjectWriter reflectionWriter =
        new ObjectMapper().writerFor(AccelerationListManager.ReflectionInfo.class);
    final StringBuilder builder = new StringBuilder();
    builder.append(SystemTable.REFLECTIONS.getDatasetPath()).append(System.lineSeparator());
    Streams.stream(reflectionStatusService.getReflections())
        .forEach(
            x -> {
              try {
                builder
                    .append(reflectionWriter.writeValueAsString(x))
                    .append(System.lineSeparator());
              } catch (IOException e) {
                logger.error("Unable to serialize reflection={}", x.reflection_id, e);
              }
            });
    dump.add(builder.toString());
    builder.setLength(0);
    builder.append(SystemTable.MATERIALIZATIONS.getDatasetPath()).append(System.lineSeparator());
    final ObjectWriter materializationWriter =
        new ObjectMapper().writerFor(MaterializationInfo.class);
    Iterator<MaterializationInfo> materializationInfos =
        AccelerationMaterializationUtils.getMaterializationsFromStore(materializationStore);
    Streams.stream(materializationInfos)
        .forEach(
            x -> {
              try {
                builder
                    .append(materializationWriter.writeValueAsString(x))
                    .append(System.lineSeparator());
              } catch (IOException e) {
                logger.error("Unable to serialize materialization={}", x.materialization_id, e);
              }
            });
    dump.add(builder.toString());
    return dump;
  }
}
