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

import com.dremio.context.RequestContext;
import com.google.common.base.Preconditions;
import com.google.protobuf.Message;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Message contents with id. The implementations must add ack/nack logic. The clients use ack(),
 * nack() after processing on their side is complete. The clients may choose to accumulate messages
 * in batches and process them in bulk. The batch logic may be affected by the underlying
 * implementation of acknowledgements.
 */
public abstract class MessageContainerBase<M extends Message> {
  private final M message;
  private final String id;
  private final RequestContext requestContext;

  protected MessageContainerBase(String id, M message, RequestContext requestContext) {
    this.id = Preconditions.checkNotNull(id);
    this.message = Preconditions.checkNotNull(message);
    this.requestContext = Preconditions.checkNotNull(requestContext);
  }

  public M getMessage() {
    return message;
  }

  public String getId() {
    return id;
  }

  public RequestContext getRequestContext() {
    return requestContext;
  }

  /**
   * Acknowledges the message. The implementations may batch acks/nacks for batch delivery to the
   * server. Should a guarantee of ack/nack delivery be required, use the returned future.
   */
  public abstract CompletableFuture<MessageAckStatus> ack();

  /** Acknowledges the message for immediate re-delivery. */
  public abstract CompletableFuture<MessageAckStatus> nack();

  /** Acknowledges the message for re-delivery with the specific delay. */
  public abstract CompletableFuture<MessageAckStatus> nackWithDelay(Duration redeliveryDelay);
}
