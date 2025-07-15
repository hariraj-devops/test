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
package com.dremio.sabot.exec.heap;

import com.dremio.common.UncaughtExceptionHandlers;
import com.dremio.sabot.exec.HeapLowMemListener;
import java.lang.management.MemoryPoolMXBean;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Low memory controller that understands operations and heap usage patterns of spilling operators.
 *
 * <p>Spilling operators further divides incoming batches to partitions (either memory or disk). The
 * memory partitions almost always incurs metadata overhead that continuously grows proportional to
 * the number of batches it processes (as it has to keep metadata such as arrow buffers and field
 * vectors in java heap, while the data pointed by arrow buffers maybe in direct memory). The heap
 * overhead per incoming batch is directly proportional to the number of columns in the incoming
 * and/or outgoing schema of the operator.
 */
public class SpillingOperatorHeapController implements HeapLowMemController, AutoCloseable {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SpillingOperatorHeapController.class);
  private static final int MAX_TRACKER_SLOTS = 128;
  private static final int MAX_TRACKER_SLOT_MASK = MAX_TRACKER_SLOTS - 1;
  private static final int PER_TRACKER_RANGE_SHIFT = 5;
  private static final int MAX_OVERHEAD_NUMBER =
      MAX_TRACKER_SLOTS * (2 << (PER_TRACKER_RANGE_SHIFT - 1));
  private static final int DEFAULT_THRESHOLD_PERCENTAGE = 75;

  private enum PoolEvent {
    ACTIVATE,
    DEACTIVATE,
    THRESHOLD_CHANGE,
    SIGNAL_USAGE
  }

  private final HeapLowMemListener lowMemListener;
  private final LowMemMonitorThread lowMemThread;
  private final SpillOnLowMemoryTracker[] trackers;
  private final ReentrantLock poolLock;
  private final Condition poolCondition;
  private final AtomicInteger totalParticipants;
  private final PriorityQueue<Integer> fattestFirst;
  private final CompositePoolMonitor poolMonitor;
  private int currentLowMemThresholdPercentage;
  private volatile int widthLowerBoundIndex;

  public SpillingOperatorHeapController() {
    this.trackers = new SpillOnLowMemoryTracker[MAX_TRACKER_SLOTS + 1];
    for (int i = 0; i <= MAX_TRACKER_SLOTS; i++) {
      this.trackers[i] = new SpillOnLowMemoryTracker();
    }
    this.fattestFirst = new PriorityQueue<>((p1, p2) -> Integer.compare(p2, p1));
    this.totalParticipants = new AtomicInteger(0);
    this.currentLowMemThresholdPercentage = DEFAULT_THRESHOLD_PERCENTAGE;
    this.widthLowerBoundIndex = MAX_TRACKER_SLOTS;
    this.poolMonitor = new CompositePoolMonitor();
    this.poolLock = new ReentrantLock();
    this.poolCondition = this.poolLock.newCondition();
    this.lowMemListener = new SpillingLowMemListener();
    this.lowMemThread = new LowMemMonitorThread();
    this.lowMemThread.setUncaughtExceptionHandler(UncaughtExceptionHandlers.processExit());
    this.lowMemThread.start();
  }

  public HeapLowMemListener getLowMemListener() {
    return lowMemListener;
  }

  @Override
  public synchronized HeapLowMemParticipant addParticipant(
      String participantId, int participantOverhead) {
    if (totalParticipants.getAndIncrement() == 0) {
      // we have at least one participant. activate pool monitoring
      notifyGenericPoolEvent(PoolEvent.ACTIVATE);
    }
    final int idx = toIndex(participantOverhead);
    assert idx >= 0 && idx <= MAX_TRACKER_SLOTS;
    if (trackers[idx].numParticipants() == 0) {
      fattestFirst.add(idx);
    }
    return trackers[idx].addParticipant(participantId, participantOverhead);
  }

  @Override
  public synchronized void removeParticipant(String participantId, int participantOverhead) {
    final int idx = toIndex(participantOverhead);
    assert idx >= 0 && idx <= MAX_TRACKER_SLOTS;
    trackers[idx].removeParticipant(participantId);
    if (trackers[idx].numParticipants() == 0) {
      fattestFirst.remove(idx);
    }
    if (totalParticipants.decrementAndGet() <= 0) {
      // no more participants..deactivate pool monitoring
      notifyGenericPoolEvent(PoolEvent.DEACTIVATE);
    }
  }

  int numParticipants() {
    return totalParticipants.get();
  }

  long computeTotalOverhead() {
    long ret = 0;
    for (int i = 0; i <= MAX_TRACKER_SLOTS; i++) {
      ret += trackers[i].currentOverhead();
    }
    return ret;
  }

  int maxParticipantsPerSlot() {
    int max = Integer.MIN_VALUE;
    for (int i = 0; i <= MAX_TRACKER_SLOTS; i++) {
      max = Math.max(max, trackers[i].numParticipants());
    }
    return max;
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void waitAndProcessMemSignals() throws InterruptedException {
    MemoryState memoryState = poolMonitor.getCurrentMemoryState();
    final int waitTime = memoryState.getWaitTimeMillis();
    int sizeFactor;
    poolLock.lock();
    try {
      if (waitTime < 0) {
        poolCondition.await();
      } else {
        if (waitTime > 0) {
          poolCondition.await(waitTime, TimeUnit.MILLISECONDS);
        }
      }
      memoryState = poolMonitor.checkMemoryState();
      sizeFactor = poolMonitor.getSizeFactor();
    } finally {
      poolLock.unlock();
    }
    if (memoryState.getSeverity() > 0) {
      chooseVictimAndTriggerSpilling(memoryState, sizeFactor);
    }
  }

  /**
   * Chooses a victim from a list of participants.
   *
   * <p>The consideration is that field count * number of batches dictates the memory overhead per
   * participant. This also dictates the growth rate per second per operator. The greater the field
   * count, the 'fatter' the victim.
   *
   * <p>Here is the calculation for. memory growth (depends on the low mem condition support key,
   * which is by default at 75% memory full post GC): BORDER (severity = 1) (25% of heap or below
   * still available post GC): LOW (severity = 2) (available memory remained at 15% or below post
   * GC) VERY LOW (severity = 3) (available memory remained at 15% or below post multiple GCs)
   *
   * <p>Here is the calculation for memory growth: if incoming batch rate is 10 per second, the heap
   * memory growth rate will be approximately 256 bytes per second per field per batch * 10 batches
   * * field count. When the field count is below 32, then this means per second growth for operator
   * is approximately < 64K per second per operator. So this means when we are at border or low
   * condition and we have already got a fatter victim that generates over 64K per second per
   * operator, we can wait for the next low mem signal for choosing the next victim. However, if we
   * are at very low memory, we need to choose even thinner victims even if we have some fatter
   * victims to avoid the race to hit OOM and query being killed.
   *
   * @param memoryState Current state of memory
   * @param sizeFactor size in GB of the pool
   */
  private synchronized void chooseVictimAndTriggerSpilling(
      MemoryState memoryState, int sizeFactor) {
    int victimsSoFar = 0;
    int maxAllowedVictims = computeAllowedVictims(memoryState);
    logger.info(
        "Choosing {} Victims. Memory state is {}, Num Trackers = {}",
        maxAllowedVictims,
        memoryState.name(),
        fattestFirst.size());
    if (maxAllowedVictims > 0) {
      var pqc = new PriorityQueue<>(fattestFirst);
      while (!pqc.isEmpty()) {
        int idx = pqc.poll();
        logger.debug(
            "[{}] width = {} -> Memory state {}", idx, memoryState.name(), widthLowerBoundIndex);
        if ((idx < widthLowerBoundIndex && memoryState.getSeverity() <= 1)
            || (idx == 0 && memoryState.getSeverity() <= 2 && victimsSoFar > 0)) {
          // victimsSoFar > 0 means we have already chosen a fatter victim this round so no need to
          // choose a very thin victim unless the low memory condition severity is > 2).
          break;
        }
        final int victimsLeft = maxAllowedVictims - victimsSoFar;
        logger.debug(
            "[{}] -> Choosing {} Victims. Memory state {}", idx, victimsLeft, memoryState.name());
        victimsSoFar += trackers[idx].chooseVictims(victimsLeft, memoryState, sizeFactor);
        if (victimsSoFar >= maxAllowedVictims) {
          break;
        }
      }
    }
    logger.info("{} victims actually chosen to spill due to low memory", victimsSoFar);
  }

  private int computeAllowedVictims(MemoryState memoryState) {
    final int firstLowerBound =
        memoryState.getSeverity() <= 1 ? MAX_TRACKER_SLOTS : widthLowerBoundIndex;
    final int secondLowerBound =
        (memoryState.getSeverity() <= 1
            ? widthLowerBoundIndex
            : (memoryState.getSeverity() == 2) ? 1 : 0);
    int prev = 0;
    int total = 0;
    var pqc = new PriorityQueue<>(fattestFirst);
    while (!pqc.isEmpty()) {
      int idx = pqc.poll();
      if (idx < secondLowerBound && (prev > 0 || total > 0)) {
        // if we have already chosen fatter victims we do not need thinner victims
        break;
      } else if (idx > firstLowerBound) {
        prev += trackers[idx].numParticipants();
      } else {
        total += trackers[idx].numParticipants();
      }
    }
    return prev + memoryState.getMaxVictims(total);
  }

  private void processPoolNotification(boolean thresholdCrossed, MemoryPoolMXBean pool) {
    poolLock.lock();
    try {
      poolMonitor.processPoolEvent(pool, currentLowMemThresholdPercentage, thresholdCrossed);
      poolCondition.signal();
    } finally {
      poolLock.unlock();
    }
  }

  private void notifyGenericPoolEvent(PoolEvent event) {
    poolLock.lock();
    try {
      switch (event) {
        case ACTIVATE:
          poolMonitor.activate();
          break;
        case DEACTIVATE:
          poolMonitor.deactivate();
          break;
        case THRESHOLD_CHANGE:
          poolMonitor.changeThreshold(currentLowMemThresholdPercentage);
          break;
        default:
          break;
      }
      poolCondition.signal();
    } finally {
      poolLock.unlock();
    }
  }

  public static SpillingOperatorHeapController create() {
    return new SpillingOperatorHeapController();
  }

  private static int toIndex(int overhead) {
    return (overhead >= MAX_OVERHEAD_NUMBER)
        ? MAX_TRACKER_SLOTS
        : (overhead >> PER_TRACKER_RANGE_SHIFT) & MAX_TRACKER_SLOT_MASK;
  }

  @Override
  public void close() {
    lowMemThread.close();
  }

  private final class SpillingLowMemListener implements HeapLowMemListener {
    @Override
    public void handleMemNotification(boolean collectionThresholdCrossed, MemoryPoolMXBean pool) {
      processPoolNotification(collectionThresholdCrossed, pool);
    }

    @Override
    public void handleUsageCrossedNotification() {
      notifyGenericPoolEvent(PoolEvent.SIGNAL_USAGE);
    }

    @Override
    public void changeLowMemOptions(
        long newThresholdPercentage, long newAggressiveWidthLowerBound) {
      currentLowMemThresholdPercentage = (int) newThresholdPercentage;
      widthLowerBoundIndex = toIndex((int) newAggressiveWidthLowerBound);
      notifyGenericPoolEvent(PoolEvent.THRESHOLD_CHANGE);
    }
  }

  private final class LowMemMonitorThread extends Thread implements AutoCloseable {
    private volatile boolean shutdown = false;

    private LowMemMonitorThread() {
      super("spilling-heap-low-mem-monitoring-thread");
      setDaemon(true);
    }

    @Override
    public void run() {
      while (!shutdown) {
        try {
          waitAndProcessMemSignals();
        } catch (final InterruptedException e) {
          logger.info("Low Memory monitor thread is exiting...");
          shutdown = true;
        } catch (final Throwable t) {
          // Treat any other exception as fatal
          logger.error("Unexpected exception in SpillOnLowMemory monitor", t);
          throw t;
        }
      }
    }

    @Override
    public void close() {
      shutdown = true;
      interrupt();
    }
  }
}
