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
package com.dremio.service.jobs;

import static com.dremio.exec.planner.sql.SqlExceptionHelper.END_COLUMN_CONTEXT;
import static com.dremio.exec.planner.sql.SqlExceptionHelper.END_LINE_CONTEXT;
import static com.dremio.exec.planner.sql.SqlExceptionHelper.START_COLUMN_CONTEXT;
import static com.dremio.exec.planner.sql.SqlExceptionHelper.START_LINE_CONTEXT;
import static com.dremio.service.jobs.JobIndexKeys.JOB_STATE;
import static com.dremio.service.jobs.JobsConstant.DEFAULT_DATASET_TYPE;
import static com.dremio.service.jobs.JobsConstant.DOT_BACKSLASH;
import static com.dremio.service.jobs.JobsConstant.EMPTY_DATASET_FIELD;
import static com.dremio.service.jobs.JobsConstant.EXTERNAL_QUERY;
import static com.dremio.service.jobs.JobsConstant.METADATA;
import static com.dremio.service.jobs.JobsConstant.QUOTES;
import static com.dremio.service.jobs.JobsConstant.UNAVAILABLE;
import static com.dremio.service.jobs.JobsConstant.__ACCELERATOR;

import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes.SearchQuery;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.AttemptEvent.State;
import com.dremio.exec.proto.UserBitShared.DremioPBError;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import com.dremio.exec.proto.UserBitShared.ExternalId;
import com.dremio.exec.proto.UserBitShared.QueryResult.QueryState;
import com.dremio.exec.proto.beans.NodeEndpoint;
import com.dremio.exec.record.RecordBatchHolder;
import com.dremio.exec.store.easy.arrow.ArrowFileMetadata;
import com.dremio.service.job.ActiveJobSummary;
import com.dremio.service.job.JobAndUserStat;
import com.dremio.service.job.JobCountByQueryType;
import com.dremio.service.job.JobDetails;
import com.dremio.service.job.JobStats;
import com.dremio.service.job.JobSummary;
import com.dremio.service.job.RecentJobSummary;
import com.dremio.service.job.RequestType;
import com.dremio.service.job.StoreJobResultRequest;
import com.dremio.service.job.SubmitJobRequest;
import com.dremio.service.job.UniqueUsersCountByQueryType;
import com.dremio.service.job.UsedReflections;
import com.dremio.service.job.VersionedDatasetPath;
import com.dremio.service.job.proto.DataSet;
import com.dremio.service.job.proto.DownloadInfo;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobFailureInfo;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobProtobuf;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.job.proto.JobState;
import com.dremio.service.job.proto.ParentDatasetInfo;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.job.proto.ResourceSchedulingInfo;
import com.dremio.service.job.proto.TableDatasetProfile;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtobufIOUtil;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** utility class. */
public final class JobsServiceUtil {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobsServiceUtil.class);

  private static final String ACCELERATOR_STORAGEPLUGIN_NAME = "__accelerator";

  private JobsServiceUtil() {}

  public static final ImmutableSet<JobState> finalJobStates =
      Sets.immutableEnumSet(JobState.CANCELED, JobState.COMPLETED, JobState.FAILED);

  public static final ImmutableSet<JobState> nonFinalJobStates =
      ImmutableSet.copyOf(Sets.difference(EnumSet.allOf(JobState.class), finalJobStates));

  public static final ImmutableSet<QueryState> finalProfileStates =
      Sets.immutableEnumSet(QueryState.CANCELED, QueryState.COMPLETED, QueryState.FAILED);

  private static final SearchQuery apparentlyAbandonedQuery;

  static {
    apparentlyAbandonedQuery =
        SearchQueryUtils.or(
            nonFinalJobStates.stream()
                .map(input -> SearchQueryUtils.newTermQuery(JOB_STATE, input.name()))
                .collect(Collectors.toList()));
  }

  static SearchQuery getApparentlyAbandonedQuery() {
    return apparentlyAbandonedQuery;
  }

  static boolean isNonFinalState(JobState jobState) {
    return jobState == null || !finalJobStates.contains(jobState);
  }

  static NodeEndpoint toStuff(CoordinationProtos.NodeEndpoint pb) {
    // TODO use schemas to do this...
    NodeEndpoint ep = new NodeEndpoint();
    ProtobufIOUtil.mergeFrom(pb.toByteArray(), ep, NodeEndpoint.getSchema());
    return ep;
  }

  static CoordinationProtos.NodeEndpoint toPB(NodeEndpoint stuf) {
    // TODO use schemas to do this...
    LinkedBuffer buffer = LinkedBuffer.allocate();
    byte[] bytes = ProtobufIOUtil.toByteArray(stuf, stuf.cachedSchema(), buffer);
    try {
      return CoordinationProtos.NodeEndpoint.parser().parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Cannot convert from protostuff to protobuf");
    }
  }

  /**
   * Translate job id to external id.
   *
   * @param jobId job id
   * @return external id
   */
  public static ExternalId getJobIdAsExternalId(JobId jobId) {
    UUID id = UUID.fromString(jobId.getId());
    return ExternalId.newBuilder()
        .setPart1(id.getMostSignificantBits())
        .setPart2(id.getLeastSignificantBits())
        .build();
  }

  /**
   * Translate external id to job id.
   *
   * @param id external id
   * @return job id
   */
  public static JobId getExternalIdAsJobId(ExternalId id) {
    return new JobId(new UUID(id.getPart1(), id.getPart2()).toString());
  }

  public static boolean ifJobAttemptHasRunningState(JobAttempt attempt) {
    for (com.dremio.exec.proto.beans.AttemptEvent event : attempt.getStateListList()) {
      if (event.getState() == com.dremio.exec.proto.beans.AttemptEvent.State.RUNNING) {
        return true;
      }
    }
    return false;
  }

  public static List<JobAndUserStat> buildDailyJobAndUserStats(
      int days, LocalDate today, List<RecordBatchHolder> results) {
    Set<String> dates = new HashSet<>();
    Map<String, List<JobCountByQueryType>> jobCountByQueryType = new HashMap<>();
    Map<String, List<UniqueUsersCountByQueryType>> uniqueUsersByQueryType = new HashMap<>();
    Map<String, Map<String, Long>> totalStats = new HashMap<>();
    for (RecordBatchHolder recordBatchHolder : results) {
      for (int i = 0; i < recordBatchHolder.getData().getVectors().get(0).getValueCount(); i++) {
        byte[] dateBytes = ((VarCharVector) recordBatchHolder.getData().getVectors().get(0)).get(i);
        if (dateBytes == null) {
          continue;
        }
        String date = new String(dateBytes, java.nio.charset.StandardCharsets.UTF_8);
        dates.add(date);
        byte[] queryTypeBytes =
            ((VarCharVector) recordBatchHolder.getData().getVectors().get(1)).get(i);
        byte[] uniqueUsersBytes =
            ((VarCharVector) recordBatchHolder.getData().getVectors().get(2)).get(i);
        String[] uniqueUsers =
            new String(uniqueUsersBytes, java.nio.charset.StandardCharsets.UTF_8).split(";");
        long jobsExecuted = ((BigIntVector) recordBatchHolder.getData().getVectors().get(3)).get(i);
        if (queryTypeBytes == null) {
          totalStats.putIfAbsent(date, new HashMap<>());
          totalStats.get(date).put("totalJobs", jobsExecuted);
          totalStats.get(date).put("totalUniqueUsers", (long) uniqueUsers.length);
          continue;
        }
        String queryType = new String(queryTypeBytes, java.nio.charset.StandardCharsets.UTF_8);
        jobCountByQueryType.putIfAbsent(date, new ArrayList<>());
        jobCountByQueryType
            .get(date)
            .add(
                JobCountByQueryType.newBuilder()
                    .setQueryType(com.dremio.service.job.QueryType.valueOf(queryType))
                    .setJobCount(jobsExecuted)
                    .build());
        uniqueUsersByQueryType.putIfAbsent(date, new ArrayList<>());
        uniqueUsersByQueryType
            .get(date)
            .add(
                UniqueUsersCountByQueryType.newBuilder()
                    .setQueryType(com.dremio.service.job.QueryType.valueOf(queryType))
                    .addAllUniqueUsers(Arrays.asList(uniqueUsers))
                    .build());
      }
    }
    List<JobAndUserStat> stats = new ArrayList<>();
    for (LocalDate date = today; date.isAfter(today.minusDays(days)); date = date.minusDays(1)) {
      if (dates.contains(date.toString())) {
        stats.add(
            JobAndUserStat.newBuilder()
                .setDate(date.toString())
                .setTotalJobs(totalStats.get(date.toString()).get("totalJobs"))
                .setTotalUniqueUsers(totalStats.get(date.toString()).get("totalUniqueUsers"))
                .addAllJobCountByQueryType(jobCountByQueryType.get(date.toString()))
                .addAllUniqueUsersCountByQueryType(uniqueUsersByQueryType.get(date.toString()))
                .build());
      } else {
        stats.add(JobAndUserStat.newBuilder().setDate(date.toString()).build());
      }
    }
    return stats;
  }

  /**
   * Returns job status, given query state.
   *
   * @param state query state
   * @return job status
   */
  static JobState queryStatusToJobStatus(QueryState state) {
    switch (state) {
      case STARTING:
        return JobState.STARTING;
      case RUNNING:
        return JobState.RUNNING;
      case ENQUEUED:
        return JobState.ENQUEUED;
      case COMPLETED:
        return JobState.COMPLETED;
      case CANCELED:
        return JobState.CANCELED;
      case FAILED:
        return JobState.FAILED;
      default:
        return JobState.NOT_SUBMITTED;
    }
  }

  /**
   * Returns job status, given attempt state.
   *
   * @param state attempt state
   * @return job status
   */
  static JobState attemptStatusToJobStatus(AttemptEvent.State state) {
    switch (state) {
      case METADATA_RETRIEVAL:
        return com.dremio.service.job.proto.JobState.METADATA_RETRIEVAL;
      case STARTING:
        return com.dremio.service.job.proto.JobState.STARTING;
      case PLANNING:
        return com.dremio.service.job.proto.JobState.PLANNING;
      case RUNNING:
        return com.dremio.service.job.proto.JobState.RUNNING;
      case COMPLETED:
        return com.dremio.service.job.proto.JobState.COMPLETED;
      case CANCELED:
        return com.dremio.service.job.proto.JobState.CANCELED;
      case FAILED:
        return com.dremio.service.job.proto.JobState.FAILED;
      case QUEUED:
        return com.dremio.service.job.proto.JobState.QUEUED;
      case PENDING:
        return com.dremio.service.job.proto.JobState.PENDING;
      case ENGINE_START:
        return com.dremio.service.job.proto.JobState.ENGINE_START;
      case EXECUTION_PLANNING:
        return com.dremio.service.job.proto.JobState.EXECUTION_PLANNING;
      default:
        return com.dremio.service.job.proto.JobState.INVALID_STATE;
    }
  }

  /**
   * convert proto to beans external state
   *
   * @param state external state
   * @return external status
   */
  static com.dremio.exec.proto.beans.AttemptEvent.State convertAttemptStatus(
      AttemptEvent.State state) {
    switch (state) {
      case METADATA_RETRIEVAL:
        return com.dremio.exec.proto.beans.AttemptEvent.State.METADATA_RETRIEVAL;
      case STARTING:
        return com.dremio.exec.proto.beans.AttemptEvent.State.STARTING;
      case PLANNING:
        return com.dremio.exec.proto.beans.AttemptEvent.State.PLANNING;
      case RUNNING:
        return com.dremio.exec.proto.beans.AttemptEvent.State.RUNNING;
      case COMPLETED:
        return com.dremio.exec.proto.beans.AttemptEvent.State.COMPLETED;
      case CANCELED:
        return com.dremio.exec.proto.beans.AttemptEvent.State.CANCELED;
      case FAILED:
        return com.dremio.exec.proto.beans.AttemptEvent.State.FAILED;
      case QUEUED:
        return com.dremio.exec.proto.beans.AttemptEvent.State.QUEUED;
      case PENDING:
        return com.dremio.exec.proto.beans.AttemptEvent.State.PENDING;
      case ENGINE_START:
        return com.dremio.exec.proto.beans.AttemptEvent.State.ENGINE_START;
      case EXECUTION_PLANNING:
        return com.dremio.exec.proto.beans.AttemptEvent.State.EXECUTION_PLANNING;
      default:
        return com.dremio.exec.proto.beans.AttemptEvent.State.INVALID_STATE;
    }
  }

  static com.dremio.exec.proto.beans.AttemptEvent createAttemptEvent(
      AttemptEvent.State state, long startTimestamp) {
    com.dremio.exec.proto.beans.AttemptEvent attemptEvent =
        new com.dremio.exec.proto.beans.AttemptEvent();
    attemptEvent.setState(JobsServiceUtil.convertAttemptStatus(state));
    attemptEvent.setStartTime(startTimestamp);
    return attemptEvent;
  }

  static JobFailureInfo toFailureInfo(String verboseError, DremioPBError.ErrorType rootErrorType) {
    // TODO: Would be easier if profile had structured error too
    String[] lines = verboseError.split("\n");
    if (lines.length < 2) {
      return null;
    }
    final JobFailureInfo.Type type;
    final String message;

    try {
      Object[] result = new MessageFormat("{0} ERROR: {1}").parse(lines[0]);

      String errorTypeAsString = (String) result[0];
      ErrorType errorType;
      try {
        errorType = ErrorType.valueOf(errorTypeAsString);
      } catch (IllegalArgumentException e) {
        errorType = null;
      }

      if (errorType != null) {
        switch (errorType) {
          case PARSE:
            type = JobFailureInfo.Type.PARSE;
            break;

          case PLAN:
            type = JobFailureInfo.Type.PLAN;
            break;

          case VALIDATION:
            type = JobFailureInfo.Type.VALIDATION;
            break;

          case FUNCTION:
            type = JobFailureInfo.Type.EXECUTION;
            break;

          default:
            type = JobFailureInfo.Type.UNKNOWN;
        }
      } else {
        type = JobFailureInfo.Type.UNKNOWN;
      }

      if (Strings.isNullOrEmpty((String) result[1])) {
        message = lines[1];
      } else {
        message = (String) result[1];
      }
    } catch (ParseException e) {
      LOGGER.warn("Cannot parse error message {}", lines[0], e);
      return null;
    }

    List<JobFailureInfo.Error> errors;
    JobFailureInfo.Error error = new JobFailureInfo.Error().setMessage(message);
    if (lines.length > 2) {
      // Parse all the context lines
      Map<String, String> context = new HashMap<>();
      for (int i = 2; i < lines.length; i++) {
        String line = lines[i];
        if (line.isEmpty()) {
          break;
        }

        String[] contextLine = line.split(" ", 2);
        if (contextLine.length < 2) {
          continue;
        }

        context.put(contextLine[0], contextLine[1]);
      }

      if (context.containsKey(START_LINE_CONTEXT)) {
        try {
          int startLine = Integer.parseInt(context.get(START_LINE_CONTEXT));
          int startColumn = Integer.parseInt(context.get(START_COLUMN_CONTEXT));
          int endLine = Integer.parseInt(context.get(END_LINE_CONTEXT));
          int endColumn = Integer.parseInt(context.get(END_COLUMN_CONTEXT));

          error
              .setStartLine(startLine)
              .setStartColumn(startColumn)
              .setEndLine(endLine)
              .setEndColumn(endColumn);
        } catch (NullPointerException | NumberFormatException e) {
          // Ignoring
        }
      }
    }
    errors = ImmutableList.of(error);

    /* Since rootErrorType can be nullable, we assign default error type as SYSTEM error. */
    UserBitShared.DremioPBError.ErrorType nullSafeRootErrorType =
        rootErrorType != null ? rootErrorType : UserBitShared.DremioPBError.ErrorType.SYSTEM;

    return new JobFailureInfo()
        .setMessage("Invalid Query Exception")
        .setType(type)
        .setRootErrorType(JobsProtoUtil.toBuf(nullSafeRootErrorType))
        .setErrorsList(errors);
  }

  private static JobInfo getJobInfo(StoreJobResultRequest request, long ttlExpireAtInMillis) {
    return new JobInfo()
        .setJobId(JobsProtoUtil.toStuff(request.getJobId()))
        .setSql(request.getSql())
        .setRequestType(JobsProtoUtil.toStuff(request.getRequestType()))
        .setUser(request.getUser())
        .setStartTime(request.getStartTime())
        .setFinishTime(request.getFinishTime())
        .setDatasetPathList(request.getDataset().getPathList())
        .setDatasetVersion(request.getDataset().getVersion())
        .setQueryType(JobsProtoUtil.toStuff(request.getQueryType()))
        .setDescription(request.getDescription())
        .setOriginalCost(request.getOriginalCost())
        .setOutputTableList(request.getOutputTableList())
        .setTtlExpireAt(ttlExpireAtInMillis);
  }

  private static List<JobAttempt> getAttempts(
      StoreJobResultRequest request, long ttlExpireAtInMillis) {
    final ArrayList<JobAttempt> jobAttempts = new ArrayList<>();
    jobAttempts.add(
        new JobAttempt()
            .setState(JobsProtoUtil.toStuff(request.getJobState()))
            .setInfo(getJobInfo(request, ttlExpireAtInMillis))
            .setAttemptId(request.getAttemptId())
            .setEndpoint(JobsProtoUtil.toStuff(request.getEndpoint()))
            .setStateListList(JobsProtoUtil.toBuf2(request.getStateListList())));

    return jobAttempts;
  }

  static JobResult toJobResult(StoreJobResultRequest request, long ttlExpireAtInMillis) {
    return new JobResult()
        .setAttemptsList(getAttempts(request, ttlExpireAtInMillis))
        .setCompleted(request.getJobState() == com.dremio.service.job.JobState.COMPLETED);
  }

  static JobSummary toJobSummary(Job job) {

    final JobAttempt firstJobAttempt = job.getAttempts().get(0);
    final JobInfo firstJobAttemptInfo = firstJobAttempt.getInfo();
    final JobAttempt lastJobAttempt = job.getJobAttempt();
    final JobInfo lastJobAttemptInfo = lastJobAttempt.getInfo();
    final List<com.dremio.exec.proto.beans.AttemptEvent> stateList;
    synchronized (lastJobAttempt) {
      if (lastJobAttempt.getStateListList() == null) {
        stateList = new ArrayList<>();
      } else {
        stateList = new ArrayList<>(lastJobAttempt.getStateListList());
      }
    }
    final List<com.dremio.service.job.proto.JobProtobuf.ParentDatasetInfo> parentsList;
    synchronized (lastJobAttemptInfo) {
      if (lastJobAttemptInfo.getParentsList() == null) {
        parentsList = new ArrayList<>();
      } else {
        parentsList = JobsProtoUtil.toBufParentDatasetInfoList(lastJobAttemptInfo.getParentsList());
      }
    }
    final List<String> chosenReflectionIds = lastJobAttemptInfo.getChosenReflectionIdsList();
    JobSummary.Builder jobSummaryBuilder =
        JobSummary.newBuilder()
            .setJobId(JobsProtoUtil.toBuf(job.getJobId()))
            .setJobState(JobsProtoUtil.toBuf(lastJobAttempt.getState()))
            .addAllStateList(JobsProtoUtil.toStuff2(stateList))
            .setUser(firstJobAttemptInfo.getUser())
            .addAllDatasetPath(lastJobAttemptInfo.getDatasetPathList())
            .setRequestType(JobsProtoUtil.toBuf(lastJobAttemptInfo.getRequestType()))
            .setQueryType(JobsProtoUtil.toBuf(lastJobAttemptInfo.getQueryType()))
            .setAccelerated(
                (chosenReflectionIds != null && !chosenReflectionIds.isEmpty())
                    || lastJobAttemptInfo.getResultsCacheUsed() != null)
            .setDatasetVersion(firstJobAttemptInfo.getDatasetVersion())
            .setSnowflakeAccelerated(false)
            .setSpilled(lastJobAttemptInfo.getSpillJobDetails() != null)
            .setSql(lastJobAttemptInfo.getSql())
            .setNumAttempts(job.getAttempts().size())
            .setRecordCount(job.getRecordCount())
            .setJobCompleted(job.isCompleted());

    if (job.getSessionId() != null) {
      jobSummaryBuilder.setSessionId(JobsProtoUtil.toBuf(job.getSessionId()));
    }

    if (lastJobAttemptInfo.getParentsList() != null
        && lastJobAttemptInfo.getParentsList().size() > 0) {
      jobSummaryBuilder.addAllParents(parentsList);
    }

    if (lastJobAttempt.getStats() != null && lastJobAttempt.getStats().getInputRecords() != null) {
      jobSummaryBuilder.setInputRecords(lastJobAttempt.getStats().getInputRecords());
    }

    if (lastJobAttemptInfo.getResourceSchedulingInfo() != null
        && lastJobAttemptInfo.getResourceSchedulingInfo().getQueueName() != null) {
      jobSummaryBuilder.setQueueName(lastJobAttemptInfo.getResourceSchedulingInfo().getQueueName());
    }

    if (lastJobAttemptInfo.getOriginalCost() != null) {
      jobSummaryBuilder.setOriginalCost(lastJobAttemptInfo.getOriginalCost());
    }

    if (lastJobAttemptInfo.getResourceSchedulingInfo() != null
        && lastJobAttemptInfo.getResourceSchedulingInfo().getEngineName() != null) {
      jobSummaryBuilder.setEngine(lastJobAttemptInfo.getResourceSchedulingInfo().getEngineName());
    }

    if (lastJobAttemptInfo.getResourceSchedulingInfo() != null
        && lastJobAttemptInfo.getResourceSchedulingInfo().getSubEngine() != null) {
      jobSummaryBuilder.setSubEngine(lastJobAttemptInfo.getResourceSchedulingInfo().getSubEngine());
    }

    if (lastJobAttempt.getDetails() != null
        && lastJobAttempt.getDetails().getWaitInClient() != null) {
      jobSummaryBuilder.setWaitInclient(lastJobAttempt.getDetails().getWaitInClient());
    }

    if (lastJobAttempt.getStats() != null && lastJobAttempt.getStats().getInputBytes() != null) {
      jobSummaryBuilder.setInputBytes(lastJobAttempt.getStats().getInputBytes());
    }

    if (lastJobAttempt.getStats() != null && lastJobAttempt.getStats().getOutputBytes() != null) {
      jobSummaryBuilder.setOutputBytes(lastJobAttempt.getStats().getOutputBytes());
    }

    if (lastJobAttempt.getStats() != null
        && lastJobAttempt.getStats().getIsOutputLimited() != null) {
      jobSummaryBuilder.setOutputLimited(lastJobAttempt.getStats().getIsOutputLimited());
    }

    if (lastJobAttempt.getStats() != null && lastJobAttempt.getStats().getOutputRecords() != null) {
      jobSummaryBuilder.setOutputRecords(lastJobAttempt.getStats().getOutputRecords());
    }

    if (lastJobAttemptInfo.getFailureInfo() != null) {
      jobSummaryBuilder.setFailureInfo(lastJobAttemptInfo.getFailureInfo());
    }

    if (firstJobAttemptInfo.getStartTime() != null) {
      jobSummaryBuilder.setStartTime(firstJobAttemptInfo.getStartTime());
    }

    if (lastJobAttemptInfo.getFinishTime() != null) {
      jobSummaryBuilder.setEndTime(lastJobAttemptInfo.getFinishTime());
    }

    if (lastJobAttemptInfo.getDescription() != null) {
      jobSummaryBuilder.setDescription(lastJobAttemptInfo.getDescription());
    }

    JobProtobuf.JobFailureInfo detailedJobFailureInfo =
        JobsProtoUtil.toBuf(lastJobAttemptInfo.getDetailedFailureInfo());
    if (detailedJobFailureInfo != null) {
      jobSummaryBuilder.setDetailedJobFailureInfo(detailedJobFailureInfo);
    }

    JobProtobuf.JobCancellationInfo jobCancellationInfo =
        JobsProtoUtil.toBuf(lastJobAttemptInfo.getCancellationInfo());
    if (jobCancellationInfo != null) {
      jobSummaryBuilder.setCancellationInfo(jobCancellationInfo);
    }

    ParentDatasetInfo parentDatasetInfo = null;
    if (lastJobAttemptInfo.getParentsList() != null
        && lastJobAttemptInfo.getParentsList().size() > 0) {
      parentDatasetInfo = lastJobAttemptInfo.getParentsList().get(0);
    }
    if (parentDatasetInfo != null) {
      jobSummaryBuilder.setParent(JobsProtoUtil.toBuf(parentDatasetInfo));
    }

    ByteString accelerationDetails = JobsProtoUtil.toBuf(lastJobAttempt.getAccelerationDetails());
    if (accelerationDetails != null) {
      jobSummaryBuilder.setAccelerationDetails(accelerationDetails);
    }

    return jobSummaryBuilder.build();
  }

  static ActiveJobSummary toActiveJobSummary(Job job) {

    final JobAttempt firstJobAttempt = job.getAttempts().get(0);
    final JobInfo firstJobAttemptInfo = firstJobAttempt.getInfo();
    final JobAttempt lastJobAttempt = job.getJobAttempt();
    final JobInfo lastJobAttemptInfo = lastJobAttempt.getInfo();

    ActiveJobSummary.Builder builder =
        ActiveJobSummary.newBuilder()
            .setJobId(JobsProtoUtil.toBuf(job.getJobId()).getId())
            .setStatus(JobsProtoUtil.toBuf(lastJobAttempt.getState()).toString())
            .setUserName(firstJobAttemptInfo.getUser())
            .setQueryType(JobsProtoUtil.toBuf(lastJobAttemptInfo.getQueryType()).toString())
            .setAccelerated(lastJobAttemptInfo.getAcceleration() != null)
            .setQuery(lastJobAttemptInfo.getSql())
            .setAttemptCount(job.getAttempts().size());

    if (firstJobAttemptInfo.getStartTime() != null) {
      builder.setSubmittedEpochMillis(firstJobAttemptInfo.getStartTime());
      builder.setSubmittedTs(
          Timestamp.newBuilder().setSeconds(firstJobAttemptInfo.getStartTime()).build());
    }

    AttemptsHelper helper = new AttemptsHelper(lastJobAttempt);
    if (helper.getStateTimeStamp(State.PENDING) != null) {
      builder.setAttemptStartedEpochMillis(helper.getStateTimeStamp(State.PENDING));
      builder.setAttemptStartedTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.PENDING)).build());
    }
    if (helper.getStateTimeStamp(State.METADATA_RETRIEVAL) != null) {
      builder.setMetadataRetrievalEpochMillis(helper.getStateTimeStamp(State.METADATA_RETRIEVAL));
      builder.setMetadataRetrievalTs(
          Timestamp.newBuilder()
              .setSeconds(helper.getStateTimeStamp(State.METADATA_RETRIEVAL))
              .build());
    }
    if (helper.getStateTimeStamp(State.PLANNING) != null) {
      builder.setPlanningStartEpochMillis(helper.getStateTimeStamp(State.PLANNING));
      builder.setPlanningStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.PLANNING)).build());
    }
    if (helper.getStateTimeStamp(State.QUEUED) != null) {
      builder.setQueryEnqueuedEpochMillis(helper.getStateTimeStamp(State.QUEUED));
      builder.setQueryEnqueuedTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.QUEUED)).build());
    }
    if (helper.getStateTimeStamp(State.ENGINE_START) != null) {
      builder.setEngineStartEpochMillis(helper.getStateTimeStamp(State.ENGINE_START));
      builder.setEngineStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.ENGINE_START)).build());
    }
    if (helper.getStateTimeStamp(State.EXECUTION_PLANNING) != null) {
      builder.setExecutionPlanningEpochMillis(helper.getStateTimeStamp(State.EXECUTION_PLANNING));
      builder.setExecutionPlanningTs(
          Timestamp.newBuilder()
              .setSeconds(helper.getStateTimeStamp(State.EXECUTION_PLANNING))
              .build());
    }
    if (helper.getStateTimeStamp(State.RUNNING) != null) {
      builder.setExecutionStartEpochMillis(helper.getStateTimeStamp(State.RUNNING));
      builder.setExecutionStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.RUNNING)).build());
    }

    if (lastJobAttempt.getStats() != null) {
      if (lastJobAttempt.getStats().getInputRecords() != null) {
        builder.setRowsScanned(lastJobAttempt.getStats().getInputRecords());
      }
      if (lastJobAttempt.getStats().getInputBytes() != null) {
        builder.setBytesScanned(lastJobAttempt.getStats().getInputBytes());
      }

      if (lastJobAttempt.getStats().getOutputRecords() != null) {
        builder.setRowsReturned(lastJobAttempt.getStats().getOutputRecords());
      }
      if (lastJobAttempt.getStats().getOutputBytes() != null) {
        builder.setBytesReturned(lastJobAttempt.getStats().getOutputBytes());
      }
    }

    builder.setPlannerEstimatedCost(lastJobAttemptInfo.getOriginalCost());

    if (lastJobAttemptInfo.getParentsList() != null
        && lastJobAttemptInfo.getDatasetPathList() != null) {
      builder.setQueriedDatasets(
          getQueriedDatasets(
              lastJobAttemptInfo.getParentsList(), lastJobAttemptInfo.getDatasetPathList()));
    }

    if (lastJobAttempt.getDetails() != null
        && lastJobAttempt.getDetails().getTableDatasetProfilesList() != null) {
      builder.setScannedDatasets(getScannedDatasets(lastJobAttempt));
    }

    if (lastJobAttemptInfo.getResourceSchedulingInfo() != null) {
      if (lastJobAttemptInfo.getResourceSchedulingInfo().getQueueName() != null) {
        builder.setQueueName(lastJobAttemptInfo.getResourceSchedulingInfo().getQueueName());
      }
      if (lastJobAttemptInfo.getResourceSchedulingInfo().getEngineName() != null) {
        builder.setEngine(lastJobAttemptInfo.getResourceSchedulingInfo().getEngineName());
      }
    }
    builder.setIsProfileIncomplete(lastJobAttempt.getIsProfileIncomplete());

    if (lastJobAttempt.getDetails() != null
        && lastJobAttempt.getDetails().getTotalMemory() != null) {
      builder.setExecutionAllocatedBytes(lastJobAttempt.getDetails().getTotalMemory());
    }

    if (lastJobAttempt.getDetails() != null && lastJobAttempt.getDetails().getCpuUsed() != null) {
      builder.setExecutionCpuTimeMillis(lastJobAttempt.getDetails().getCpuUsed());
    }

    return builder.build();
  }

  static RecentJobSummary toRecentJobSummary(JobId jobId, JobResult job) {

    int attemptsSize = job.getAttemptsList().size();
    final JobAttempt firstJobAttempt = job.getAttemptsList().get(0);
    final JobInfo firstJobAttemptInfo = firstJobAttempt.getInfo();
    final JobAttempt lastJobAttempt = job.getAttemptsList().get(attemptsSize - 1);
    final JobInfo lastJobAttemptInfo = lastJobAttempt.getInfo();
    final ResourceSchedulingInfo resourceSchedulingInfo =
        lastJobAttemptInfo.getResourceSchedulingInfo();

    RecentJobSummary.Builder builder =
        RecentJobSummary.newBuilder()
            .setJobId(JobsProtoUtil.toBuf(jobId).getId())
            .setStatus(JobsProtoUtil.toBuf(lastJobAttempt.getState()).toString())
            .setUserName(firstJobAttemptInfo.getUser())
            .setQueryType(JobsProtoUtil.toBuf(lastJobAttemptInfo.getQueryType()).toString())
            .setAccelerated(lastJobAttemptInfo.getAcceleration() != null)
            .setQuery(lastJobAttemptInfo.getSql())
            .setAttemptCount(attemptsSize);

    if (firstJobAttemptInfo.getStartTime() != null) {
      builder.setSubmittedEpochMillis(firstJobAttemptInfo.getStartTime());
      builder.setSubmittedTs(
          Timestamp.newBuilder().setSeconds(firstJobAttemptInfo.getStartTime()).build());
    }

    AttemptsHelper helper = new AttemptsHelper(lastJobAttempt);
    if (helper.getStateTimeStamp(State.PENDING) != null) {
      builder.setAttemptStartedEpochMillis(helper.getStateTimeStamp(State.PENDING));
      builder.setAttemptStartedTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.PENDING)).build());
    }
    if (helper.getStateTimeStamp(State.METADATA_RETRIEVAL) != null) {
      builder.setMetadataRetrievalEpochMillis(helper.getStateTimeStamp(State.METADATA_RETRIEVAL));
      builder.setMetadataRetrievalTs(
          Timestamp.newBuilder()
              .setSeconds(helper.getStateTimeStamp(State.METADATA_RETRIEVAL))
              .build());
    }
    if (helper.getStateTimeStamp(State.PLANNING) != null) {
      builder.setPlanningStartEpochMillis(helper.getStateTimeStamp(State.PLANNING));
      builder.setPlanningStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.PLANNING)).build());
    }
    if (helper.getStateTimeStamp(State.QUEUED) != null) {
      builder.setQueryEnqueuedEpochMillis(helper.getStateTimeStamp(State.QUEUED));
      builder.setQueryEnqueuedTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.QUEUED)).build());
    }
    if (helper.getStateTimeStamp(State.ENGINE_START) != null) {
      builder.setEngineStartEpochMillis(helper.getStateTimeStamp(State.ENGINE_START));
      builder.setEngineStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.ENGINE_START)).build());
    }
    if (helper.getStateTimeStamp(State.EXECUTION_PLANNING) != null) {
      builder.setExecutionPlanningEpochMillis(helper.getStateTimeStamp(State.EXECUTION_PLANNING));
      builder.setExecutionPlanningTs(
          Timestamp.newBuilder()
              .setSeconds(helper.getStateTimeStamp(State.EXECUTION_PLANNING))
              .build());
    }
    if (helper.getStateTimeStamp(State.RUNNING) != null) {
      builder.setExecutionStartEpochMillis(helper.getStateTimeStamp(State.RUNNING));
      builder.setExecutionStartTs(
          Timestamp.newBuilder().setSeconds(helper.getStateTimeStamp(State.RUNNING)).build());
    }
    if (helper.getFinalStateTimeStamp() != null) {
      builder.setFinalStateEpochMillis(helper.getFinalStateTimeStamp());
      builder.setFinalStateTs(
          Timestamp.newBuilder().setSeconds(helper.getFinalStateTimeStamp()).build());
    }

    if (lastJobAttempt.getStats() != null) {
      if (lastJobAttempt.getStats().getInputRecords() != null) {
        builder.setRowsScanned(lastJobAttempt.getStats().getInputRecords());
      }
      if (lastJobAttempt.getStats().getInputBytes() != null) {
        builder.setBytesScanned(lastJobAttempt.getStats().getInputBytes());
      }

      if (lastJobAttempt.getStats().getOutputRecords() != null) {
        builder.setRowsReturned(lastJobAttempt.getStats().getOutputRecords());
      }
      if (lastJobAttempt.getStats().getOutputBytes() != null) {
        builder.setBytesReturned(lastJobAttempt.getStats().getOutputBytes());
      }
    }

    builder.setPlannerEstimatedCost(lastJobAttemptInfo.getOriginalCost());

    if (lastJobAttemptInfo.getParentsList() != null
        && lastJobAttemptInfo.getDatasetPathList() != null) {
      builder.setQueriedDatasets(
          getQueriedDatasets(
              lastJobAttemptInfo.getParentsList(), lastJobAttemptInfo.getDatasetPathList()));
    }

    if (lastJobAttempt.getDetails() != null
        && lastJobAttempt.getDetails().getTableDatasetProfilesList() != null) {
      builder.setScannedDatasets(getScannedDatasets(lastJobAttempt));
    }

    if (resourceSchedulingInfo != null) {
      if (resourceSchedulingInfo.getQueueName() != null) {
        builder.setQueueName(resourceSchedulingInfo.getQueueName());
      }
      if (resourceSchedulingInfo.getEngineName() != null) {
        builder.setEngine(resourceSchedulingInfo.getEngineName());
      }
      if (resourceSchedulingInfo.getQueryCost() != null) {
        builder.setQueryCost(resourceSchedulingInfo.getQueryCost());
      }
    }

    if (lastJobAttemptInfo.getExecutionCpuTimeNs() != null) {
      builder.setExecutionCpuTimeNs(lastJobAttemptInfo.getExecutionCpuTimeNs());
    }
    if (lastJobAttemptInfo.getSetupTimeNs() != null) {
      builder.setSetupTimeNs(lastJobAttemptInfo.getSetupTimeNs());
    }
    if (lastJobAttemptInfo.getWaitTimeNs() != null) {
      builder.setWaitTimeNs(lastJobAttemptInfo.getWaitTimeNs());
    }
    if (lastJobAttemptInfo.getMemoryAllocated() != null) {
      builder.setMemoryAllocated(lastJobAttemptInfo.getMemoryAllocated());
    }
    if (lastJobAttemptInfo.getFailureInfo() != null) {
      builder.setErrorMsg(lastJobAttemptInfo.getFailureInfo());
    }

    final List<String> contextList = lastJobAttemptInfo.getContextList();
    if (contextList != null) {
      builder.setContext(contextList.toString());
    }

    builder.setIsProfileIncomplete(lastJobAttempt.getIsProfileIncomplete());

    if (lastJobAttempt.getDetails() != null
        && lastJobAttempt.getDetails().getTotalMemory() != null) {
      builder.setExecutionAllocatedBytes(lastJobAttempt.getDetails().getTotalMemory());
    }

    if (lastJobAttempt.getDetails() != null && lastJobAttempt.getDetails().getCpuUsed() != null) {
      builder.setExecutionCpuTimeMillis(lastJobAttempt.getDetails().getCpuUsed());
    }

    return builder.build();
  }

  private static String getQueriedDatasets(List<ParentDatasetInfo> parents, List<String> pathList) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    if (parents != null && parents.size() > 0) {
      parents.forEach(
          parent -> {
            append(sb, StringUtils.join(parent.getDatasetPathList(), "."));
          });
    } else if (isTruePath(pathList)) {
      append(sb, StringUtils.join(pathList, "."));
    }
    sb.append("]");
    return sb.toString();
  }

  private static String getScannedDatasets(JobAttempt jobAttempt) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    jobAttempt.getDetails().getTableDatasetProfilesList().stream()
        .forEach(
            dataset -> {
              String datasetName = "";
              List<String> pathList = new ArrayList<>();
              List<String> paths = getDatasetPath(dataset);
              paths.forEach(s -> pathList.add(s.replaceAll(QUOTES, "")));
              if (CollectionUtils.isNotEmpty(pathList)) {
                boolean isReflection = pathList.get(0).equals(__ACCELERATOR);
                if (isReflection) {
                  datasetName = StringUtils.join(pathList, ".");
                } else {
                  datasetName =
                      getScannedDatasetName(
                          jobAttempt.getInfo().getParentsList(),
                          jobAttempt.getInfo().getGrandParentsList(),
                          dataset);
                }

                append(sb, datasetName);
              }
            });
    sb.append("]");
    return sb.toString();
  }

  private static List<String> getDatasetPath(TableDatasetProfile dataset) {
    try {
      int datasetPathSize =
          dataset.getDatasetProfile().getDatasetPathsList().get(0).getDatasetPathList().size();
      return Arrays.asList(
          dataset
              .getDatasetProfile()
              .getDatasetPathsList()
              .get(0)
              .getDatasetPathList()
              .get(datasetPathSize - 1)
              .split(DOT_BACKSLASH));
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  // To get custom datasetname in case of query on non reflection datasets.
  private static String getScannedDatasetName(
      List<ParentDatasetInfo> parentsList,
      List<ParentDataset> grandParentsList,
      TableDatasetProfile dataset) {
    try {
      String datasetFullPath =
          StringUtils.join(
                  dataset.getDatasetProfile().getDatasetPathsList().get(0).getDatasetPathList(),
                  ".")
              .replaceAll(QUOTES, "");
      // Access grandparents list in case of query on VDS
      if (grandParentsList != null) {
        for (ParentDataset grandParentDataset : grandParentsList) {
          String grandParentsPath = StringUtils.join(grandParentDataset.getDatasetPathList(), ".");
          if (datasetFullPath.equals(grandParentsPath)) {
            return grandParentDataset
                .getDatasetPathList()
                .get(grandParentDataset.getDatasetPathList().size() - 1);
          }
        }
      }
      // Access parents list in case of query on PDS
      if (parentsList != null) {
        for (ParentDatasetInfo parentDataset : parentsList) {
          String parentsPath = StringUtils.join(parentDataset.getDatasetPathList(), ".");
          if (datasetFullPath.equals(parentsPath)) {
            return parentDataset
                .getDatasetPathList()
                .get(parentDataset.getDatasetPathList().size() - 1);
          }
        }
      }
    } catch (Exception ignore) {
    }

    return "";
  }

  static void append(StringBuilder sb, String str) {
    if (sb.length() > 1) {
      sb.append(", ");
    }
    sb.append(str);
  }

  static boolean isTruePath(List<String> datasetPathList) {
    return datasetPathList != null
        && !datasetPathList.isEmpty()
        && !datasetPathList.get(0).equals("UNKNOWN")
        && !(datasetPathList.get(0).equals("tmp") && datasetPathList.get(1).equals("UNTITLED"));
  }

  static JobDetails toJobDetails(Job job, boolean provideResultInfo) {
    final JobDetails.Builder jobDetailsBuilder = JobDetails.newBuilder();
    int numAttempts = job.getAttempts().size();
    for (int i = 0; i < numAttempts; ++i) {
      JobAttempt attempt = job.getAttempts().get(i);
      synchronized (attempt) {
        jobDetailsBuilder.addAttempts(JobsProtoUtil.toBuf(attempt));
      }
    }
    jobDetailsBuilder.setJobId(JobsProtoUtil.toBuf(job.getJobId())).setCompleted(job.isCompleted());

    if (job.getSessionId() != null) {
      jobDetailsBuilder.setSessionId(JobsProtoUtil.toBuf(job.getSessionId()));
    }

    if (provideResultInfo && job.getJobAttempt().getState() == JobState.COMPLETED) {
      final boolean hasResults = job.hasResults();
      jobDetailsBuilder.setHasResults(hasResults);

      // Gets JobResultsTableName from JobData, but only load if job is completed
      if (hasResults || job.isInternal()) {
        jobDetailsBuilder.setJobResultTableName(job.getData().getJobResultsTable());
      }
    }
    return jobDetailsBuilder.build();
  }

  public static JobTypeStats.Types toType(JobStats.Type type) {
    switch (type) {
      case UI:
        return JobTypeStats.Types.UI;
      case EXTERNAL:
        return JobTypeStats.Types.EXTERNAL;
      case ACCELERATION:
        return JobTypeStats.Types.ACCELERATION;
      case DOWNLOAD:
        return JobTypeStats.Types.DOWNLOAD;
      case INTERNAL:
        return JobTypeStats.Types.INTERNAL;
      case DAILY_JOBS:
        return JobTypeStats.Types.DAILY_JOBS;
      case USER_JOBS:
        return JobTypeStats.Types.USER_JOBS;
      default:
      case UNRECOGNIZED:
        throw new IllegalArgumentException();
    }
  }

  /** Creates JobInfo from SubmitJobRequest */
  public static JobInfo createJobInfo(
      SubmitJobRequest jobRequest,
      JobId jobId,
      String inSpace,
      int sqlTruncateLen,
      long ttlExpireAtInMillis) {
    boolean isSqlTruncated = jobRequest.getSqlQuery().getSql().length() > sqlTruncateLen;
    String sqlText =
        isSqlTruncated
            ? jobRequest.getSqlQuery().getSql().substring(0, sqlTruncateLen)
            : jobRequest.getSqlQuery().getSql();

    final JobInfo jobInfo =
        new JobInfo(
                jobId,
                sqlText,
                jobRequest.getVersionedDataset().getVersion(),
                JobsProtoUtil.toStuff(jobRequest.getQueryType()))
            .setSpace(inSpace)
            .setUser(jobRequest.getUsername())
            .setStartTime(System.currentTimeMillis())
            .setDatasetPathList(jobRequest.getVersionedDataset().getPathList())
            .setResultMetadataList(new ArrayList<ArrowFileMetadata>())
            .setContextList(jobRequest.getSqlQuery().getContextList())
            .setQueryLabel(JobsProtoUtil.toStuff(jobRequest.getQueryLabel()))
            .setIsTruncatedSql(isSqlTruncated)
            .setTtlExpireAt(ttlExpireAtInMillis);

    if (jobRequest.hasDownloadSettings()) {
      jobInfo.setDownloadInfo(
          new DownloadInfo()
              .setDownloadId(jobRequest.getDownloadSettings().getDownloadId())
              .setFileName(jobRequest.getDownloadSettings().getFilename())
              .setExtension(jobRequest.getDownloadSettings().getExtension())
              .setTriggeringJobId(jobRequest.getDownloadSettings().getTriggeringJobId()));
    } else if (jobRequest.hasMaterializationSettings()) {
      jobInfo.setMaterializationFor(
          JobsProtoUtil.toStuff(
              jobRequest.getMaterializationSettings().getMaterializationSummary()));
    }
    return jobInfo;
  }

  /**
   * Returns attempt state, given job status.
   *
   * @param state job status
   * @return attempt state
   */
  static AttemptEvent.State jobStatusToAttemptStatus(JobState state) {
    switch (state) {
      case METADATA_RETRIEVAL:
        return AttemptEvent.State.METADATA_RETRIEVAL;
      case STARTING:
        return AttemptEvent.State.STARTING;
      case PLANNING:
        return AttemptEvent.State.PLANNING;
      case RUNNING:
        return AttemptEvent.State.RUNNING;
      case COMPLETED:
        return AttemptEvent.State.COMPLETED;
      case CANCELED:
        return AttemptEvent.State.CANCELED;
      case FAILED:
        return AttemptEvent.State.FAILED;
      case QUEUED:
        return AttemptEvent.State.QUEUED;
      case PENDING:
        return AttemptEvent.State.PENDING;
      case ENGINE_START:
        return AttemptEvent.State.ENGINE_START;
      case EXECUTION_PLANNING:
        return AttemptEvent.State.EXECUTION_PLANNING;
      default:
        return AttemptEvent.State.INVALID_STATE;
    }
  }

  static AttemptEvent.State getLastEventState(JobSummary jobSummary) {
    int lastIndex = jobSummary.getStateListList().size() - 1;
    return jobSummary.getStateList(lastIndex).getState();
  }

  static IndexKey getIndexKey(UsedReflections.UsageType usageType) {
    if (usageType == UsedReflections.UsageType.CONSIDERED) {
      return JobIndexKeys.CONSIDERED_REFLECTION_IDS;
    } else if (usageType == UsedReflections.UsageType.MATCHED) {
      return JobIndexKeys.MATCHED_REFLECTION_IDS;
    } else {
      return JobIndexKeys.CHOSEN_REFLECTION_IDS;
    }
  }

  static SearchQuery getDatasetFilter(VersionedDatasetPath datasetPath) {
    final NamespaceKey namespaceKey = new NamespaceKey(datasetPath.getPathList());
    Preconditions.checkNotNull(namespaceKey);

    final ImmutableList.Builder<SearchQuery> builder =
        ImmutableList.<SearchQuery>builder()
            .add(SearchQueryUtils.newTermQuery(JobIndexKeys.ALL_DATASETS, namespaceKey.toString()))
            .add(JobIndexKeys.UI_EXTERNAL_JOBS_FILTER);

    if (!Strings.isNullOrEmpty(datasetPath.getVersion())) {
      final DatasetVersion datasetVersion = new DatasetVersion(datasetPath.getVersion());
      builder.add(
          SearchQueryUtils.newTermQuery(JobIndexKeys.DATASET_VERSION, datasetVersion.getVersion()));
    }
    return SearchQueryUtils.and(builder.build());
  }

  static SearchQuery getReflectionIdFilter(String reflectionId, IndexKey indexKey) {
    final ImmutableList.Builder<SearchQuery> builder =
        ImmutableList.<SearchQuery>builder()
            .add(
                SearchQueryUtils.newTermQuery(
                    indexKey, new StringBuilder(reflectionId).reverse().toString()))
            .add(
                SearchQueryUtils.or(
                    JobIndexKeys.UI_EXTERNAL_JOBS_FILTER, JobIndexKeys.ACCELERATION_JOBS_FILTER));
    return SearchQueryUtils.and(builder.build());
  }

  // derived from JobIndexKeys.UI_EXTERNAL_JOBS_FILTER & JobIndexKeys.ACCELERATION_JOBS_FILTER
  public static boolean countReflectionUsage(QueryType type) {
    switch (type) {
      case UI_PREVIEW:
      case UI_EXPORT:
      case UI_RUN:
      case ODBC:
      case JDBC:
      case REST:
      case FLIGHT:
      case D2D:
      case ACCELERATOR_CREATE:
      case ACCELERATOR_DROP:
      case ACCELERATOR_EXPLAIN:
      case ACCELERATOR_OPTIMIZE:
        return true;
      default:
        return false;
    }
  }

  public static String getJobDescription(
      com.dremio.proto.model.attempts.RequestType requestType, String sql, String desc) {
    return getJobDescription(RequestType.valueOf(requestType.toString()), sql, desc);
  }

  public static String getJobDescription(RequestType requestType, String sql, String desc) {
    // description of a job is same as the sql text for RUN_SQL request types (Ref.
    // UserRequest.java)
    return requestType == RequestType.RUN_SQL ? sql : desc;
  }

  public static boolean isUserQuery(QueryType type) {
    switch (type) {
      case UI_PREVIEW:
      case UI_EXPORT:
      case UI_RUN:
      case ODBC:
      case JDBC:
      case REST:
      case FLIGHT:
      case D2D:
        return true;
      default:
        return false;
    }
  }

  public static String getUserQueryFilter() {
    String userQueryTypes =
        Stream.of(QueryType.values())
            .filter(JobsServiceUtil::isUserQuery)
            .map(QueryType::toString)
            .collect(Collectors.joining(","));
    return String.format("qt=in=(%s)", userQueryTypes);
  }

  public static List<DataSet> getQueriedDatasets(JobInfo jobInfo, RequestType requestType) {
    return buildQueriedDatasets(
        jobInfo.getParentsList(), requestType, jobInfo.getDatasetPathList());
  }

  public static List<DataSet> buildQueriedDatasets(
      List<ParentDatasetInfo> parents, RequestType requestType, List<String> pathList) {
    List<DataSet> queriedDatasets = new ArrayList<>();
    if (parents != null && parents.size() > 0) {
      parents.stream()
          .forEach(
              parent -> {
                final List<String> datasetPathList = parent.getDatasetPathList();
                final String datasetName = datasetPathList.get(datasetPathList.size() - 1);
                final String datasetPath = StringUtils.join(datasetPathList, ".");
                final String versionContext = parent.getVersionContext();

                if (!queriedDatasets.stream()
                    .anyMatch(dataSet -> dataSet.getDatasetPath().equals(datasetPath))) {
                  String datasetType = DEFAULT_DATASET_TYPE;
                  if (!datasetPathList.contains(EXTERNAL_QUERY)) {
                    try {
                      datasetType = parent.getType().name();
                    } catch (NullPointerException ex) {
                      datasetType =
                          com.dremio.service.namespace.dataset.proto.DatasetType.values()[0]
                              .toString();
                    }
                  }

                  populateQueriedDataset(
                      queriedDatasets,
                      datasetName,
                      datasetType,
                      datasetPath,
                      datasetPathList,
                      versionContext);
                }
              });
    } else if (isTruePath(pathList)) {
      final String datasetName = pathList.get(pathList.size() - 1);
      final String datasetPath = StringUtils.join(pathList, ".");
      final String datasetType = EMPTY_DATASET_FIELD;
      populateQueriedDataset(queriedDatasets, datasetName, datasetType, datasetPath, pathList, "");
    } else {
      populateQueriedDataset(
          queriedDatasets,
          UNAVAILABLE,
          EMPTY_DATASET_FIELD,
          EMPTY_DATASET_FIELD,
          new ArrayList<>(),
          "");
      switch (requestType) {
        case GET_CATALOGS:
        case GET_COLUMNS:
        case GET_SCHEMAS:
        case GET_TABLES:
          queriedDatasets.get(queriedDatasets.size() - 1).setDatasetName(METADATA);
          break;
        default:
          break;
      }
    }

    return queriedDatasets;
  }

  public static void populateQueriedDataset(
      List<DataSet> queriedDatasets,
      String datasetName,
      String datasetType,
      String datasetPath,
      List<String> datasetPathList,
      String versionContext) {
    final DataSet dataset =
        new DataSet()
            .setDatasetName(datasetName)
            .setDatasetPath(datasetPath)
            .setDatasetType(datasetType)
            .setDatasetPathsList(datasetPathList);

    if (versionContext != null) {
      dataset.setVersionContext(versionContext);
    }

    queriedDatasets.add(dataset);
  }

  public static boolean ensureJobIsRunningOrFinishedWith(
      JobState expectedFinalState, JobState state) {
    if (expectedFinalState.equals(state)) {
      return true;
    }

    return !finalJobStates.contains(state);
  }
}
