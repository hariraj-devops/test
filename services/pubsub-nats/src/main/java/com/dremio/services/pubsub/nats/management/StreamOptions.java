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
package com.dremio.services.pubsub.nats.management;

import java.util.OptionalInt;
import java.util.OptionalLong;
import org.immutables.value.Value;

@Value.Immutable
public interface StreamOptions {
  // TODO: can we have it injected based on the values.yaml NATS replicas?
  // The number of replicas should be equal to the number of NATS instances in the cluster - 1
  // to ensure that the stream is available even if one of the NATS instances goes down
  // and a quorum is not lost during node failure
  int DEFAULT_NUMBER_OF_REPLICAS = 2;

  // Maximum number of messages stored in the stream removing oldest messages
  // if the Stream exceeds this number of messages.
  // This value is picked in a conservative way to ensure that the stream does not impact other
  // streams.
  // If you have a stream that is expected to have more than 1_000_000 messages,
  // consider increasing this parameter. However, you should calculate the total expected size (GB)
  // of your stream and ensure that the NATS cluster has enough disk space to accommodate your
  // stream.
  long DEFAULT_MAX_MESSAGES = 1_000_000L;

  /**
   * The stream name is a unique identifier for a stream. Each stream must have a unique name within
   * the JetStream context. This name is used to manage and interact with the stream (e.g.,
   * updating, querying, or deleting it).
   */
  String streamName();

  /**
   * The number of replicas on which the stream will be replicated. If not specified, the default
   * (EQUALS to number of NATS instances in the cluster) will be used
   */
  OptionalInt numberOfReplicas();

  /**
   * The maximum number of messages that can be stored in the stream. If not specified, the default
   * (EQUALS to 1_000_000) will be used
   */
  OptionalLong maxMessages();

  /**
   * Get the number of replicas with a default value. If not provided, returns
   * DEFAULT_NUMBER_OF_REPLICAS.
   */
  @Value.Default
  default int getNumberOfReplicasOrDefault() {
    return numberOfReplicas().orElse(DEFAULT_NUMBER_OF_REPLICAS);
  }

  /**
   * The maximum number of messages that can be stored in the stream. If not specified, the default
   * (EQUALS to 1_000_000) will be used
   */
  @Value.Default
  default long getMaxMessagesOrDefault() {
    return maxMessages().orElse(DEFAULT_MAX_MESSAGES);
  }
}
