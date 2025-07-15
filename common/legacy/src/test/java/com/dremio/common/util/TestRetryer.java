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

package com.dremio.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** Tests for Retryer */
public class TestRetryer {
  private static final int MAX_RETRIES = 4;

  private ListAppender<ILoggingEvent> appender;

  @SuppressWarnings("Slf4jIllegalPassedClass") // intentionally using logger from another class
  private final ch.qos.logback.classic.Logger retryerLogger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Retryer.class);

  @Before
  public void beforeAll() {
    appender = new ListAppender<>();
    appender.start();
    retryerLogger.addAppender(appender);
  }

  @After
  public void afterAll() {
    retryerLogger.detachAppender(appender);
  }

  @Test
  public void testMaxRetries() {
    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, 1, 1)
            .retryIfExceptionOfType(RuntimeException.class)
            .setMaxRetries(MAX_RETRIES)
            .build();

    // Succeed only in last attempt. Throw exceptions before that.
    AtomicInteger counter = new AtomicInteger(0);
    boolean result =
        retryer.call(
            () -> {
              if (counter.incrementAndGet() < MAX_RETRIES) {
                throw new RuntimeException("Failure");
              }
              // Retry triggered even after success
              return counter.get() == MAX_RETRIES;
            });
    assertTrue("Retry happened even without exception", result);
  }

  @Test
  public void testNoRetryAfterSuccess() {
    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, 1, 1)
            .retryIfExceptionOfType(RuntimeException.class)
            .setMaxRetries(MAX_RETRIES)
            .build();

    final int succeedAfter = MAX_RETRIES / 2;

    // Succeed only in mid attempt. Throw exceptions before that.
    AtomicInteger counter = new AtomicInteger(0);
    boolean result =
        retryer.call(
            () -> {
              if (counter.incrementAndGet() < succeedAfter) {
                throw new RuntimeException("Failure");
              }
              // Retry triggered even after success
              return counter.get() == succeedAfter;
            });
    assertTrue("Retry happened even without exception", result);
    assertEquals(counter.get(), succeedAfter);
  }

  @Test
  public void testFlatWaitStrategy() {
    final int expectedWait = 100;

    Retryer retryer =
        spy(
            Retryer.newBuilder()
                .setWaitStrategy(Retryer.WaitStrategy.FLAT, expectedWait, expectedWait)
                .retryIfExceptionOfType(RuntimeException.class)
                .setMaxRetries(MAX_RETRIES)
                .build());
    ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);

    try {
      retryer.call(
          () -> {
            throw new RuntimeException("Failure");
          });
    } catch (RuntimeException e) {
      // Nothing to do
    }
    verify(retryer, times(MAX_RETRIES - 1)).sleep(captor.capture());
    captor.getAllValues().forEach(v -> assertTrue(100L == v));
  }

  @Test(expected = RuntimeException.class)
  public void testRetryIfException() {
    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, 1, 1)
            .retryIfExceptionOfType(IOException.class)
            .retryIfExceptionOfType(SQLException.class)
            .setMaxRetries(MAX_RETRIES)
            .build();

    // Throw IOException and SQLException first. They should fall under retry. Other exceptions
    // shouldn't
    AtomicInteger counter = new AtomicInteger(0);
    boolean result =
        retryer.call(
            () -> {
              if (counter.incrementAndGet() < (MAX_RETRIES - 2)) {
                throw new IOException("Should retry");
              } else if (counter.get() == (MAX_RETRIES - 2)) {
                throw new SQLException("Should retry");
              } else if (counter.get() == (MAX_RETRIES - 1)) {
                throw new RuntimeException("Should fail");
              } else {
                // Retry triggered even after failure
                return false;
              }
            });

    // fail if didn't come out of call() with runtime exception.
    assertTrue(result);
  }

  @Test(expected = RuntimeException.class)
  public void testRetryIfExceptionFunc() {
    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, 1, 1)
            .retryOnExceptionFunc(ex -> ex instanceof IOException || ex instanceof SQLException)
            .setMaxRetries(MAX_RETRIES)
            .build();

    // Throw IOException and SQLException first. They should fall under retry. Other exceptions
    // shouldn't
    AtomicInteger counter = new AtomicInteger(0);
    boolean result =
        retryer.call(
            () -> {
              if (counter.incrementAndGet() < (MAX_RETRIES - 2)) {
                throw new IOException("Should retry");
              } else if (counter.get() == (MAX_RETRIES - 2)) {
                throw new SQLException("Should retry");
              } else if (counter.get() == (MAX_RETRIES - 1)) {
                throw new RuntimeException("Should fail");
              } else {
                // Retry triggered even after failure
                return false;
              }
            });

    // fail if didn't come out of call() with runtime exception.
    assertTrue(result);
  }

  @Test
  public void testRetryWithInfiniteRetriesActivated() throws InterruptedException {
    int maxRetries = 3;
    AtomicBoolean keepFailing = new AtomicBoolean(true);
    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, 1, 1)
            .retryOnExceptionFunc(ex -> keepFailing.get())
            .setMaxRetries(maxRetries)
            .setInfiniteRetries(true)
            .build();

    AtomicInteger counter = new AtomicInteger(0);
    CompletableFuture.runAsync(
        () -> {
          try {
            retryer.run(
                () -> {
                  counter.incrementAndGet();
                  throw new RuntimeException("Failure");
                });
          } catch (RuntimeException e) {
            // do nothing
          }
        });

    Thread.sleep(1000);
    keepFailing.set(false);

    // retry will not throw exception because infinite was set, only condition is reached, counter
    // should be greater than the max retries passed in params
    assertTrue(counter.get() > maxRetries);
  }

  @Test
  public void testRetryMentionsCaller() {
    final int expectedWait = 100;

    Retryer retryer =
        Retryer.newBuilder()
            .setWaitStrategy(Retryer.WaitStrategy.FLAT, expectedWait, expectedWait)
            .retryIfExceptionOfType(RuntimeException.class)
            .setMaxRetries(MAX_RETRIES)
            .build();

    try {
      retryer.call(
          () -> {
            throw new RuntimeException("Failure");
          });
    } catch (RuntimeException e) {
      // Nothing to do
    }

    assertThat(appender.list)
        .allSatisfy(
            iLoggingEvent ->
                assertThat(iLoggingEvent.getFormattedMessage())
                    .contains("TestRetryer:testRetryMentionsCaller"));
  }
}
