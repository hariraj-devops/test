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
package com.dremio.common;

import com.dremio.context.RequestContext;
import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Provider;

/**
 * Handles wakeup events for the various managers. Ensures only a single instance of the manager is
 * running and that no wakeup event is lost.
 *
 * <p>Only use WakeupHandler if you need to trigger a wakeup event while the manager is already
 * running ,and you need the manager to run again because of the wakeup event. Otherwise, if you
 * need to run some code once or on a schedule, create a Schedule for the SchedulerService. Be
 * careful with using the SchedulerService and WakeupHandler together because two threads will be
 * needed to run the manager code.
 *
 * <p>An example use case for the WakeupHandler is reflection management where the manager needs to
 * run every 10 seconds. If a user manually creates or edits a reflection, we need the manager to
 * run immediately or again after the current run to not miss the user's action.
 */
public class WakeupHandler {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(WakeupHandler.class);

  private final AtomicBoolean wakeup = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean();

  private final Runnable manager;
  private final ExecutorService executor;
  private final Provider<RequestContext> requestContextProvider;

  public WakeupHandler(ExecutorService executor, Runnable manager) {
    this(executor, manager, null);
  }

  public WakeupHandler(
      ExecutorService executor, Runnable manager, Provider<RequestContext> requestContextProvider) {
    this.executor = Preconditions.checkNotNull(executor, "executor service required");
    this.manager = Preconditions.checkNotNull(manager, "runnable manager required");
    this.requestContextProvider = requestContextProvider;
  }

  public Future<?> handle(String reason) {
    logger.trace("waking up manager, reason: {}", reason);
    if (!wakeup.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }
    // following check if not necessary. It helps not submitting a thread if the manager is already
    // running
    if (running.get()) {
      return CompletableFuture.completedFuture(null);
    }

    return executor.submit(
        new Runnable() {

          @Override
          public void run() {
            while (wakeup.get()) {
              if (!running.compareAndSet(false, true)) {
                return; // another thread is already running the manager
              }

              try {
                wakeup.set(false);
                if (requestContextProvider != null) {
                  requestContextProvider.get().run(() -> manager.run());
                } else {
                  manager.run();
                }
              } catch (Exception e) {
                logger.error("manager failed to run, reason: {}", reason, e);
              } finally {
                running.set(false);
              }
            }
            // thread can only exit if both wakeup and running are set to false. This ensures we
            // never miss a wakeup event
          }
        });
  }
}
