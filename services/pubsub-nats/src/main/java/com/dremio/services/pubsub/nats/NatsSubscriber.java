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

import static com.dremio.services.pubsub.nats.NatsConnectionOptionsProvider.getOptions;

import com.dremio.context.RequestContext;
import com.dremio.services.pubsub.MessageAckStatus;
import com.dremio.services.pubsub.MessageConsumer;
import com.dremio.services.pubsub.MessageContainerBase;
import com.dremio.services.pubsub.MessageSubscriber;
import com.dremio.services.pubsub.MessageSubscriberOptions;
import com.dremio.services.pubsub.Subscription;
import com.dremio.services.pubsub.nats.exceptions.NatsSubscriberException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.MessageHandler;
import io.nats.client.Nats;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NatsSubscriber<M extends Message> implements MessageSubscriber<M> {
  private static final Logger logger = LoggerFactory.getLogger(NatsSubscriber.class);
  public static final Duration DEFAULT_ACK_WAIT_SECONDS = Duration.ofSeconds(10);
  public static final long DEFAULT_MAX_PENDING = 1000L;
  private final Subscription<M> subscription;
  private final String natsServerUrl;
  private final MessageConsumer<M> messageConsumer;
  private final MessageSubscriberOptions<M> options;
  private Connection natsConnection;
  private JetStream jetStream;
  private JetStreamManagement jetStreamManagement;
  private io.nats.client.MessageConsumer consumer;

  public NatsSubscriber(
      Subscription<M> subscription,
      String natsServerUrl,
      MessageConsumer<M> messageConsumer,
      MessageSubscriberOptions<M> options) {
    this.subscription = subscription;
    this.natsServerUrl = natsServerUrl;
    this.messageConsumer = messageConsumer;
    this.options = options;
    logger.info(
        "Creating NatsSubscriber for subscription: {} and options: {}",
        subscription.getName(),
        options);
  }

  public void connect() {
    try {
      this.natsConnection = Nats.connect(getOptions(natsServerUrl));
      this.jetStreamManagement = natsConnection.jetStreamManagement();
      this.jetStream = natsConnection.jetStream();
    } catch (Exception e) {
      throw new NatsSubscriberException("Problem when connecting to NATS", e);
    }
  }

  /**
   * According to NATS docs: We recommend pull consumers for new projects. In particular when
   * scalability, detailed flow control or error handling are a concern.
   */
  @Override
  public void start() {
    // Validate required input
    String streamName =
        options
            .streamName()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "The stream name is required for the NATS subscriber."));

    Duration ackWait = options.ackWait().orElse(DEFAULT_ACK_WAIT_SECONDS);
    long maxAckPending = options.maxAckPending().orElse(DEFAULT_MAX_PENDING);
    String durableName = options.subscriberGroupName().orElseGet(this::generateRandomDurableName);

    // Build our subscription options. Durable is REQUIRED for pull based subscriptions
    // this allows consumer to re-start and resume from the last known state.
    ConsumerConfiguration consumerConfiguration =
        ConsumerConfiguration.builder()
            .durable(durableName)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(ackWait.toMillis())
            .maxAckPending(maxAckPending)
            .build();

    try {
      ConsumerInfo consumer =
          jetStreamManagement.addOrUpdateConsumer(streamName, consumerConfiguration);
      logger.info("Created consumer: {} for streamName: {}", consumer, streamName);

    } catch (Exception e) {
      logger.error("Problem when creating consumer", e);
      throw new NatsSubscriberException("Problem when creating consumer", e);
    }

    try {
      logger.info(
          "Starting subscriber for ackWait: {}, maxAckPending: {}, streamName: {}, durableName: {}",
          ackWait,
          maxAckPending,
          streamName,
          durableName);
      ConsumerContext consumerContext = jetStream.getConsumerContext(streamName, durableName);
      this.consumer =
          consumerContext.consume(new NatsMessageHandler<>(subscription, messageConsumer));
    } catch (IOException | JetStreamApiException e) {
      throw new NatsSubscriberException("Problem when consuming messages", e);
    }
  }

  static class NatsMessageHandler<M extends Message> implements MessageHandler {
    private final Subscription<M> subscription;
    private final MessageConsumer<M> messageConsumer;

    public NatsMessageHandler(Subscription<M> subscription, MessageConsumer<M> messageConsumer) {
      this.subscription = subscription;
      this.messageConsumer = messageConsumer;
    }

    @Override
    public void onMessage(io.nats.client.Message message) {
      RequestContext requestContext = RequestContext.current();
      // create a protobuf instance from bytes received from NATS
      M protoMessage;
      try {
        protoMessage = subscription.getMessageParser().parseFrom(message.getData());
      } catch (InvalidProtocolBufferException e) {
        throw new NatsSubscriberException("Problem when parsing message", e);
      }

      MessageContainerBase<M> natsMessageContainer =
          new NatsMessageContainer<>(message.getSID(), protoMessage, message, requestContext);
      messageConsumer.process(natsMessageContainer);
    }
  }

  private String generateRandomDurableName() {
    return "random-durable-name-" + new Random().nextInt(Integer.MAX_VALUE);
  }

  private static final class NatsMessageContainer<M extends Message>
      extends MessageContainerBase<M> {

    private final io.nats.client.Message natsMessage;

    private NatsMessageContainer(
        String id,
        M protoMessage,
        io.nats.client.Message natsMessage,
        RequestContext requestContext) {
      super(id, protoMessage, requestContext);
      this.natsMessage = natsMessage;
    }

    @Override
    public CompletableFuture<MessageAckStatus> ack() {
      natsMessage.ack();
      return CompletableFuture.completedFuture(MessageAckStatus.SUCCESSFUL);
    }

    @Override
    public CompletableFuture<MessageAckStatus> nack() {
      natsMessage.nak();
      return CompletableFuture.completedFuture(MessageAckStatus.SUCCESSFUL);
    }

    @Override
    public CompletableFuture<MessageAckStatus> nackWithDelay(Duration redeliveryDelay) {
      natsMessage.nakWithDelay(redeliveryDelay);
      return CompletableFuture.completedFuture(MessageAckStatus.SUCCESSFUL);
    }

    @Override
    public String toString() {
      return "NatsMessageContainer{" + "natsMessage=" + natsMessage + '}';
    }
  }

  @Override
  public void close() {
    try {
      natsConnection.close();
      consumer.stop();
    } catch (InterruptedException e) {
      throw new NatsSubscriberException("Problem when closing connection", e);
    }
  }
}
