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
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.store.ReflectionChangeNotificationHandler;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes reflection change events to pubsub. */
public class PubSubReflectionChangeNotificationHandler
    implements ReflectionChangeNotificationHandler {
  private static final Logger logger =
      LoggerFactory.getLogger(PubSubReflectionChangeNotificationHandler.class);

  private final ReflectionEventPublisherProvider eventPublisherProvider;

  public PubSubReflectionChangeNotificationHandler(
      ReflectionEventPublisherProvider eventPublisherProvider) {
    this.eventPublisherProvider = eventPublisherProvider;
  }

  @Override
  public void onChange(ReflectionGoal reflection) {
    try {
      eventPublisherProvider.get().publish(convert(reflection)).get();
    } catch (InterruptedException | ExecutionException e) {
      // Swallow the exception not to interrupt any reflection logic.
      logger.error("Failed to publish reflection event for: {}", reflection, e);
    }
  }

  private static ReflectionEventProto.ReflectionEventMessage convert(ReflectionGoal reflection) {
    return ReflectionEventProto.ReflectionEventMessage.newBuilder()
        .setId(reflection.getId().getId())
        .setDatasetId(reflection.getDatasetId())
        .setName(reflection.getName())
        .setState(convertState(reflection.getState()))
        .build();
  }

  private static ReflectionEventProto.ReflectionEventMessage.State convertState(
      ReflectionGoalState state) {
    switch (state) {
      case DELETED:
        return ReflectionEventProto.ReflectionEventMessage.State.STATE_DELETED;
      case ENABLED:
        return ReflectionEventProto.ReflectionEventMessage.State.STATE_ENABLED;
      case DISABLED:
        return ReflectionEventProto.ReflectionEventMessage.State.STATE_DISABLED;
      default:
        throw new IllegalArgumentException(String.format("Unexpected state: %s", state));
    }
  }
}
