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
package com.dremio.exec.store.iceberg.nessie;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.catalog.VersionedPlugin.EntityType;
import com.dremio.exec.store.ReferenceConflictException;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.model.IcebergCommitOrigin;
import com.dremio.plugins.NessieClient;
import com.dremio.plugins.NessieCommitUsernameContext;
import com.dremio.plugins.NessieContent;
import com.dremio.plugins.NessieTableAdapter;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.writer.WriterCommitterOperator;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.projectnessie.error.NessieConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergNessieVersionedTableOperations extends BaseMetastoreTableOperations
    implements IcebergCommitOriginAwareTableOperations {

  private static final Logger logger =
      LoggerFactory.getLogger(IcebergNessieVersionedTableOperations.class);

  private final OperatorStats operatorStats;
  private final FileIO fileIO;
  private final NessieClient nessieClient;
  private final List<String> tableKey;
  private final String fullTableName;
  private ResolvedVersionContext version;
  private @Nullable IcebergCommitOrigin commitOrigin;
  private final String jobId;
  private final String userName;
  private String baseContentId;
  private final @Nullable String nessieCommitUserId;

  public IcebergNessieVersionedTableOperations(
      OperatorStats operatorStats,
      FileIO fileIO,
      NessieClient nessieClient,
      IcebergNessieVersionedTableIdentifier nessieVersionedTableIdentifier,
      @Nullable IcebergCommitOrigin commitOrigin,
      String jobId,
      String userName,
      @Nullable String nessieCommitUserId) {
    this.operatorStats = operatorStats;
    this.fileIO = fileIO;
    this.fullTableName = nessieVersionedTableIdentifier.getTableIdentifier().toString();
    this.nessieClient = Preconditions.checkNotNull(nessieClient);

    Preconditions.checkNotNull(nessieVersionedTableIdentifier);
    this.tableKey = nessieVersionedTableIdentifier.getTableKey();
    this.version = nessieVersionedTableIdentifier.getVersion();
    this.commitOrigin = commitOrigin;
    this.jobId = jobId;
    this.baseContentId = null;
    this.userName = userName;
    this.nessieCommitUserId = nessieCommitUserId;
  }

  @Override
  public FileIO io() {
    return fileIO;
  }

  @Override
  protected String tableName() {
    return fullTableName;
  }

  @Override
  protected void doRefresh() {
    if (version.isBranch()) {
      version =
          callWithContext(
              () ->
                  nessieClient.resolveVersionContext(
                      VersionContext.ofBranch(version.getRefName()), jobId));
    }
    String metadataLocation = null;
    Optional<NessieContent> maybeNessieContent =
        callWithContext(() -> nessieClient.getContent(tableKey, version, jobId));
    if (maybeNessieContent.isPresent()) {
      NessieContent nessieContent = maybeNessieContent.get();
      baseContentId = nessieContent.getContentId();
      metadataLocation =
          nessieContent
              .getMetadataLocation()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No metadataLocation for iceberg table: "
                              + tableKey
                              + " ref: "
                              + version));
    }

    try {
      refreshFromMetadataLocation(metadataLocation, 2);
    } catch (NotFoundException nfe) {
      throw UserException.invalidMetadataError(nfe)
          .message(
              "Metadata for table [%s] is not available for the commit [%s]. The metadata files may have expired and been garbage collected based on the table history retention policy.",
              String.join(".", tableKey), version.getCommitHash().substring(0, 8))
          .addContext(
              String.format(
                  "Failed to locate metadata for table at the commit [%s]",
                  version.getCommitHash()))
          .build(logger);
    }
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    Stopwatch stopwatchWriteNewMetadata = Stopwatch.createStarted();
    String newMetadataLocation =
        writeNewMetadata(IcebergUtils.fixupDefaultProperties(metadata), currentVersion() + 1);
    long totalMetadataWriteTime = stopwatchWriteNewMetadata.elapsed(TimeUnit.MILLISECONDS);
    if (operatorStats != null) {
      operatorStats.addLongStat(
          WriterCommitterOperator.Metric.ICEBERG_METADATA_WRITE_TIME, totalMetadataWriteTime);
    }
    boolean threw = true;
    try {
      Stopwatch stopwatchCatalogUpdate = Stopwatch.createStarted();
      getUserNameAndIdWrappedRequestContext()
          .run(
              () ->
                  nessieClient.commitTable(
                      tableKey,
                      newMetadataLocation,
                      new NessieTableAdapter(
                          metadata.currentSnapshot().snapshotId(),
                          metadata.currentSchemaId(),
                          metadata.defaultSpecId(),
                          metadata.sortOrder().orderId()),
                      version,
                      baseContentId,
                      commitOrigin,
                      jobId,
                      userName));
      threw = false;
      long totalCatalogUpdateTime = stopwatchCatalogUpdate.elapsed(TimeUnit.MILLISECONDS);
      if (operatorStats != null) {
        operatorStats.addLongStat(
            WriterCommitterOperator.Metric.ICEBERG_CATALOG_UPDATE_TIME, totalCatalogUpdateTime);
      }
    } catch (ReferenceConflictException e) {
      if (e.getCause() instanceof NessieConflictException) {
        logger.debug(
            String.format("Retrying commit for table %s because of conflict exception", tableKey));
        throw new CommitFailedException(e.getCause());
      } else {
        throw new IllegalStateException(e);
      }
    } finally {
      if (threw) {
        logger.info(
            "Cleaning up after failed commit. Deleting metadata file {} of table {}.",
            newMetadataLocation,
            tableKey);
        io().deleteFile(newMetadataLocation);
      }
    }
  }

  public void deleteKey() {
    getUserNameAndIdWrappedRequestContext()
        .run(
            () ->
                nessieClient.deleteCatalogEntry(
                    tableKey, EntityType.ICEBERG_TABLE, version, userName));
  }

  protected RequestContext getUserNameAndIdWrappedRequestContext() {
    RequestContext currentContext = RequestContext.current();
    if (StringUtils.isEmpty(nessieCommitUserId)) {
      return currentContext.with(
          NessieCommitUsernameContext.CTX_KEY, new NessieCommitUsernameContext(userName));
    }
    return currentContext
        .with(UserContext.CTX_KEY, UserContext.of(nessieCommitUserId))
        .with(NessieCommitUsernameContext.CTX_KEY, new NessieCommitUsernameContext(userName));
  }

  private <T> T callWithContext(Callable<T> callable) {
    try {
      return getUserNameAndIdWrappedRequestContext().call(callable);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void tryNarrowCommitOrigin(
      @Nullable IcebergCommitOrigin oldOrigin, IcebergCommitOrigin newOrigin) {
    if (this.commitOrigin == oldOrigin) {
      this.commitOrigin = newOrigin;
    }
  }
}
