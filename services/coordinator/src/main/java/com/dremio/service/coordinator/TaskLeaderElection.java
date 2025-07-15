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
package com.dremio.service.coordinator;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.telemetry.api.metrics.CounterWithOutcome;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.TimeGauge;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.inject.Provider;

/**
 * Task Leader election service - allows to elect leader among nodes that handle a particular
 * service
 */
public class TaskLeaderElection implements AutoCloseable {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(TaskLeaderElection.class);

  private static final AtomicLong LAST_LEADER_ELECTION = new AtomicLong();

  static {
    TimeGauge.builder(
            "task_leader_election.last_elected_time",
            LAST_LEADER_ELECTION::get,
            TimeUnit.MILLISECONDS)
        .description("Timestamp of the last successful leader election")
        .register(Metrics.globalRegistry);
  }

  private static final CounterWithOutcome LEADERSHIP_ELECTION_COUNTER =
      CounterWithOutcome.of("task_leader_election");

  private static final long LEADER_UNAVAILABLE_DURATION_SECS = 600; // 10 minutes

  private final Provider<ClusterServiceSetManager> clusterServiceSetManagerProvider;
  private final Provider<ClusterElectionManager> clusterElectionManagerProvider;
  private final TaskLeaderStatusListener taskLeaderStatusListener;
  private final String serviceName;
  private final AtomicReference<Long> leaseExpirationTime = new AtomicReference<>(null);
  private final ScheduledExecutorService executorService;
  private final Provider<CoordinationProtos.NodeEndpoint> currentEndPoint;
  private final long failSafeLeaderUnavailableDuration;

  private volatile ElectionRegistrationHandle electionHandle;
  private final AtomicBoolean isTaskLeader = new AtomicBoolean(false);
  private ServiceSet serviceSet;
  private volatile RegistrationHandle nodeEndpointRegistrationHandle;
  private Future leadershipReleaseFuture;
  private ConcurrentMap<TaskLeaderChangeListener, TaskLeaderChangeListener> listeners =
      new ConcurrentHashMap<>();
  private volatile boolean electionHandleClosed = false;
  private final Function<ElectionListener, ElectionListener> electionListenerProvider;
  private FailSafeReElectionTask failSafeReElectionTask;
  private ScheduledThreadPoolExecutor reElectionExecutor;

  /**
   * If we don't use relinquishing leadership - don't need executor
   *
   * @param serviceName
   * @param clusterServiceSetManagerProvider
   * @param currentEndPoint
   */
  public TaskLeaderElection(
      String serviceName,
      Provider<ClusterServiceSetManager> clusterServiceSetManagerProvider,
      Provider<ClusterElectionManager> clusterElectionManagerProvider,
      Provider<CoordinationProtos.NodeEndpoint> currentEndPoint) {
    this(
        serviceName,
        clusterServiceSetManagerProvider,
        clusterElectionManagerProvider,
        null,
        currentEndPoint,
        null);
  }

  public TaskLeaderElection(
      String serviceName,
      Provider<ClusterServiceSetManager> clusterServiceSetManagerProvider,
      Provider<ClusterElectionManager> clusterElectionManagerProvider,
      Long leaseExpirationTime,
      Provider<CoordinationProtos.NodeEndpoint> currentEndPoint,
      ScheduledExecutorService executorService) {
    this(
        serviceName,
        clusterServiceSetManagerProvider,
        clusterElectionManagerProvider,
        leaseExpirationTime,
        currentEndPoint,
        executorService,
        LEADER_UNAVAILABLE_DURATION_SECS,
        Function.identity());
  }

  public TaskLeaderElection(
      String serviceName,
      Provider<ClusterServiceSetManager> clusterServiceSetManagerProvider,
      Provider<ClusterElectionManager> clusterElectionManagerProvider,
      Long leaseExpirationTime,
      Provider<CoordinationProtos.NodeEndpoint> currentEndPoint,
      ScheduledExecutorService executorService,
      Long failSafeLeaderUnavailableDuration,
      Function<ElectionListener, ElectionListener> electionListenerProvider) {
    this.serviceName = serviceName;
    this.clusterServiceSetManagerProvider = clusterServiceSetManagerProvider;
    this.clusterElectionManagerProvider = clusterElectionManagerProvider;
    this.leaseExpirationTime.set(leaseExpirationTime);
    this.currentEndPoint = currentEndPoint;
    this.taskLeaderStatusListener =
        new TaskLeaderStatusListener(serviceName, clusterServiceSetManagerProvider);
    this.failSafeLeaderUnavailableDuration =
        failSafeLeaderUnavailableDuration != null
            ? failSafeLeaderUnavailableDuration
            : LEADER_UNAVAILABLE_DURATION_SECS;
    this.electionListenerProvider = electionListenerProvider;

    if (executorService == null) {
      reElectionExecutor =
          new ScheduledThreadPoolExecutor(
              1,
              new ThreadFactoryBuilder()
                  .setDaemon(true)
                  .setNameFormat("TaskLeaderElection-serviceName")
                  .build());
      reElectionExecutor.setRemoveOnCancelPolicy(true);
      this.executorService = reElectionExecutor;
    } else {
      this.executorService = executorService;
    }
  }

  public void start() throws Exception {
    logger.info("Starting TaskLeaderElection service {}", serviceName);
    serviceSet = clusterServiceSetManagerProvider.get().getOrCreateServiceSet(serviceName);
    taskLeaderStatusListener.start();
    enterElections();
  }

  public void addListener(TaskLeaderChangeListener listener) {
    listeners.put(listener, listener);
  }

  public void removeListener(TaskLeaderChangeListener listener) {
    listeners.remove(listener);
  }

  public void updateLeaseExpirationTime(Long newLeaseExpirationTime) {
    leaseExpirationTime.updateAndGet(operand -> newLeaseExpirationTime);
  }

  @VisibleForTesting
  public Collection<TaskLeaderChangeListener> getTaskLeaderChangeListeners() {
    return listeners.values();
  }

  private void enterElections() {
    logger.info("Starting TaskLeader Election Service for {}", serviceName);

    // setting this before calling joinElection, trying to avoid the callback on electionListener
    // returning before this
    // is configured and then missing an onElected operation
    // there is a call to enterElections() from reset() where no synchronizer is used (can't be used
    // because the handle is closed & set to null).
    electionHandleClosed = false;

    final ElectionListener electionListener =
        new ElectionListener() {
          @Override
          public void onElected() {
            // if handle pointer is null it means we are getting a callback
            // before join election returns; there is no synchronisation point here
            Object electionLock = electionHandle == null ? this : electionHandle.synchronizer();
            synchronized (electionLock) {
              if (electionHandleClosed) {
                logger.info(
                    "onElected Event: election handle closed for {}. Will not proceed with the on elected function",
                    serviceName);
                return;
              }

              // in case ZK connection is lost but reestablished later
              // it may get to the situation when 'onElected' is called
              // multiple times - this can create an issue with registering
              // currentEndPoint as master again and again
              // therefore checking if we were a leader before registering
              // and doing other operations
              if (isTaskLeader.compareAndSet(false, true)) {
                logger.info("onElected Event: Electing Leader for {}", serviceName);

                LAST_LEADER_ELECTION.set(System.currentTimeMillis());
                LEADERSHIP_ELECTION_COUNTER.succeeded();

                // registering node with service
                nodeEndpointRegistrationHandle = serviceSet.register(currentEndPoint.get());
                listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipGained);

                // start thread only if relinquishing leadership time was set
                if (leaseExpirationTime.get() != null) {
                  logger.info(
                      "onElected Event: Restarting leadership lease expiration task {} with {} ms timeout",
                      serviceName,
                      leaseExpirationTime.get());
                  leadershipReleaseFuture =
                      executorService.schedule(
                          new LeadershipReset(), leaseExpirationTime.get(), TimeUnit.MILLISECONDS);
                }
              } else {
                logger.debug(
                    "onElected Event: This node is already the leader for {}", serviceName);
              }
            }
          }

          @Override
          public void onCancelled() {
            if (isTaskLeader.compareAndSet(true, false)) {
              logger.info("onCancelled Event: Rejecting Leader for {}", serviceName);
              if (leadershipReleaseFuture != null) {
                leadershipReleaseFuture.cancel(false);
              }
              // unregistering node from service
              nodeEndpointRegistrationHandle.close();
              listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipLost);
            } else {
              logger.debug(
                  "onCancelled Event: This node is already NOT the leader for {}", serviceName);
            }
          }
        };

    electionHandle =
        clusterElectionManagerProvider
            .get()
            .joinElection(serviceName, electionListenerProvider.apply(electionListener));

    // no need to do anything if it is a follower

    failSafeReElectionTask = new FailSafeReElectionTask();
  }

  public CoordinationProtos.NodeEndpoint getTaskLeader() {
    try {
      taskLeaderStatusListener.waitForTaskLeader();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return taskLeaderStatusListener.getTaskLeaderNode();
  }

  public boolean isTaskLeader() {
    return isTaskLeader.get();
  }

  @VisibleForTesting
  public void onElectedLeadership() {
    isTaskLeader.set(true);
    listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipGained);
  }

  @VisibleForTesting
  public void onCancelledLeadership() {
    isTaskLeader.set(false);
    listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipLost);
  }

  @VisibleForTesting
  public void onLeadershipReliquish() {
    listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipRelinquished);
  }

  public Long getLeaseExpirationTime() {
    return leaseExpirationTime.get();
  }

  @VisibleForTesting
  public CoordinationProtos.NodeEndpoint getCurrentEndPoint() {
    return currentEndPoint.get();
  }

  /** To abandon leadership after some time and enter elections again */
  private final class LeadershipReset implements Runnable {
    @Override
    public void run() {
      // do not abandon elections if there is no more participants in the elections
      if (isTaskLeader.compareAndSet(true, false) && electionHandle.instanceCount() > 1) {
        reset();
      } else {
        logger.info(
            "Do not relinquish leadership as it is {} and number of election participants is {}",
            (isTaskLeader.get()) ? "task leader" : "task follower",
            electionHandle.instanceCount());
      }
    }

    void reset() {
      try {
        logger.info(
            "Trying to relinquish leadership for {}, as number of participants is {}",
            serviceName,
            electionHandle.instanceCount());
        listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipRelinquished);
        // abandon leadership
        // and reenter elections
        // unregistering node from service
        closeHandles();
        if (leadershipReleaseFuture != null) {
          leadershipReleaseFuture.cancel(false);
        }
      } catch (InterruptedException ie) {
        logger.error(
            "Current thread is interrupted. stopping elections before leader reelections for {}",
            serviceName,
            ie);
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        logger.error(
            "Error while trying to close elections before leader reelections for {}", serviceName);
      }
      enterElections();
    }
  }

  // In general, when ZK restarts, the current leader will loose leadership, and when the clients
  // gets reconnected one of
  // the election participant would become a leader. However, in some situations the curator client
  // looses the leader
  // election notification from ZK, because of which there arises a situation where no leader is
  // elected across the participants.
  // FailSafeReElectionTask resolves this situation. FailSafeReElectionTask, periodically (every 5
  // minutes) checks if there is
  // a leader. If there is no leader for 10 minutes consecutively, every participant will end the
  // current election
  // and re-enters the election.
  private class FailSafeReElectionTask {
    private long leaderIsNotAvailableFrom = Long.MAX_VALUE;
    private boolean leaderIsNotAvailable = false;
    private final Future failSafeReElectionFuture;

    FailSafeReElectionTask() {
      failSafeReElectionFuture =
          executorService.scheduleAtFixedRate(
              this::checkAndReElect, 0, failSafeLeaderUnavailableDuration / 2, TimeUnit.SECONDS);
    }

    private void checkAndReElect() {
      if (!leaderIsNotAvailable && taskLeaderStatusListener.getTaskLeaderNode() == null) {
        leaderIsNotAvailable = true;
        leaderIsNotAvailableFrom = System.currentTimeMillis();
      }

      if (leaderIsNotAvailable) {
        if (taskLeaderStatusListener.getTaskLeaderNode() != null) {
          leaderIsNotAvailableFrom = Long.MAX_VALUE;
          leaderIsNotAvailable = false;
        } else {
          long leaderUnavailableDuration =
              (System.currentTimeMillis() - leaderIsNotAvailableFrom) / 1000;
          if (leaderUnavailableDuration >= failSafeLeaderUnavailableDuration) {
            synchronized (electionHandle.synchronizer()) {
              electionHandleClosed = true;

              LEADERSHIP_ELECTION_COUNTER.errored();

              if (isTaskLeader.compareAndSet(true, false)) {
                logger.warn(
                    "this is the leader, but looks like leader is not available - closing current election handle and reentering elections for {} as there is no leader for {} secs",
                    serviceName,
                    leaderUnavailableDuration);
                LeadershipReset leadershipReset = new LeadershipReset();
                leadershipReset.reset();
                this.cancel(false);
              } else {
                logger.warn(
                    "this is NOT the leader, and looks like there is no leader available - closing current election handle and reentering elections for {} as there is no leader for {} secs",
                    serviceName,
                    leaderUnavailableDuration);
                try {
                  AutoCloseables.close(electionHandle);
                  enterElections();
                  this.cancel(false);
                } catch (Exception e) {
                  logger.error("Failed to end current election handle");
                }
              }
            }
          }
        }
      }
    }

    void cancel(boolean mayInterruptRunning) {
      if (failSafeReElectionFuture != null) {
        failSafeReElectionFuture.cancel(mayInterruptRunning);
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (isTaskLeader.compareAndSet(true, false)) {
      listeners.keySet().forEach(TaskLeaderChangeListener::onLeadershipLost);
    }
    listeners.clear();
    if (leadershipReleaseFuture != null) {
      leadershipReleaseFuture.cancel(true);
    }

    if (failSafeReElectionTask != null) {
      failSafeReElectionTask.cancel(true);
    }

    if (reElectionExecutor != null) {
      AutoCloseables.close(reElectionExecutor::shutdown);
    }

    closeHandles();
    AutoCloseables.close(taskLeaderStatusListener);

    logger.info("Stopped TaskLeaderElection for service {}", serviceName);
  }

  private synchronized void closeHandles() throws Exception {
    AutoCloseables.close(nodeEndpointRegistrationHandle, electionHandle);
    nodeEndpointRegistrationHandle = null;
    electionHandle = null;
  }
}
