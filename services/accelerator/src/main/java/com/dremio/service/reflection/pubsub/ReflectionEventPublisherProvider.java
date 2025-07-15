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
package com.dremio.service.reflection.pubsub;

import com.dremio.service.reflection.proto.ReflectionEventProto;
import com.dremio.services.pubsub.MessagePublisher;
import java.util.concurrent.CompletableFuture;

/** {@link MessagePublisher} provider for reflection events. */
public interface ReflectionEventPublisherProvider {
  /** No-op publisher provider. */
  ReflectionEventPublisherProvider NO_OP =
      () ->
          new MessagePublisher<>() {
            @Override
            public CompletableFuture<String> publish(
                ReflectionEventProto.ReflectionEventMessage message) {
              return CompletableFuture.completedFuture("Publishing disabled.");
            }

            @Override
            public void close() {}
          };

  MessagePublisher<ReflectionEventProto.ReflectionEventMessage> get();
}
