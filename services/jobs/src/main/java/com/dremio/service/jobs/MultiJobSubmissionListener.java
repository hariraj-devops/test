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

import com.dremio.service.job.proto.JobId;
import java.util.List;

/** Adapts {@link JobSubmissionListener} interface to support multiple listeners. */
public class MultiJobSubmissionListener implements JobSubmissionListener {
  private final List<JobSubmissionListener> jobSubmissionListeners;

  public MultiJobSubmissionListener(List<JobSubmissionListener> jobSubmissionListeners) {
    this.jobSubmissionListeners = jobSubmissionListeners;
  }

  /** Sequentially calls all registered listeners. */
  @Override
  public void onJobSubmitted(JobId jobId) {
    jobSubmissionListeners.forEach(listener -> listener.onJobSubmitted(jobId));
  }
}
