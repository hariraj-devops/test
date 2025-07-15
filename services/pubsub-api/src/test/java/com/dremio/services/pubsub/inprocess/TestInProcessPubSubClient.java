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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators;
import com.dremio.services.pubsub.ImmutableMessagePublisherOptions;
import com.dremio.services.pubsub.ImmutableMessageSubscriberOptions;
import com.dremio.services.pubsub.MessageContainerBase;
import com.dremio.services.pubsub.MessagePublisher;
import com.dremio.services.pubsub.MessageSubscriber;
import com.dremio.services.pubsub.Subscription;
import com.dremio.services.pubsub.TestMessageConsumer;
import com.dremio.services.pubsub.Topic;
import com.google.protobuf.Parser;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.opentelemetry.api.OpenTelemetry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TestInProcessPubSubClient {
  @Mock private Provider<RequestContext> requestContextProvider;
  @Mock private OptionManager optionManager;
  @Mock private InProcessPubSubEventListener eventListener;

  private InProcessPubSubClient client;
  private MessagePublisher<Timestamp> publisher;
  private final TestMessageConsumer<Timestamp> messageConsumer = new TestMessageConsumer<>();

  @AfterEach
  public void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  /** Default mocking of the {@link OptionManager}. */
  private void mockOptionManager() {
    doAnswer(
            (args) -> {
              TypeValidators.LongValidator validator = args.getArgument(0);
              return validator.getDefault().getNumVal();
            })
        .when(optionManager)
        .getOption(any(TypeValidators.LongValidator.class));
  }

  private void mockRequestContextProvider() {
    when(requestContextProvider.get())
        .thenReturn(
            RequestContext.current()
                .with(UserContext.CTX_KEY, UserContext.of(UUID.randomUUID().toString())));
  }

  private void startClient() {
    startClient(null);
  }

  private void startClient(@Nullable Function<Timestamp, String> parallelizationKeyProvider) {
    client =
        new InProcessPubSubClient(
            requestContextProvider, optionManager, OpenTelemetry.noop(), eventListener);
    publisher =
        client.getPublisher(
            TestTopic.class, new ImmutableMessagePublisherOptions.Builder().build());
    var optionBuilder = new ImmutableMessageSubscriberOptions.Builder<Timestamp>();
    if (parallelizationKeyProvider != null) {
      optionBuilder.setParallelizationKeyProvider(parallelizationKeyProvider);
    }
    MessageSubscriber<Timestamp> subscriber =
        client.getSubscriber(TestSubscription.class, messageConsumer, optionBuilder.build());
    subscriber.start();
  }

  @Test
  public void test_shutdown() {
    mockOptionManager();
    startClient();

    // expect no exceptions
    client.close();
  }

  @Test
  public void test_cannotAddSameTopic() {
    mockOptionManager();
    startClient();

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                client.getPublisher(
                    TestTopic.class, new ImmutableMessagePublisherOptions.Builder().build()));
    assertThat(e).hasMessage("Publisher for topic test is already registered");
  }

  @Test
  public void test_cannotAddSameSubscription() {
    mockOptionManager();
    startClient();

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                client.getSubscriber(
                    TestSubscription.class,
                    messageConsumer,
                    new ImmutableMessageSubscriberOptions.Builder<Timestamp>().build()));
    assertThat(e).hasMessage("Subscriber for subscription test is already registered");
  }

  @Test
  public void test_publishAndSubscribe() throws Exception {
    mockOptionManager();
    mockRequestContextProvider();
    startClient();

    CountDownLatch consumerLatch = messageConsumer.initLatch(1);

    Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L).build();
    publisher.publish(timestamp);

    assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));

    // Verify.
    assertThat(messageConsumer.getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumer.getMessages().get(0);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();

    verify(eventListener, times(1))
        .onPublish(eq(new TestTopic().getName()), anyInt(), eq(true), eq(null));
    verify(eventListener, times(1))
        .onMessageReceived(
            eq(new TestTopic().getName()),
            eq(new TestSubscription().getName()),
            eq(true),
            eq(null));
  }

  /** Test that nack results in delayed re-delivery. */
  @Test
  public void test_nack() throws Exception {
    final long minDelaySeconds = 1;
    final long maxDelaySeconds = 2;
    doAnswer(
            (args) -> {
              TypeValidators.LongValidator validator = args.getArgument(0);
              switch (validator.getOptionName()) {
                case "pubsub.inprocess.min_delay_for_redelivery_seconds":
                  return minDelaySeconds;
                case "pubsub.inprocess.max_delay_for_redelivery_seconds":
                  return maxDelaySeconds;
                default:
                  return validator.getDefault().getNumVal();
              }
            })
        .when(optionManager)
        .getOption(any(TypeValidators.LongValidator.class));
    mockRequestContextProvider();
    startClient();

    CountDownLatch consumerLatch = messageConsumer.initLatch(1);

    Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L).build();
    publisher.publish(timestamp);

    assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumer.getMessages()).hasSize(1);
    MessageContainerBase<Timestamp> messageContainer = messageConsumer.getMessages().get(0);

    long timeBeforeNack = System.currentTimeMillis();
    consumerLatch = messageConsumer.initLatch(1);
    messageContainer.nack();

    // Wait for redelivery.
    assertTrue(consumerLatch.await(10, TimeUnit.SECONDS));

    assertThat(messageConsumer.getMessages()).hasSize(2);
    messageContainer = messageConsumer.getMessages().get(1);
    assertThat(messageContainer.getMessage()).isEqualTo(timestamp);
    messageContainer.ack();

    // Check that redelivery was delayed by a second.
    assertThat((System.currentTimeMillis() - timeBeforeNack) / 1000)
        .isBetween(minDelaySeconds, maxDelaySeconds);
  }

  /**
   * This tests that too many publishing requests with slow processing of messages results in
   * publisher being blocked until executor service frees up.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void test_blockIfTooManyInProcessing(boolean simulateTimeout) throws Exception {
    final long minDelaySeconds = 1;
    final long maxDelaySeconds = 2;
    final long maxMessagesInProcessing = 1;
    final long maxMessagesToPoll = 1;
    final long simulatedPublishTimeoutMillis = 100;
    doAnswer(
            (args) -> {
              TypeValidators.LongValidator validator = args.getArgument(0);
              switch (validator.getOptionName()) {
                case "pubsub.inprocess.min_delay_for_redelivery_seconds":
                  return minDelaySeconds;
                case "pubsub.inprocess.max_delay_for_redelivery_seconds":
                  return maxDelaySeconds;
                // Set queue size and max processing count to the same value.
                case "pubsub.inprocess.max_messages_in_queue":
                case "pubsub.inprocess.max_messages_in_processing":
                  return maxMessagesInProcessing;
                case "pubsub.inprocess.max_messages_to_poll":
                  return maxMessagesToPoll;
                case "pubsub.inprocess.publish_timeout_milliseconds":
                  return simulateTimeout
                      ? simulatedPublishTimeoutMillis
                      : validator.getDefault().getNumVal();
                default:
                  return validator.getDefault().getNumVal();
              }
            })
        .when(optionManager)
        .getOption(any(TypeValidators.LongValidator.class));
    mockRequestContextProvider();
    startClient();

    // Set processing delay.
    long delayMillis = 1000;
    messageConsumer.setProcessingDelayMillis(delayMillis);

    // Publish messages:
    //  - First message processing blocks for a second.
    //  - Second message is put into the queue quickly but cannot get out of the queue because
    //    too many messages are being processed.
    //  - Third message cannot be put into the blocking queue as it's full, so the call to publish
    //    blocks for delayMillis at least.
    long beforePublishMillis = System.currentTimeMillis();
    Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L).build();
    publisher.publish(timestamp);
    publisher.publish(timestamp);
    String thirdMessageId = publisher.publish(timestamp).get();
    long afterPublishMillis = System.currentTimeMillis();
    long elapsedMillis = afterPublishMillis - beforePublishMillis;

    // Verify delay.
    if (simulateTimeout) {
      // Expect empty 3rd message id because of timeout.
      assertThat(thirdMessageId).isEmpty();
      // Expect the publish calls took at least the simulated publish timeout but less than the
      // delay in processing.
      assertThat(elapsedMillis).isBetween(simulatedPublishTimeoutMillis, (2 * delayMillis) / 3);
    } else {
      assertThat(thirdMessageId).isNotEmpty();
      assertThat(elapsedMillis).isBetween(delayMillis, 5 * delayMillis);
    }
  }

  /**
   * This tests that delays in synchronization primitives waits don't add up to a large delay in
   * processing. The test runs with two parallelism options to test order of event processing.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 10})
  public void test_throughput(long parallelism) throws Exception {
    doAnswer(
            (args) -> {
              TypeValidators.LongValidator validator = args.getArgument(0);
              if (validator
                  .getOptionName()
                  .equals(
                      InProcessPubSubClientOptions.MAX_MESSAGES_IN_PROCESSING.getOptionName())) {
                return parallelism;
              }
              return validator.getDefault().getNumVal();
            })
        .when(optionManager)
        .getOption(any(TypeValidators.LongValidator.class));
    mockRequestContextProvider();
    startClient();

    // Publish and wait for all to arrive.
    long startTime = System.currentTimeMillis();
    int messagesToPublish = 10000;
    CountDownLatch latch = messageConsumer.initLatch(messagesToPublish);
    List<Long> expectedListOfSeconds = new ArrayList<>();
    for (int i = 0; i < messagesToPublish; i++) {
      Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L + i).build();
      expectedListOfSeconds.add(timestamp.getSeconds());
      publisher.publish(timestamp);
    }

    // The default delay in queue processing is 10ms, which for 10K items
    // would by far exceed below times if excessive wait existed.
    // The messages are processed sequentially w/o parallelizationKey so the times are comparable:
    //   ELAPSED (1): 3826ms
    //   ELAPSED (10): 3517ms
    assertTrue(latch.await(30000, TimeUnit.MILLISECONDS));
    System.err.printf("ELAPSED (%d): %dms\n", parallelism, System.currentTimeMillis() - startTime);

    // Verify that the messages were processed in order.
    List<Long> actualListOfSeconds =
        messageConsumer.getMessages().stream()
            .map(m -> m.getMessage().getSeconds())
            .collect(Collectors.toList());
    if (!actualListOfSeconds.equals(expectedListOfSeconds)) {
      assertThat(actualListOfSeconds.size()).isEqualTo(expectedListOfSeconds.size());
      for (int i = 0; i < actualListOfSeconds.size(); i++) {
        if (!actualListOfSeconds.get(i).equals(expectedListOfSeconds.get(i))) {
          Assertions.fail(
              String.format(
                  "Invalid order at %d: %d vs %d",
                  i, actualListOfSeconds.get(i), expectedListOfSeconds.get(i)));
          break;
        }
      }
    }
  }

  @Test
  public void test_throughputWithConcurrentKey() throws Exception {
    runConcurrentKeyTest(1);
    runConcurrentKeyTest(10);
    runConcurrentKeyTest(100);
  }

  private long runConcurrentKeyTest(long parallelism) throws Exception {
    doAnswer(
            (args) -> {
              TypeValidators.LongValidator validator = args.getArgument(0);
              if (validator
                  .getOptionName()
                  .equals(
                      InProcessPubSubClientOptions.MAX_MESSAGES_IN_PROCESSING.getOptionName())) {
                return parallelism;
              }
              return validator.getDefault().getNumVal();
            })
        .when(optionManager)
        .getOption(any(TypeValidators.LongValidator.class));
    mockRequestContextProvider();
    startClient((t) -> Long.toString(Timestamps.toMicros(t)));

    // Publish and wait for all to arrive.
    messageConsumer.setProcessingDelayMillis(1);
    long startTime = System.currentTimeMillis();
    int messagesToPublish = 10000;
    CountDownLatch latch = messageConsumer.initLatch(messagesToPublish);
    Set<Long> expectedSeconds = new HashSet<>();
    for (int i = 0; i < messagesToPublish; i++) {
      Timestamp timestamp = Timestamp.newBuilder().setSeconds(1000L + i).build();
      expectedSeconds.add(timestamp.getSeconds());
      publisher.publish(timestamp);
    }

    // It takes ~200ms to run it, set 10x that timeout to avoid flakiness.
    // The default delay in queue processing is 10ms, which for 10K items
    // would by far exceed 2s if excessive wait existed.
    // With 1ms delay, there is proportional decrease in processing time:
    //   ELAPSED (1): 12689ms
    //   ELAPSED (10): 1173ms
    //   ELAPSED (100): 143ms
    // With 10ms delay, there is proportional decrease in processing time:
    //   ELAPSED (1): 102192ms
    //   ELAPSED (10): 11360ms
    //   ELAPSED (100): 1098ms
    // With 100ms delay, there is proportional increase in processing time compared to 10ms:
    //   ELAPSED (100): 10257ms
    // Note that this timeout does not include blocks in publish calls above, they may be blocked.
    assertTrue(latch.await(30000, TimeUnit.MILLISECONDS));
    long elapsed = System.currentTimeMillis() - startTime;
    System.err.printf("ELAPSED (%d): %dms\n", parallelism, elapsed);

    // Verify that the messages were processed in order.
    Set<Long> actualSeconds =
        messageConsumer.getMessages().stream()
            .map(m -> m.getMessage().getSeconds())
            .collect(Collectors.toSet());
    assertTrue(actualSeconds.equals(expectedSeconds));
    return elapsed;
  }

  @Test
  public void test_requestContextPropagates() throws InterruptedException {
    mockOptionManager();

    UserContext userContext = UserContext.of(UUID.randomUUID().toString());
    when(requestContextProvider.get())
        .thenReturn(RequestContext.current().with(UserContext.CTX_KEY, userContext));
    AtomicReference<Throwable> throwable = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    //noinspection resource
    InProcessPubSubClient client =
        new InProcessPubSubClient(
            requestContextProvider, optionManager, OpenTelemetry.noop(), eventListener);
    client
        .getSubscriber(
            TestSubscription.class,
            message -> {
              try {
                assertNotNull(RequestContext.current().get(UserContext.CTX_KEY));
                assertThat(RequestContext.current().get(UserContext.CTX_KEY))
                    .isEqualTo(userContext);
              } catch (Throwable e) {
                throwable.set(e);
              } finally {
                latch.countDown();
              }
            },
            new ImmutableMessageSubscriberOptions.Builder<Timestamp>().build())
        .start();

    client
        .getPublisher(TestTopic.class, new ImmutableMessagePublisherOptions.Builder().build())
        .publish(Timestamp.getDefaultInstance());

    assertTrue(latch.await(30000, TimeUnit.MILLISECONDS));
    assertNull(throwable.get());

    client.close();
  }

  public static final class TestTopic implements Topic<Timestamp> {
    @Override
    public String getName() {
      return "test";
    }

    @Override
    public Class<Timestamp> getMessageClass() {
      return Timestamp.class;
    }
  }

  public static final class TestSubscription implements Subscription<Timestamp> {
    @Override
    public String getName() {
      return "test";
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
