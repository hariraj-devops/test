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

import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.ops.ReflectionContext;
import com.dremio.exec.planner.AccelerationDetailsPopulator;
import com.dremio.exec.planner.sql.PartitionTransform;
import com.dremio.exec.planner.sql.SchemaUtilities;
import com.dremio.exec.planner.sql.parser.SqlCreateReflection;
import com.dremio.exec.planner.sql.parser.SqlCreateReflection.MeasureType;
import com.dremio.exec.planner.sql.parser.SqlCreateReflection.NameAndMeasures;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.sys.accel.AccelerationManager;
import com.dremio.exec.store.sys.accel.LayoutDefinition;
import com.dremio.exec.store.sys.accel.LayoutDefinition.Type;
import com.dremio.service.accelerator.AccelerationUtils;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.proto.BucketTransform;
import com.dremio.service.reflection.proto.DimensionGranularity;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.PartitionDistributionStrategy;
import com.dremio.service.reflection.proto.ReflectionDetails;
import com.dremio.service.reflection.proto.ReflectionDimensionField;
import com.dremio.service.reflection.proto.ReflectionField;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionMeasureField;
import com.dremio.service.reflection.proto.ReflectionPartitionField;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.proto.Transform;
import com.dremio.service.reflection.proto.TruncateTransform;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.inject.Provider;

/** Exposes the acceleration manager interface to the rest of the system (coordinator side) */
public class AccelerationManagerImpl implements AccelerationManager {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(AccelerationManagerImpl.class);

  private final Provider<ReflectionAdministrationService.Factory>
      reflectionAdministrationServiceFactory;
  private final Provider<ReflectionService> reflectionService;
  private final Provider<CatalogService> catalogService;

  public AccelerationManagerImpl(
      Provider<ReflectionService> reflectionService,
      Provider<ReflectionAdministrationService.Factory> reflectionAdministrationServiceFactory,
      Provider<CatalogService> catalogService) {
    super();
    this.reflectionService = reflectionService;
    this.catalogService = catalogService;
    this.reflectionAdministrationServiceFactory = reflectionAdministrationServiceFactory;
  }

  @Override
  @Deprecated
  public ExcludedReflectionsProvider getExcludedReflectionsProvider() {
    return reflectionService.get().getExcludedReflectionsProvider(false);
  }

  @Override
  public void dropAcceleration(List<String> path, boolean raiseErrorIfNotFound) {}

  @Override
  public void addLayout(
      SchemaUtilities.TableWithPath tableWithPath,
      LayoutDefinition definition,
      ReflectionContext reflectionContext) {
    final NamespaceKey key = new NamespaceKey(tableWithPath.getPath());
    final DatasetConfig datasetConfig;
    final EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    String datasetId = null;
    try {
      datasetId = tableWithPath.getTable().getDatasetConfig().getId().getId();
      datasetConfig = CatalogUtil.getDatasetConfig(catalog, datasetId);
      if (datasetConfig == null) {
        throw UserException.validationError()
            .message("Unable to find requested dataset %s.", key)
            .build(logger);
      }
    } catch (Exception e) {
      throw UserException.validationError(e)
          .message("Unable to find requested dataset %s.", key)
          .build(logger);
    }
    validateReflectionSupportForTimeTravelOnVersionedSource(
        tableWithPath.getPath().get(0), datasetId, (Catalog) catalog);

    ReflectionGoal goal = new ReflectionGoal();
    ReflectionDetails details = new ReflectionDetails();
    goal.setDetails(details);
    details.setDimensionFieldList(toDimensionFields(definition.getDimension()));
    details.setDisplayFieldList(toDescriptor(definition.getDisplay()));
    details.setDistributionFieldList(toDescriptor(definition.getDistribution()));
    details.setMeasureFieldList(toMeasureFields(definition.getMeasure()));
    final List<ReflectionPartitionField> reflectionPartitionFields = new ArrayList<>();
    for (final PartitionTransform partitionTransform : definition.getPartition()) {
      final List<String> fieldName = new ArrayList<>();
      fieldName.add(partitionTransform.getColumnName());
      List<ReflectionField> reflectionFields = toDescriptor(fieldName);
      ReflectionField reflectionField = reflectionFields.get(0);
      ReflectionPartitionField reflectionPartitionField =
          new ReflectionPartitionField(reflectionField.getName());
      Transform transform = buildProtoTransformFromPartitionTransform(partitionTransform);
      reflectionPartitionField.setTransform(transform);
      reflectionPartitionFields.add(reflectionPartitionField);
    }
    details.setPartitionFieldList(reflectionPartitionFields);
    details.setSortFieldList(toDescriptor(definition.getSort()));
    details.setPartitionDistributionStrategy(
        definition.getPartitionDistributionStrategy()
                == com.dremio.exec.planner.sql.parser.PartitionDistributionStrategy.STRIPED
            ? PartitionDistributionStrategy.STRIPED
            : PartitionDistributionStrategy.CONSOLIDATED);

    goal.setName(definition.getName());
    goal.setState(ReflectionGoalState.ENABLED);
    goal.setType(
        definition.getType() == Type.AGGREGATE ? ReflectionType.AGGREGATION : ReflectionType.RAW);
    goal.setDatasetId(datasetConfig.getId().getId());

    reflectionAdministrationServiceFactory.get().get(reflectionContext).create(goal);
  }

  /**
   * Given a PartitionTransform convert it to equivalent proto.Transform
   *
   * @param partitionTransform partition transform to convert
   * @return proto.Transform equivalent to the passed in PartitionTransform
   */
  public Transform buildProtoTransformFromPartitionTransform(
      PartitionTransform partitionTransform) {
    Transform transform = new Transform();
    transform.setType(Transform.Type.valueOf(partitionTransform.getType().toString()));
    switch (transform.getType()) {
      case BUCKET:
        Integer count = partitionTransform.getArgumentValue(0, Integer.class);
        BucketTransform bucketTransform = new BucketTransform();
        bucketTransform.setBucketCount(count);
        transform.setBucketTransform(bucketTransform);
        break;
      case TRUNCATE:
        Integer length = partitionTransform.getArgumentValue(0, Integer.class);
        TruncateTransform truncateTransform = new TruncateTransform();
        truncateTransform.setTruncateLength(length);
        transform.setTruncateTransform(truncateTransform);
        break;
      case IDENTITY:
      case YEAR:
      case MONTH:
      case DAY:
      case HOUR:
        // do nothing, transform.setType is already called above so type is set correctly
        // no arguments are needed for the transforms in this list
        break;
      default:
        throw new RuntimeException(
            String.format("Unsupported partition transform type %s.", transform.getType()));
    }
    return transform;
  }

  private List<ReflectionDimensionField> toDimensionFields(
      List<SqlCreateReflection.NameAndGranularity> fields) {
    return FluentIterable.from(AccelerationUtils.selfOrEmpty(fields))
        .transform(
            new Function<SqlCreateReflection.NameAndGranularity, ReflectionDimensionField>() {
              @Nullable
              @Override
              public ReflectionDimensionField apply(
                  @Nullable final SqlCreateReflection.NameAndGranularity input) {
                final DimensionGranularity granularity =
                    input.getGranularity() == SqlCreateReflection.Granularity.BY_DAY
                        ? DimensionGranularity.DATE
                        : DimensionGranularity.NORMAL;
                return new ReflectionDimensionField()
                    .setName(input.getName())
                    .setGranularity(granularity);
              }
            })
        .toList();
  }

  private List<ReflectionField> toDescriptor(List<String> fields) {
    if (fields == null) {
      return ImmutableList.of();
    }

    return FluentIterable.from(fields)
        .transform(
            new Function<String, ReflectionField>() {

              @Override
              public ReflectionField apply(String input) {
                return new ReflectionField(input);
              }
            })
        .toList();
  }

  private List<ReflectionMeasureField> toMeasureFields(List<NameAndMeasures> fields) {
    if (fields == null) {
      return ImmutableList.of();
    }

    return FluentIterable.from(fields)
        .transform(
            new Function<NameAndMeasures, ReflectionMeasureField>() {

              @Override
              public ReflectionMeasureField apply(NameAndMeasures input) {
                return new ReflectionMeasureField(input.getName())
                    .setMeasureTypeList(
                        input.getMeasureTypes().stream()
                            .map(AccelerationManagerImpl::toMeasureType)
                            .collect(Collectors.toList()));
              }
            })
        .toList();
  }

  private static com.dremio.service.reflection.proto.MeasureType toMeasureType(MeasureType t) {
    switch (t) {
      case APPROX_COUNT_DISTINCT:
        return com.dremio.service.reflection.proto.MeasureType.APPROX_COUNT_DISTINCT;
      case COUNT:
        return com.dremio.service.reflection.proto.MeasureType.COUNT;
      case MAX:
        return com.dremio.service.reflection.proto.MeasureType.MAX;
      case MIN:
        return com.dremio.service.reflection.proto.MeasureType.MIN;
      case SUM:
        return com.dremio.service.reflection.proto.MeasureType.SUM;
      case UNKNOWN:
      default:
        throw new UnsupportedOperationException(t.name());
    }
  }

  @Override
  public void addExternalReflection(
      String name,
      List<String> table,
      List<String> targetTable,
      ReflectionContext reflectionContext) {
    reflectionAdministrationServiceFactory
        .get()
        .get(reflectionContext)
        .createExternalReflection(name, table, targetTable);
  }

  @Override
  public void dropLayout(
      SchemaUtilities.TableWithPath tableWithPath,
      final String layoutIdOrName,
      ReflectionContext reflectionContext) {
    ReflectionAdministrationService administrationReflectionService =
        reflectionAdministrationServiceFactory.get().get(reflectionContext);
    for (ReflectionGoal rg :
        administrationReflectionService.getReflectionsByDatasetId(
            tableWithPath.getTable().getDatasetConfig().getId().getId())) {
      if (rg.getId().getId().equals(layoutIdOrName) || layoutIdOrName.equals(rg.getName())) {
        administrationReflectionService.remove(rg, ChangeCause.SQL_DROP_BY_USER_CAUSE);
        // only match first and exist.
        return;
      }
    }

    Optional<ExternalReflection> er =
        StreamSupport.stream(
                administrationReflectionService
                    .getExternalReflectionByDatasetPath(tableWithPath.getPath())
                    .spliterator(),
                false)
            .filter(
                externalReflection -> {
                  return layoutIdOrName.equalsIgnoreCase(externalReflection.getName())
                      || layoutIdOrName.equals(externalReflection.getId());
                })
            .findFirst();

    if (er.isPresent()) {
      administrationReflectionService.dropExternalReflection(er.get().getId());
      return;
    }
    throw UserException.validationError().message("No matching reflection found.").build(logger);
  }

  @Override
  public void toggleAcceleration(
      SchemaUtilities.TableWithPath tableWithPath,
      Type type,
      boolean enable,
      ReflectionContext reflectionContext) {
    Exception ex = null;
    ReflectionAdministrationService administrationReflectionService =
        reflectionAdministrationServiceFactory.get().get(reflectionContext);

    for (ReflectionGoal g :
        administrationReflectionService.getReflectionsByDatasetId(
            tableWithPath.getTable().getDatasetConfig().getId().getId())) {
      if ((type == Type.AGGREGATE && g.getType() != ReflectionType.AGGREGATION)
          || (type == Type.RAW && g.getType() != ReflectionType.RAW)
          || (g.getState() == ReflectionGoalState.ENABLED && enable)
          || (g.getState() == ReflectionGoalState.DISABLED && !enable)) {
        continue;
      }

      try {
        g.setState(enable ? ReflectionGoalState.ENABLED : ReflectionGoalState.DISABLED);
        administrationReflectionService.update(g, ChangeCause.REST_UPDATE_BY_USER_CAUSE);
      } catch (Exception e) {
        if (ex == null) {
          ex = e;
        } else {
          ex.addSuppressed(e);
        }
      }
    }

    if (ex != null) {
      throw UserException.validationError(ex)
          .message("Unable to toggle acceleration, already in requested state.")
          .build(logger);
    }
  }

  @Override
  public void replanlayout(String layoutId) {}

  @Override
  public AccelerationDetailsPopulator newPopulator() {
    return new ReflectionDetailsPopulatorImpl(reflectionService.get(), catalogService.get());
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (ReflectionService.class.isAssignableFrom(clazz)) {
      return (T) reflectionService.get();
    }
    if (ReflectionAdministrationService.Factory.class.isAssignableFrom(clazz)) {
      return (T) reflectionAdministrationServiceFactory.get();
    }
    return null;
  }

  // This helper is to determine if there ia TIMESTAMP specified on an Versioned table.
  // We want to disallow AT TIMESTAMP specifiication when creating reflections. This is because the
  // VersionDatasetId
  // will not contain the branch information if we allow this. So when we later go to lookup the
  // table using the saved VersionedDatasetId,
  // we won't have the branch information to lookup the table in Nessie and will always lookup only
  // in the default branch.
  // Eg if the currrent context is dev and we are creating a reflection AT TIMESTAMP T1 , the table
  // is resolved to <dev + T1>.
  // Later when we lookup the table, we  don't save the 'dev' branch context in it, so we will
  // lookup the tableat <default branch + T1>.
  private void validateReflectionSupportForTimeTravelOnVersionedSource(
      String source, String datasetId, Catalog catalog) {
    VersionedDatasetId versionedDatasetId = VersionedDatasetId.tryParse(datasetId);
    if (versionedDatasetId == null) {
      // Assume this is a non versioned dataset id .
      return;
    }
    if ((CatalogUtil.requestedPluginSupportsVersionedTables(source, catalog))
        && versionedDatasetId.getVersionContext().getType() == TableVersionType.TIMESTAMP) {
      throw UserException.validationError()
          .message(
              "Cannot create reflection on versioned table or view with TIMESTAMP specified. Please use BRANCH, TAG or COMMIT instead.")
          .build(logger);
    }
  }
}
