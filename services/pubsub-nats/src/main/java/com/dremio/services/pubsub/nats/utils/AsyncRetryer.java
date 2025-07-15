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
package com.dremio.services.pubsub.nats.utils;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class AsyncRetryer {

  /**
   * Retry utility for asynchronous operations.
   *
   * @param supplier The asynchronous operation to perform.
   * @param maxRetries Maximum number of retries.
   * @return A CompletableFuture of the operation result.
   */
  public static <T> CompletableFuture<T> retryAsync(
      Supplier<CompletableFuture<T>> supplier, int maxRetries) {
    return supplier
        .get()
        .handle(
            (result, ex) -> {
              if (ex == null) {
                // If there is no exception, return a successful CompletableFuture
                return CompletableFuture.completedFuture(result);
              } else if (maxRetries <= 0) {
                // If retries are exhausted, propagate the exception
                return CompletableFuture.<T>failedFuture(ex);
              } else {
                // Retry with decremented retries
                return retryAsync(supplier, maxRetries - 1);
              }
            })
        .thenCompose(Function.identity()); // Flatten the nested CompletableFuture
  }
}
