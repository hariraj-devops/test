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

import com.dremio.common.util.Closeable;
import com.dremio.context.RequestContext;
import com.dremio.options.OptionManager;
import com.dremio.services.pubsub.MessageAckStatus;
import com.dremio.services.pubsub.MessageConsumer;
import com.dremio.services.pubsub.MessageContainerBase;
import com.dremio.services.pubsub.MessagePublisher;
import com.dremio.services.pubsub.MessagePublisherOptions;
import com.dremio.services.pubsub.MessageSubscriber;
import com.dremio.services.pubsub.MessageSubscriberOptions;
import com.dremio.services.pubsub.PubSubClient;
import com.dremio.services.pubsub.PubSubTracerDecorator;
import com.dremio.services.pubsub.Subscription;
import com.dremio.services.pubsub.Topic;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.Message;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import org.apache.commons.lang3.reflect.ConstructorUtils;

/**
 * In-process pubsub implementation intended for decoupling of notification processing from the
 * publishers of the notifications. The messages are stored in a queue during publish call and are
 * processed on an executor asynchronously. The {@link RequestContext} is passed from the publisher
 * and can be used by {@link MessageConsumer} to run processing in the same context.
 *
 * <p>There is no message redelivery unless messages are nacked. Ack() has no effect as there is no
 * storage for messages and the messages are removed from the queue before the consumer is called.
 *
 * <p>Work stealing executor is used to avoid blocking, if a thread is waiting (e.g. for an IO
 * operation), another thread may be added to maintain the level of parallelism requested. One
 * thread in the pool is reserved for polling from the queues.
 */
public class InProcessPubSubClient implements PubSubClient, Closeable {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(InProcessPubSubClient.class);

  private static final String INSTRUMENTATION_SCOPE_NAME = "pubsub.inprocess";

  private static final Random random = new Random();

  private final Provider<RequestContext> requestContextProvider;
  private final OptionManager optionManager;
  private final Tracer tracer;
  private final InProcessPubSubEventListener eventListener;

  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final ExecutorService executorService;
  private final Map<String, Publisher<? extends Message>> publisherByTopicName = new HashMap<>();
  private final ConcurrentMap<String, ArrayBlockingQueue<MessageContainer<?>>> queuesByTopicName =
      new ConcurrentHashMap<>();
  private final BoundedBlockingPriorityQueue<MessageContainer<?>> redeliveryQueue;
  private final Multimap<String, String> subscriptionNamesByTopicName = ArrayListMultimap.create();
  private final Map<String, Subscriber<? extends Message>> subscribersBySubscriptionName =
      new HashMap<>();
  private final AutoResetEvent startProcessingEvent = new AutoResetEvent();
  private final Semaphore messagesInProcessingSemaphore;

  @Inject
  public InProcessPubSubClient(
      Provider<RequestContext> requestContextProvider,
      OptionManager optionManager,
      OpenTelemetry openTelemetry,
      InProcessPubSubEventListener eventListener) {
    this.requestContextProvider = requestContextProvider;
    this.optionManager = optionManager;
    this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE_NAME);
    this.eventListener = eventListener;

    // Resource usage would be better with virtual threads as there is no heavy work done here.
    // TODO: once Java 21 is enabled switch to Executors.newVirtualThreadPerTaskExecutor().
    this.executorService =
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setDaemon(true) // change from default thread pool, no need to block app exit
                .setPriority(Thread.NORM_PRIORITY)
                .setNameFormat("InProcessPubSub worker %d")
                .build());
    this.redeliveryQueue =
        new BoundedBlockingPriorityQueue<>(
            (int) optionManager.getOption(InProcessPubSubClientOptions.MAX_REDELIVERY_MESSAGES),
            Comparator.comparingLong(MessageContainer::getRedeliverTimeMillis));
    this.messagesInProcessingSemaphore =
        new Semaphore(
            (int) optionManager.getOption(InProcessPubSubClientOptions.MAX_MESSAGES_IN_PROCESSING));

    // Use one thread for polling.
    Thread queueProcessingThread = new Thread(this::processQueues, "InProcessPubSub processQueue");
    queueProcessingThread.setDaemon(true);
    queueProcessingThread.setPriority(Thread.NORM_PRIORITY);
    queueProcessingThread.start();
  }

  @Override
  public <M extends Message> MessagePublisher<M> getPublisher(
      Class<? extends Topic<M>> topicClass, MessagePublisherOptions options) {
    Topic<M> topic;
    try {
      topic = ConstructorUtils.invokeConstructor(topicClass);
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | InstantiationException e) {
      throw new RuntimeException(e);
    }

    synchronized (publisherByTopicName) {
      if (publisherByTopicName.containsKey(topic.getName())) {
        throw new RuntimeException(
            String.format("Publisher for topic %s is already registered", topic.getName()));
      }

      Publisher<M> publisher = new Publisher<M>(topic.getName());
      publisherByTopicName.put(topic.getName(), publisher);
      return publisher;
    }
  }

  @Override
  public <M extends Message> MessageSubscriber<M> getSubscriber(
      Class<? extends Subscription<M>> subscriptionClass,
      MessageConsumer<M> messageConsumer,
      MessageSubscriberOptions<M> options) {
    if (options.maxAckPending().isPresent()
        || options.ackWait().isPresent()
        || options.subscriberGroupName().isPresent()
        || options.streamName().isPresent()) {
      throw new UnsupportedOperationException(
          "The settings: maxAckPending, ackWait, subscriberGroupName, streamName are not supported for this subscriber.");
    }
    Subscription<M> subscription;
    Topic<M> topic;
    try {
      subscription = ConstructorUtils.invokeConstructor(subscriptionClass);
      topic = ConstructorUtils.invokeConstructor(subscription.getTopicClass());
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | InstantiationException e) {
      throw new RuntimeException(e);
    }

    // Add subscriber. It's activated by its start() method.
    synchronized (subscribersBySubscriptionName) {
      if (subscribersBySubscriptionName.containsKey(subscription.getName())) {
        throw new RuntimeException(
            String.format(
                "Subscriber for subscription %s is already registered", subscription.getName()));
      }
      Subscriber<M> subscriber =
          new Subscriber<>(topic.getName(), subscription.getName(), messageConsumer, options);
      subscribersBySubscriptionName.put(subscription.getName(), subscriber);
      return subscriber;
    }
  }

  @Override
  public void close() {
    shutdownLatch.countDown();
    try {
      // Wait for queue processing to complete.
      executorService.shutdown();
      if (!executorService.awaitTermination(
          optionManager.getOption(InProcessPubSubClientOptions.TERMINATION_TIMEOUT_MILLIS),
          TimeUnit.MILLISECONDS)) {
        // Forcefully remove runnables from the executor queue in case of timeout.
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      logger.warn("Interrupted while shutting down", e);
    }
  }

  private void onPublish(
      String topicName, int queueLength, boolean success, @Nullable String exceptionName) {
    eventListener.onPublish(topicName, queueLength, success, exceptionName);
  }

  private void onMessageReceived(
      String topicName, String subscriptionName, boolean success, @Nullable String exceptionName) {
    eventListener.onMessageReceived(topicName, subscriptionName, success, exceptionName);
  }

  /** Iterates over messages in the message queues and processes them on the executor. */
  private void processQueues() {
    try {
      while (!shutdownLatch.await(0, TimeUnit.MILLISECONDS)) {
        // Continue waiting if no messages to process.
        startProcessingEvent.waitEvent(
            optionManager.getOption(InProcessPubSubClientOptions.QUEUE_POLL_MILLIS));

        // Iterate over all topics, poll messages up to a limit and submit for execution.
        // Ok to do it under a lock as it only queues the tasks w/o running them.
        boolean setProcessingEvent = false;
        for (Map.Entry<String, ArrayBlockingQueue<MessageContainer<?>>> entry :
            queuesByTopicName.entrySet()) {
          // Process these many messages in one pass per topic at most. Process them in order
          // by parallelization key.
          long maxMessagesToPoll =
              optionManager.getOption(InProcessPubSubClientOptions.MAX_MESSAGES_TO_POLL);
          boolean startedProcessing = false;
          while (!entry.getValue().isEmpty() && maxMessagesToPoll-- > 0) {
            MessageContainer<?> messageContainer = entry.getValue().peek();
            Subscriber<?> subscriber = getSubscriber(messageContainer.getSubscriptionName());
            if (subscriber != null) {
              // Cannot add more work to it until it's done to preserve order.
              if (!subscriber.isProcessing(messageContainer)) {
                // Try to acquire permit to process the message. This blocks for a short time until
                // a slot is available.
                if (messagesInProcessingSemaphore.tryAcquire(
                    optionManager.getOption(InProcessPubSubClientOptions.QUEUE_POLL_MILLIS),
                    TimeUnit.MILLISECONDS)) {
                  MessageContainer<?> finalMessageContainer = entry.getValue().take();
                  subscriber.setIsProcessing(finalMessageContainer);
                  executorService.submit(() -> subscriber.processMessage(finalMessageContainer));
                  startedProcessing = true;
                }
              }
            } else {
              // Subscriber does not exist, remove message from the queue.
              entry.getValue().take().ack();
            }
          }

          // If the queue is not empty and some messages were posted to be processed,
          // trigger next batch processing w/o wait.
          if (!entry.getValue().isEmpty() && startedProcessing) {
            setProcessingEvent = true;
          }
        }

        // Re-add messages from the redelivery queue.
        long currentTimeMillis = System.currentTimeMillis();
        while (!redeliveryQueue.isEmpty()
            && redeliveryQueue.peek().getRedeliverTimeMillis() <= currentTimeMillis) {
          MessageContainer<?> messageContainer = redeliveryQueue.take();
          ArrayBlockingQueue<MessageContainer<?>> queue =
              queuesByTopicName.get(messageContainer.getTopicName());
          if (queue != null) {
            queue.put(messageContainer);
          }
        }

        // If any of the queues is not empty, immediately start next processing iteration.
        if (setProcessingEvent || !redeliveryQueue.isEmpty()) {
          startProcessingEvent.set();
        }
      }
    } catch (InterruptedException e) {
      logger.warn("Interrupted queue processing", e);
    } catch (Exception e) {
      logger.error("Failed in queueProcessingThread", e);
    }
  }

  private Subscriber<?> getSubscriber(String subscriptionName) {
    synchronized (subscribersBySubscriptionName) {
      return subscribersBySubscriptionName.get(subscriptionName);
    }
  }

  /**
   * The publisher adds {@link MessageContainer}s, one for every subscriber registered at the time
   * of message publish.
   */
  private final class Publisher<M extends Message> implements MessagePublisher<M> {
    private final String topicName;

    private Publisher(String topicName) {
      this.topicName = topicName;
    }

    @Override
    public CompletableFuture<String> publish(M message) {
      return PubSubTracerDecorator.addPublishTracing(
          tracer,
          topicName,
          (span) -> {
            try {
              if (shutdownLatch.await(0, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("The pubsub client was stopped");
              }
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }

            try {
              // Build messages per subscription.
              String messageId = UUID.randomUUID().toString();
              RequestContext requestContext = RequestContext.current();
              List<MessageContainer<M>> messageContainersToAdd = new ArrayList<>();
              synchronized (subscriptionNamesByTopicName) {
                for (String subscriptionName : subscriptionNamesByTopicName.get(topicName)) {
                  messageContainersToAdd.add(
                      new MessageContainer<>(
                          topicName, subscriptionName, messageId, message, requestContext));
                }
              }

              // Add messages to the processing queue.
              if (!messageContainersToAdd.isEmpty()) {
                // Use a blocking queue to guard against OOM.
                int maxMessagesInQueue =
                    (int)
                        optionManager.getOption(InProcessPubSubClientOptions.MAX_MESSAGES_IN_QUEUE);
                ArrayBlockingQueue<MessageContainer<?>> queue =
                    queuesByTopicName.computeIfAbsent(
                        topicName, (key) -> new ArrayBlockingQueue<>(maxMessagesInQueue));
                span.setAttribute(
                    InProcessPubSubClientSpanAttribute.PUBSUB_INPROCESS_QUEUE_SIZE, queue.size());
                span.setAttribute(
                    InProcessPubSubClientSpanAttribute.PUBSUB_INPROCESS_QUEUE_SATURATION,
                    queue.size() / (double) maxMessagesInQueue);
                int timeoutMilliseconds =
                    (int)
                        optionManager.getOption(
                            InProcessPubSubClientOptions.PUBLISH_TIMEOUT_MILLISECONDS);
                for (MessageContainer<M> container : messageContainersToAdd) {
                  try {
                    if (!queue.offer(container, timeoutMilliseconds, TimeUnit.MILLISECONDS)) {
                      logger.error(
                          "Timeout while waiting to put message {} into topic {} for subscription {}."
                              + " Silently, returning from the publish call.",
                          message,
                          topicName,
                          container.getSubscriptionName());
                      return CompletableFuture.completedFuture("");
                    }
                  } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting to put message in queue", e);
                    throw new RuntimeException(e);
                  }
                }

                // Notify queue processing thread that new messages are available.
                startProcessingEvent.set();

                // Notify listener.
                onPublish(topicName, queue.size(), true, null);
              }

              return CompletableFuture.completedFuture(messageId);
            } catch (Exception e) {
              // Notify listener.
              Queue<MessageContainer<?>> queue = queuesByTopicName.get(topicName);
              onPublish(
                  topicName, queue != null ? queue.size() : 0, false, e.getClass().getSimpleName());
              throw e;
            }
          });
    }

    @Override
    public void close() {
      synchronized (publisherByTopicName) {
        publisherByTopicName.remove(topicName);
      }
    }
  }

  /** Subscriber adds/removes itself in start/close and processes incoming messages. */
  private final class Subscriber<M extends Message> implements MessageSubscriber<M> {

    private final String topicName;
    private final String subscriptionName;
    private final MessageConsumer<M> messageConsumer;
    @Nullable private final Function<M, String> parallelizationKeyProvider;
    private final ConcurrentHashMap<String, AtomicBoolean> keysInProcessing =
        new ConcurrentHashMap<>();

    private Subscriber(
        String topicName,
        String subscriptionName,
        MessageConsumer<M> messageConsumer,
        MessageSubscriberOptions<M> options) {
      this.topicName = topicName;
      this.subscriptionName = subscriptionName;
      this.messageConsumer = messageConsumer;
      this.parallelizationKeyProvider = options.parallelizationKeyProvider().orElse(null);
    }

    /** Messages per subscriber are processed synchronously to guarantee the order. */
    private void processMessage(MessageContainer<?> messageContainer) {
      try {
        PubSubTracerDecorator.addSubscribeTracing(
            tracer,
            subscriptionName,
            Context.current(),
            (span) -> {
              try {
                //noinspection unchecked
                requestContextProvider
                    .get()
                    .run(() -> messageConsumer.process((MessageContainer<M>) messageContainer));

                // Notify listener.
                onMessageReceived(topicName, subscriptionName, true, null);
              } catch (Exception e) {
                span.recordException(e);

                // Notify listener.
                onMessageReceived(topicName, subscriptionName, false, e.getClass().getSimpleName());

                // All uncaught exceptions result in an ack, the message consumer must decide how
                // to handle exceptions otherwise.
                messageContainer.ack();
              } finally {
                // Release the permit.
                messagesInProcessingSemaphore.release();
              }
              return null;
            });
      } finally {
        clearIsProcessing(messageContainer);

        // Raise startProcessingEvent as the queue processing thread may be waiting for
        // this subscriber to finish processing before submitting more work.
        startProcessingEvent.set();
      }
    }

    private boolean isProcessing(MessageContainer<?> messageContainer) {
      String key = parallelizationKey(messageContainer);
      return keysInProcessing.computeIfAbsent(key, (ignoreKey) -> new AtomicBoolean(false)).get();
    }

    private void setIsProcessing(MessageContainer<?> messageContainer) {
      String key = parallelizationKey(messageContainer);
      keysInProcessing.get(key).set(true);
    }

    private void clearIsProcessing(MessageContainer<?> messageContainer) {
      String key = parallelizationKey(messageContainer);
      keysInProcessing.remove(key);
    }

    private String parallelizationKey(MessageContainer<?> messageContainer) {
      // By default, parallelize by subscription name.
      String key = subscriptionName;
      if (parallelizationKeyProvider != null) {
        // Otherwise, use custom key.
        key = parallelizationKeyProvider.apply((M) messageContainer.getMessage());
      }
      return key;
    }

    @Override
    public void start() {
      // Add subscription name to the map for listening.
      synchronized (subscriptionNamesByTopicName) {
        subscriptionNamesByTopicName.put(topicName, subscriptionName);
      }
    }

    @Override
    public void close() {
      // Remove this subscriber from listening.
      synchronized (subscriptionNamesByTopicName) {
        subscriptionNamesByTopicName.remove(topicName, subscriptionName);
      }

      // Remove subscriber.
      synchronized (subscribersBySubscriptionName) {
        subscribersBySubscriptionName.remove(subscriptionName);
      }
    }
  }

  /** Message container with ack/nack methods. */
  private final class MessageContainer<M extends Message> extends MessageContainerBase<M> {
    private final String topicName;
    private final String subscriptionName;
    private long remainingRedeliveryAttempts =
        optionManager.getOption(InProcessPubSubClientOptions.MAX_REDELIVERY_ATTEMPTS);
    private long redeliverTimeMillis;

    private MessageContainer(
        String topicName,
        String subscriptionName,
        String id,
        M message,
        RequestContext requestContext) {
      super(id, message, requestContext);
      this.topicName = topicName;
      this.subscriptionName = subscriptionName;
    }

    private String getTopicName() {
      return topicName;
    }

    private String getSubscriptionName() {
      return subscriptionName;
    }

    private void setRedeliverTimeMillis() {
      long millis = System.currentTimeMillis();
      long minMillis =
          millis
              + 1000
                  * optionManager.getOption(
                      InProcessPubSubClientOptions.MIN_DELAY_FOR_REDELIVERY_SECONDS);
      long maxMillis =
          millis
              + 1000
                  * optionManager.getOption(
                      InProcessPubSubClientOptions.MAX_DELAY_FOR_REDELIVERY_SECONDS);
      this.redeliverTimeMillis = (long) (minMillis + (maxMillis - minMillis) * random.nextDouble());
    }

    private long getRedeliverTimeMillis() {
      return redeliverTimeMillis;
    }

    @Override
    public CompletableFuture<MessageAckStatus> ack() {
      // Nothing to do, just return success.
      return CompletableFuture.completedFuture(MessageAckStatus.SUCCESSFUL);
    }

    @Override
    public CompletableFuture<MessageAckStatus> nack() {
      if (remainingRedeliveryAttempts-- > 0) {
        // Enqueue self for re-delivery.
        setRedeliverTimeMillis();
        try {
          redeliveryQueue.offer(this);
        } catch (InterruptedException e) {
          logger.error("Interrupted while adding to redelivery queue", e);
          return CompletableFuture.completedFuture(MessageAckStatus.OTHER);
        }
        return CompletableFuture.completedFuture(MessageAckStatus.SUCCESSFUL);
      } else {
        // This message will not be re-attempted.
        return CompletableFuture.completedFuture(MessageAckStatus.FAILED_PRECONDITION);
      }
    }

    @Override
    public CompletableFuture<MessageAckStatus> nackWithDelay(Duration redeliveryDelay) {
      throw new UnsupportedOperationException(
          "The nackWithDelay is not supported by the InProcessPubSubClient.");
    }

    @Override
    public String toString() {
      return String.format("%s, %s: %s", topicName, subscriptionName, getMessage());
    }
  }

  /** Bounded blocking priority queue. */
  private static final class BoundedBlockingPriorityQueue<E> {
    private final PriorityBlockingQueue<E> queue;
    private final Semaphore semaphore;

    public BoundedBlockingPriorityQueue(int maxSize, Comparator<E> comparator) {
      if (maxSize <= 0) {
        throw new IllegalArgumentException("maxSize should be greater than 0");
      }
      this.queue = new PriorityBlockingQueue<>(maxSize, comparator);
      this.semaphore = new Semaphore(maxSize);
    }

    public boolean offer(E e) throws InterruptedException {
      semaphore.acquire();
      boolean wasAdded = false;
      try {
        synchronized (queue) {
          wasAdded = queue.offer(e);
        }
        return wasAdded;
      } finally {
        if (!wasAdded) {
          semaphore.release();
        }
      }
    }

    public E take() throws InterruptedException {
      E item = queue.take();
      semaphore.release();
      return item;
    }

    public E peek() {
      return queue.peek();
    }

    public boolean isEmpty() {
      return queue.isEmpty();
    }
  }

  /**
   * Similar to C# AutoReset event, this class is a simple boolean event that resets after notifying
   * subscribers.
   */
  private static final class AutoResetEvent {
    private final Object monitor = new Object();
    private volatile boolean eventSet;

    public boolean waitEvent(long millis) throws InterruptedException {
      synchronized (monitor) {
        monitor.wait(millis);
        boolean wasSet = eventSet;
        eventSet = false;
        return wasSet;
      }
    }

    public void set() {
      synchronized (monitor) {
        eventSet = true;
        monitor.notify();
      }
    }
  }
}
