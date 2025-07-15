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

import com.dremio.services.pubsub.MessagePublisher;
import com.dremio.services.pubsub.nats.exceptions.NatsPublisherException;
import com.dremio.services.pubsub.nats.utils.AsyncRetryer;
import com.google.protobuf.Message;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import java.util.concurrent.CompletableFuture;

public class NatsPublisher<M extends Message> implements MessagePublisher<M> {
  private static final int PUBLISHER_MAX_RETRIES = 2;

  // a dedicated connection for this publisher
  private Connection natsConnection;
  private final String subjectName;
  private final String natsServerUrl;
  private JetStream jetStream;

  public NatsPublisher(String subjectName, String natsServerUrl) {
    this.subjectName = subjectName;
    this.natsServerUrl = natsServerUrl;
  }

  public void connect() {
    try {

      // TODO(DX-94549): add metrics and tracing
      this.natsConnection = Nats.connect(getOptions(natsServerUrl));
      this.jetStream = natsConnection.jetStream();
    } catch (Exception e) {
      throw new NatsPublisherException("Problem when connecting to NATS", e);
    }
  }

  @Override
  public CompletableFuture<String> publish(M message) {
    return AsyncRetryer.retryAsync(
        () ->
            jetStream
                .publishAsync(subjectName, message.toByteArray())
                // TODO(DX-94554): Make the ID returned by the publisher unique
                .thenApply(v -> Long.toString(v.getSeqno())),
        PUBLISHER_MAX_RETRIES);
  }

  @Override
  public void close() {
    try {
      natsConnection.close();
    } catch (InterruptedException e) {
      throw new NatsPublisherException("Problem when closing NATS connection", e);
    }
  }
}
