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

import static com.dremio.service.job.proto.QueryType.METADATA_REFRESH;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.logging.StructuredLogger;
import com.dremio.common.utils.protos.ExternalIdHelper;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.api.LegacyIndexedStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.SearchProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.ExternalId;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.proto.beans.AttemptEvent;
import com.dremio.exec.proto.beans.NodeEndpoint;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.JobResultsStoreConfig;
import com.dremio.exec.store.sys.accel.AccelerationManager;
import com.dremio.exec.work.protector.ForemenTool;
import com.dremio.exec.work.rpc.CoordTunnelCreator;
import com.dremio.exec.work.user.LocalQueryExecutor;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.service.commandpool.CommandPool;
import com.dremio.service.commandpool.CommandPoolFactory;
import com.dremio.service.conduit.client.ConduitProvider;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.job.JobDetailsRequest;
import com.dremio.service.job.JobEvent;
import com.dremio.service.job.JobState;
import com.dremio.service.job.JobSummary;
import com.dremio.service.job.QueryProfileRequest;
import com.dremio.service.job.RecentJobSummary;
import com.dremio.service.job.RecentJobsRequest;
import com.dremio.service.job.SubmitJobRequest;
import com.dremio.service.job.proto.ExecutionNode;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobProtobuf;
import com.dremio.service.job.proto.JobResult;
import com.dremio.service.job.proto.JobSubmission;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.job.proto.SessionId;
import com.dremio.service.jobcounts.JobCountsClient;
import com.dremio.service.jobtelemetry.GetQueryProfileResponse;
import com.dremio.service.jobtelemetry.JobTelemetryClient;
import com.dremio.service.jobtelemetry.JobTelemetryServiceGrpc;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.usersessions.UserSessionService;
import com.dremio.telemetry.utils.TracerFacade;
import com.dremio.test.DremioTest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link LocalJobsService} */
@RunWith(MockitoJUnitRunner.class)
public class TestLocalJobsService {
  private final DremioConfig dremioConfig =
      DremioConfig.create(null, DremioTest.DEFAULT_SABOT_CONFIG)
          .withValue(DremioConfig.SCHEDULER_LEADERLESS_CLUSTERED_SINGLETON, "true");
  private LocalJobsService localJobsService;
  private final QueryProfile queryProfile =
      QueryProfile.newBuilder()
          .setPlan("PLAN_VALUE")
          .setQuery("Select * from plan")
          .setCancelReason("Cancel plan")
          .build();
  private final GetQueryProfileResponse getQueryProfileResponse =
      GetQueryProfileResponse.newBuilder().setProfile(queryProfile).build();

  @Mock private LegacyKVStoreProvider kvStoreProvider;
  @Mock private BufferAllocator bufferAllocator;
  private JobResultsStoreConfig jobResultsStoreConfig;
  @Mock private JobResultsStore jobResultsStore;
  @Mock private LocalQueryExecutor localQueryExecutor;
  @Mock private CoordTunnelCreator coordTunnelCreator;
  @Mock private ForemenTool foremenTool;
  @Mock private ClusterCoordinator clusterCoordinator;
  private CoordinationProtos.NodeEndpoint nodeEndpoint;
  @Mock private OptionManager optionManager;
  @Mock private AccelerationManager accelerationManager;
  @Mock private SchedulerService schedulerService;
  @Mock private CommandPool commandPool;
  @Mock private JobTelemetryClient jobTelemetryClient;
  @Mock private StructuredLogger<Job> jobResultLogger;
  @Mock private ConduitProvider conduitProvider;
  @Mock private UserSessionService userSessionService;
  @Mock private OptionValidatorListing optionValidatorProvider;
  @Mock private CatalogService catalogServiceProvider;
  @Mock private LegacyIndexedStore<JobId, JobResult> legacyIndexedStore;
  @Mock private JobCountsClient jobCountsClient;
  @Mock private JobSubmissionListener jobSubmissionListener;
  @Mock private JobTelemetryServiceGrpc.JobTelemetryServiceBlockingStub jobTelemetryServiceStub;

  @Before
  public void setup() {
    when(jobTelemetryClient.getBlockingStub()).thenReturn(jobTelemetryServiceStub);
    when(kvStoreProvider.getStore(Mockito.any())).thenReturn(legacyIndexedStore);
    localJobsService = createLocalJobsService(true);
  }

  private LocalJobsService createLocalJobsService(boolean isMaster) {
    return createLocalJobsService(isMaster, localQueryExecutor, commandPool, kvStoreProvider);
  }

  private LocalJobsService createLocalJobsService(
      boolean isMaster,
      LocalQueryExecutor localQueryExecutor,
      CommandPool commandPool,
      LegacyKVStoreProvider kvStoreProvider) {

    return new LocalJobsService(
        () -> kvStoreProvider,
        bufferAllocator,
        () -> jobResultsStoreConfig,
        () -> jobResultsStore,
        () -> localQueryExecutor,
        () -> coordTunnelCreator,
        () -> foremenTool,
        () -> nodeEndpoint,
        () -> clusterCoordinator,
        () -> optionManager,
        () -> accelerationManager,
        () -> schedulerService,
        () -> commandPool,
        () -> jobTelemetryClient,
        jobResultLogger,
        isMaster,
        () -> conduitProvider,
        () -> userSessionService,
        () -> optionValidatorProvider,
        () -> catalogServiceProvider,
        () -> jobCountsClient,
        () -> jobSubmissionListener,
        dremioConfig);
  }

  @Test
  public void runQueryAsJobSuccess() throws Exception {
    final LocalJobsService spy = spy(localJobsService);
    JobSummary jobSummary = JobSummary.newBuilder().setJobState(JobState.COMPLETED).build();
    JobEvent jobEvent = JobEvent.newBuilder().setFinalJobSummary(jobSummary).build();
    doAnswer(
            (Answer<JobSubmission>)
                invocationOnMock -> {
                  invocationOnMock.getArgument(1, StreamObserver.class).onNext(jobEvent);
                  return new JobSubmission().setJobId(new JobId("foo"));
                })
        .when(spy)
        .submitJob(
            any(SubmitJobRequest.class),
            any(StreamObserver.class),
            any(PlanTransformationListener.class));

    spy.runQueryAsJob("my query", "my_username", METADATA_REFRESH.name(), null);
  }

  @Test
  public void runQueryAsJobCanceled() {
    final LocalJobsService spy = spy(localJobsService);
    JobSummary jobSummary = JobSummary.newBuilder().setJobState(JobState.CANCELED).build();
    JobEvent jobEvent = JobEvent.newBuilder().setFinalJobSummary(jobSummary).build();
    doAnswer(
            (Answer<JobSubmission>)
                invocationOnMock -> {
                  invocationOnMock.getArgument(1, StreamObserver.class).onNext(jobEvent);
                  return new JobSubmission().setJobId(new JobId("foo"));
                })
        .when(spy)
        .submitJob(
            any(SubmitJobRequest.class),
            any(StreamObserver.class),
            any(PlanTransformationListener.class));

    assertThatThrownBy(
            () -> spy.runQueryAsJob("my query", "my_username", METADATA_REFRESH.name(), null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @SuppressWarnings("DremioGRPCStreamObserverOnError")
  public void runQueryAsJobFailure() {
    final LocalJobsService spy = spy(localJobsService);
    doAnswer(
            (Answer<JobSubmission>)
                invocationOnMock -> {
                  invocationOnMock
                      .getArgument(1, StreamObserver.class)
                      .onError(new NumberFormatException());
                  return JobSubmission.getDefaultInstance();
                })
        .when(spy)
        .submitJob(
            any(SubmitJobRequest.class),
            any(StreamObserver.class),
            any(PlanTransformationListener.class));

    assertThatThrownBy(
            () -> spy.runQueryAsJob("my query", "my_username", METADATA_REFRESH.name(), null))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(NumberFormatException.class);
  }

  @Test
  public void scheduleCleanupOnlyWhenMaster() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);
    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);
    localJobsService.start();

    verify(schedulerService, times(2)).schedule(any(Schedule.class), any(Runnable.class));
  }

  @Test
  public void scheduleCleanupOnlyWhenNotMaster() throws Exception {
    localJobsService = createLocalJobsService(false);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);
    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    localJobsService.start();

    verify(schedulerService).schedule(any(Schedule.class), any(Runnable.class));
  }

  @Test
  public void testSubmitLocalQueryFailure() throws Exception {
    LocalQueryExecutor localQueryExecutor = mock(LocalQueryExecutor.class);
    when(localQueryExecutor.canAcceptWork()).thenReturn(true);
    // Mocking localQueryExecutor to throw exception while trying to submit query
    doThrow(new RejectedExecutionException())
        .when(localQueryExecutor)
        .submitLocalQuery(any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyLong());

    LegacyKVStoreProvider legacyKVStoreProvider = mock(LegacyKVStoreProvider.class);
    LegacyIndexedStore legacyIndexedStore = mock(LegacyIndexedStore.class);
    when(legacyKVStoreProvider.getStore(any())).thenReturn(legacyIndexedStore);

    CommandPool commandPool =
        CommandPoolFactory.INSTANCE.newPool(DremioConfig.create(), TracerFacade.INSTANCE);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);
    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    localJobsService =
        createLocalJobsService(true, localQueryExecutor, commandPool, legacyKVStoreProvider);
    localJobsService.start();

    CountDownLatch latch = new CountDownLatch(1);
    final StreamObserver<JobEvent> eventObserver = getEventObserver(latch);
    ArgumentCaptor<JobResult> jobResultsCaptor = ArgumentCaptor.forClass(JobResult.class);
    localJobsService.submitJob(
        getSubmitJobRequest("select 1", SYSTEM_USERNAME, QueryType.JDBC).toBuilder()
            .setRunInSameThread(true)
            .build(),
        eventObserver,
        PlanTransformationListener.NO_OP);
    latch.await();
    verify(legacyIndexedStore, times(2)).put(any(), jobResultsCaptor.capture());
    List<JobResult> jobResults = jobResultsCaptor.getAllValues();
    // Query will fail due to failure in submitting the query, but on catching the exception the
    // query should be marked as completed, hence
    // the last update of the job to jobsStore should have job.completed set to true
    Assert.assertTrue(jobResults.get(jobResults.size() - 1).getCompleted());
    Assert.assertEquals(
        jobResults.get(jobResults.size() - 1).getAttemptsList().get(0).getState(),
        com.dremio.service.job.proto.JobState.FAILED);

    commandPool.close();
  }

  private StreamObserver<JobEvent> getEventObserver(CountDownLatch latch) {
    return new StreamObserver<JobEvent>() {
      @Override
      public void onNext(JobEvent value) {}

      @Override
      public void onError(Throwable t) {
        latch.countDown();
      }

      @Override
      public void onCompleted() {
        latch.countDown();
      }
    };
  }

  public static SubmitJobRequest getSubmitJobRequest(
      String sqlQuery, String userName, QueryType queryType) {
    final SubmitJobRequest.Builder jobRequestBuilder =
        SubmitJobRequest.newBuilder()
            .setSqlQuery(JobsProtoUtil.toBuf(new SqlQuery(sqlQuery, userName)))
            .setQueryType(JobsProtoUtil.toBuf(queryType));
    return jobRequestBuilder.build();
  }

  @Test
  public void testJobProfileAndStoreCalls1() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);

    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    when(jobTelemetryServiceStub.getQueryProfile(Mockito.any()))
        .thenReturn(getQueryProfileResponse);

    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);

    // If job details contain RUNNING state attempt and profileDetailsCapturedPostTermination is
    // false, then query profile should be fetched to populate profile details
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.RUNNING, false);
    when(legacyIndexedStore.get(new JobId(jobId.getId()))).thenReturn(jobResult);
    localJobsService.start();
    JobDetailsRequest request =
        JobDetailsRequest.newBuilder()
            .setJobId(
                JobProtobuf.JobId.newBuilder()
                    // setting provide_profile_info to true
                    .setId(jobId.getId())
                    .build())
            .setSkipProfileInfo(false)
            .build();
    localJobsService.getJobDetails(request);
    // If populating profile details is required and provide_profile_info is set to true, then query
    // profile should be fetched once
    verify(jobTelemetryServiceStub, times(1)).getQueryProfile(any());
    verify(legacyIndexedStore, times(1)).get(any(JobId.class));
  }

  @Test
  public void testJobProfileAndStoreCalls2() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);

    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);

    ExecutionNode executionNode = new ExecutionNode();
    // If job details contain RUNNING state attempt and profileDetailsCapturedPostTermination is
    // true, then populating profile details is not required
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.RUNNING, true);
    when(legacyIndexedStore.get(new JobId(jobId.getId()))).thenReturn(jobResult);
    localJobsService.start();
    JobDetailsRequest request =
        JobDetailsRequest.newBuilder()
            .setJobId(
                JobProtobuf.JobId.newBuilder()
                    // setting provide_profile_info to false
                    .setId(jobId.getId())
                    .build())
            .setSkipProfileInfo(true)
            .build();
    localJobsService.getJobDetails(request);
    // If populating profile details is not required and provide_profile_info is set to false, then
    // query profile should not be fetched
    verify(jobTelemetryServiceStub, times(0)).getQueryProfile(any());
    verify(legacyIndexedStore, times(1)).get(any(JobId.class));
  }

  @Test
  public void testJobProfileAndStoreCalls3() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);

    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    when(jobTelemetryServiceStub.getQueryProfile(Mockito.any()))
        .thenReturn(getQueryProfileResponse);

    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);

    ExecutionNode executionNode = new ExecutionNode();
    // If job details contain RUNNING state attempt and profileDetailsCapturedPostTermination is
    // true, then populating profile details is not required
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.RUNNING, true);
    when(legacyIndexedStore.get(new JobId(jobId.getId()))).thenReturn(jobResult);
    localJobsService.start();
    JobDetailsRequest request =
        JobDetailsRequest.newBuilder()
            .setJobId(
                JobProtobuf.JobId.newBuilder()
                    // setting provide_profile_info to true
                    .setId(jobId.getId())
                    .build())
            .setSkipProfileInfo(false)
            .build();
    localJobsService.getJobDetails(request);
    // If populating profile details is not required and provide_profile_info is set to true, then
    // query profile should be fetched once
    verify(jobTelemetryServiceStub, times(1)).getQueryProfile(any());
    verify(legacyIndexedStore, times(1)).get(any(JobId.class));
  }

  @Test
  public void testJobProfileAndStoreCalls4() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);

    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);

    // If job details does not contain RUNNING state attempt, then populating profile details is not
    // required
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.COMPLETED, false);
    when(legacyIndexedStore.get(new JobId(jobId.getId()))).thenReturn(jobResult);
    localJobsService.start();
    JobDetailsRequest request =
        JobDetailsRequest.newBuilder()
            .setJobId(
                JobProtobuf.JobId.newBuilder()
                    // setting provide_profile_info to false
                    .setId(jobId.getId())
                    .build())
            .setSkipProfileInfo(true)
            .build();
    localJobsService.getJobDetails(request);
    // If populating profile details is not required and provide_profile_info is set to false, then
    // query profile should not be fetched
    verify(jobTelemetryServiceStub, times(0)).getQueryProfile(any());
    verify(legacyIndexedStore, times(1)).get(any(JobId.class));
  }

  @Test
  public void testGetJobProfileFailureForFailedJob() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);
    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    // Create a FAILED but Failure Job Type is not ABANDONED
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.FAILED, false);
    when(legacyIndexedStore.get(new JobId(jobId.getId()))).thenReturn(jobResult);
    // Throw exception similar to case when profile is missing
    when(jobTelemetryServiceStub.getQueryProfile(any()))
        .thenThrow(
            Status.INVALID_ARGUMENT
                .withDescription("Unable to get query profile.")
                .asRuntimeException());
    // Forwarded getProfile also fails with DEADLINE_EXCEEDED
    when(conduitProvider.getOrCreateChannel(any()))
        .thenThrow(
            Status.DEADLINE_EXCEEDED
                .withDescription("Coordinator DEADLINE_EXCEEDED.")
                .asRuntimeException());
    localJobsService.start();
    QueryProfileRequest request =
        QueryProfileRequest.newBuilder()
            .setJobId(JobsProtoUtil.toBuf(jobId))
            .setAttempt(0)
            .setUserName(SYSTEM_USERNAME)
            .build();
    assertThatThrownBy(() -> localJobsService.getProfile(request))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("Unable to fetch profile at the moment, try after sometime.");
  }

  @Test
  public void testGetRecentJobs() throws Exception {
    localJobsService = createLocalJobsService(true);
    nodeEndpoint = CoordinationProtos.NodeEndpoint.newBuilder().build();
    jobResultsStoreConfig = new JobResultsStoreConfig("dummy", Path.of("UNKNOWN"), null);

    Cancellable mockTask = mock(Cancellable.class);
    when(schedulerService.schedule(any(Schedule.class), any(Runnable.class))).thenReturn(mockTask);

    // Failed Job
    ExternalId externalId = ExternalIdHelper.generateExternalId();
    JobId jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    JobResult jobResult = getJobResult(jobId, AttemptEvent.State.FAILED, false);
    RecentJobsRequest recentJobsRequest =
        RecentJobsRequest.newBuilder()
            .setQuery(
                SearchProtos.SearchQuery.newBuilder()
                    .setEquals(
                        SearchProtos.SearchQuery.Equals.newBuilder()
                            .setField("status")
                            .setStringValue(com.dremio.service.job.proto.JobState.FAILED.toString())
                            .build())
                    .build())
            .build();
    Map<JobId, JobResult> failedJobEntries = Collections.singletonMap(jobId, jobResult);
    when(legacyIndexedStore.find(localJobsService.createRecentJobCondition(recentJobsRequest)))
        .thenReturn(new ArrayList<>(failedJobEntries.entrySet()));
    localJobsService.start();
    Iterable<RecentJobSummary> failedJobs = localJobsService.getRecentJobs(recentJobsRequest);
    for (RecentJobSummary recentJobSummary : failedJobs) {
      Assert.assertEquals("Error Message: The job has failed.", recentJobSummary.getErrorMsg());
      Assert.assertEquals(10, recentJobSummary.getFinalStateEpochMillis());
    }

    // Completed Job
    externalId = ExternalIdHelper.generateExternalId();
    jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    jobResult = getJobResult(jobId, AttemptEvent.State.COMPLETED, false);
    recentJobsRequest =
        RecentJobsRequest.newBuilder()
            .setQuery(
                SearchProtos.SearchQuery.newBuilder()
                    .setEquals(
                        SearchProtos.SearchQuery.Equals.newBuilder()
                            .setField("status")
                            .setStringValue(
                                com.dremio.service.job.proto.JobState.COMPLETED.toString())
                            .build())
                    .build())
            .build();
    Map<JobId, JobResult> completedJobEntries = Collections.singletonMap(jobId, jobResult);
    when(legacyIndexedStore.find(localJobsService.createRecentJobCondition(recentJobsRequest)))
        .thenReturn(new ArrayList<>(completedJobEntries.entrySet()));
    localJobsService.start();
    Iterable<RecentJobSummary> completedJobs = localJobsService.getRecentJobs(recentJobsRequest);
    for (RecentJobSummary recentJobSummary : completedJobs) {
      Assert.assertEquals("", recentJobSummary.getErrorMsg());
      Assert.assertEquals(100, recentJobSummary.getFinalStateEpochMillis());
    }

    // Cancelled Job
    externalId = ExternalIdHelper.generateExternalId();
    jobId = JobsServiceUtil.getExternalIdAsJobId(externalId);
    jobResult = getJobResult(jobId, AttemptEvent.State.CANCELED, false);
    recentJobsRequest =
        RecentJobsRequest.newBuilder()
            .setQuery(
                SearchProtos.SearchQuery.newBuilder()
                    .setEquals(
                        SearchProtos.SearchQuery.Equals.newBuilder()
                            .setField("status")
                            .setStringValue(
                                com.dremio.service.job.proto.JobState.CANCELED.toString())
                            .build())
                    .build())
            .build();
    Map<JobId, JobResult> cancelledJobEntries = Collections.singletonMap(jobId, jobResult);
    when(legacyIndexedStore.find(localJobsService.createRecentJobCondition(recentJobsRequest)))
        .thenReturn(new ArrayList<>(cancelledJobEntries.entrySet()));
    localJobsService.start();
    Iterable<RecentJobSummary> cancelledJobs = localJobsService.getRecentJobs(recentJobsRequest);
    for (RecentJobSummary recentJobSummary : cancelledJobs) {
      Assert.assertEquals("", recentJobSummary.getErrorMsg());
      Assert.assertEquals(50, recentJobSummary.getFinalStateEpochMillis());
    }
  }

  private JobResult getJobResult(
      JobId jobId, AttemptEvent.State attemptState, boolean profileDetailsCapturedPostTermination) {
    JobInfo jobInfo = new JobInfo();
    jobInfo.setQueryType(QueryType.UI_RUN);
    jobInfo.setJobId(jobId);
    jobInfo.setSql("query");
    jobInfo.setDatasetVersion("version");
    jobInfo.setUser("dremio");
    if (attemptState == AttemptEvent.State.FAILED) {
      jobInfo.setFailureInfo("Error Message: The job has failed.");
    }

    JobAttempt jobAttempt = new JobAttempt();
    jobAttempt.setInfo(jobInfo);
    List<AttemptEvent> attemptEvents = new ArrayList<>();
    if (attemptState == AttemptEvent.State.FAILED) {
      jobAttempt.setState(com.dremio.service.job.proto.JobState.FAILED);
      attemptEvents.add(
          JobsServiceUtil.createAttemptEvent(UserBitShared.AttemptEvent.State.FAILED, 10L));
    } else if (attemptState == AttemptEvent.State.CANCELED) {
      jobAttempt.setState(com.dremio.service.job.proto.JobState.CANCELED);
      attemptEvents.add(
          JobsServiceUtil.createAttemptEvent(UserBitShared.AttemptEvent.State.CANCELED, 50L));
    } else {
      jobAttempt.setState(com.dremio.service.job.proto.JobState.COMPLETED);
      attemptEvents.add(
          JobsServiceUtil.createAttemptEvent(UserBitShared.AttemptEvent.State.COMPLETED, 100L));
    }
    jobAttempt.setEndpoint(new NodeEndpoint().setAddress("TestNode").setConduitPort(12345));
    jobAttempt.setStateListList(attemptEvents);

    JobResult jobResult = new JobResult();
    jobResult.setCompleted(true);
    jobResult.setSessionId(SessionId.getDefaultInstance());
    jobResult.setAttemptsList(Collections.singletonList(jobAttempt));
    jobResult.setProfileDetailsCapturedPostTermination(profileDetailsCapturedPostTermination);
    return jobResult;
  }
}
