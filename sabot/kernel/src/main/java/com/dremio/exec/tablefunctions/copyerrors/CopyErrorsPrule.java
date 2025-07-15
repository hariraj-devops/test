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
package com.dremio.exec.tablefunctions.copyerrors;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.config.copyinto.CopyIntoFileLoadInfo;
import com.dremio.exec.planner.CopyErrorsPlanBuilder;
import com.dremio.exec.planner.CopyIntoTablePlanBuilderBase;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.sql.handlers.query.CopyErrorContext;
import com.dremio.exec.planner.sql.handlers.query.CopyIntoTableContext;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.RecordBatchHolder;
import com.dremio.exec.server.SimpleJobRunner;
import com.dremio.exec.store.dfs.copyinto.CopyFileHistoryTableSchemaProvider;
import com.dremio.exec.store.dfs.copyinto.CopyJobHistoryTableSchemaProvider;
import com.dremio.exec.store.dfs.system.SystemIcebergTableMetadata;
import com.dremio.exec.store.dfs.system.SystemIcebergTableMetadataFactory.SupportedSystemIcebergTable;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePlugin;
import com.dremio.exec.store.dfs.system.SystemIcebergTablesStoragePluginConfig;
import com.dremio.exec.store.iceberg.model.IcebergBaseCommand;
import com.dremio.exec.util.VectorUtil;
import com.dremio.service.users.SystemUser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.arrow.vector.VarCharVector;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prule for copy errors function, convert {@link CopyErrorsDrel} to {@link
 * CopyErrorsPlanBuilder#buildPlan()} Based on the copy error context, we do a lookup in the
 * copy_job_history and copy_file_history internal tables to reconsturct the original
 * CopyIntoTableContext, where as a list of files we supply the list of files with rejections.
 */
public final class CopyErrorsPrule extends RelOptRule {

  private static final Logger LOG = LoggerFactory.getLogger(CopyErrorsPrule.class);
  private static final String COPY_ERRORS_PLAN_QUERY_LABEL = "COPY";
  private static final String COPY_ERRORS_PLAN_QUERY_TYPE = "COPY_ERRORS_PLAN";

  @VisibleForTesting
  public static final UserException COPY_INTO_JOB_NOT_FOUND_EXCEPTION =
      UserException.planError()
          .message(
              "Could not find COPY INTO job details. Make sure the given table name "
                  + "(and jobId) are correct. Moreover the current user must match the one that triggered the original "
                  + "COPY INTO command.")
          .buildSilently();

  private final OptimizerRulesContext optimizerRulesContext;

  public CopyErrorsPrule(OptimizerRulesContext optimizerRulesContext) {
    super(RelOptHelper.any(CopyErrorsDrel.class), "CopyErrorsPrule");
    this.optimizerRulesContext = optimizerRulesContext;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final CopyErrorsDrel drel = call.rel(0);

    QueryContext context = (QueryContext) optimizerRulesContext;

    // Need to use the OptionManager from the session otherwise session level settings won't work
    long schemaVersion =
        context
            .getSession()
            .getOptions()
            .getOption(ExecConstants.SYSTEM_ICEBERG_TABLES_SCHEMA_VERSION);

    long maxInputFiles =
        context
            .getSession()
            .getOptions()
            .getOption(ExecConstants.COPY_ERRORS_TABLE_FUNCTION_MAX_INPUT_FILES);

    // CopyErrorContext already stores validated user inputs
    CopyErrorContext copyErrorContext = drel.getContext();
    String resolvedTargetTableName =
        PathUtils.constructFullPath(copyErrorContext.getResolvedTargetTable().getQualifiedName());
    String jobId = copyErrorContext.getCopyIntoJobId();
    String user = copyErrorContext.getResolvedTargetTable().getTable().getDataset().getUser();

    SimpleJobRunner jobRunner = context.getJobsRunner().get();
    CopyJobHistoryTableSchemaProvider copyJobHistoryTableSchemaProvider =
        new CopyJobHistoryTableSchemaProvider(schemaVersion);
    CopyFileHistoryTableSchemaProvider copyFileHistoryTableSchemaProvider =
        new CopyFileHistoryTableSchemaProvider(schemaVersion);

    SystemIcebergTablesStoragePlugin plugin =
        context
            .getCatalogService()
            .getSource(SystemIcebergTablesStoragePluginConfig.SYSTEM_ICEBERG_TABLES_PLUGIN_NAME);
    SystemIcebergTableMetadata jobHistoryMetadata =
        plugin.getTableMetadata(
            ImmutableList.of(SupportedSystemIcebergTable.COPY_JOB_HISTORY.getTableName()));

    // 1. if not given, find out last COPY INTO jobId for specified table
    if (jobId == null) {
      StringBuilder jobIdQuery = new StringBuilder();
      jobIdQuery
          .append("SELECT")
          .append(" \"")
          .append(copyJobHistoryTableSchemaProvider.getJobIdColName())
          .append("\"")
          .append(" FROM ")
          .append(jobHistoryMetadata.getNamespaceKey().getRoot())
          .append(".")
          .append(jobHistoryMetadata.getTableName())
          .append(" WHERE \"")
          .append(copyJobHistoryTableSchemaProvider.getTableNameColName())
          .append("\" = '")
          .append(resolvedTargetTableName)
          .append("'")
          .append(" AND \"")
          .append(copyJobHistoryTableSchemaProvider.getUserNameColName())
          .append("\" = '")
          .append(user)
          .append("'")
          .append(" ORDER BY \"")
          .append(copyJobHistoryTableSchemaProvider.getExecutedAtColName())
          .append("\" DESC LIMIT 1");

      List<RecordBatchHolder> jobIdEntry = null;
      try {
        jobIdEntry =
            jobRunner.runQueryAsJobForResults(
                jobIdQuery.toString(),
                SystemUser.SYSTEM_USERNAME,
                COPY_ERRORS_PLAN_QUERY_TYPE,
                COPY_ERRORS_PLAN_QUERY_LABEL,
                0,
                1);
        verifyRecordBatchHolderSize(jobIdEntry);
        RecordBatchData recordBatchData = jobIdEntry.get(0).getData();
        if (recordBatchData.getRecordCount() != 1) {
          throw COPY_INTO_JOB_NOT_FOUND_EXCEPTION;
        }
        jobId =
            new String(
                ((VarCharVector)
                        VectorUtil.getVectorFromSchemaPath(
                            recordBatchData.getVectorAccessible(),
                            copyJobHistoryTableSchemaProvider.getJobIdColName()))
                    .get(0));
      } catch (Exception e) {
        if (COPY_INTO_JOB_NOT_FOUND_EXCEPTION.equals(e)) {
          throw COPY_INTO_JOB_NOT_FOUND_EXCEPTION;
        } else {
          throwExceptionDuringInternalQuery(e);
        }
      } finally {
        try {
          AutoCloseables.close(jobIdEntry);
        } catch (Exception e) {
          throwExceptionDuringInternalQuery(e);
        }
      }
    }

    // 2. retrieve aggregated error information in order to prepare executing the original COPY INTO
    // command in validation mode

    SystemIcebergTableMetadata fileHistoryMetadata =
        plugin.getTableMetadata(
            ImmutableList.of(SupportedSystemIcebergTable.COPY_FILE_HISTORY.getTableName()));

    StringBuilder joinQuery = new StringBuilder();
    joinQuery
        .append("SELECT")
        .append(" jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getJobIdColName())
        .append("\",")
        .append(" jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getStorageLocationColName())
        .append("\",")
        .append(" jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getFileFormatColName())
        .append("\",")
        .append(" jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getCopyOptionsColName())
        .append("\",")
        .append(" jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getTransformationProperties())
        .append("\",")
        .append(" gfh.\"file_paths\"")
        .append(" FROM ")
        .append(jobHistoryMetadata.getNamespaceKey().getRoot())
        .append(".")
        .append(jobHistoryMetadata.getTableName())
        .append(" AS jh")
        .append(" INNER JOIN (SELECT")
        .append(" lfp.\"")
        .append(copyFileHistoryTableSchemaProvider.getJobIdColName())
        .append("\",")
        .append(" LISTAGG(lfp.\"")
        .append(copyFileHistoryTableSchemaProvider.getFilePathColName())
        .append("\", ',') AS \"file_paths\"")
        .append(" FROM (SELECT")
        .append(" fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getJobIdColName())
        .append("\",")
        .append(" fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getFilePathColName())
        .append("\"")
        .append(" FROM ")
        .append(fileHistoryMetadata.getNamespaceKey().getRoot())
        .append(".")
        .append(fileHistoryMetadata.getTableName())
        .append(" AS fh")
        .append(" WHERE fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getJobIdColName())
        .append("\" = '")
        .append(jobId)
        .append("'")
        .append(" AND (fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getFileStateColName())
        .append("\" = '")
        .append(CopyIntoFileLoadInfo.CopyIntoFileState.SKIPPED)
        .append("'")
        .append(" OR fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getFileStateColName())
        .append("\" = '")
        .append(CopyIntoFileLoadInfo.CopyIntoFileState.PARTIALLY_LOADED)
        .append("')")
        .append(" ORDER BY fh.\"")
        .append(copyFileHistoryTableSchemaProvider.getFilePathColName())
        .append("\" ASC")
        .append(" LIMIT ")
        .append(maxInputFiles)
        .append(") AS lfp")
        .append(" GROUP BY lfp.\"")
        .append(copyFileHistoryTableSchemaProvider.getJobIdColName())
        .append("\") AS gfh")
        .append(" ON jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getJobIdColName())
        .append("\" = gfh.\"")
        .append(copyFileHistoryTableSchemaProvider.getJobIdColName())
        .append("\"")
        .append(" WHERE jh.\"")
        .append(copyJobHistoryTableSchemaProvider.getUserNameColName())
        .append("\" = '")
        .append(user)
        .append("'");

    CopyIntoTableContext copyIntoTableContext = null;

    List<RecordBatchHolder> copyErrorEntry = null;
    try {
      // limit=5 for the unlikely scenario the joinQuery result would blow up
      copyErrorEntry =
          jobRunner.runQueryAsJobForResults(
              joinQuery.toString(),
              SystemUser.SYSTEM_USERNAME,
              COPY_ERRORS_PLAN_QUERY_TYPE,
              COPY_ERRORS_PLAN_QUERY_LABEL,
              0,
              5);
      verifyRecordBatchHolderSize(copyErrorEntry);
      RecordBatchData recordBatchData = copyErrorEntry.get(0).getData();
      if (recordBatchData.getRecordCount() != 1) {
        throw COPY_INTO_JOB_NOT_FOUND_EXCEPTION;
      }
      copyIntoTableContext =
          CopyIntoTableContext.createFromCopyErrorsQueryResult(recordBatchData, schemaVersion);

    } catch (Exception e) {
      if (COPY_INTO_JOB_NOT_FOUND_EXCEPTION.equals(e)) {
        throw COPY_INTO_JOB_NOT_FOUND_EXCEPTION;
      } else {
        throwExceptionDuringInternalQuery(e);
      }
    } finally {
      try {
        AutoCloseables.close(copyErrorEntry);
      } catch (Exception e) {
        throwExceptionDuringInternalQuery(e);
      }
    }

    // 3. consistency check: verify with iceberg metadata query that the target table was committed
    // successfully in the past by a COPY INTO job matching our jobId
    // TODO: remove this step if a cross catalog locking feature is available
    if (copyErrorContext.isStrictConsistency()) {

      boolean isCopyIntoJobFound = false;
      boolean isCopyIntoJobSuccess = false;
      boolean isJobIdFoundInSnapshot = false;

      // 3.1 query sys job history to learn original COPY INTO job outcome
      StringBuilder jobHistoryLookupQuery = new StringBuilder();
      jobHistoryLookupQuery
          .append("SELECT * FROM sys.jobs_recent")
          .append(" WHERE job_id = '")
          .append(jobId)
          .append('\'');

      List<RecordBatchHolder> jobHistoryLookupResult = null;
      try {
        jobHistoryLookupResult =
            jobRunner.runQueryAsJobForResults(
                jobHistoryLookupQuery.toString(),
                user,
                COPY_ERRORS_PLAN_QUERY_TYPE,
                COPY_ERRORS_PLAN_QUERY_LABEL,
                0,
                1);
        verifyRecordBatchHolderSize(jobHistoryLookupResult);
        RecordBatchData recordBatchData = jobHistoryLookupResult.get(0).getData();
        if (recordBatchData.getRecordCount() == 1) {
          isCopyIntoJobFound = true;
          String jobStatus =
              new String(
                  ((VarCharVector)
                          VectorUtil.getVectorFromSchemaPath(
                              recordBatchData.getVectorAccessible(), "status"))
                      .get(0));
          isCopyIntoJobSuccess = "COMPLETED".equals(jobStatus);
        }
      } catch (Exception e) {
        throwExceptionDuringInternalQuery(e);
      } finally {
        try {
          AutoCloseables.close(jobHistoryLookupResult);
        } catch (Exception e) {
          throwExceptionDuringInternalQuery(e);
        }
      }

      // 3.2 query target table snapshot summaries if we don't have job history result or the job
      // status was found FAILED
      if (!isCopyIntoJobSuccess) {
        StringBuilder jobIdLookupInTargetTableSnapshotQuery = new StringBuilder();
        jobIdLookupInTargetTableSnapshotQuery
            .append("SELECT")
            .append(" t.flat.\"key\" AS \"key\", t.flat.\"value\" as \"value\"")
            .append(" FROM (")
            .append("  SELECT")
            .append("  FLATTEN(summary) as flat")
            .append("  FROM TABLE( table_snapshot('")
            .append(resolvedTargetTableName)
            .append("'))")
            .append(" ) t")
            .append(" WHERE \"key\" = '")
            .append(IcebergBaseCommand.DREMIO_JOB_ID_ICEBERG_PROPERTY)
            .append("'")
            .append(" AND \"value\" = '")
            .append(jobId)
            .append("'");

        List<RecordBatchHolder> jobIdSnapshotLookupResult = null;
        try {
          jobIdSnapshotLookupResult =
              jobRunner.runQueryAsJobForResults(
                  jobIdLookupInTargetTableSnapshotQuery.toString(),
                  user,
                  COPY_ERRORS_PLAN_QUERY_TYPE,
                  COPY_ERRORS_PLAN_QUERY_LABEL,
                  0,
                  1);
          verifyRecordBatchHolderSize(jobIdSnapshotLookupResult);
          RecordBatchData recordBatchData = jobIdSnapshotLookupResult.get(0).getData();
          isJobIdFoundInSnapshot = recordBatchData.getRecordCount() == 1;
        } catch (Exception e) {
          throwExceptionDuringInternalQuery(e);
        } finally {
          try {
            AutoCloseables.close(jobIdSnapshotLookupResult);
          } catch (Exception e) {
            throwExceptionDuringInternalQuery(e);
          }
        }
      }

      if (!isCopyIntoJobSuccess && !isJobIdFoundInSnapshot) {
        LOG.error(
            "Unable to verify data consistency for job ({}). {} The jobId was also not found among the "
                + "Iceberg target table's ({}) now available snapshots. Need to investigate if rows produced by"
                + " that job are really persisted in target table.",
            jobId,
            isCopyIntoJobFound
                ? "As per Dremio's job history the job did not complete successfully."
                : "No Dremio job history entry for this job can be found.",
            resolvedTargetTableName);
        throw UserException.planError()
            .message(
                "Unable to verify data consistency for the original COPY INTO ON_ERROR 'continue' job (%s). "
                    + " Rows might not have been committed successfully into the target table. Please either"
                    + " re-run the original COPY INTO query, or run %s() table function again without this verification by"
                    + " setting the 3rd argument (%s) to false.",
                jobId,
                CopyErrorsMacro.MACRO_NAME,
                CopyErrorsMacro.FUNCTION_PARAMETERS.get(2).getName())
            .buildSilently();
      }
    }

    CopyIntoTablePlanBuilderBase builder =
        new CopyErrorsPlanBuilder(
            drel.getTable(),
            drel.getRowType(),
            drel.getCluster(),
            drel.getTraitSet().plus(Prel.PHYSICAL),
            ((DremioPrepareTable) drel.getTable()).getTable().getDataset(),
            optimizerRulesContext,
            copyIntoTableContext,
            drel.getMetadata());
    Prel prel = builder.buildPlan();
    call.transformTo(prel);
  }

  private static void throwExceptionDuringInternalQuery(Exception e) {
    throw UserException.planError(e)
        .message(
            "Error during running an internal query for the COPY_ERRORS table function planning.")
        .buildSilently();
  }

  private static void verifyRecordBatchHolderSize(List<RecordBatchHolder> recordBatchHolder) {
    if (recordBatchHolder == null || recordBatchHolder.isEmpty()) {
      throwExceptionDuringInternalQuery(
          new IllegalStateException(
              "Expected at least 1 record batch holder for the internal query."));
    }
  }
}
