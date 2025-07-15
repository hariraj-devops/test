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
package com.dremio.service.namespace.catalogstatusevents;

public class CatalogStatusEventTopic {
  private final String topicName;

  public CatalogStatusEventTopic(String topicName) {
    this.topicName = topicName;
  }

  @Override
  public final int hashCode() {
    return topicName.hashCode();
  }

  @Override
  public final boolean equals(final Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof CatalogStatusEventTopic)) {
      return false;
    }
    CatalogStatusEventTopic otherTopic = (CatalogStatusEventTopic) other;
    return otherTopic.topicName.equals(topicName);
  }
}
