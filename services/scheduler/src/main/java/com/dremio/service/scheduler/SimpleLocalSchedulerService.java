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

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.CloseableSchedulerThreadPool;
import com.google.common.annotations.VisibleForTesting;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.jetbrains.annotations.NotNull;

/**
 * Simple implementation of {@link SchedulerService} that only accepts tasks that needs to be
 * scheduled locally.
 */
public class SimpleLocalSchedulerService implements SchedulerService {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(SimpleLocalSchedulerService.class);
  private static final String THREAD_NAME_PREFIX = "scheduler-";
  private final CloseableSchedulerThreadPool executorService;
  private final AtomicInteger idCounter;
  private final Provider<Clock> clockProvider;

  public SimpleLocalSchedulerService(int corePoolSize) {
    this(corePoolSize, Clock::systemUTC);
  }

  @VisibleForTesting
  public SimpleLocalSchedulerService(int corePoolSize, Provider<Clock> clockProvider) {
    executorService = new CloseableSchedulerThreadPool(THREAD_NAME_PREFIX, corePoolSize);
    idCounter = new AtomicInteger(0);
    this.clockProvider = clockProvider;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(executorService);
    LOGGER.info("Stopped Simple Local SchedulerService");
  }

  @Override
  public void start() throws Exception {
    LOGGER.info("Simple Local SchedulerService is up");
  }

  private class CancellableTask implements Cancellable, Runnable {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Iterator<Instant>> instantsRef;
    private final AtomicReference<ScheduledFuture<?>> currentTask;
    private final int taskId;
    private final Runnable task;
    private volatile boolean taskDone;
    private Schedule currentSchedule;
    private Instant lastRun = Instant.MIN;

    public CancellableTask(Schedule schedule, Runnable task, int taskId) {
      this.instantsRef = new AtomicReference<>(schedule.iterator());
      this.currentSchedule = schedule;
      this.task = task;
      this.taskDone = false;
      this.currentTask = new AtomicReference<>(null);
      this.taskId = taskId;
    }

    @Override
    public String toString() {
      return "Simple" + taskId;
    }

    @Override
    public void run() {
      if (cancelled.get()) {
        LOGGER.info("Task `{}` already cancelled", this);
        return;
      }

      try {
        task.run();
      } catch (Exception e) {
        LOGGER.warn("Task `{}` execution failed", this, e);
      }

      lastRun = instantNow();
      scheduleNext();
    }

    private synchronized void scheduleNext() {
      if (cancelled.get()) {
        LOGGER.info("Task `{}` was cancelled. Will not be re-scheduled", this);
        return;
      }

      Instant instant = checkAndAdjustForScheduleModifications();
      // if instant == null - it is the end of the scheduling
      if (instant == null) {
        LOGGER.debug("This is the end of the task {}", this);
        taskDone = true;
        currentTask.set(getDefaultFuture());
      } else {
        long delay = ChronoUnit.MILLIS.between(instantNow(), instant);
        LOGGER.debug("Task {} is scheduled to run in {} milliseconds", this, delay);
        ScheduledFuture<?> future = executorService.schedule(this, delay, TimeUnit.MILLISECONDS);
        currentTask.set(future);
      }
    }

    private ScheduledFuture<Object> getDefaultFuture() {
      return new ScheduledFuture<Object>() {

        @Override
        public int compareTo(@NotNull Delayed o) {
          return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          return false;
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public boolean isDone() {
          return true;
        }

        @Override
        public Object get() {
          return null;
        }

        @Override
        public Object get(long timeout, @NotNull TimeUnit unit) {
          return null;
        }

        @Override
        public long getDelay(@NotNull TimeUnit unit) {
          return 0;
        }
      };
    }

    private Instant nextInstant() {
      Instant result = null;
      final Iterator<Instant> instants = instantsRef.get();
      while (instants.hasNext()) {
        result = instants.next();
        if (!result.isBefore(lastRun)) {
          break;
        }
      }
      return result;
    }

    private Instant checkAndAdjustForScheduleModifications() {
      if (!lastRun.equals(Instant.MIN)) {
        final Schedule newSchedule = currentSchedule.getScheduleModifier().apply(currentSchedule);
        if (newSchedule != null) {
          currentSchedule = newSchedule;
          instantsRef.set(newSchedule.iterator());
        }
      }
      return nextInstant();
    }

    private Instant instantNow() {
      return Instant.now(clockProvider.get());
    }

    @Override
    public void cancel(boolean mayInterruptIfRunning) {
      if (cancelled.getAndSet(true)) {
        // Already cancelled
        return;
      }

      LOGGER.info("Cancelling task {}", this);
      ScheduledFuture<?> future = currentTask.getAndSet(null);
      if (future != null) {
        future.cancel(mayInterruptIfRunning);
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return taskDone;
    }
  }

  @Override
  public Cancellable schedule(Schedule schedule, Runnable task) {
    CancellableTask cancellableTask =
        new CancellableTask(schedule, task, idCounter.incrementAndGet());
    cancellableTask.scheduleNext();

    return cancellableTask;
  }
}
