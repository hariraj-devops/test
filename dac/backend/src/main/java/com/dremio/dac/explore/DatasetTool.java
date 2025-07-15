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
package com.dremio.dac.explore;

import static com.dremio.dac.explore.model.InitialPreviewResponse.INITIAL_RESULTSET_SIZE;

import com.dremio.catalog.model.VersionContext;
import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.SqlUtils;
import com.dremio.dac.explore.model.Column;
import com.dremio.dac.explore.model.DatasetName;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetSummary;
import com.dremio.dac.explore.model.DatasetUI;
import com.dremio.dac.explore.model.DatasetVersionResourcePath;
import com.dremio.dac.explore.model.FromBase;
import com.dremio.dac.explore.model.History;
import com.dremio.dac.explore.model.HistoryItem;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.explore.model.InitialRunResponse;
import com.dremio.dac.explore.model.InitialUntitledRunResponse;
import com.dremio.dac.explore.model.TransformBase;
import com.dremio.dac.explore.model.VersionContextReq;
import com.dremio.dac.model.job.JobData;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.model.job.JobDataFragmentWrapper;
import com.dremio.dac.model.job.JobDetailsUI;
import com.dremio.dac.model.job.JobUI;
import com.dremio.dac.model.job.QueryError;
import com.dremio.dac.model.spaces.TempSpace;
import com.dremio.dac.proto.model.dataset.DataType;
import com.dremio.dac.proto.model.dataset.DatasetVersionOrigin;
import com.dremio.dac.proto.model.dataset.Derivation;
import com.dremio.dac.proto.model.dataset.From;
import com.dremio.dac.proto.model.dataset.FromType;
import com.dremio.dac.proto.model.dataset.NameDatasetRef;
import com.dremio.dac.proto.model.dataset.SourceVersionReference;
import com.dremio.dac.proto.model.dataset.Transform;
import com.dremio.dac.proto.model.dataset.TransformCreateFromParent;
import com.dremio.dac.proto.model.dataset.TransformType;
import com.dremio.dac.proto.model.dataset.VirtualDatasetState;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.resource.JobResource;
import com.dremio.dac.server.ApiErrorModel;
import com.dremio.dac.server.GenericErrorMessage;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.DatasetNotFoundException;
import com.dremio.dac.service.errors.DatasetVersionNotFoundException;
import com.dremio.dac.service.errors.InvalidQueryException;
import com.dremio.dac.service.errors.NewDatasetQueryException;
import com.dremio.dac.util.InvalidQueryErrorConverter;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.SupportsMutatingViews;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchHolder;
import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.service.job.JobDetails;
import com.dremio.service.job.JobDetailsRequest;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobState;
import com.dremio.service.job.proto.ParentDatasetInfo;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.job.proto.SessionId;
import com.dremio.service.jobs.JobDataClientUtils;
import com.dremio.service.jobs.JobNotFoundException;
import com.dremio.service.jobs.JobsProtoUtil;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.jobs.JobsVersionContext;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.jobs.metadata.proto.QueryMetadata;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.protostuff.ByteString;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.ws.rs.core.SecurityContext;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.Field;

/** Class that helps with generating common dataset patterns. */
public class DatasetTool {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(DatasetTool.class);

  private final DatasetVersionMutator datasetService;
  private final JobsService jobsService;
  private final QueryExecutor executor;
  private final SecurityContext context;
  public static final DatasetPath TMP_DATASET_PATH =
      new DatasetPath(TempSpace.impl(), new DatasetName("UNTITLED"));

  public DatasetTool(
      DatasetVersionMutator datasetService,
      JobsService jobsService,
      QueryExecutor executor,
      SecurityContext context) {
    this.datasetService = datasetService;
    this.jobsService = jobsService;
    this.executor = executor;
    this.context = context;
  }

  /**
   * Helper method to create {@link InitialPreviewResponse} for existing dataset.
   *
   * @param newDataset
   * @param tipVersion - a top level history version for a dataset.
   * @return
   * @throws DatasetVersionNotFoundException
   */
  InitialPreviewResponse createPreviewResponseForExistingDataset(
      VirtualDatasetUI newDataset,
      DatasetVersionResourcePath tipVersion,
      String engineName,
      String sessionId,
      String triggerJob)
      throws DatasetVersionNotFoundException, NamespaceException {
    JobId jobId = null;
    if (shouldTriggerJob(triggerJob)) {
      SqlQuery query =
          new SqlQuery(
              newDataset.getSql(),
              newDataset.getState().getContextList(),
              username(),
              engineName,
              sessionId);
      MetadataJobStatusListener listener = new MetadataJobStatusListener(this, newDataset, null);
      listener.waitToApplyMetadataAndSaveDataset();

      JobData jobData =
          executor.runQueryWithListener(
              query,
              QueryType.UI_PREVIEW,
              tipVersion.getDataset(),
              newDataset.getVersion(),
              listener);
      jobId = jobData.getJobId();
      listener.setJobId(jobId);
    }
    return getInitialPreviewResponse(
        newDataset, jobId, new SessionId().setId(sessionId), tipVersion, null, null);
  }

  // Convert String to boolean, but with default as true.
  // Only if s is exactly matching false, then it return false
  // Otherwise it returns true.
  // s - null or empty -> true
  // s - true -> true
  // s - false -> false
  // s - falsee -> true
  // s - truee -> true
  // s - xyz -> true
  public static boolean shouldTriggerJob(String s) {
    return (s == null) || !"false".equalsIgnoreCase(s);
  }

  private String username() {
    return context.getUserPrincipal().getName();
  }

  /**
   * Helper method to create {@link InitialPreviewResponse} from given inputs
   *
   * @param datasetUI
   * @param job
   * @param tipVersion a combination of dataset version + path to a dataset. It represents a top
   *     history version. Path here could differ from path that {@code datasetUI} has, as {@code
   *     datasetUI} could be a history version, that references on other dataset with different
   *     path.
   * @param maxRecords
   * @param catchExecutionError
   * @return
   * @throws DatasetVersionNotFoundException
   * @throws NamespaceException
   * @throws JobNotFoundException
   */
  InitialPreviewResponse createPreviewResponse(
      VirtualDatasetUI datasetUI,
      JobData job,
      DatasetVersionResourcePath tipVersion,
      BufferAllocator allocator,
      Integer maxRecords,
      boolean catchExecutionError)
      throws DatasetVersionNotFoundException, NamespaceException, JobNotFoundException {
    JobDataFragment dataLimited = null;
    ApiErrorModel<?> error = null;
    try (AutoCloseables.RollbackCloseable cls = new AutoCloseables.RollbackCloseable(true)) {
      if (maxRecords == null) {
        maxRecords = INITIAL_RESULTSET_SIZE;
      }

      try {
        if (maxRecords > 0) {
          JobDataClientUtils.waitForFinalState(jobsService, job.getJobId());
          dataLimited = cls.add(job.truncate(allocator, maxRecords));
        } else {
          final JobDetailsRequest request =
              JobDetailsRequest.newBuilder()
                  .setJobId(JobsProtoUtil.toBuf(job.getJobId()))
                  .setUserName(username())
                  .build();
          JobDetails jobDetails = jobsService.getJobDetails(request);
          if (JobsProtoUtil.getLastAttempt(jobDetails).getInfo().getBatchSchema() == null) {
            JobDataClientUtils.waitForBatchSchema(
                jobsService, JobsProtoUtil.toStuff(jobDetails.getJobId()));
            jobDetails = jobsService.getJobDetails(request);
          }
          dataLimited =
              cls.add(
                  getDataOnlyWithColumns(
                      JobsProtoUtil.toStuff(jobDetails.getJobId()),
                      job.getSessionId(),
                      JobsProtoUtil.getLastAttempt(jobDetails).getInfo().getBatchSchema()));
        }
      } catch (Exception ex) {
        if (!catchExecutionError) {
          throw ex;
        }

        if (ex instanceof UserException) {
          // TODO - Why is this not thrown?
          toInvalidQueryException(
              (UserException) ex,
              datasetUI.getSql(),
              ImmutableList.<String>of(),
              job.getJobId(),
              job.getSessionId());
        }
        error =
            new ApiErrorModel<Void>(
                ApiErrorModel.ErrorType.INITIAL_PREVIEW_ERROR,
                ex.getMessage(),
                GenericErrorMessage.printStackTrace(ex),
                null);
      }
      cls.commit();
    } catch (DatasetVersionNotFoundException | NamespaceException | JobNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      Throwables.throwIfUnchecked(ex);
      throw new RuntimeException(ex);
    }
    return getInitialPreviewResponse(
        datasetUI, job.getJobId(), job.getSessionId(), tipVersion, dataLimited, error);
  }

  private InitialPreviewResponse getInitialPreviewResponse(
      VirtualDatasetUI datasetUI,
      JobId jobId,
      SessionId sessionId,
      DatasetVersionResourcePath tipVersion,
      JobDataFragment dataLimited,
      ApiErrorModel<?> error)
      throws DatasetVersionNotFoundException, NamespaceException {
    final History history =
        getHistory(tipVersion.getDataset(), datasetUI.getVersion(), tipVersion.getVersion());
    // This is requires as BE generates apiLinks, that is used by UI to send requests for
    // preview/run. In case, when history
    // of a dataset reference on a version for other dataset. And a user navigate to that version
    // and tries to preview it,
    // we would not be resolve a tip version and preview will fail. We should always send requests
    // to original dataset
    // path (tip version path) to be able to get a preview/run data
    // TODO(DX-14701) move links from BE to UI
    datasetUI.setFullPathList(tipVersion.getDataset().toPathList());
    return InitialPreviewResponse.of(
        newDataset(datasetUI, tipVersion.getVersion()),
        jobId,
        sessionId,
        dataLimited,
        true,
        history,
        error);
  }

  private JobDataFragment getDataOnlyWithColumns(
      JobId jobId, SessionId sessionId, ByteString batchSchema) {
    if (batchSchema == null) {
      return null;
    }
    BatchSchema schema = BatchSchema.deserialize(batchSchema);
    List<Column> columns = JobDataFragmentWrapper.getColumnsFromSchema(schema).values().asList();
    return new JobDataFragment() {
      @Override
      public JobId getJobId() {
        return jobId;
      }

      @Override
      public SessionId getSessionId() {
        return sessionId;
      }

      @Override
      public List<Column> getColumns() {
        return columns;
      }

      @Override
      public List<Field> getFields() {
        return schema.getFields();
      }

      @Override
      public List<RecordBatchHolder> getRecordBatches() {
        return Collections.emptyList();
      }

      @Override
      public int getReturnedRowCount() {
        return 0;
      }

      @Override
      public Column getColumn(String name) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String extractString(String column, int index) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Object extractValue(String column, int index) {
        throw new UnsupportedOperationException();
      }

      @Override
      public DataType extractType(String column, int index) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void close() {}
    };
  }

  InitialRunResponse createRunResponse(
      VirtualDatasetUI datasetUI, JobId jobId, SessionId sessionId, DatasetVersion tipVersion)
      throws DatasetVersionNotFoundException, NamespaceException {
    final History history =
        getHistory(
            new DatasetPath(datasetUI.getFullPathList()), datasetUI.getVersion(), tipVersion);
    return new InitialRunResponse(
        newDataset(datasetUI, null),
        JobResource.getPaginationURL(jobId),
        jobId,
        sessionId,
        history);
  }

  InitialUntitledRunResponse createUntitledRunResponse(
      List<String> datasetPath, DatasetVersion tipVersion, JobId jobId, SessionId sessionId)
      throws DatasetVersionNotFoundException {
    return new InitialUntitledRunResponse(
        datasetPath,
        tipVersion.getVersion(),
        JobResource.getPaginationURL(jobId),
        jobId,
        sessionId);
  }

  InitialPreviewResponse createPreviewResponse(
      DatasetPath path,
      Transformer.DatasetAndData datasetAndData,
      BufferAllocator allocator,
      int maxRecords,
      boolean catchExecutionError)
      throws DatasetVersionNotFoundException, NamespaceException, JobNotFoundException {
    return createPreviewResponse(
        datasetAndData.getDataset(),
        datasetAndData.getJobData(),
        new DatasetVersionResourcePath(path, datasetAndData.getDataset().getVersion()),
        allocator,
        maxRecords,
        catchExecutionError);
  }

  InitialPreviewResponse createReviewResponse(
      DatasetPath datasetPath,
      VirtualDatasetUI newDataset,
      String jobId,
      DatasetVersion tipVersion,
      BufferAllocator allocator,
      Integer limit)
      throws DatasetVersionNotFoundException, NamespaceException, JobNotFoundException {

    JobDetails jobDetails;
    JobUI job;
    JobDataFragment dataLimited = null;
    boolean isApproximate;
    ApiErrorModel<?> error = null;

    try (AutoCloseables.RollbackCloseable cls = new AutoCloseables.RollbackCloseable(true)) {
      final String userName = username();
      final JobDetailsRequest request =
          JobDetailsRequest.newBuilder()
              .setJobId(JobsProtoUtil.toBuf(new JobId(jobId)))
              .setUserName(userName)
              .build();
      jobDetails = jobsService.getJobDetails(request);
      SessionId sessionId =
          jobDetails.getSessionId() == null
              ? null
              : JobsProtoUtil.toStuff(jobDetails.getSessionId());
      job = new JobUI(jobsService, new JobId(jobId), sessionId, userName);
      final JobInfo jobInfo = JobsProtoUtil.getLastAttempt(jobDetails).getInfo();

      if (!canViewJobResult(jobInfo)) {
        throw new AccessControlException("Not authorized to access the job results");
      }

      QueryType queryType = jobInfo.getQueryType();
      isApproximate =
          queryType == QueryType.UI_PREVIEW
              || queryType == QueryType.UI_INTERNAL_PREVIEW
              || queryType == QueryType.UI_INITIAL_PREVIEW;

      JobState jobState = job.getJobAttempt().getState();
      switch (jobState) {
        case COMPLETED:
          {
            int finalLimit = limit == null ? INITIAL_RESULTSET_SIZE : limit;
            if (finalLimit > 0) {
              try {
                dataLimited = cls.add(job.getData().truncate(allocator, limit));
              } catch (Exception ex) {
                error =
                    new ApiErrorModel<Void>(
                        ApiErrorModel.ErrorType.INITIAL_PREVIEW_ERROR,
                        ex.getMessage(),
                        GenericErrorMessage.printStackTrace(ex),
                        null);
              }
            } else {
              dataLimited =
                  cls.add(
                      getDataOnlyWithColumns(
                          JobsProtoUtil.toStuff(jobDetails.getJobId()),
                          sessionId,
                          JobsProtoUtil.getLastAttempt(jobDetails).getInfo().getBatchSchema()));
            }
            break;
          }
        case FAILED:
          {
            com.dremio.dac.model.job.JobFailureInfo failureInfo =
                JobDetailsUI.toJobFailureInfo(
                    jobInfo.getFailureInfo(), jobInfo.getDetailedFailureInfo());
            if (failureInfo.getMessage() != null) {
              switch (failureInfo.getType()) {
                case PARSE:
                case EXECUTION:
                case VALIDATION:
                  error =
                      new ApiErrorModel<>(
                          ApiErrorModel.ErrorType.INVALID_QUERY,
                          failureInfo.getMessage(),
                          null,
                          new InvalidQueryException.Details(
                              jobInfo.getSql(),
                              ImmutableList.<String>of(),
                              failureInfo.getErrors(),
                              null,
                              jobInfo.getJobId(),
                              sessionId));
                  break;

                case UNKNOWN:
                default:
                  error =
                      new ApiErrorModel<Void>(
                          ApiErrorModel.ErrorType.INITIAL_PREVIEW_ERROR,
                          failureInfo.getMessage(),
                          null,
                          null);
              }
            }
            break;
          }
        case CANCELED:
          {
            // TODO(DX-14099): surface cancellation reason for initial preview
            break;
          }
        default:
          // nothing
          break;
      }
    } catch (DatasetVersionNotFoundException | NamespaceException | JobNotFoundException ex) {
      throw ex;
    } catch (Exception ex) {
      Throwables.throwIfUnchecked(ex);
      throw new RuntimeException(ex);
    }

    final History history = getHistory(datasetPath, newDataset.getVersion(), tipVersion);
    return InitialPreviewResponse.of(
        newDataset(newDataset, null),
        job.getJobId(),
        job.getSessionId(),
        dataLimited,
        isApproximate,
        history,
        error);
  }

  public InitialPreviewResponse newUntitled(
      BufferAllocator allocator,
      FromBase from,
      DatasetVersion version,
      List<String> context,
      Integer limit)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {
    return newUntitled(allocator, from, version, context, null, false, limit);
  }

  private List<String> getParentDataset(FromBase from) {
    switch (from.wrap().getType()) {
      case Table:
        return DatasetPath.defaultImpl(from.wrap().getTable().getDatasetPath()).toPathList();

      case SQL:
      case SubQuery:
      default:
        return null;
    }
  }

  /**
   * Check if UserException can be converted into {@code InvalidQueryException} and throw this
   * exception instead
   *
   * @param e
   * @param sql
   * @param context
   * @param jobId
   * @param sessionId
   * @return the original exception if it cannot be converted into {@code InvalidQueryException}
   */
  public static UserException toInvalidQueryException(
      UserException e, String sql, List<String> context, JobId jobId, SessionId sessionId) {
    return toInvalidQueryException(e, sql, context, null, jobId, sessionId);
  }

  /**
   * Check if UserException can be converted into {@code InvalidQueryException} and throw this
   * exception instead
   *
   * @param e
   * @param sql
   * @param context
   * @param datasetSummary
   * @param jobId
   * @param sessionId
   * @return the original exception if it cannot be converted into {@code InvalidQueryException}
   */
  public static UserException toInvalidQueryException(
      UserException e,
      String sql,
      List<String> context,
      DatasetSummary datasetSummary,
      JobId jobId,
      SessionId sessionId) {
    switch (e.getErrorType()) {
      case PARSE:
      case PLAN:
      case VALIDATION:
        String errorMessage = InvalidQueryErrorConverter.convert(e.getOriginalMessage());
        throw new InvalidQueryException(
            new InvalidQueryException.Details(
                sql, context, QueryError.of(e), datasetSummary, jobId, sessionId),
            e,
            errorMessage);

      default:
        return e;
    }
  }

  /**
   * Create a new untitled dataset, and load preview data.
   *
   * @param from Source from where the dataset is created (can be a query or other dataset)
   * @param version Initial version of the new dataset
   * @param context Dataset context or current schema
   * @return
   * @throws DatasetNotFoundException
   * @throws DatasetVersionNotFoundException
   * @throws NamespaceException
   */
  public InitialPreviewResponse newUntitled(
      BufferAllocator allocator,
      FromBase from,
      DatasetVersion version,
      List<String> context,
      DatasetSummary parentSummary,
      boolean prepare,
      Integer limit)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {
    return newUntitled(allocator, from, version, context, parentSummary, prepare, limit, false);
  }

  public InitialPreviewResponse newUntitled(
      BufferAllocator allocator,
      FromBase from,
      DatasetVersion version,
      List<String> context,
      DatasetSummary parentSummary,
      boolean prepare,
      Integer limit,
      String engineName,
      String sessionId,
      Map<String, VersionContextReq> references)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {
    return newUntitled(
        allocator,
        from,
        version,
        context,
        parentSummary,
        prepare,
        limit,
        false,
        engineName,
        sessionId,
        references);
  }

  /**
   * Create a new untitled dataset, and load preview data.
   *
   * @param from Source from where the dataset is created (can be a query or other dataset)
   * @param version Initial version of the new dataset
   * @param context Dataset context or current schema
   * @param runInSameThread runs metadata query in same AttemptManager thread
   * @return
   * @throws DatasetNotFoundException
   * @throws DatasetVersionNotFoundException
   * @throws NamespaceException
   */
  @WithSpan
  public InitialPreviewResponse newUntitled(
      BufferAllocator allocator,
      FromBase from,
      DatasetVersion version,
      List<String> context,
      DatasetSummary parentSummary,
      boolean prepare,
      Integer limit,
      boolean runInSameThread,
      String engineName,
      String sessionId,
      Map<String, VersionContextReq> references)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {

    final VirtualDatasetUI newDataset =
        createNewUntitledMetadataOnly(from, version, context, references);
    final Map<String, JobsVersionContext> sourceVersionMapping =
        createSourceVersionMapping(references);
    final SqlQuery query =
        new SqlQuery(
            newDataset.getSql(),
            newDataset.getState().getContextList(),
            username(),
            engineName,
            sessionId,
            sourceVersionMapping);

    JobId jobId = null;
    SessionId jobDataSessionId = null;
    try {
      final MetadataCollectingJobStatusListener listener =
          new MetadataCollectingJobStatusListener();
      final QueryType queryType = prepare ? QueryType.PREPARE_INTERNAL : QueryType.UI_PREVIEW;
      final JobData jobData =
          executor.runQueryWithListener(
              query,
              queryType,
              TMP_DATASET_PATH,
              newDataset.getVersion(),
              listener,
              runInSameThread,
              true);
      jobId = jobData.getJobId();
      jobDataSessionId = jobData.getSessionId();
      final QueryMetadata queryMetadata = listener.getMetadata();
      applyQueryMetaToDatasetAndSave(jobId, queryMetadata, newDataset, from);
      if (prepare) {
        limit = 0;
      }
      // in case of initial preview a returned dataset should be actual tip version. Dataset's path
      // and version should
      // be consistent and represent actual key in dataset version store. So use dataset's path and
      // version as tipVersion
      return createPreviewResponse(
          newDataset,
          jobData,
          new DatasetVersionResourcePath(
              new DatasetPath(newDataset.getFullPathList()), newDataset.getVersion()),
          allocator,
          limit,
          false);
    } catch (Exception ex) {
      List<String> parentDataset = getParentDataset(from);

      if (ex instanceof UserException) {
        // TODO - Why is this not thrown?
        toInvalidQueryException(
            (UserException) ex, query.getSql(), context, parentSummary, jobId, jobDataSessionId);
      }

      // make sure we pass the parentSummary so that the UI can render edit original sql
      throw new NewDatasetQueryException(
          new NewDatasetQueryException.ExplorePageInfo(
              parentDataset,
              query.getSql(),
              context,
              newDataset(newDataset, null).getDatasetType(),
              parentSummary),
          ex);
    }
  }

  /**
   * Create a new untitled dataset, and load preview data.
   *
   * @param from Source from where the dataset is created (can be a query or other dataset)
   * @param version Initial version of the new dataset
   * @param context Dataset context or current schema
   * @return
   * @throws DatasetNotFoundException
   * @throws DatasetVersionNotFoundException
   * @throws NamespaceException
   * @throws NewDatasetQueryException
   */
  public InitialUntitledRunResponse newTmpUntitled(
      FromBase from,
      DatasetVersion version,
      List<String> context,
      String engineName,
      String sessionId,
      Map<String, VersionContextReq> references)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {

    final VirtualDatasetUI vds = createNewUntitledMetadataOnly(from, version, context, references);
    final Map<String, JobsVersionContext> sourceVersionMapping =
        createSourceVersionMapping(references);
    final SqlQuery query =
        new SqlQuery(
            vds.getSql(),
            vds.getState().getContextList(),
            username(),
            engineName,
            sessionId,
            sourceVersionMapping);

    MetadataJobStatusListener listener = new MetadataJobStatusListener(this, vds, from);
    listener.waitToApplyMetadataAndSaveDataset();

    JobId jobId = null;
    SessionId jobDataSessionId = null;
    try {
      final JobData jobData =
          executor.runQueryWithListener(
              query,
              QueryType.UI_PREVIEW,
              TMP_DATASET_PATH,
              vds.getVersion(),
              listener,
              false,
              true);
      jobId = jobData.getJobId();
      jobDataSessionId = jobData.getSessionId();
      listener.setJobId(jobId);

      return createUntitledRunResponse(
          vds.getFullPathList(), vds.getVersion(), jobId, jobDataSessionId);
    } catch (Exception ex) {
      if (ex instanceof UserException) {
        throw toInvalidQueryException(
            (UserException) ex, query.getSql(), context, jobId, jobDataSessionId);
      }

      // make sure we pass the parentSummary so that the UI can render edit original sql
      throw new NewDatasetQueryException(
          new NewDatasetQueryException.ExplorePageInfo(
              getParentDataset(from),
              query.getSql(),
              context,
              newDataset(vds, null).getDatasetType(),
              null),
          ex);
    }
  }

  public InitialPreviewResponse newUntitled(
      BufferAllocator allocator,
      FromBase from,
      DatasetVersion version,
      List<String> context,
      DatasetSummary parentSummary,
      boolean prepare,
      Integer limit,
      boolean runInSameThread)
      throws DatasetNotFoundException,
          DatasetVersionNotFoundException,
          NamespaceException,
          NewDatasetQueryException {
    return newUntitled(
        allocator,
        from,
        version,
        context,
        parentSummary,
        prepare,
        limit,
        runInSameThread,
        null,
        null,
        null);
  }

  VirtualDatasetUI createNewUntitledMetadataOnly(
      FromBase from,
      DatasetVersion version,
      List<String> context,
      Map<String, VersionContextReq> references) {
    final VirtualDatasetUI newDataset =
        newDatasetBeforeQueryMetadata(
            TMP_DATASET_PATH,
            version,
            from.wrap(),
            context,
            username(),
            datasetService.getCatalog(),
            references);
    newDataset.setLastTransform(
        new Transform(TransformType.createFromParent)
            .setTransformCreateFromParent(new TransformCreateFromParent(from.wrap())));

    final List<SourceVersionReference> sourceVersionReferences =
        DatasetResourceUtils.createSourceVersionReferenceList(references);
    newDataset.setReferencesList(sourceVersionReferences);
    newDataset.getState().setReferenceList(sourceVersionReferences);

    return newDataset;
  }

  InitialRunResponse newUntitledAndRun(
      FromBase from,
      DatasetVersion version,
      List<String> context,
      String engineName,
      String sessionId,
      Map<String, VersionContextReq> references)
      throws DatasetNotFoundException, NamespaceException, DatasetVersionNotFoundException {

    final VirtualDatasetUI newDataset =
        createNewUntitledMetadataOnly(from, version, context, references);
    final Map<String, JobsVersionContext> sourceVersionMapping =
        createSourceVersionMapping(references);
    final SqlQuery query =
        new SqlQuery(
            newDataset.getSql(),
            newDataset.getState().getContextList(),
            username(),
            engineName,
            sessionId,
            sourceVersionMapping);

    newDataset.setLastTransform(
        new Transform(TransformType.createFromParent)
            .setTransformCreateFromParent(new TransformCreateFromParent(from.wrap())));
    MetadataCollectingJobStatusListener listener = new MetadataCollectingJobStatusListener();

    JobId jobId = null;
    SessionId jobDataSessionId = null;
    try {
      final JobData jobData =
          executor.runQueryWithListener(
              query, QueryType.UI_RUN, TMP_DATASET_PATH, version, listener);
      jobId = jobData.getJobId();
      jobDataSessionId = jobData.getSessionId();
      final QueryMetadata queryMetadata = listener.getMetadata();
      applyQueryMetaToDatasetAndSave(jobId, queryMetadata, newDataset, from);
      return createRunResponse(newDataset, jobId, jobDataSessionId, newDataset.getVersion());
    } catch (UserException e) {
      String failureMessage = e.getOriginalMessage();
      if (failureMessage.startsWith("ResourceAllocationException")) {
        throw UserException.dataReadError().message(failureMessage).build(logger);
      } else {
        throw toInvalidQueryException(e, query.getSql(), context, jobId, jobDataSessionId);
      }
    } catch (JobNotFoundException e) {
      // should never be thrown
      UserException uex = UserException.systemError(e).buildSilently();
      throw toInvalidQueryException(uex, query.getSql(), context, jobId, jobDataSessionId);
    }
  }

  /**
   * Create a new untitled dataset, and load preview data.
   *
   * @param from Source from where the dataset is created (can be a query or other dataset)
   * @param version Initial version of the new dataset
   * @param context Dataset context or current schema
   * @param engineName Engine to runt the query
   * @param sessionId SessionId
   * @param references References
   * @return {@link InitialUntitledRunResponse)}
   * @throws DatasetNotFoundException
   * @throws DatasetVersionNotFoundException
   */
  InitialUntitledRunResponse newTmpUntitledAndRun(
      FromBase from,
      DatasetVersion version,
      List<String> context,
      String engineName,
      String sessionId,
      Map<String, VersionContextReq> references)
      throws DatasetNotFoundException, DatasetVersionNotFoundException {

    final VirtualDatasetUI newDataset =
        createNewUntitledMetadataOnly(from, version, context, references);
    final Map<String, JobsVersionContext> sourceVersionMapping =
        createSourceVersionMapping(references);
    final SqlQuery query =
        new SqlQuery(
            newDataset.getSql(),
            newDataset.getState().getContextList(),
            username(),
            engineName,
            sessionId,
            sourceVersionMapping);

    newDataset.setLastTransform(
        new Transform(TransformType.createFromParent)
            .setTransformCreateFromParent(new TransformCreateFromParent(from.wrap())));
    MetadataJobStatusListener listener = new MetadataJobStatusListener(this, newDataset, from);
    // Call non-blocking method to apply metadata and save dataset when the metadata is collected.
    listener.waitToApplyMetadataAndSaveDataset();

    JobId jobId = null;
    SessionId jobDataSessionId = null;
    try {
      final JobData jobData =
          executor.runQueryWithListener(
              query, QueryType.UI_RUN, TMP_DATASET_PATH, version, listener);
      jobId = jobData.getJobId();
      jobDataSessionId = jobData.getSessionId();
      listener.setJobId(jobId);

      return createUntitledRunResponse(
          newDataset.getFullPathList(), newDataset.getVersion(), jobId, jobDataSessionId);
    } catch (UserException e) {
      String failureMessage = e.getOriginalMessage();
      if (failureMessage.startsWith("ResourceAllocationException")) {
        throw UserException.dataReadError().message(failureMessage).build(logger);
      } else {
        throw toInvalidQueryException(e, query.getSql(), context, jobId, jobDataSessionId);
      }
    }
  }

  protected Map<String, JobsVersionContext> createSourceVersionMapping(
      Map<String, VersionContextReq> references) {
    final Map<String, JobsVersionContext> sourceVersionMapping = new HashMap<>();

    if (references != null) {
      references.forEach(
          (source, ref) -> {
            if (ref.getType() == VersionContextReq.VersionContextType.BRANCH) {
              sourceVersionMapping.put(
                  source,
                  new JobsVersionContext(
                      JobsVersionContext.VersionContextType.BRANCH, ref.getValue()));
            } else if (ref.getType() == VersionContextReq.VersionContextType.TAG) {
              sourceVersionMapping.put(
                  source,
                  new JobsVersionContext(
                      JobsVersionContext.VersionContextType.TAG, ref.getValue()));
            } else if (ref.getType() == VersionContextReq.VersionContextType.COMMIT) {
              sourceVersionMapping.put(
                  source,
                  new JobsVersionContext(
                      JobsVersionContext.VersionContextType.BARE_COMMIT, ref.getValue()));
            }
          });
    }

    return sourceVersionMapping;
  }

  public void applyQueryMetaToDatasetAndSave(
      JobId jobId, QueryMetadata queryMetadata, VirtualDatasetUI newDataset, FromBase from)
      throws DatasetNotFoundException, NamespaceException, JobNotFoundException {
    // get the job's info after the query metadata is available to make sure the schema has already
    // been populated
    final JobDetails jobDetails =
        jobsService.getJobDetails(
            JobDetailsRequest.newBuilder()
                .setJobId(JobsProtoUtil.toBuf(jobId))
                .setUserName(username())
                .build());
    final JobInfo jobInfo = JobsProtoUtil.getLastAttempt(jobDetails).getInfo();

    QuerySemantics.populateSemanticFields(
        JobsProtoUtil.toStuff(queryMetadata.getFieldTypeList()), newDataset.getState());

    applyQueryMetadata(
        datasetService.getCatalog(),
        newDataset,
        Optional.ofNullable(jobInfo.getParentsList()),
        Optional.ofNullable(jobInfo.getBatchSchema()).map(BatchSchema::deserialize),
        Optional.ofNullable(jobInfo.getGrandParentsList()),
        queryMetadata);

    if (from == null || from.wrap().getType() == FromType.SQL) {
      newDataset.setState(QuerySemantics.extract(queryMetadata));
    }

    datasetService.putVersion(newDataset);
  }

  public static VirtualDatasetUI newDatasetBeforeQueryMetadata(
      final DatasetPath datasetPath,
      final DatasetVersion version,
      final From from,
      final List<String> sqlContext,
      final String owner,
      Catalog catalog,
      final Map<String, VersionContextReq> references) {
    VirtualDatasetState dss = new VirtualDatasetState().setFrom(from);
    dss.setContextList(sqlContext);
    VirtualDatasetUI vds = new VirtualDatasetUI();

    vds.setOwner(owner);
    vds.setIsNamed(false);
    vds.setVersion(version);
    vds.setFullPathList(datasetPath.toPathList());
    vds.setName(datasetPath.getDataset().getName());
    vds.setState(dss);
    vds.setSql(SQLGenerator.generateSQL(dss));
    vds.setId(UUID.randomUUID().toString());
    vds.setContextList(sqlContext);

    switch (from.getType()) {
      case SQL:
        vds.setDerivation(Derivation.SQL);
        break;
      case Table:
        vds.setDerivation(Derivation.DERIVED_UNKNOWN);
        dss.setReferredTablesList(Collections.singletonList(from.getTable().getAlias()));

        updateVersionedDatasetId(vds, from, catalog, references);
        break;
      case SubQuery:
      default:
        vds.setDerivation(Derivation.UNKNOWN);
        dss.setReferredTablesList(Collections.singletonList(from.getSubQuery().getAlias()));
        break;
    }

    // if we're doing a select * from table, and the context matches the base path of the table,
    // let's avoid qualifying the table name.
    if (from.getType() == FromType.Table) {
      NamespaceKey path = new DatasetPath(from.getTable().getDatasetPath()).toNamespaceKey();
      if (path.getParent().getPathComponents().equals(sqlContext)) {
        vds.setSql(String.format("SELECT * FROM %s", SqlUtils.quoteIdentifier(path.getLeaf())));
      }
    }

    return vds;
  }

  /** Update the datasetId in the given dataset UI. This method only applies to versioned table. */
  private static void updateVersionedDatasetId(
      VirtualDatasetUI vds,
      final From from,
      Catalog catalog,
      final Map<String, VersionContextReq> references) {
    if (references == null || references.isEmpty() || catalog == null) {
      return;
    }

    final NamespaceKey namespaceKey =
        new DatasetPath(from.getTable().getDatasetPath()).toNamespaceKey();
    final Map<String, VersionContext> versionContextMapping =
        DatasetResourceUtils.createSourceVersionMapping(references);

    if (!CatalogUtil.requestedPluginSupportsVersionedTables(namespaceKey, catalog)) {
      return;
    }

    vds.setId(catalog.resolveCatalog(versionContextMapping).getDatasetId(namespaceKey));
  }

  /**
   * Get the history before a given version. This should only be used if this version is known to be
   * the last version in the history. Otherwise, the other version of this method that takes a tip
   * version as well as a current version.
   *
   * @param datasetPath
   * @param currentDataset
   * @return
   * @throws DatasetVersionNotFoundException
   */
  History getHistory(final DatasetPath datasetPath, DatasetVersion currentDataset)
      throws DatasetVersionNotFoundException {
    return getHistory(datasetPath, currentDataset, currentDataset);
  }

  /**
   * Get the history for a given dataset path, starting at a given version to treat as the tip of
   * the history.
   *
   * <p>The current version is also passed because it can trail behind the tip of the history if a
   * user selects a previous point in the history. We still want to show the future history items to
   * allow them to navigate "Back to the Future" (TM).
   *
   * @param datasetPath the dataset path of the version at the tip of the history
   * @param versionToMarkCurrent the version currently selected in the client
   * @param tipVersion the latest history item known which may be passed the selected
   *     versionToMarkCurrent, this can be null and the tip will be assumed to be the
   *     versionToMarkCurrent the same behavior as the version of this method that lacks the
   *     tipVersion entirely
   * @return
   * @throws DatasetVersionNotFoundException
   */
  History getHistory(
      final DatasetPath datasetPath,
      final DatasetVersion versionToMarkCurrent,
      DatasetVersion tipVersion)
      throws DatasetVersionNotFoundException {
    // while the current callers of this method all do their own null guarding, adding this
    // defensively for
    // future callers that may fail to handle this case
    tipVersion = tipVersion != null ? tipVersion : versionToMarkCurrent;

    final List<HistoryItem> historyItems = new ArrayList<>();
    VirtualDatasetUI currentDataset = null;
    DatasetVersion currentVersion = tipVersion;
    DatasetPath currentPath = datasetPath;
    NameDatasetRef previousVersion;
    try {
      do {
        currentDataset = datasetService.getVersion(currentPath, currentVersion);
        DatasetVersionResourcePath versionedResourcePath =
            new DatasetVersionResourcePath(currentPath, currentVersion);

        historyItems.add(
            new HistoryItem(
                versionedResourcePath,
                JobState.COMPLETED,
                TransformBase.unwrap(currentDataset.getLastTransform())
                    .accept(new DescribeTransformation()),
                username(),
                currentDataset.getCreatedAt(),
                0L,
                true,
                null,
                null));

        previousVersion = currentDataset.getPreviousVersion();
        if (previousVersion != null) {
          currentVersion = new DatasetVersion(previousVersion.getDatasetVersion());
          currentPath = new DatasetPath(previousVersion.getDatasetPath());
        }
      } while (previousVersion != null);
    } catch (DatasetNotFoundException | DatasetVersionNotFoundException e) {
      // If for some reason the history chain is broken/corrupt, we will get an
      // DatasetNotFoundException or
      // DatasetVersionNotFoundException.  If we have a partial history, we return it.  If no
      // history items are found,
      // rethrow the exception.
      if (currentDataset == null) {
        throw e;
      }

      logger.warn(
          "Dataset history for [{}] and tip version [{}] is broken at path [{}] and version [{}]",
          datasetPath,
          tipVersion,
          currentPath,
          currentVersion);
    }

    Collections.reverse(historyItems);

    final Catalog catalog = datasetService.getCatalog();
    final NamespaceKey namespaceKey = new NamespaceKey(datasetPath.toPathList());
    final boolean versioned =
        CatalogUtil.requestedPluginSupportsVersionedTables(namespaceKey, catalog);

    // isEdited
    DatasetVersion savedVersion = null;
    try {
      savedVersion =
          (versioned
                  || CatalogUtil.supportsInterface(
                      namespaceKey, catalog, SupportsMutatingViews.class))
              ? datasetService.getLatestVersionByOrigin(
                  datasetPath, tipVersion, DatasetVersionOrigin.SAVE)
              : datasetService.get(datasetPath).getVersion();
    } catch (DatasetNotFoundException | NamespaceException e) {
      // do nothing
    }

    boolean isEdited =
        savedVersion == null
            ? currentDataset.getDerivation() == Derivation.SQL || historyItems.size() > 1
            : !tipVersion.equals(savedVersion);

    return new History(
        historyItems, versionToMarkCurrent, currentDataset.getVersion().getValue(), isEdited);
  }

  /**
   * When we save a version that previously lacked a name, or we are changing the name of a dataset,
   * we go back and update the data path for all untitled versions stored throughout the history.
   *
   * <p>In the case of a version that did have a name other than untitled, the most recent history
   * item will be saved again with the requested name because finding a history item currently
   * requires a version number and dataset path.
   *
   * @param versionToSave the dataset path of the version at the tip of the history
   * @param newPath the new path to copy throughout the history
   * @throws DatasetVersionNotFoundException
   * @throws DatasetNotFoundException
   * @throws NamespaceException
   */
  void rewriteHistory(final VirtualDatasetUI versionToSave, final DatasetPath newPath)
      throws DatasetVersionNotFoundException, DatasetNotFoundException {

    DatasetVersion previousDatasetVersion;
    DatasetPath previousPath;
    NameDatasetRef previousVersion;
    VirtualDatasetUI currentDataset = versionToSave;
    boolean previousVersionRequiresRename;
    // Rename the last history item, and all previous history items that are unnamed or with
    // different path.
    // The loop terminates when hitting the end of the history or a named history item, this
    // means that the history for one dataset can contain history items that are stored
    // with a name it had previously.
    do {
      previousVersion = currentDataset.getPreviousVersion();
      // change the path in this history item to the new one, will be persisted below
      // after possibly changing the link to the previous version if it will also be renamed
      currentDataset.setFullPathList(newPath.toPathList());
      if (previousVersion != null) {
        previousPath = new DatasetPath(previousVersion.getDatasetPath());
        previousDatasetVersion = new DatasetVersion(previousVersion.getDatasetVersion());
        previousVersionRequiresRename = !previousPath.equals(newPath);
        VirtualDatasetUI previousDataset =
            datasetService.getVersion(previousPath, previousDatasetVersion);
        // If the previous VDS version is incomplete, ignore that version.  This could happen when
        // the user click on a
        // PDS, an incomplete VDS version is created to show the PDS in UI.  If the user modify the
        // SQL and save the
        // VDS, the previous VDS version is incomplete since it never run and doesn't have metadata.
        try {
          DatasetVersionMutator.validate(previousPath, previousDataset);
        } catch (Exception e) {
          previousVersionRequiresRename = false;
        }

        if (previousVersionRequiresRename) {
          // create a new link to the previous dataset with a changed dataset path
          NameDatasetRef prev =
              new NameDatasetRef()
                  .setDatasetPath(newPath.toPathString())
                  .setDatasetVersion(previousVersion.getDatasetVersion());
          currentDataset.setPreviousVersion(prev);
          currentDataset.setName(newPath.getDataset().getName());
          datasetService.putVersion(currentDataset);
          currentDataset = previousDataset;
        } else {
          datasetService.putVersion(currentDataset);
        }
      } else {
        previousVersionRequiresRename = false;
        datasetService.putVersion(currentDataset);
      }
    } while (previousVersionRequiresRename);
  }

  public static void applyQueryMetadata(
      Catalog userCatalog,
      VirtualDatasetUI dataset,
      Optional<List<ParentDatasetInfo>> parents,
      Optional<BatchSchema> batchSchema,
      Optional<List<ParentDataset>> grandParents,
      QueryMetadata metadata) {
    List<ViewFieldType> viewFieldTypesList = JobsProtoUtil.toStuff(metadata.getFieldTypeList());
    if (batchSchema.isPresent()) {
      dataset.setSqlFieldsList(ViewFieldsHelper.getBatchSchemaFields(batchSchema.get()));
      dataset.setRecordSchema(batchSchema.get().toByteString());
    } else {
      dataset.setSqlFieldsList(viewFieldTypesList);
    }

    if (parents.isPresent()) {
      List<ParentDataset> otherParents = new ArrayList<>();
      for (ParentDatasetInfo parent : parents.get()) {
        otherParents.add(
            new ParentDataset()
                .setDatasetPathList(parent.getDatasetPathList())
                .setType(parent.getType())
                .setLevel(1));
      }
      dataset.setParentsList(otherParents);
    } else {
      dataset.setParentsList(Collections.emptyList());
    }

    dataset.setGrandParentsList(grandParents.orElse(Collections.emptyList()));
    updateDerivationAfterLearningOriginsAndAncestors(userCatalog, dataset);
  }

  private static void updateDerivationAfterLearningOriginsAndAncestors(
      Catalog userCatalog, VirtualDatasetUI newDataset) {
    // only resolve if we need to, otherwise we should leave the previous derivation alone. (e.g.
    // during a transform)
    if (newDataset.getDerivation() != Derivation.DERIVED_UNKNOWN) {
      return;
    }

    // if we have don't have one parent, we must have had issues detecting parents of SQL we
    // generated, fallback.
    if (newDataset.getParentsList() != null
        && newDataset.getParentsList().size() != 1
        && newDataset.getParentsList().get(0) != null) {
      newDataset.setDerivation(Derivation.UNKNOWN);
      return;
    }

    List<NamespaceKey> parentsUpstreamPhysicalDatasets =
        userCatalog.getUpstreamPhysicalDatasets(
            new NamespaceKey(newDataset.getParentsList().get(0).getDatasetPathList()));

    // logic: if we have a single parent and that parent is also the only
    // table that we depend on, then we are derived from a physical
    // dataset. Otherwise, we are a virtual dataset.
    if (parentsUpstreamPhysicalDatasets.size() == 1
        && parentsUpstreamPhysicalDatasets
            .iterator()
            .next()
            .getPathComponents()
            .equals(newDataset.getParentsList().get(0).getDatasetPathList())) {
      newDataset.setDerivation(Derivation.DERIVED_PHYSICAL);
    } else {
      newDataset.setDerivation(Derivation.DERIVED_VIRTUAL);
    }
  }

  protected DatasetUI newDataset(VirtualDatasetUI vds, DatasetVersion tipVersion)
      throws NamespaceException {
    return DatasetUI.newInstance(vds, tipVersion, datasetService.getCatalog());
  }

  protected boolean canViewJobResult(JobInfo jobInfo) {
    return true;
  }

  /**
   * Helper method to create {@link InitialPreviewResponse} for physical dataset without running a
   * query job.
   *
   * @param from
   * @param newVersion
   * @param context
   * @param datasetConfig
   * @param references
   * @return
   * @throws NamespaceException
   */
  @WithSpan
  protected InitialPreviewResponse createPreviewResponseForPhysicalDataset(
      FromBase from,
      DatasetVersion newVersion,
      List<String> context,
      DatasetConfig datasetConfig,
      Map<String, VersionContextReq> references,
      String sql)
      throws NamespaceException {
    final VirtualDatasetUI newDataset =
        createNewUntitledMetadataOnly(from, newVersion, context, references);
    List<ParentDataset> parents = new ArrayList<>();
    DatasetType parentType = datasetConfig.getType();
    List<String> parentFullPathList = datasetConfig.getFullPathList();
    final ParentDataset parent =
        new ParentDataset().setDatasetPathList(parentFullPathList).setType(parentType);
    parents.add(parent);
    newDataset.setParentsList(parents);
    switch (parentType) {
      case PHYSICAL_DATASET:
      case PHYSICAL_DATASET_SOURCE_FILE:
      case PHYSICAL_DATASET_SOURCE_FOLDER:
      case PHYSICAL_DATASET_HOME_FILE:
      case PHYSICAL_DATASET_HOME_FOLDER:
        newDataset.setDerivation(Derivation.DERIVED_PHYSICAL);
        break;

      case VIRTUAL_DATASET:
        newDataset.setDerivation(Derivation.DERIVED_VIRTUAL);
        break;

      default:
        newDataset.setDerivation(Derivation.DERIVED_UNKNOWN);
    }

    if (sql != null) {
      newDataset.setSql(sql);
    }

    // Save the incomplete dataset (without metadata) to allow data graph and catalog working on UI.
    // Later run/preview calls will save the complete dataset.
    datasetService.putTempVersionWithoutValidation(newDataset);

    final DatasetUI datasetUI = newDataset(newDataset, null);
    final History history =
        getHistory(new DatasetPath(datasetUI.getFullPath()), newDataset.getVersion(), null);

    return InitialPreviewResponse.of(datasetUI, null, new SessionId(), null, true, history, null);
  }
}
