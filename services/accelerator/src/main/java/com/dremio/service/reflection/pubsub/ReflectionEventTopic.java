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
import com.dremio.services.pubsub.Topic;

/** Topic for publishing reflection change events to pubsub. */
public class ReflectionEventTopic implements Topic<ReflectionEventProto.ReflectionEventMessage> {
  @Override
  public String getName() {
    return "reflection-event";
  }

  @Override
  public Class<ReflectionEventProto.ReflectionEventMessage> getMessageClass() {
    return ReflectionEventProto.ReflectionEventMessage.class;
  }
}
