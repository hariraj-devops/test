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

import static com.dremio.service.coordinator.LinearizableHierarchicalStore.CommandType.CREATE_PERSISTENT;
import static com.dremio.service.coordinator.LinearizableHierarchicalStore.PathCommand;

import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.CloseableSchedulerThreadPool;
import com.dremio.common.concurrent.CloseableThreadPool;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.io.file.Path;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.LinearizableHierarchicalStore;
import com.dremio.service.coordinator.LostConnectionObserver;
import com.dremio.service.coordinator.exceptions.PathExistsException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.inject.Provider;

/**
 * A task scheduler that ensures that a task identified by a taskName will be scheduled only on any
 * one of the service instances, even if the task with the same name was scheduled simultaneously
 * across multiple instances.
 *
 * <p>The assumption here is that a given task is uniquely identified by the {@code taskName}
 * property provided in the {@code Schedule} object.
 */
public class ClusteredSingletonTaskScheduler extends ClusteredSingletonCommon
    implements ModifiableSchedulerService {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(ClusteredSingletonTaskScheduler.class);

  // by default weight based balancing is disabled
  private static final int DEFAULT_WEIGHT_BASED_BALANCING_PERIOD_SECS = 0;
  private static final int DEFAULT_WEIGHT_TOLERANCE = 0;
  // keep a reasonably high value for expiry check interval post re-connection as default, in case
  // we cannot get it from underlying store
  private static final int DEFAULT_EXPIRY_CHECK_INTERVAL_MILLIS = 3 * 60 * 1000;
  private static final int EXPIRY_CHECK_DEFER_TIME = 10 * 1000;
  private static final String MAIN_POOL_NAME = "clustered_singleton";
  private static final String EXPIRY_POOL_NAME = "clustered_singleton_expiry";
  private static final String STICKY_POOL_NAME = "clustered_singleton_sticky";
  private static final int MAX_TIME_TO_WAIT_POST_CANCEL_SECONDS = 10;
  // main pool can be very small as the wrapper task is lightweight and short-lived.
  private static final int MAIN_POOL_SIZE = 3;
  private static final String SERVICE_NAME_PREFIX = MAIN_POOL_NAME + "-";
  private static final String DONE_PATH = "done";
  private static final String STEAL_PATH = "steal-set";
  private static final String WEIGHT_PATH = "weight";
  // default task group used for tasks that are scheduled
  private final ScheduleTaskGroup defaultGroup;
  // root path that uniquely identifies this service. All instances of the same service must have
  // the same root path
  private final String rootPath;
  // Parent path for notifying that a task is done to other service instances
  private final String doneFqPath;
  // Parent path for notifying that a task can be stolen as this instance does not have the
  // bandwidth to run
  private final String stealFqPath;
  // Root path for tracking weights for weight based load balancing
  private final String weightFqPath;
  // Name of the service (derived from the root path)
  private final String serviceName;
  // Endpoint Provider for this service instance
  private final Provider<CoordinationProtos.NodeEndpoint> currentEndPoint;
  // Cluster coordinator instance provide for this service instance
  private final Provider<ClusterCoordinator> clusterCoordinatorProvider;
  // Pools created by clients to isolate and run their tasks (e.g metadata refresh)
  private final ConcurrentMap<String, CloseableThreadPool> taskPools;
  // All created task schedules
  private final ConcurrentMap<String, PerTaskScheduleTracker> allTasks;
  // Pool used for internal processing of the clustered singleton (e.g wrapped run)
  private final CloseableSchedulerThreadPool scheduleCommonPool;
  private final CloseableSchedulerThreadPool serviceExpiryCheckerPool;
  // Pool used to handle overflow of sticky schedules
  private final CloseableThreadPool stickyPool;
  // Tasks that has been cancelled locally. They are cached for sometime before the booking is
  // released
  private final Set<PerTaskScheduleTracker> cancelledTasks;
  // Lock to deal with cancellations
  private final ReentrantLock cancelHandlerLock;
  private final Condition cancelCompleteCondition;
  // Recovery monitoring of all tasks whose hash falls into the current endpoint
  private final TaskRecoveryMonitor recoveryMonitor;
  private final TaskDoneHandler doneHandler;
  private final TaskLoadController loadController;
  private final TaskStatsCollector statsCollector;
  private final TaskInfoLogger infoLogger;
  private final TaskCompositeEventCollector eventCollector;
  private final WeightBalancer weightBalancer;
  private final AtomicInteger lockStepCounter;
  private final boolean haltOnZkLost;
  private final AtomicBoolean active;
  private final String unVersionedDoneFqPath;
  private final int maxWaitTimePostCancel;
  private final AtomicBoolean zombie;
  private final AtomicBoolean ignoreReconnects;
  private final int weightBasedBalancingPeriodSecs;
  private final AtomicInteger sessionExpiryCheckIncarnation;
  // Reference to the distributed task store (e.g. zk)
  private volatile LinearizableHierarchicalStore taskStore;
  // version of this service on the latest restart
  private volatile String serviceVersion;
  private volatile String versionedDoneFqPath;
  private volatile boolean rollingUpgradeInProgress;
  private volatile int expiryCheckIntervalMillis;

  public ClusteredSingletonTaskScheduler(
      ScheduleTaskGroup defaultGroup,
      String nameSpace,
      Provider<ClusterCoordinator> clusterCoordinatorProvider,
      Provider<CoordinationProtos.NodeEndpoint> currentNode,
      int maxWaitTimePostCancel) {
    this(
        defaultGroup,
        nameSpace,
        clusterCoordinatorProvider,
        currentNode,
        false,
        maxWaitTimePostCancel,
        DEFAULT_WEIGHT_BASED_BALANCING_PERIOD_SECS,
        DEFAULT_WEIGHT_TOLERANCE);
  }

  public ClusteredSingletonTaskScheduler(
      ScheduleTaskGroup defaultGroup,
      String nameSpace,
      Provider<ClusterCoordinator> clusterCoordinatorProvider,
      Provider<CoordinationProtos.NodeEndpoint> currentNode,
      boolean haltOnZkLost) {
    this(
        defaultGroup,
        nameSpace,
        clusterCoordinatorProvider,
        currentNode,
        haltOnZkLost,
        MAX_TIME_TO_WAIT_POST_CANCEL_SECONDS,
        DEFAULT_WEIGHT_BASED_BALANCING_PERIOD_SECS,
        DEFAULT_WEIGHT_TOLERANCE);
  }

  public ClusteredSingletonTaskScheduler(
      ScheduleTaskGroup defaultGroup,
      String nameSpace,
      Provider<ClusterCoordinator> clusterCoordinatorProvider,
      Provider<CoordinationProtos.NodeEndpoint> currentNode,
      boolean haltOnZkLost,
      int maxWaitTimePostCancel,
      int weightBasedBalancingPeriodSecs,
      int weightTolerance) {
    Preconditions.checkArgument(defaultGroup != null, "Must specify a default group for schedules");
    Preconditions.checkArgument(
        nameSpace != null && !nameSpace.isEmpty(), "Must specify a valid name space");
    Preconditions.checkArgument(
        maxWaitTimePostCancel > 0, "Must specify a valid wait time post cancel for reuse");
    this.defaultGroup = defaultGroup;
    this.maxWaitTimePostCancel = maxWaitTimePostCancel;
    // Namespace in the cluster coordinator already isolates different dremio apps/services, so
    // root path need not start from the passed root path
    this.rootPath = Path.SEPARATOR + MAIN_POOL_NAME;
    this.doneFqPath = this.rootPath + Path.SEPARATOR + DONE_PATH;
    this.stealFqPath = this.rootPath + Path.SEPARATOR + STEAL_PATH;
    this.serviceName = SERVICE_NAME_PREFIX + nameSpace.replace(Path.SEPARATOR_CHAR, '-');
    this.clusterCoordinatorProvider = clusterCoordinatorProvider;
    this.currentEndPoint = currentNode;
    this.taskPools = new ConcurrentHashMap<>();
    this.scheduleCommonPool = new CloseableSchedulerThreadPool(MAIN_POOL_NAME, MAIN_POOL_SIZE);
    this.serviceExpiryCheckerPool = new CloseableSchedulerThreadPool(EXPIRY_POOL_NAME, 1);
    this.stickyPool = new CloseableThreadPool(STICKY_POOL_NAME);
    this.allTasks = new ConcurrentHashMap<>();
    this.cancelledTasks = new HashSet<>();
    this.cancelHandlerLock = new ReentrantLock();
    this.cancelCompleteCondition = cancelHandlerLock.newCondition();
    this.zombie = new AtomicBoolean(false);
    this.ignoreReconnects = new AtomicBoolean(false);
    this.statsCollector = new TaskStatsCollector(this);
    this.infoLogger = new TaskInfoLogger(this);
    this.eventCollector =
        new TaskCompositeEventCollector(Arrays.asList(statsCollector, infoLogger));
    this.recoveryMonitor =
        new TaskRecoveryMonitor(clusterCoordinatorProvider, this, eventCollector);
    this.doneHandler = new TaskDoneHandler(this, eventCollector);
    this.loadController = new TaskLoadController(this, eventCollector);
    this.active = new AtomicBoolean(false);
    this.lockStepCounter = new AtomicInteger(0);
    this.haltOnZkLost = haltOnZkLost;
    this.rollingUpgradeInProgress = true;
    this.unVersionedDoneFqPath = this.doneFqPath + Path.SEPARATOR + "default";
    this.weightBasedBalancingPeriodSecs = weightBasedBalancingPeriodSecs;
    if (weightBasedBalancingPeriodSecs > 0) {
      Preconditions.checkArgument(weightTolerance > 0, "Weight tolerance value must be non zero");
      this.weightFqPath = this.rootPath + Path.SEPARATOR + WEIGHT_PATH;
      this.weightBalancer =
          new TaskWeightTracker(
              this, eventCollector, weightBasedBalancingPeriodSecs, weightTolerance);
    } else {
      this.weightFqPath = null;
      this.weightBalancer = new TaskWeightTracker.DummyWeightTracker();
    }
    this.sessionExpiryCheckIncarnation = new AtomicInteger(0);
    // keep a very high value for session timeout
    this.expiryCheckIntervalMillis = DEFAULT_EXPIRY_CHECK_INTERVAL_MILLIS;
  }

  @Override
  public void start() throws Exception {
    this.taskStore = clusterCoordinatorProvider.get().getHierarchicalStore();
    var sessionTimeout = clusterCoordinatorProvider.get().getSessionTimeoutMillis();
    if (sessionTimeout > 0) {
      this.expiryCheckIntervalMillis = sessionTimeout + EXPIRY_CHECK_DEFER_TIME;
    }
    createBasePathIgnoreIfExists(rootPath);
    createBasePathIgnoreIfExists(doneFqPath);
    createBasePathIgnoreIfExists(stealFqPath);
    createBasePathIgnoreIfExists(unVersionedDoneFqPath);
    if (weightFqPath != null) {
      createBasePathIgnoreIfExists(weightFqPath);
    }
    createTaskPool(this.defaultGroup);
    serviceVersion = retrieveServiceVersion();
    versionedDoneFqPath = doneFqPath + Path.SEPARATOR + serviceVersion;
    createBasePathIgnoreIfExists(versionedDoneFqPath);
    this.recoveryMonitor.start();
    this.doneHandler.start();
    this.loadController.start();
    this.statsCollector.start();
    this.infoLogger.start();
    this.weightBalancer.start();
    this.active.set(true);
    taskStore.registerLostConnectionObserver(new SessionLostHandler());
    LOGGER.info(
        "Clustered Singleton Task Scheduler `{}` (Version: {}) is up and registered",
        this.serviceName,
        this.serviceVersion);
  }

  @Override
  public Cancellable schedule(Schedule schedule, Runnable task) {
    // These pre-conditions will never happen as we have trusted internal clients; but check for
    // them
    // to avoid more nasty failures later.
    if (weightBasedBalancingPeriodSecs <= 0) {
      Preconditions.checkArgument(
          Objects.isNull(schedule.getWeightProvider()),
          "Weight Based balancing schedules is not enabled for this clustered singleton");
    }
    Preconditions.checkState(
        this.taskStore != null, "Internal Error: Clustered Singleton Scheduler not started yet");
    Preconditions.checkArgument(
        schedule != null && task != null,
        "Illegal API Usage: Null arguments provided to schedule API");
    Preconditions.checkArgument(
        schedule.getTaskName() != null && !schedule.getTaskName().isEmpty(),
        "Illegal API Usage: Task name must be specified for clustered singleton");
    Preconditions.checkState(
        this.active.get(),
        "Internal Error: Schedule API called on an Inactive clustered singleton");
    final String taskFqPathForBook = rootPath + Path.SEPARATOR + schedule.getTaskName();
    if (!allTasks.containsKey(schedule.getTaskName())) {
      try {
        createBasePathIgnoreIfExists(taskFqPathForBook);
      } catch (Exception e) {
        throw new IllegalStateException(
            "Unable to create task path "
                + taskFqPathForBook
                + " for task "
                + schedule.getTaskName()
                + " in the hierarchical store",
            e);
      }
    }
    AtomicBoolean firstTime = new AtomicBoolean(false);
    blockOnCancel(allTasks.get(schedule.getTaskName()));
    PerTaskScheduleTracker tracker =
        allTasks.compute(
            schedule.getTaskName(),
            (k, v) -> {
              if (v == null) {
                firstTime.set(true);
                return new PerTaskScheduleTracker(
                    schedule,
                    task,
                    taskFqPathForBook,
                    getRunningPool(schedule),
                    this,
                    this::handleCancel,
                    loadController,
                    eventCollector,
                    recoveryMonitor,
                    doneHandler,
                    weightBalancer);
              } else {
                if (schedule.isInLockStep() || v.getSchedule().isInLockStep()) {
                  firstTime.set(true);
                  final String uniqueTaskName =
                      schedule.getTaskName() + lockStepCounter.incrementAndGet();
                  final String taskFqPath = rootPath + Path.SEPARATOR + uniqueTaskName;
                  // create a new schedule which has same booking path as previous but with a
                  // different task path for rest of the
                  // managers
                  try {
                    createBasePathIgnoreIfExists(taskFqPath);
                  } catch (Exception e) {
                    throw new IllegalStateException(
                        "Unable to create task path "
                            + taskFqPath
                            + " for task "
                            + schedule.getTaskName()
                            + " in the hierarchical store",
                        e);
                  }
                  return new PerTaskScheduleTracker(
                      schedule,
                      task,
                      taskFqPath,
                      taskFqPathForBook,
                      v.getBookingOwnerSessionId(),
                      uniqueTaskName,
                      getRunningPool(schedule),
                      this,
                      this::handleCancel,
                      loadController,
                      eventCollector,
                      recoveryMonitor,
                      doneHandler,
                      weightBalancer);
                } else {
                  v.setNewSchedule(schedule, true);
                }
                return v;
              }
            });
    if (firstTime.get() && !tracker.isDone()) {
      LOGGER.info("Schedule Request Details: {}", schedule);
      tracker.startRun(false);
    }
    return tracker;
  }

  @Override
  public Optional<CoordinationProtos.NodeEndpoint> getCurrentTaskOwner(String taskName) {
    PerTaskScheduleTracker tracker = allTasks.get(taskName);
    return tracker == null ? Optional.empty() : tracker.getCurrentTaskOwner();
  }

  @Override
  public boolean isRollingUpgradeInProgress(String taskName) {
    if (rollingUpgradeInProgress) {
      // if election path exists, rolling upgrade is in progress
      rollingUpgradeInProgress = taskStore.electionPathExists(taskName);
    }
    return rollingUpgradeInProgress;
  }

  private void handlePotentialSessionLoss() {
    sessionExpiryCheckIncarnation.incrementAndGet();
    LOGGER.warn(
        "{}:{}:An error has occurred in the underlying task store that may cause session loss. Incarnation {}",
        ENDPOINT_AS_STRING.apply(getThisEndpoint()),
        this.serviceName,
        sessionExpiryCheckIncarnation);
    allTasks
        .values()
        .forEach(
            (tracker) -> {
              // if the task is being cancelled no need to handle session loss
              if (!cancelledTasks.contains(tracker)) {
                tracker.handlePotentialSessionLoss();
              }
            });
  }

  private void recoverOrInitiateNewSession() {
    LOGGER.info(
        "{}:{}: Scheduling expiry check task after a potential session loss. Expiry check is at {} millis from now",
        ENDPOINT_AS_STRING.apply(getThisEndpoint()),
        this.serviceName,
        this.expiryCheckIntervalMillis);
    // schedule expiry check a few minutes after reconnect as the underlying cluster coordinator
    // reuses the
    // same client (LHS store client) session post session expiry.
    final int expiryNumber = sessionExpiryCheckIncarnation.get();
    serviceExpiryCheckerPool.schedule(
        () -> {
          LOGGER.info(
              "{}:{}: Session expiry check after a potential session loss",
              ENDPOINT_AS_STRING.apply(getThisEndpoint()),
              this.serviceName);
          if (expiryNumber != sessionExpiryCheckIncarnation.get()) {
            // we have another one coming soon. Ignore this
            LOGGER.info(
                "Ignoring this session expiry schedule as a new one is coming soon {} {}",
                expiryNumber,
                sessionExpiryCheckIncarnation.get());
            return;
          }
          boolean sessionExpired = false;
          boolean sessionExpiryChecked = false;
          for (var tracker : allTasks.values()) {
            if (!cancelledTasks.contains(tracker)) {
              if (tracker.isBookingOwner()) {
                sessionExpiryChecked = true;
              }
              if (!tracker.tryRecoverSession()) {
                sessionExpired = true;
              }
            }
          }
          if (sessionExpired || !sessionExpiryChecked) {
            // Either we have confirmed session expiry OR there were no owned tasks by this node,
            // which we can use to check expiry.
            // Refresh in both cases, as when we are not sure if session is expired, better to
            // treat it as expired and refresh.
            recoveryMonitor.refresh();
          }
        },
        expiryCheckIntervalMillis,
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() throws Exception {
    if (active.compareAndSet(true, false)) {
      AutoCloseables.close(loadController, scheduleCommonPool, stickyPool, weightBalancer);
      AutoCloseables.close(taskPools.values());
      AutoCloseables.close(recoveryMonitor, infoLogger);
      taskPools.clear();
    }
  }

  @Override
  public void addTaskGroup(ScheduleTaskGroup group) {
    // as of now only pool capacity is associated with a schedule task group. Also a task group
    // cannot be deleted
    // once created
    createTaskPool(group);
  }

  @Override
  public void modifyTaskGroup(String groupName, ScheduleTaskGroup group) {
    CloseableThreadPool pool =
        taskPools.computeIfPresent(
            groupName,
            (k, v) -> {
              final int newSize = group.getCapacity();
              final int currentSize = v.getCorePoolSize();
              if (currentSize == newSize || newSize <= 0) {
                return v;
              }
              if (!group.getGroupName().equals(k)) {
                LOGGER.warn(
                    "Ignoring modify task group request with wrong group name `{}` for `{}`",
                    group.getGroupName(),
                    k);
                return v;
              }
              LOGGER.info(
                  "Group `{}` modify request. current capacity = {}, requested capacity = {}",
                  group.getGroupName(),
                  currentSize,
                  newSize);
              if (currentSize > newSize) {
                // shrinking
                v.setCorePoolSize(newSize);
                v.setMaximumPoolSize(newSize);
              } else {
                // expanding
                v.setMaximumPoolSize(newSize);
                v.setCorePoolSize(newSize);
              }
              return v;
            });
    if (pool == null) {
      throw new IllegalArgumentException("Specified Task group '" + groupName + "' does not exist");
    }
  }

  @Override
  String getBaseServiceName() {
    return MAIN_POOL_NAME;
  }

  @Override
  String getFqServiceName() {
    return serviceName;
  }

  @Override
  String getVersionedDoneFqPath() {
    return versionedDoneFqPath;
  }

  @Override
  String getUnVersionedDoneFqPath() {
    return unVersionedDoneFqPath;
  }

  @Override
  String getStealFqPath() {
    return stealFqPath;
  }

  @Override
  CloseableSchedulerThreadPool getSchedulePool() {
    return scheduleCommonPool;
  }

  @Override
  CloseableThreadPool getStickyPool() {
    return stickyPool;
  }

  @Override
  LinearizableHierarchicalStore getTaskStore() {
    return taskStore;
  }

  @Override
  String getServiceVersion() {
    return serviceVersion;
  }

  @Override
  CoordinationProtos.NodeEndpoint getThisEndpoint() {
    return currentEndPoint.get();
  }

  @Override
  int getMaxWaitTimePostCancel() {
    return maxWaitTimePostCancel;
  }

  @Override
  boolean isActive() {
    return active.get();
  }

  @VisibleForTesting
  void actDead() {
    zombie.set(true);
  }

  @VisibleForTesting
  void bringAlive() {
    zombie.set(false);
  }

  @VisibleForTesting
  void ignoreReconnects() {
    ignoreReconnects.set(true);
  }

  @VisibleForTesting
  void allowReconnects() {
    ignoreReconnects.set(false);
  }

  @Override
  boolean isZombie() {
    return zombie.get();
  }

  @Override
  boolean shouldIgnoreReconnects() {
    return ignoreReconnects.get();
  }

  @Override
  String getWeightFqPath() {
    return weightFqPath;
  }

  /**
   * Callback handling an explicit schedule cancellation on the {@code Cancellable} of the {@code
   * PerTaskScheduleTracker}.
   *
   * <p>The cancelled tasks are kept in cache for sometime to ensure that a task schedule with the
   * same name is not created through the API. This will also allow us to clean up the task schedule
   * after monitoring whether the task is still running.
   *
   * @param scheduleTracker the tracker on which the cancel was called.
   */
  private void handleCancel(PerTaskScheduleTracker scheduleTracker) {
    boolean scheduleCancelMonitoring = false;
    // remove it from recovery monitoring and load controller as this node is no longer interested
    // in this task
    loadController.removeTask(scheduleTracker.getTaskName());
    recoveryMonitor.removeTask(scheduleTracker.getTaskName());
    cancelHandlerLock.lock();
    try {
      cancelledTasks.add(scheduleTracker);
      if (cancelledTasks.size() == 1) {
        scheduleCancelMonitoring = true;
      }
    } finally {
      cancelHandlerLock.unlock();
    }
    if (scheduleCancelMonitoring) {
      LOGGER.info("Scheduling cancel monitoring as at least one task is cancelled locally");
      scheduleCommonPool.scheduleWithFixedDelay(
          () -> {
            cancelHandlerLock.lock();
            try {
              final boolean removed =
                  cancelledTasks.removeIf(
                      (tracker) -> {
                        boolean cleanedUp = tracker.checkAndReleaseBookingOnCancelCompletion();
                        if (cleanedUp) {
                          // task is cleaned up on this instance; remove from map
                          LOGGER.info(
                              "Task {} is completely cleaned up locally", tracker.getTaskName());
                          allTasks.remove(tracker.getTaskName());
                        }
                        return cleanedUp;
                      });
              if (removed) {
                cancelCompleteCondition.signal();
              }
              if (cancelledTasks.isEmpty()) {
                // just a trick to terminate the periodic schedule
                LOGGER.info("Done cleaning up all cancelled tasks");
                throw new RuntimeException("Done with cancellation processing");
              }
            } finally {
              cancelHandlerLock.unlock();
            }
          },
          maxWaitTimePostCancel,
          maxWaitTimePostCancel,
          TimeUnit.SECONDS);
    }
  }

  private void blockOnCancel(PerTaskScheduleTracker tracker) {
    if (tracker == null) {
      return;
    }
    cancelHandlerLock.lock();
    try {
      while (cancelledTasks.contains(tracker)) {
        try {
          LOGGER.info("Previous task {} is still cancelling", tracker.getTaskName());
          cancelCompleteCondition.await(maxWaitTimePostCancel, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new RuntimeException();
        }
      }
    } finally {
      cancelHandlerLock.unlock();
    }
  }

  private String retrieveServiceVersion() {
    final CoordinationProtos.NodeEndpoint endpoint = currentEndPoint.get();
    final String version;
    if (!endpoint.hasDremioVersion()) {
      // if dremio version is not available, log a warning and use last uptime as version
      long timeStamp =
          (endpoint.hasStartTime()) ? endpoint.getStartTime() : System.currentTimeMillis();
      version = String.valueOf(timeStamp);
      LOGGER.warn("No Service Version found. Using generated version {} from timestamp", version);
    } else {
      version = endpoint.getDremioVersion();
    }
    return version;
  }

  private CloseableThreadPool getRunningPool(Schedule schedule) {
    String poolName =
        (schedule.getTaskGroupName() == null)
            ? defaultGroup.getGroupName()
            : schedule.getTaskGroupName();
    CloseableThreadPool pool = this.taskPools.get(poolName);
    if (pool == null) {
      throw new IllegalArgumentException(
          "Non existent pool "
              + poolName
              + "specified in the schedule for task"
              + schedule.getTaskName());
    }
    return pool;
  }

  @SuppressWarnings("resource")
  private void createTaskPool(ScheduleTaskGroup taskGroup) {
    this.taskPools.computeIfAbsent(
        taskGroup.getGroupName(),
        (k) ->
            CloseableThreadPool.newFixedThreadPool(
                taskGroup.getGroupName() + "-", taskGroup.getCapacity()));
  }

  private void createBasePathIgnoreIfExists(String path) throws Exception {
    try {
      this.taskStore.executeSingle(new PathCommand(CREATE_PERSISTENT, path));
    } catch (PathExistsException ignored) {
      LOGGER.debug("{} already exists", path);
    }
  }

  /**
   * Handles connection lost and regained events to check and act on potential session expiry.
   *
   * <p>The following assumptions are made about the underlying store (which is already true for
   * Curator/ZK): 1. Lost connection and notify regained connection are ordered (send through a
   * single thread). 2. Currently, the same client/curator session is used even on a session expiry,
   * which means we have to explicitly check for session expiry for clustered singleton tasks.
   */
  final class SessionLostHandler implements LostConnectionObserver {

    @Override
    public void notifyLostConnection() {
      if (isActive()) {
        eventCollector.hitUnexpectedError();
        if (haltOnZkLost) {
          LOGGER.error(
              "An unrecoverable error has occurred in the underlying task store that has caused session loss."
                  + "System is doing an abrupt halt to preserve data integrity");
          Runtime.getRuntime().halt(1);
        } else {
          handlePotentialSessionLoss();
        }
      }
    }

    @Override
    public void notifyConnectionRegainedAfterLost() {
      if (isActive() && !zombie.get()) {
        recoverOrInitiateNewSession();
      }
    }
  }
}
