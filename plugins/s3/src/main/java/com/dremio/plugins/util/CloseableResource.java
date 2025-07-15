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
package com.dremio.plugins.util;

import com.google.common.base.Preconditions;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wraps AutoCloseable implementation of non-closeable objects */
public class CloseableResource<T> implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(CloseableResource.class);
  private T resource;
  private Consumer<T> closerFunc;
  private int refCnt = 1;

  public CloseableResource(T resource, Consumer<T> closerFunc) {
    this.resource = resource;
    this.closerFunc = closerFunc;
  }

  public synchronized T getResource() {
    Preconditions.checkNotNull(resource);
    ++refCnt;
    logger.debug(
        "Class {} acquired the ref for {}:{}, Ref {}",
        getCallingClass(),
        resource.getClass().getSimpleName(),
        System.identityHashCode(resource),
        refCnt);
    return resource;
  }

  @Override
  public synchronized void close() throws Exception {
    if (refCnt == 0) {
      return;
    }

    if (--refCnt == 0 && resource != null) {
      logger.debug("Closing resource {}", resource.getClass().getSimpleName());
      closerFunc.accept(resource);
      resource = null;
    }

    if (resource != null) {
      logger.debug(
          "Class {} released the ref for {}:{}, Current ref count:{}",
          getCallingClass(),
          resource.getClass().getSimpleName(),
          System.identityHashCode(resource),
          refCnt);
    }
  }

  private String getCallingClass() {
    final StackTraceElement traceElement = Thread.currentThread().getStackTrace()[3];
    return traceElement.getClassName()
        + ":"
        + traceElement.getMethodName()
        + ":"
        + traceElement.getLineNumber();
  }
}
