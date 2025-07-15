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
package com.dremio.service.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.common.util.TestTools;
import com.dremio.test.DremioTest;
import com.dremio.test.zookeeper.ZkTestServerRule;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

/**
 * Tests the session expiration (which includes re-mastering) aspects of the new implementation of
 * the ClusteredSingleton
 */
public class TestClusteredSingletonSessionExpiration extends DremioTest {
  // number of schedules per test.
  private static final int NUM_TEST_CLIENTS = 2;

  @Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(300, TimeUnit.SECONDS);

  @ClassRule
  public static final ZkTestServerRule zkServerResource = new ZkTestServerRule("/css/dremio");

  private final TestClient[] testClients = new TestClient[NUM_TEST_CLIENTS];

  @SuppressWarnings("resource")
  @Before
  public void setup() throws Exception {
    for (int i = 0; i < NUM_TEST_CLIENTS; i++) {
      testClients[i] = new TestClient(i, zkServerResource.getConnectionString(), true);
    }
  }

  @After
  public void tearDown() throws Exception {
    Arrays.stream(testClients).filter(Objects::nonNull).forEach(TestClient::close);
  }

  @Test
  public void testCleanupOnExpiration() throws Exception {
    final AtomicInteger cleanupCount = new AtomicInteger(0);
    Schedule testSchedule =
        Schedule.Builder.everyMillis(1500)
            .asClusteredSingleton(testName.getMethodName())
            .withCleanup(cleanupCount::incrementAndGet)
            .build();
    final CountDownLatch latch1 = new CountDownLatch(15);
    AtomicInteger incrementer = new AtomicInteger();
    Runnable task =
        () -> {
          incrementer.incrementAndGet();
          latch1.countDown();
        };
    testClients[0].getSingletonScheduler().schedule(testSchedule, task);
    Thread.yield();
    for (int i = 1; i < NUM_TEST_CLIENTS; i++) {
      testClients[i].getSingletonScheduler().schedule(testSchedule, task);
    }
    Thread.yield();
    // inject session expiration to client 0
    testClients[0].injectSessionExpiration();
    latch1.await();
    // Should be exactly 15, but allow some leeway for slow build machines with high CPU contention
    assertThat(incrementer.get()).isBetween(15, 16);
    assertThat(cleanupCount.get()).isEqualTo(1);
  }

  @Test
  public void testMultiClientExpiration() throws Exception {
    final AtomicInteger cleanupCount = new AtomicInteger(0);
    Schedule testSchedule =
        Schedule.Builder.everyMillis(1000)
            .asClusteredSingleton(testName.getMethodName())
            .withCleanup(cleanupCount::incrementAndGet)
            .build();
    final CountDownLatch latch1 = new CountDownLatch(10);
    final CountDownLatch latch2 = new CountDownLatch(30);
    AtomicInteger incrementer = new AtomicInteger();
    Runnable task =
        () -> {
          incrementer.incrementAndGet();
          latch1.countDown();
          latch2.countDown();
        };
    testClients[1].getSingletonScheduler().schedule(testSchedule, task);
    Thread.yield();
    testClients[0].getSingletonScheduler().schedule(testSchedule, task);
    // inject session expiration to client 0
    testClients[1].injectSessionExpiration();
    latch1.await();
    testClients[0].injectSessionExpiration();
    latch2.await();
    assertThat(incrementer.get()).isBetween(30, 32);
    // not guaranteed in this case that the schedule would have switched over. So cannot
    // guarantee a cleanup count of 2
    assertThat(cleanupCount.get()).isGreaterThanOrEqualTo(1);
  }

  private static final int NUM_TEST_SCHEDULES = 4;

  @Test
  public void testSimultaneousClientExpirationWithExtraClientAndMultipleSchedules()
      throws Exception {
    final AtomicInteger cleanupCount = new AtomicInteger(0);
    final Schedule[] testSchedules = new Schedule[NUM_TEST_SCHEDULES];
    final CountDownLatch[] latches = new CountDownLatch[NUM_TEST_SCHEDULES];
    final AtomicInteger[] incrementers = new AtomicInteger[NUM_TEST_SCHEDULES];
    final Runnable[] tasks = new Runnable[NUM_TEST_SCHEDULES];
    for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
      latches[i] = new CountDownLatch(40);
      incrementers[i] = new AtomicInteger(0);
      testSchedules[i] =
          Schedule.Builder.everyMillis(500)
              .asClusteredSingleton(testName.getMethodName() + i)
              .withCleanup(cleanupCount::incrementAndGet)
              .build();
      final int idx = i;
      tasks[i] =
          () -> {
            incrementers[idx].incrementAndGet();
            latches[idx].countDown();
          };
    }
    for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
      testClients[0].getSingletonScheduler().schedule(testSchedules[i], tasks[i]);
    }
    for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
      testClients[1].getSingletonScheduler().schedule(testSchedules[i], tasks[i]);
    }
    // inject session expiration to client 0 and client 1 simultaneously
    testClients[0].injectSessionExpiration();
    testClients[1].injectSessionExpiration();
    // bring up a 3rd client to check 3 simultaneous events; two session loss and 1 new session
    // starting
    // at the same time
    try (var extraClient =
        new TestClient(NUM_TEST_CLIENTS, zkServerResource.getConnectionString())) {
      for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
        extraClient.getSingletonScheduler().schedule(testSchedules[i], tasks[i]);
      }
      for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
        latches[i].await();
      }
      for (int i = 0; i < NUM_TEST_SCHEDULES; i++) {
        assertThat(cleanupCount.get()).isBetween(NUM_TEST_SCHEDULES, NUM_TEST_SCHEDULES * 2);
      }
    }
  }

  @Test
  public void testRemasteringAndCancelReUse() throws Exception {
    final AtomicInteger cleanupCount = new AtomicInteger(0);
    Schedule testSchedule =
        Schedule.Builder.everyMillis(1000)
            .asClusteredSingleton(testName.getMethodName())
            .withCleanup(cleanupCount::incrementAndGet)
            .build();
    final CountDownLatch latch1 = new CountDownLatch(1);
    final CountDownLatch latch2 = new CountDownLatch(20);
    AtomicInteger incrementer = new AtomicInteger();
    Runnable task =
        () -> {
          incrementer.incrementAndGet();
          latch1.countDown();
          latch2.countDown();
        };
    runTaskAndCancel(testSchedule, task, latch1, true);
    assertThat(incrementer.get()).isGreaterThanOrEqualTo(1);
    runTaskAndCancel(testSchedule, task, latch2, false);
    latch2.await();
    assertThat(incrementer.get()).isGreaterThanOrEqualTo(20);
  }

  private void runTaskAndCancel(
      Schedule testSchedule, Runnable task, CountDownLatch latch, boolean injectSessionLoss)
      throws Exception {
    final Cancellable task0 = testClients[0].getSingletonScheduler().schedule(testSchedule, task);
    Thread.yield();
    final Cancellable task1 = testClients[1].getSingletonScheduler().schedule(testSchedule, task);
    latch.await();
    task0.cancel(false);
    task1.cancel(false);
    if (injectSessionLoss) {
      testClients[1].injectSessionExpiration();
    }
  }
}
