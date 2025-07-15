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
package com.dremio.services.pubsub;

import com.google.protobuf.Message;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.immutables.value.Value;

@Value.Immutable
public interface MessageSubscriberOptions<M extends Message> {

  /**
   * The maxAckPending setting specifies the maximum number of unacknowledged messages that a
   * consumer can have at any given time. Once this limit is reached, the server will stop
   * delivering new messages to the consumer until some of the outstanding messages are
   * acknowledged. See the MaxAckPending
   * (https://docs.nats.io/nats-concepts/jetstream/consumers#maxackpending)
   *
   * <p>If not set, the default will be set to 1000.
   *
   * <p>It allows the same behaviour as GCP Pub-Sub max_outstanding_elements_count The one
   * difference is that for GCP, max_outstanding_elements_count is per subscriber, and here, it
   * works across all subscriptions bound to a consumer. So, the GCP value must be multiplied by the
   * number of expected NATS consumers.
   */
  OptionalLong maxAckPending();

  /**
   * The AckWait setting determines the amount of time the server will wait for an acknowledgment
   * for a delivered message before considering it as not acknowledged (nack) and redelivering it.
   * This is particularly useful in ensuring that messages are not lost and can be redelivered if
   * the consumer fails to acknowledge them within the specified timeframe.
   *
   * <p>If not set, the default will be set to 10 seconds
   *
   * <p>For the pub-sub, see https://cloud.google.com/pubsub/docs/lease-management
   */
  Optional<Duration> ackWait();

  /**
   * The subscriber group is used when creating a Subscription. All subscribers within the same
   * group will consume events from a given topic in a round-robin fashion. If N subscribers are
   * created with different groupName values, they will consume events independently, resulting in
   * the same message being received by each subscriber. To achieve load balancing across N
   * instances of your service, ensure all subscribers share the same groupName. This works
   * similarly to Kafka's consumer_group concept. For independent applications consuming events from
   * the same topic, use different groupName values.
   *
   * <p>If not set, a random group name will be generated for each of the subscribers.
   */
  Optional<String> subscriberGroupName();

  /**
   * The stream name is the name of the stream to which the subscriber is bound.
   *
   * <p>If not set, an exception will be thrown if using NATS implementation.
   */
  Optional<String> streamName();

  /**
   * By default messages are processed in order of their publishing. When supported, the pubsub
   * client can use the function to parallelize across keys. The function returns parallelization
   * key given a message.
   *
   * <p>Example 1: when the key is a random string (e.g. UUID.random().toString()), then all
   * messages are processed concurrently.
   *
   * <p>Example 2: when the keys are the same for some messages, the messages are processed in
   * order, but concurrently with messages that have different keys.
   */
  Optional<Function<M, String>> parallelizationKeyProvider();
}
