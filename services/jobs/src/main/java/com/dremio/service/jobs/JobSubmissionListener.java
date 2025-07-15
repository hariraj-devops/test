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

/**
 * A listener that is invoked when a job is submitted. The caller is expected to call this before
 * the job begins executing; therefore, this class can be used, for example, to set up a job event
 * subscriber that will ensure leading events are not missed.
 */
public interface JobSubmissionListener {
  JobSubmissionListener NOOP = jobId -> {};

  /**
   * @implSpec This method must run and complete quickly, since it may be called synchronously on
   *     performance-sensitive code paths, for example during job submission.
   */
  void onJobSubmitted(JobId jobId);
}
