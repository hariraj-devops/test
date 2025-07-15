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
package com.dremio.dac.util;

import static java.lang.String.format;

import com.dremio.dac.explore.model.DownloadFormat;
import com.dremio.dac.service.datasets.DatasetDownloadManager;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.service.job.JobDetails;
import com.dremio.service.job.JobDetailsRequest;
import com.dremio.service.job.proto.JobAttempt;
import com.dremio.service.job.proto.JobId;
import com.dremio.service.job.proto.JobInfo;
import com.dremio.service.job.proto.JobState;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobDataClientUtils;
import com.dremio.service.jobs.JobNotFoundException;
import com.dremio.service.jobs.JobsProtoUtil;
import com.dremio.service.jobs.JobsService;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.WebApplicationException;
import org.glassfish.jersey.server.ChunkedOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Download Utils */
public class DownloadUtil {
  private static final Logger logger = LoggerFactory.getLogger(DownloadUtil.class);

  private static final String DOWNLOAD_POOL_SIZE = "dremio_download_pool_size";
  private static final Set<QueryType> JOB_TYPES_TO_DOWNLOAD =
      ImmutableSet.of(
          QueryType.UI_RUN,
          QueryType.UI_PREVIEW,
          QueryType.UI_INTERNAL_RUN,
          QueryType.UI_INTERNAL_PREVIEW,
          QueryType.UI_EXPORT);

  private static final ScheduledExecutorService executorService =
      Executors.newScheduledThreadPool(
          Integer.parseInt(System.getProperty(DOWNLOAD_POOL_SIZE, "5")),
          r -> new Thread(r, "job-download"));

  private final JobsService jobsService;
  private final DatasetVersionMutator datasetService;

  public DownloadUtil(JobsService jobsService, DatasetVersionMutator datasetService) {
    this.jobsService = jobsService;
    this.datasetService = datasetService;
  }

  public void checkAccess(JobId previewJobId, String currentUser) throws JobNotFoundException {
    // ensure that we could access to the job.
    final JobDetails previewJobDetails =
        jobsService.getJobDetails(
            JobDetailsRequest.newBuilder()
                .setJobId(JobsProtoUtil.toBuf(previewJobId))
                .setUserName(currentUser)
                .build());

    // ensure job type is supported for download
    final JobInfo previewJobInfo = JobsProtoUtil.getLastAttempt(previewJobDetails).getInfo();
    if (!JOB_TYPES_TO_DOWNLOAD.contains(previewJobInfo.getQueryType())) {
      logger.error(
          "Not supported job type: {} for job '{}'. Supported job types are: {}",
          previewJobInfo.getQueryType(),
          previewJobId,
          String.join(
              ", ",
              JOB_TYPES_TO_DOWNLOAD.stream()
                  .map(queryType -> String.valueOf(queryType.getNumber()))
                  .collect(Collectors.toList())));
      throw new IllegalArgumentException("Data for the job could not be downloaded");
    }
  }

  public JobId submitAsyncDownload(
      JobId previewJobId, String currentUser, DownloadFormat downloadFormat)
      throws JobNotFoundException {
    checkAccess(previewJobId, currentUser);
    JobDetails previewJobDetails =
        jobsService.getJobDetails(
            JobDetailsRequest.newBuilder()
                .setJobId(JobsProtoUtil.toBuf(previewJobId))
                .setUserName(currentUser)
                .setSkipProfileInfo(true)
                .build());
    final JobInfo previewJobInfo = JobsProtoUtil.getLastAttempt(previewJobDetails).getInfo();
    final List<String> datasetPath = previewJobInfo.getDatasetPathList();
    // return job id of downloadJob
    if (previewJobInfo.getQueryType() != QueryType.UI_EXPORT) {
      DatasetDownloadManager manager = datasetService.downloadManager();
      return manager.scheduleDownload(
          datasetPath,
          previewJobInfo.getSql(),
          downloadFormat,
          previewJobInfo.getContextList(),
          currentUser,
          previewJobId);
    } else {
      return previewJobId;
    }
  }

  public ChunkedOutput<byte[]> startChunckedDownload(
      JobId previewJobId, String currentUser, DownloadFormat downloadFormat, long delay)
      throws JobNotFoundException {

    checkAccess(previewJobId, currentUser);
    final ChunkedOutput<byte[]> output = new ChunkedOutput<>(byte[].class);

    executorService.schedule(
        () -> {
          triggerInternalJobWaitForCompletionAndGetDownloadFile(
              previewJobId, downloadFormat, currentUser, output);
        },
        delay,
        TimeUnit.MILLISECONDS);

    return output;
  }

  public ChunkedOutput<byte[]> startChunckedDownload(
      JobDetails downloadJobDetails, String currentUser, long delay) {
    final ChunkedOutput<byte[]> output = new ChunkedOutput<>(byte[].class);
    executorService.schedule(
        () -> {
          try {
            getDownloadFile(downloadJobDetails, currentUser, output);
          } catch (Exception e) {
            handleException(e, output);
          }
        },
        delay,
        TimeUnit.MILLISECONDS);
    return output;
  }

  private void triggerInternalJobWaitForCompletionAndGetDownloadFile(
      JobId previewJobId,
      DownloadFormat downloadFormat,
      String currentUser,
      ChunkedOutput<byte[]> output) {
    try {
      JobDataClientUtils.waitForFinalState(jobsService, previewJobId);
      JobDetails previewJobDetails =
          jobsService.getJobDetails(
              JobDetailsRequest.newBuilder()
                  .setJobId(JobsProtoUtil.toBuf(previewJobId))
                  .setUserName(currentUser)
                  .build());
      // read current job state
      checkJobCompletionState(previewJobDetails);

      JobDetails downloadJobDetails = previewJobDetails;
      final JobInfo previewJobInfo = JobsProtoUtil.getLastAttempt(previewJobDetails).getInfo();
      final List<String> datasetPath = previewJobInfo.getDatasetPathList();

      if (previewJobInfo.getQueryType() != QueryType.UI_EXPORT) {
        DatasetDownloadManager manager = datasetService.downloadManager();

        JobId downloadJobId =
            manager.scheduleDownload(
                datasetPath,
                previewJobInfo.getSql(),
                downloadFormat,
                previewJobInfo.getContextList(),
                currentUser,
                previewJobId);

        JobDataClientUtils.waitForFinalState(jobsService, downloadJobId);
        // get the final state of a job after completion
        JobDetailsRequest jobDetailsRequest =
            JobDetailsRequest.newBuilder()
                .setUserName(currentUser)
                .setJobId(JobsProtoUtil.toBuf(downloadJobId))
                .build();
        downloadJobDetails = jobsService.getJobDetails(jobDetailsRequest);
        checkJobCompletionState(downloadJobDetails);
      }
      getDownloadFile(downloadJobDetails, currentUser, output);
    } catch (Exception e) {
      handleException(e, output);
    }
  }

  public void getDownloadFile(
      JobDetails downloadJobDetails, String currentUser, ChunkedOutput<byte[]> output)
      throws IOException {
    final JobAttempt lastAttempt = JobsProtoUtil.getLastAttempt(downloadJobDetails);
    final DatasetDownloadManager.DownloadDataResponse downloadDataResponse =
        datasetService.downloadData(
            lastAttempt.getInfo().getDownloadInfo(),
            lastAttempt.getInfo().getResultMetadataList(),
            currentUser);

    try (InputStream input = downloadDataResponse.getInput();
        ChunkedOutput<byte[]> toClose = output) {
      byte[] buf = new byte[4096];
      int bytesRead = input.read(buf);
      while (bytesRead >= 0) {
        // Send a buffer copy to ChunkedOutput to ensure enqueued buffers remain unmodified until
        // they are processed, starting after the context is set in the ChunkedOutput object.
        output.write(Arrays.copyOf(buf, bytesRead));
        bytesRead = input.read(buf);
      }
    }
  }

  private void handleException(Exception e, ChunkedOutput<byte[]> output) {
    try {
      // TODO : https://dremio.atlassian.net/browse/DX-34302
      // no error propagation on failures.
      logger.error("Failed downloading the file", e);
      output.close();
    } catch (IOException ex) {
      logger.warn("Failure closing the output.");
    }
    throw new WebApplicationException(e);
  }

  private static void checkJobCompletionState(final JobDetails jobDetails)
      throws ForbiddenException {
    final JobState jobState = JobsProtoUtil.getLastAttempt(jobDetails).getState();
    final JobInfo jobInfo = JobsProtoUtil.getLastAttempt(jobDetails).getInfo();

    // requester should check that job state is COMPLETED before downloading, but return nicely
    switch (jobState) {
      case COMPLETED:
        // job is completed. Do nothing
        break;
      case FAILED:
        throw new ForbiddenException(jobInfo.getFailureInfo());
      case CANCELED:
        if (jobInfo.getCancellationInfo() != null) {
          throw new ForbiddenException(jobInfo.getCancellationInfo().getMessage());
        }
        throw new ForbiddenException(
            "Download job was canceled, but further information is unavailable");
      default:
        throw new ForbiddenException(
            format(
                "Could not download results (job: %s, state: %s)",
                jobDetails.getJobId(), jobState));
    }
  }
}
