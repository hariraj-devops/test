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
package com.dremio.services.pubsub.inprocess;

import com.dremio.options.Options;
import com.dremio.options.TypeValidators;

/** Options for {@link InProcessPubSubClient}. */
@Options
public final class InProcessPubSubClientOptions {
  /** How often to poll from the message queue in absence of any publishing events. */
  public static final TypeValidators.PositiveLongValidator QUEUE_POLL_MILLIS =
      new TypeValidators.PositiveLongValidator("pubsub.inprocess.queue_poll_millis", 100, 10);

  /** How long to wait for executor service to terminate. */
  public static final TypeValidators.PositiveLongValidator TERMINATION_TIMEOUT_MILLIS =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.termination_timeout_millis", 10_000, 100);

  /**
   * Maximum number of messages to poll in one pass. If nothing is published, with the queue poll
   * interval this defines the maximum rate of processing.
   */
  public static final TypeValidators.PositiveLongValidator MAX_MESSAGES_TO_POLL =
      new TypeValidators.PositiveLongValidator("pubsub.inprocess.max_messages_to_poll", 100, 50);

  /**
   * When publishing messages, they are put in a blocking queue, this option is the timeout for the
   * offer call to the queue. The timed out messages are dropped to prevent breaking critical code
   * paths.
   */
  public static final TypeValidators.PositiveLongValidator PUBLISH_TIMEOUT_MILLISECONDS =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.publish_timeout_milliseconds", 100_000, 5000);

  /**
   * This is maximum number of runnables in-progress in the executor service. The current executor
   * service is a cached pool that spawns a thread whenever there is a runnable to run. So,
   * effectively, this is a limit on maximum number of threads.
   */
  public static final TypeValidators.PositiveLongValidator MAX_MESSAGES_IN_PROCESSING =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.max_messages_in_processing", 500, 100);

  /** Maximum number of redelivery attempts. */
  public static final TypeValidators.PositiveLongValidator MAX_REDELIVERY_ATTEMPTS =
      new TypeValidators.PositiveLongValidator("pubsub.inprocess.max_redelivery_attempts", 10, 5);

  /** Maximum number of messages in a topic queue before it starts blocking publishing. */
  public static final TypeValidators.PositiveLongValidator MAX_MESSAGES_IN_QUEUE =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.max_messages_in_queue", 100_000, 10_000);

  /** Maximum number of messages in the redelivery queue before it starts blocking. */
  public static final TypeValidators.PositiveLongValidator MAX_REDELIVERY_MESSAGES =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.max_redelivery_messages", 10_000, 1_000);

  /** Minimum delay in seconds for redelivery. */
  public static final TypeValidators.PositiveLongValidator MIN_DELAY_FOR_REDELIVERY_SECONDS =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.min_delay_for_redelivery_seconds", 60, 10);

  /** Maximum delay in seconds for redelivery. */
  public static final TypeValidators.PositiveLongValidator MAX_DELAY_FOR_REDELIVERY_SECONDS =
      new TypeValidators.PositiveLongValidator(
          "pubsub.inprocess.max_delay_for_redelivery_seconds", 600, 60);
}
