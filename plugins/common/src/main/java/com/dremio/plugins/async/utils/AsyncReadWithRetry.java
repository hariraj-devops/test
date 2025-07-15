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
package com.dremio.plugins.async.utils;

import com.dremio.http.BufferBasedCompletionHandler;
import com.dremio.io.ExponentialBackoff;
import java.io.FileNotFoundException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.apache.hadoop.fs.Path;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class that does read with retry */
public class AsyncReadWithRetry {
  private static final Logger logger = LoggerFactory.getLogger(AsyncReadWithRetry.class);
  private static final int MAX_RETRIES = 10;

  private final Function<Throwable, AsyncReadWithRetry.Error> efforFunction;

  public AsyncReadWithRetry(Function<Throwable, AsyncReadWithRetry.Error> errorFunction) {
    this.efforFunction = errorFunction;
  }

  public enum Error {
    PRECONDITION_NOT_MET,
    PATH_NOT_FOUND,
    UNKNOWN
  }

  public CompletableFuture<Void> read(
      AsyncHttpClient asyncHttpClient,
      Function<Void, Request> requestBuilderFunction,
      MetricsLogger metrics,
      Path path,
      String threadName,
      BufferBasedCompletionHandler responseHandler,
      int retryAttemptNum,
      ExponentialBackoff backoff) {

    metrics.startTimer("total");

    if (asyncHttpClient.isClosed()) {
      throw new IllegalStateException("AsyncHttpClient is closed");
    }

    Request req = requestBuilderFunction.apply(null);

    metrics.startTimer("request");
    return asyncHttpClient
        .executeRequest(req, responseHandler)
        .toCompletableFuture()
        .whenComplete(
            (response, throwable) -> {
              metrics.endTimer("request");
              if (throwable == null) {
                metrics.incrementCounter("success");
              }
            })
        .thenAccept(
            response -> {
              metrics.endTimer("total");
              metrics.logAllMetrics();
            }) // Discard the response, which has already been handled.
        .thenApply(CompletableFuture::completedFuture)
        .exceptionally(
            throwable -> {
              metrics.incrementCounter("error");
              logger.error("[{}] Error while executing request", threadName, throwable);

              final CompletableFuture<Void> errorFuture = new CompletableFuture<>();
              Error error = efforFunction.apply(throwable);
              if (error == Error.PRECONDITION_NOT_MET) {
                errorFuture.completeExceptionally(
                    new FileNotFoundException("Version of file has changed " + path));
                return errorFuture;
              } else if (error == Error.PATH_NOT_FOUND) {
                errorFuture.completeExceptionally(
                    new FileNotFoundException(
                        "File " + path + " not found: " + throwable.getMessage()));
                return errorFuture;
              } else if (retryAttemptNum > MAX_RETRIES) {
                metrics.incrementCounter("retry" + retryAttemptNum);
                logger.error(
                    "[{}] Error while reading {}. Operation failing beyond retries.",
                    threadName,
                    path);
                errorFuture.completeExceptionally(throwable);
                return errorFuture;
              }
              metrics.startTimer("backoffwait-" + retryAttemptNum);
              backoff.backoffWait(retryAttemptNum);

              metrics.endTimer("backoffwait-" + retryAttemptNum);

              metrics.endTimer("total");
              metrics.logAllMetrics();
              responseHandler.reset();
              return read(
                  asyncHttpClient,
                  requestBuilderFunction,
                  metrics,
                  path,
                  threadName,
                  responseHandler,
                  retryAttemptNum + 1,
                  backoff);
            })
        .thenCompose(Function.identity());
  }
}
