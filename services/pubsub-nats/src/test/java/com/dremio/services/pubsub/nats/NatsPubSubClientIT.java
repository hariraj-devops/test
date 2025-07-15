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
package com.dremio.services.pubsub.nats;

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.common.util.Closeable;
import com.dremio.services.pubsub.ImmutableMessageSubscriberOptions;
import com.dremio.services.pubsub.MessageContainerBase;
import com.dremio.services.pubsub.MessagePublisher;
import com.dremio.services.pubsub.MessageSubscriber;
import com.dremio.services.pubsub.Subscription;
import com.dremio.services.pubsub.TestMessageConsumer;
import com.dremio.services.pubsub.Topic;
import com.dremio.services.pubsub.nats.integration.NatsTestStarter;
import com.dremio.services.pubsub.nats.management.ImmutableStreamOptions;
import com.google.protobuf.Parser;
import com.google.protobuf.Timestamp;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NatsPubSubClientIT {

  private static final NatsTestStarter natsTestStarter = new NatsTestStarter();

  @BeforeAll
  public static void setUp() throws IOException, InterruptedException {
    natsTestStarter.setUp();
  }

  @AfterAll
  public static void tearDown() {
    natsTestStarter.tearDown();
  }

  public static final String STREAM_NAME = "TEST_STREAM";

  private MessagePublisher<Timestamp> publisher;
  private final List<MessageSubscriber<Timestamp>> subscribers = new ArrayList<>();
  private final List<TestMessageConsumer<Timestamp>> messageConsumers = new ArrayList<>();

  public void createStream() {
    createStream(1000);
  }

  public void createStream(long maxMessages) {
    NatsPubSubClient client = new NatsPubSubClient(natsTestStarter.getNatsUrl());
    client
        .getStreamManager()
        .createStreamIfNotExists(
            new ImmutableStreamOptions.Builder()
                .setStreamName(STREAM_NAME)
                .setNumberOfReplicas(1)
                .setMaxMessages(maxMessages)
                .build(),
            List.of(TestTopic.class));
  }

  @AfterEach
  public void deleteStream() throws IOException, JetStreamApiException {
    // Create JetStream Management context
    JetStreamManagement jsm = natsTestStarter.getNatsConnection().jetStreamManagement();

    jsm.deleteStream(STREAM_NAME);

    subscribers.forEach(Closeable::close);
    publisher.close();
  }

  @Test
  public void test_publishAndSubscribe() throws InterruptedException, ExecutionException {
    createStream();
    startClient(100, 1000);

    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(1);

    Timestamp timestamp =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    CompletableFuture<String> result = publisher.publish(timestamp);
    assertThat(result.get()).isNotEmpty();

    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();
  }

  @Test
  public void test_publishAndSubscribe_withMaxMessagesSet()
      throws InterruptedException, ExecutionException {
    // Create a stream with maxMsgs set to 1
    createStream(1);

    // Start publisher and subscriber
    startPublisher();

    // Publish two different messages
    Timestamp first = Timestamp.newBuilder().setSeconds(1111L).build();
    Timestamp second = Timestamp.newBuilder().setSeconds(2222L).build();

    publisher.publish(first).get();
    publisher.publish(second).get();

    // Wait and assert only one message is received
    startSubscriber(10, 1000);
    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(1);
    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));
    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);

    // Assert it's the second message
    MessageContainerBase<Timestamp> received = messageConsumers.get(0).getMessages().get(0);
    assertThat(received.getMessage()).isEqualTo(second);
    received.ack();
  }

  /** Test that nack results in immediate re-delivery. */
  @Test
  public void test_nack() throws Exception {
    createStream();
    startClient(100, 1000);

    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(1);

    Timestamp timestamp = Timestamp.newBuilder().setSeconds(Integer.MAX_VALUE).build();
    publisher.publish(timestamp);

    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));
    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);

    consumerLatch = messageConsumers.get(0).initLatch(1);
    messageContainer.nack();

    // Wait for redelivery. (in NASK we cannot control how fast the NACK message is re-delivered)
    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumers.get(0).getMessages()).hasSize(2);
    messageContainer = messageConsumers.get(0).getMessages().get(1);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();
  }

  /** Test that nack results in delayed re-delivery. */
  @Test
  public void test_nack_with_delay() throws Exception {
    createStream();
    startClient(100, 1000);

    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(1);

    Timestamp timestamp = Timestamp.newBuilder().setSeconds(Integer.MAX_VALUE).build();
    publisher.publish(timestamp);

    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));
    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);

    consumerLatch = messageConsumers.get(0).initLatch(1);
    messageContainer.nackWithDelay(Duration.ofHours(1));

    // Wait for redelivery. It should not happen earlier than 1 hour. We wait only 10 second to not
    // block the test execution longer
    Assertions.assertFalse(
        consumerLatch.await(10, TimeUnit.SECONDS), "Message was redelivered earlier than expected");

    // Verify that the message count has not increased
    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
  }

  /**
   * This tests that when the ack was not called for a message, the new message is not send when the
   * maxMessagesInProcessing is set to 1.
   */
  @Test
  public void test_blockIfTooManyInProcessing() throws InterruptedException {
    createStream();
    final long maxMessagesInProcessing = 1;
    startClient(maxMessagesInProcessing, 1000);
    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(2);

    // Publish two messages without ack. Customer should get only the first one
    Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L).build();
    publisher.publish(timestamp);
    publisher.publish(timestamp);

    // it won't get two messages (only the first one)
    Assertions.assertFalse(consumerLatch.await(10, TimeUnit.SECONDS));

    // ack the first message, should get both two messages
    messageConsumers.get(0).getMessages().get(0).ack();
    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));
  }

  /**
   * This tests verifies that message gets re-delivered when there was no nack/ack and the
   * maxWaitTime elapsed.
   */
  @Test
  public void test_redeliversWhenNoAckNackAndWaitTimeElapsed() throws InterruptedException {
    createStream();
    final long maxMessagesInProcessing = 10;
    int ackWaitSeconds = 10;
    startClient(maxMessagesInProcessing, ackWaitSeconds);
    CountDownLatch consumerLatch = messageConsumers.get(0).initLatch(2);

    // Publish one message without ack/nack. Customer should get only the first one
    // and second one should be retried
    Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L).build();
    publisher.publish(timestamp);

    // it won't get two messages (only the first one)
    Assertions.assertFalse(consumerLatch.await(10, TimeUnit.SECONDS));

    // the ackWaitSeconds elapsed, should get both two messages
    Assertions.assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));
  }

  @Test
  public void test_deliverToBothSubscribersWithinTheSameGroupNameInRoundRobinWay()
      throws InterruptedException, ExecutionException {
    createStream();
    // given two subscribers within the same group name
    startPublisher();
    startSubscriber(100, 1000, "group_name", 0);
    startSubscriber(100, 1000, "group_name", 1);

    CountDownLatch consumerLatch1 = messageConsumers.get(0).initLatch(1);
    CountDownLatch consumerLatch2 = messageConsumers.get(1).initLatch(1);

    // when publish
    Timestamp timestamp =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    Timestamp timestamp2 =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    CompletableFuture<String> result = publisher.publish(timestamp);
    assertThat(result.get()).isNotEmpty();
    result = publisher.publish(timestamp2);
    assertThat(result.get()).isNotEmpty();

    // then message should be delivered in round-robin way
    Assertions.assertTrue(consumerLatch1.await(10, TimeUnit.SECONDS));
    Assertions.assertTrue(consumerLatch2.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();

    assertThat(messageConsumers.get(1).getMessages()).hasSize(1);
    messageContainer = messageConsumers.get(1).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp2);
    messageContainer.ack();
  }

  @Test
  public void test_deliverToDuplicatesToIndependentSubscribersForDifferentGroupNames()
      throws InterruptedException, ExecutionException {
    createStream();
    // given two subscribers within the same group name
    startPublisher();
    startSubscriber(100, 1000, "group_name1", 0);
    startSubscriber(100, 1000, "group_name2", 1);

    CountDownLatch consumerLatch1 = messageConsumers.get(0).initLatch(1);
    CountDownLatch consumerLatch2 = messageConsumers.get(1).initLatch(1);

    // when publish one message
    Timestamp timestamp =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    CompletableFuture<String> result = publisher.publish(timestamp);
    assertThat(result.get()).isNotEmpty();

    // then message should be delivered to both subscribers
    Assertions.assertTrue(consumerLatch1.await(10, TimeUnit.SECONDS));
    Assertions.assertTrue(consumerLatch2.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();

    assertThat(messageConsumers.get(1).getMessages()).hasSize(1);
    messageContainer = messageConsumers.get(1).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();
  }

  @Test
  public void test_shouldAllowUpdateConfigForTheSameGroupName_andDeliverInRoundRobinWay()
      throws InterruptedException, ExecutionException {
    createStream();
    // given two subscribers within the same group name but different config
    startPublisher();
    startSubscriber(100, 1000, "group_name", 0);
    startSubscriber(200, 1000, "group_name", 1);

    CountDownLatch consumerLatch1 = messageConsumers.get(0).initLatch(1);
    CountDownLatch consumerLatch2 = messageConsumers.get(1).initLatch(1);

    // when publish
    Timestamp timestamp =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    Timestamp timestamp2 =
        Timestamp.newBuilder().setSeconds(new Random().nextInt(Integer.MAX_VALUE)).build();
    CompletableFuture<String> result = publisher.publish(timestamp);
    assertThat(result.get()).isNotEmpty();
    result = publisher.publish(timestamp2);
    assertThat(result.get()).isNotEmpty();

    // then message should be delivered in round-robin way
    Assertions.assertTrue(consumerLatch1.await(10, TimeUnit.SECONDS));
    Assertions.assertTrue(consumerLatch2.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumers.get(0).getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumers.get(0).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();

    assertThat(messageConsumers.get(1).getMessages()).hasSize(1);
    messageContainer = messageConsumers.get(1).getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp2);
    messageContainer.ack();
  }

  private void startClient(long maxMessagesInProcessing, int ackWaitSeconds) {
    startPublisher();
    startSubscriber(maxMessagesInProcessing, ackWaitSeconds);
  }

  private void startPublisher() {
    NatsPubSubClient client = new NatsPubSubClient(natsTestStarter.getNatsUrl());
    publisher = client.getPublisher(TestTopic.class, null);
  }

  private void startSubscriber(
      long maxMessagesInProcessing, int ackWaitSeconds, String groupName, int index) {
    messageConsumers.add(index, new TestMessageConsumer<>());
    NatsPubSubClient client = new NatsPubSubClient(natsTestStarter.getNatsUrl());

    MessageSubscriber<Timestamp> subscriber =
        client.getSubscriber(
            TestSubscription.class,
            messageConsumers.get(index),
            new ImmutableMessageSubscriberOptions.Builder<Timestamp>()
                .setMaxAckPending(maxMessagesInProcessing)
                .setAckWait(Duration.ofSeconds(ackWaitSeconds))
                .setSubscriberGroupName(groupName)
                .setStreamName(STREAM_NAME)
                .build());
    subscribers.add(subscriber);

    subscriber.start();
  }

  private void startSubscriber(long maxMessagesInProcessing, int ackWaitSeconds) {
    startSubscriber(maxMessagesInProcessing, ackWaitSeconds, "my_group_name", 0);
  }

  public static final class TestTopic implements Topic<Timestamp> {
    @Override
    public String getName() {
      return "test-subject";
    }

    @Override
    public Class<Timestamp> getMessageClass() {
      return Timestamp.class;
    }
  }

  public static final class TestSubscription implements Subscription<Timestamp> {
    @Override
    public String getName() {
      return "test-subject";
    }

    @Override
    public Parser<Timestamp> getMessageParser() {
      return Timestamp.parser();
    }

    @Override
    public Class<? extends Topic<Timestamp>> getTopicClass() {
      return TestTopic.class;
    }
  }
}
