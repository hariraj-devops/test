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
package com.dremio.exec.catalog;

import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET;
import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET_HOME_FILE;
import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET_HOME_FOLDER;
import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET_SOURCE_FILE;
import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER;
import static com.google.common.base.Preconditions.checkArgument;

import com.dremio.catalog.exception.SourceDoesNotExistException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.common.exceptions.IcebergTableNotFoundException;
import com.dremio.common.exceptions.UserException;
import com.dremio.connector.metadata.DatasetMetadataVerifyResult;
import com.dremio.connector.metadata.DatasetSplit;
import com.dremio.connector.metadata.DatasetVerifyAppendOnlyResult;
import com.dremio.connector.metadata.DatasetVerifyDataModifiedResult;
import com.dremio.connector.metadata.PartitionChunk;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.options.MetadataVerifyRequest;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.connector.metadata.options.VerifyAppendOnlyRequest;
import com.dremio.connector.metadata.options.VerifyDataModifiedRequest;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.NoDefaultBranchException;
import com.dremio.exec.store.ReferenceTypeConflictException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.UnAuthenticatedException;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.source.SourceNamespaceService;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.orphanage.proto.OrphanEntry;
import com.dremio.service.users.SystemUser;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import org.projectnessie.model.ContentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatalogUtil {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogUtil.class);

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CatalogUtil.class);

  private CatalogUtil() {}

  /**
   * Save a partition chunk listing that refers to all partition chunks since the last invocation of
   * {@link #savePartitionChunksInSplitsStores(DatasetMetadataSaver, PartitionChunkListing)}, or
   * since the creation of this metadata saver, whichever came last. Also calculates to total number
   * of records across every split and every partition chunk listed.
   *
   * @param chunkListing The partition chunks to save.
   * @return The total record count of all splits in chunkListing.
   */
  public static long savePartitionChunksInSplitsStores(
      DatasetMetadataSaver saver, PartitionChunkListing chunkListing) throws IOException {
    long recordCountFromSplits = 0;
    final Iterator<? extends PartitionChunk> chunks = chunkListing.iterator();
    while (chunks.hasNext()) {
      final PartitionChunk chunk = chunks.next();

      final Iterator<? extends DatasetSplit> splits = chunk.getSplits().iterator();
      while (splits.hasNext()) {
        final DatasetSplit split = splits.next();
        saver.saveDatasetSplit(split);
        recordCountFromSplits += split.getRecordCount();
      }
      saver.savePartitionChunk(chunk);
    }
    return recordCountFromSplits;
  }

  public static boolean supportsInterface(
      NamespaceKey namespaceKey, Catalog catalog, Class<?> clazz) {
    try {
      return catalog.getSource(namespaceKey.getRoot()).isWrapperFor(clazz);
    } catch (UserException ignored) {
      // Source not found
      return false;
    }
  }

  @Deprecated(
      since = "Catalog should be the only one running this business logic",
      forRemoval = true)
  public static boolean requestedPluginSupportsVersionedTables(
      NamespaceKey key, SourceCatalog catalog) {
    return requestedPluginSupportsVersionedTables(key.getRoot(), catalog);
  }

  /**
   * Rather than allowing anyone to call into this method, Catalog itself should be the one handling
   * this sort of check. All calls leading into this method should eventually be updated such that
   * it doesn't need to call this method.
   *
   * <p>Move this to Catalog itself, perhaps?
   */
  @Deprecated(
      since = "Catalog should be the only one running this business logic",
      forRemoval = true)
  public static boolean requestedPluginSupportsVersionedTables(
      String sourceName, SourceCatalog catalog) {
    try {
      return catalog.getSource(sourceName) != null
          && catalog.getSource(sourceName).isWrapperFor(VersionedPlugin.class);
    } catch (UserException ignored) {
      // Source not found
      return false;
    }
  }

  public static String getDefaultBranch(String sourceName, SourceCatalog catalog) {
    if (!requestedPluginSupportsVersionedTables(sourceName, catalog)) {
      return null;
    }
    try {
      VersionedPlugin versionedPlugin = catalog.getSource(sourceName);
      return (versionedPlugin == null ? null : versionedPlugin.getDefaultBranch().getRefName());
    } catch (NoDefaultBranchException e1) {
      throw UserException.validationError(e1)
          .message("Unable to get default branch for Source %s", sourceName)
          .buildSilently();
    } catch (UnAuthenticatedException e2) {
      throw UserException.resourceError(e2)
          .message(
              "Unable to get default branch.  Unable to authenticate to the Nessie server. "
                  + "Make sure that the token is valid and not expired.")
          .build();
    }
  }

  public static ResolvedVersionContext resolveVersionContext(
      Catalog catalog, String sourceName, VersionContext version) {
    if (!requestedPluginSupportsVersionedTables(sourceName, catalog)) {
      return null;
    }
    try {
      return catalog.resolveVersionContext(sourceName, version);
    } catch (VersionNotFoundInNessieException e) {
      throw UserException.validationError(e)
          .message("Requested %s not found in source %s.", version, sourceName)
          .buildSilently();
    } catch (ReferenceTypeConflictException e) {
      throw UserException.validationError(e)
          .message("Requested %s in source %s is not the requested type.", version, sourceName)
          .buildSilently();
    }
  }

  public static boolean hasIcebergMetadata(DatasetConfig datasetConfig) {
    if (datasetConfig.getPhysicalDataset() != null) {
      if (datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled() != null
          && datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled()
          && datasetConfig.getPhysicalDataset().getIcebergMetadata() != null) {
        return true;
      }
    }
    return false;
  }

  public static OrphanEntry.Orphan createIcebergMetadataOrphan(DatasetConfig datasetConfig) {
    String tableUuid = datasetConfig.getPhysicalDataset().getIcebergMetadata().getTableUuid();
    OrphanEntry.OrphanIcebergMetadata icebergOrphan =
        OrphanEntry.OrphanIcebergMetadata.newBuilder()
            .setIcebergTableUuid(tableUuid)
            .setDatasetTag(datasetConfig.getTag())
            .addAllDatasetFullPath(datasetConfig.getFullPathList())
            .build();
    long currTime = System.currentTimeMillis();
    return OrphanEntry.Orphan.newBuilder()
        .setOrphanType(OrphanEntry.OrphanType.ICEBERG_METADATA)
        .setCreatedAt(currTime)
        .setScheduledAt(currTime)
        .setOrphanDetails(icebergOrphan.toByteString())
        .build();
  }

  public static void addIcebergMetadataOrphan(DatasetConfig datasetConfig, Orphanage orphanage) {

    if (hasIcebergMetadata(datasetConfig)) {
      OrphanEntry.Orphan orphanEntry = createIcebergMetadataOrphan(datasetConfig);
      addIcebergMetadataOrphan(orphanEntry, orphanage);
    }
  }

  public static void addIcebergMetadataOrphan(OrphanEntry.Orphan orphanEntry, Orphanage orphanage) {
    orphanage.addOrphan(orphanEntry);
  }

  public static SourceNamespaceService.DeleteCallback getDeleteCallback(Orphanage orphanage) {
    SourceNamespaceService.DeleteCallback deleteCallback =
        (DatasetConfig datasetConfig) -> {
          addIcebergMetadataOrphan(datasetConfig, orphanage);
        };
    return deleteCallback;
  }

  public static void validateResolvedVersionIsBranch(
      ResolvedVersionContext resolvedVersionContext) {
    if ((resolvedVersionContext != null) && !resolvedVersionContext.isBranch()) {
      throw UserException.validationError()
          .message(
              "DDL and DML operations are only supported for branches - not on tags or commits. %s is not a branch. ",
              resolvedVersionContext.getRefName())
          .buildSilently();
    }
  }

  public static boolean isFSInternalIcebergTableOrJsonTableOrMongo(
      SourceCatalog catalog, NamespaceKey path, DatasetConfig dataset) {
    StoragePlugin storagePlugin;
    try {
      storagePlugin = catalog.getSource(path.getRoot());
    } catch (UserException uex) {
      throw UserException.validationError().message("Source [%s] not found", path).buildSilently();
    }

    if (!(storagePlugin instanceof MutablePlugin)) {
      return false;
    }

    return ((MutablePlugin) storagePlugin).isSupportUserDefinedSchema(dataset);
  }

  /**
   * Utility to return TimeTravelRequest for query : select * from iceberg_table AT
   * SNAPSHOT/TIMESTAMP
   */
  public static @Nullable TimeTravelOption.TimeTravelRequest getIcebergTimeTravelRequest(
      NamespaceKey key, TableVersionContext context) {
    switch (context.getType()) {
      case SNAPSHOT_ID:
        return TimeTravelOption.newSnapshotIdRequest(context.getValueAs(String.class));
      case TIMESTAMP:
        final long millis = context.getValueAs(Long.class);
        if (millis > System.currentTimeMillis()) {
          throw UserException.validationError()
              .message(
                  "For table '%s', the provided time travel timestamp value '%d' is out of range",
                  key.getPathComponents(), millis)
              .buildSilently();
        }
        return TimeTravelOption.newTimestampRequest(millis);
      case BRANCH:
      case TAG:
      case COMMIT:
      case REFERENCE:
      case NOT_SPECIFIED:
        return null;
      default:
        throw new AssertionError("Unsupported type " + context.getType());
    }
  }

  /** Is CatalogEntityKey going to be used for <AT specifier> accesss */
  public static boolean forATSpecifierAccess(
      CatalogEntityKey catalogEntityKey, SourceCatalog catalog) {
    boolean isVersionedTable =
        requestedPluginSupportsVersionedTables(catalogEntityKey.getRootEntity(), catalog);
    return ((catalogEntityKey.hasTableVersionContext())
        && (isVersionedTable || catalogEntityKey.getTableVersionContext().isTimeTravelType()));
  }

  /**
   * This Catalog will allow the caller to search for entries but will not promote entries that are
   * missing in Namespace KV store It will not check validity of metadata
   *
   * @param catalogService
   * @return
   */
  public static Catalog getSystemCatalogForReflections(CatalogService catalogService) {
    return catalogService.getCatalog(
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(
                SchemaConfig.newBuilder(CatalogUser.from(SystemUser.SYSTEM_USERNAME)).build())
            .setCheckValidity(false)
            .setNeverPromote(true)
            .build());
  }

  public static Catalog getSystemCatalogForMaterializationCache(CatalogService catalogService) {
    return catalogService.getCatalog(
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(
                SchemaConfig.newBuilder(CatalogUser.from(SystemUser.SYSTEM_USERNAME)).build())
            .setCheckValidity(false) // We want the cache to come online as soon as possible
            .setNeverPromote(
                false) // However, we need to promote missing datasets or else risk losing the
            // materialization
            .build());
  }

  public static Catalog getSystemCatalogForPlanCacheInvalidation(CatalogService catalogService) {
    return catalogService.getCatalog(
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(
                SchemaConfig.newBuilder(CatalogUser.from(SystemUser.SYSTEM_USERNAME))
                    .exposeInternalSources(true)
                    .build())
            .setCheckValidity(false)
            .setNeverPromote(false)
            .setErrorOnUnspecifiedSourceVersion(true)
            .build());
  }

  public static void clearAllDatasetCache(Catalog catalog) {
    catalog
        .getAllRequestedTables()
        .forEach(t -> catalog.clearDatasetCache(t.getPath(), t.getVersionContext()));
  }

  public static Catalog getSystemCatalogForDatasetResource(CatalogService catalogService) {
    return catalogService.getCatalog(
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(
                SchemaConfig.newBuilder(CatalogUser.from(SystemUser.SYSTEM_USERNAME)).build())
            .setCheckValidity(false)
            .setNeverPromote(true)
            .build());
  }

  public static DatasetConfig getDatasetConfig(
      CatalogEntityKey catalogEntityKey, EntityExplorer catalog) {
    final DremioTable table = catalog.getTable(catalogEntityKey);

    return (table == null) ? null : table.getDatasetConfig();
  }

  public static DatasetConfig getDatasetConfig(EntityExplorer catalog, String datasetId) {
    DremioTable dremioTable = catalog.getTable(datasetId);
    DatasetConfig datasetConfig = null;
    if (dremioTable != null) {
      datasetConfig = dremioTable.getDatasetConfig();
    }
    return datasetConfig;
  }

  /**
   * Gets the dataset config for a given dataset key.
   *
   * @return the dataset config, or null if the dataset is not found.
   */
  public static DatasetConfig getDatasetConfig(EntityExplorer catalog, NamespaceKey key) {
    DremioTable dremioTable = catalog.getTable(key);
    DatasetConfig datasetConfig = null;
    if (dremioTable != null) {
      datasetConfig = dremioTable.getDatasetConfig();
    }
    return datasetConfig;
  }

  /**
   * Check if the versioned table/view/folder exists in Nessie Note that this check does not
   * retrieve any metadata from S3 unless it is a TIMESTAMP or AT SNAPSHOT specification, in which
   * case we have to go to the Iceberg metadata json file to retrieve the snapshot/timestamp
   * information.
   *
   * @param catalog
   * @param id
   * @return
   */
  public static boolean versionedEntityExists(Catalog catalog, VersionedDatasetId id) {
    NamespaceKey tableNamespaceKey = new NamespaceKey(id.getTableKey());
    if (id.getVersionContext().isTimeTravelType()) {
      return (catalog.getTable(tableNamespaceKey) != null);
    }
    String source = tableNamespaceKey.getRoot();
    VersionContext versionContext = id.getVersionContext().asVersionContext();
    ResolvedVersionContext resolvedVersionContext =
        CatalogUtil.resolveVersionContext(catalog, source, versionContext);
    VersionedPlugin versionedPlugin = catalog.getSource(source);
    List<String> versionedTableKey = tableNamespaceKey.getPathWithoutRoot();
    String retrievedContentId;
    // Run as System User, and not as running User, to check that the table exists.
    try {
      retrievedContentId = versionedPlugin.getContentId(versionedTableKey, resolvedVersionContext);
    } catch (Exception e) {
      LOGGER.warn("Failed to get content id for {}", id, e);
      // TODO: DX-68489: Investigate proper exception handling in CatalogUtil
      throw UserException.unsupportedError(e).buildSilently();
    }

    return (retrievedContentId != null && (retrievedContentId.equals(id.getContentId())));
  }

  static MetadataVerifyRequest getMetadataVerifyRequest(
      NamespaceKey key, TableMetadataVerifyRequest tableMetadataVerifyRequest) {
    checkArgument(key != null);
    checkArgument(tableMetadataVerifyRequest != null);

    if (tableMetadataVerifyRequest instanceof TableMetadataVerifyAppendOnlyRequest) {
      TableMetadataVerifyAppendOnlyRequest request =
          (TableMetadataVerifyAppendOnlyRequest) tableMetadataVerifyRequest;
      return new VerifyAppendOnlyRequest(request.getBeginSnapshotId(), request.getEndSnapshotId());
    } else if (tableMetadataVerifyRequest instanceof TableMetadataVerifyDataModifiedRequest) {
      TableMetadataVerifyDataModifiedRequest request =
          (TableMetadataVerifyDataModifiedRequest) tableMetadataVerifyRequest;
      return new VerifyDataModifiedRequest(
          request.getBeginSnapshotId(), request.getEndSnapshotId());
    }
    throw new UnsupportedOperationException(
        String.format(
            "Unsupported TableMetadataVerifyRequest type %s for table '%s'",
            tableMetadataVerifyRequest.getClass().toString(), key.getPathComponents()));
  }

  static TableMetadataVerifyResult getMetadataVerifyResult(
      NamespaceKey key, DatasetMetadataVerifyResult datasetMetadataVerifyResult) {
    checkArgument(key != null);
    checkArgument(datasetMetadataVerifyResult != null);

    if (datasetMetadataVerifyResult instanceof DatasetVerifyAppendOnlyResult) {
      return new VerifyAppendOnlyResultAdapter(
          (DatasetVerifyAppendOnlyResult) datasetMetadataVerifyResult);
    } else if (datasetMetadataVerifyResult instanceof DatasetVerifyDataModifiedResult) {
      return new VerifyDataModifiedResultAdapter(
          (DatasetVerifyDataModifiedResult) datasetMetadataVerifyResult);
    }
    throw new UnsupportedOperationException(
        String.format(
            "Unsupported DatasetMetadataVerifyResult type %s for table '%s'",
            datasetMetadataVerifyResult.getClass().toString(), key.getPathComponents()));
  }

  /**
   * Returns the validated table path, if one exists.
   *
   * <p>Note: Due to the way the tables get cached, we have to use Catalog.getTableNoResolve, rather
   * than using Catalog.getTable.
   */
  // TODO: DX-68443 Refactor this method.
  public static NamespaceKey getResolvePathForTableManagement(Catalog catalog, NamespaceKey path) {
    return getResolvePathForTableManagement(
        catalog, path, new TableVersionContext(TableVersionType.NOT_SPECIFIED, ""));
  }

  public static NamespaceKey getResolvePathForTableManagement(
      Catalog catalog, NamespaceKey path, TableVersionContext versionContextFromSql) {
    NamespaceKey resolvedPath = catalog.resolveToDefault(path);
    StoragePlugin maybeSource;
    DremioTable table;
    CatalogEntityKey.Builder keyBuilder = CatalogEntityKey.newBuilder();
    if (resolvedPath != null) {
      maybeSource =
          getAndValidateSourceForTableManagement(catalog, versionContextFromSql, resolvedPath);
      keyBuilder = keyBuilder.keyComponents(resolvedPath.getPathComponents());
      if (maybeSource != null && maybeSource.isWrapperFor(VersionedPlugin.class)) {
        keyBuilder = keyBuilder.tableVersionContext(versionContextFromSql);
      }
      table = catalog.getTableNoResolve(keyBuilder.build());
      if (table != null) {
        return table.getPath();
      }
    }
    // Since the table was undiscovered with the resolved path, use `path` and try again.
    maybeSource = getAndValidateSourceForTableManagement(catalog, versionContextFromSql, path);
    keyBuilder = CatalogEntityKey.newBuilder().keyComponents(path.getPathComponents());
    if (maybeSource != null && maybeSource.isWrapperFor(VersionedPlugin.class)) {
      keyBuilder = keyBuilder.tableVersionContext(versionContextFromSql);
    }
    table = catalog.getTableNoResolve(keyBuilder.build());
    if (table != null) {
      return table.getPath();
    }

    throw UserException.validationError(new IcebergTableNotFoundException(path.toString()))
        .message("Table [%s] does not exist.", path)
        .buildSilently();
  }

  public static StoragePlugin getAndValidateSourceForTableManagement(
      SourceCatalog catalog, TableVersionContext tableVersionContext, NamespaceKey path) {
    StoragePlugin source;
    try {
      source = catalog.getSource(path.getRoot());
    } catch (UserException ue) {
      // Cannot find source
      logger.debug(String.format("Cannot find source %s", path.getRoot()));
      return null;
    }

    // TODO: Refactor within DX-82879
    if ((source != null)
        && !(source.isWrapperFor(VersionedPlugin.class))
        && (tableVersionContext != null)
        && (tableVersionContext.getType() != TableVersionType.NOT_SPECIFIED)) {
      throw UserException.validationError()
          .message(
              String.format(
                  "Source [%s] does not support AT [%s] version specification for DML operations",
                  path.getRoot(), tableVersionContext))
          .buildSilently();
    }
    return source;
  }

  public static boolean permittedNessieKey(NamespaceKey key) {
    return key.getPathComponents().size() <= ContentKey.MAX_ELEMENTS;
  }

  public static VersionedPlugin.EntityType getVersionedEntityType(
      final SourceCatalog catalog, final VersionedDatasetId id) throws SourceDoesNotExistException {
    final List<String> fullPath = id.getTableKey();
    final VersionContext versionContext = id.getVersionContext().asVersionContext();
    return getVersionedEntityType(catalog, fullPath, versionContext);
  }

  public static VersionedPlugin.EntityType getVersionedEntityType(
      final SourceCatalog catalog, final List<String> fullPath, final VersionContext versionContext)
      throws SourceDoesNotExistException {
    final VersionedPlugin versionedPlugin = getVersionedPlugin(catalog, fullPath);
    final ResolvedVersionContext resolvedVersionContext =
        versionedPlugin.resolveVersionContext(versionContext);
    final List<String> contentKey = fullPath.subList(1, fullPath.size());
    final VersionedPlugin.EntityType entityType =
        versionedPlugin.getType(contentKey, resolvedVersionContext);
    if (entityType == null) {
      return VersionedPlugin.EntityType.UNKNOWN;
    }
    return entityType;
  }

  private static VersionedPlugin getVersionedPlugin(
      final SourceCatalog catalog, final List<String> fullPath) throws SourceDoesNotExistException {
    final StoragePlugin plugin = getStoragePlugin(catalog, fullPath.get(0));
    Preconditions.checkArgument(plugin.isWrapperFor(VersionedPlugin.class));
    return plugin.unwrap(VersionedPlugin.class);
  }

  private static StoragePlugin getStoragePlugin(final SourceCatalog catalog, String sourceName)
      throws SourceDoesNotExistException {
    final StoragePlugin plugin = catalog.getSource(sourceName);

    if (plugin == null) {
      throw new SourceDoesNotExistException(sourceName);
    }

    return plugin;
  }

  public static boolean isDatasetTypeATable(DatasetType type) {
    return type == PHYSICAL_DATASET
        || type == PHYSICAL_DATASET_SOURCE_FILE
        || type == PHYSICAL_DATASET_SOURCE_FOLDER
        || type == PHYSICAL_DATASET_HOME_FILE
        || type == PHYSICAL_DATASET_HOME_FOLDER;
  }
}
