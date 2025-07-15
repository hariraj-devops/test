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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

public class AsyncRetryerTest {

  @Test
  public void testRetryAsync_SuccessOnFirstTry() {
    // Mock the supplier to return a successful future
    Supplier<CompletableFuture<String>> supplier =
        () -> CompletableFuture.completedFuture("Success");

    CompletableFuture<String> result = AsyncRetryer.retryAsync(supplier, 3);

    // Verify the result
    assertEquals("Success", result.join());
  }

  @Test
  public void testRetryAsync_SuccessAfterRetries() {
    AtomicInteger attemptCounter = new AtomicInteger(0);

    // Mock the supplier to fail twice and then succeed
    Supplier<CompletableFuture<String>> supplier =
        () -> {
          if (attemptCounter.incrementAndGet() <= 2) {
            return CompletableFuture.failedFuture(new RuntimeException("Failure"));
          } else {
            return CompletableFuture.completedFuture("Success");
          }
        };

    CompletableFuture<String> result = AsyncRetryer.retryAsync(supplier, 3);

    // Verify the result
    assertEquals("Success", result.join());
    assertEquals(3, attemptCounter.get()); // Ensure it retried twice before succeeding
  }

  @Test
  public void testRetryAsync_AllRetriesFail() {
    AtomicInteger attemptCounter = new AtomicInteger(0);

    // Mock the supplier to always fail
    Supplier<CompletableFuture<String>> supplier =
        () -> {
          attemptCounter.incrementAndGet();
          return CompletableFuture.failedFuture(new RuntimeException("Failure"));
        };

    CompletableFuture<String> result = AsyncRetryer.retryAsync(supplier, 3);

    // Verify that the retries exhausted and an exception is thrown
    assertThrows(RuntimeException.class, result::join);
    assertEquals(4, attemptCounter.get()); // Ensure it tried 3 retries + 1 initial attempt
  }

  @Test
  public void testRetryAsync_NoRetriesOnSuccess() {
    AtomicInteger callCounter = new AtomicInteger(0);

    // Create a supplier that increments the counter and returns a successful future
    Supplier<CompletableFuture<String>> supplier =
        () -> {
          callCounter.incrementAndGet();
          return CompletableFuture.completedFuture("Success");
        };

    CompletableFuture<String> result = AsyncRetryer.retryAsync(supplier, 3);

    // Verify the result
    assertEquals("Success", result.join());

    // Verify the supplier was called only once
    assertEquals(1, callCounter.get());
  }

  @Test
  public void testRetryAsync_NoRetriesAllowed() {
    AtomicInteger attemptCounter = new AtomicInteger(0);

    // Mock the supplier to always fail
    Supplier<CompletableFuture<String>> supplier =
        () -> {
          attemptCounter.incrementAndGet();
          return CompletableFuture.failedFuture(new RuntimeException("Failure"));
        };

    CompletableFuture<String> result = AsyncRetryer.retryAsync(supplier, 0);

    // Verify that no retries happen
    assertThrows(RuntimeException.class, result::join);
    assertEquals(1, attemptCounter.get()); // Ensure it only tried once
  }
}
