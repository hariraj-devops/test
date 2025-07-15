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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class helps in maintaining multiple references for a closeable object. The actual close
 * happens only when all refs have released.
 *
 * @param <T>
 */
public class CloseableRef<T extends AutoCloseable> implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(CloseableRef.class);
  private int refCnt;
  private T obj;

  public CloseableRef(T obj) {
    logger.debug(
        "Class {} acquired a new ref for {}:{}",
        getCallingClass(),
        obj.getClass().getSimpleName(),
        System.identityHashCode(obj));

    this.obj = obj;
    refCnt = 1;
  }

  public synchronized T acquireRef() {
    Preconditions.checkNotNull(obj);
    logger.debug(
        "Class {} acquired the ref for {}:{}",
        getCallingClass(),
        obj.getClass().getSimpleName(),
        System.identityHashCode(obj));

    ++refCnt;
    return obj;
  }

  @Override
  public synchronized void close() throws Exception {
    if (refCnt == 0) {
      return;
    }

    if (--refCnt == 0 && obj != null) {
      logger.debug("Closing ref {}", obj.getClass().getSimpleName());
      obj.close();
      obj = null;
    }

    if (obj != null) {
      logger.debug(
          "Class {} released the ref for {}:{}, Current ref count:{}",
          getCallingClass(),
          obj.getClass().getSimpleName(),
          System.identityHashCode(obj),
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
