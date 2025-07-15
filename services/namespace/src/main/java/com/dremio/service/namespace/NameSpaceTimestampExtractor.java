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
package com.dremio.service.namespace;

import com.dremio.service.namespace.proto.NameSpaceContainer;

/** timestamp extractor for namespace container. */
final class NameSpaceTimestampExtractor {
  public static Long getCreatedAt(NameSpaceContainer value) {
    if (value == null) {
      return null;
    }

    switch (value.getType()) {
      case DATASET:
        return value.getDataset().getCreatedAt();
      case FOLDER:
        return null;
      case HOME:
        return value.getHome().getCtime();
      case SOURCE:
        return value.getSource().getCtime();
      case SPACE:
        return value.getSpace().getCtime();
      case FUNCTION:
        return value.getFunction().getCreatedAt();
      default:
        throw new UnsupportedOperationException("Unknown type: " + value.getType());
    }
  }

  public static void setCreatedAt(NameSpaceContainer value, Long createdAt) {
    if (value == null) {
      return;
    }

    switch (value.getType()) {
      case DATASET:
        value.getDataset().setCreatedAt(createdAt);
        break;
      case FOLDER:
        break;
      case HOME:
        value.getHome().setCtime(createdAt);
        break;
      case SOURCE:
        value.getSource().setCtime(createdAt);
        break;
      case SPACE:
        value.getSpace().setCtime(createdAt);
        break;
      case FUNCTION:
        value.getFunction().setCreatedAt(createdAt);
        break;
      default:
        throw new UnsupportedOperationException("Unknown type: " + value.getType());
    }
  }

  public static Long getLastModifiedAt(NameSpaceContainer value) {
    if (value == null) {
      return null;
    }

    switch (value.getType()) {
      case DATASET:
        return value.getDataset().getLastModified();
      case SOURCE:
        return value.getSource().getLastModifiedAt();
      case FUNCTION:
        return value.getFunction().getLastModified();
      case FOLDER:
      case HOME:
      case SPACE:
        return null;
      default:
        throw new UnsupportedOperationException("Unknown type: " + value.getType());
    }
  }

  public static void setLastModifiedAt(NameSpaceContainer value, Long lastModifiedAt) {
    if (value == null) {
      return;
    }

    switch (value.getType()) {
      case DATASET:
        value.getDataset().setLastModified(lastModifiedAt);
        break;
      case SOURCE:
        value.getSource().setLastModifiedAt(lastModifiedAt);
        break;
      case FUNCTION:
        value.getFunction().setLastModified(lastModifiedAt);
        break;
      case FOLDER:
      case HOME:
      case SPACE:
        break;
      default:
        throw new UnsupportedOperationException("Unknown type: " + value.getType());
    }
  }
}
