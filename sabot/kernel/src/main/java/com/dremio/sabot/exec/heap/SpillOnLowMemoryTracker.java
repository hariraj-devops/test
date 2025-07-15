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

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

/** Tracker that monitors and signals a range of participants based on their field overhead. */
final class SpillOnLowMemoryTracker {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SpillOnLowMemoryTracker.class);
  private final Map<String, Participant> participantMap;
  private final AtomicLong totalOverhead;

  SpillOnLowMemoryTracker() {
    this.participantMap = new ConcurrentHashMap<>();
    totalOverhead = new AtomicLong();
  }

  public HeapLowMemParticipant addParticipant(String participantId, int participantOverhead) {
    return participantMap.compute(
        participantId,
        (k, v) -> (v == null) ? new Participant(participantOverhead, totalOverhead::addAndGet) : v);
  }

  public void removeParticipant(String participantId) {
    Participant p = participantMap.remove(participantId);
    if (p != null) {
      p.done();
    }
  }

  int chooseVictims(int maxVictims, MemoryState memoryState, int sizeFactor) {
    if (participantMap.isEmpty()) {
      return 0;
    }
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Tracker details: Overhead {}, Size Factor {}, Overhead Factor {}, Individual Overhead {}",
          totalOverhead.get(),
          sizeFactor,
          memoryState.getTotalOverheadFactor(sizeFactor),
          memoryState.getIndividualOverhead());
    }
    int numVictims = 0;
    if (totalOverhead.get() >= memoryState.getTotalOverheadFactor(sizeFactor)) {
      // copy into a local priority queue all the overheads of filtered participants and then
      // traverse the q in max heap order of overhead at the time of insertion..If the overhead
      // values change post insertion, we can live with it as we are interested only in taking
      // top N at given instant in time and slight variations to the order between now and
      // q traversal will not cause issues to the correctness of the algorithm as long as we
      // mask the priority q comparator from these changes by duplicating the overhead value.
      PriorityQueue<Map.Entry<Long, Participant>> participants =
          participantMap.values().stream()
              .filter(Participant::isNotVictim)
              .filter(
                  participant -> participant.getOverhead() >= memoryState.getIndividualOverhead())
              .map(participant -> Map.entry(participant.getOverhead(), participant))
              .collect(
                  Collectors.toCollection(
                      () ->
                          new PriorityQueue<>(
                              Comparator.comparingLong(
                                      (Map.Entry<Long, Participant> p) -> p.getKey())
                                  .reversed())));
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Num Participants = {}, Participant List with overhead greater than {} = `{}`",
            participants.size(),
            memoryState.getIndividualOverhead(),
            participants.stream()
                .map(p -> p.getValue().toString())
                .collect(Collectors.joining(",")));
      }
      while (!participants.isEmpty()) {
        var p = participants.poll();
        p.getValue().setAsVictim();
        numVictims++;
        if (numVictims >= maxVictims) {
          break;
        }
      }
    }
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Chosen {} victims. Total overhead of Tracker: {}", numVictims, totalOverhead.get());
    }
    return numVictims;
  }

  int numParticipants() {
    return participantMap.size();
  }

  long currentOverhead() {
    return totalOverhead.get();
  }

  /**
   * Low memory participant, such as an operator or even a partition within the operator.
   *
   * <p>Since all operators operates within minor fragments, each participant is typically written
   * only by a single thread and eventual read consistency is assumed for readers. Eventual
   * consistency is guaranteed even on weakly consistent processors due to the periodic syncing of
   * batch counts.
   */
  private static final class Participant implements HeapLowMemParticipant {
    private static final int NUM_BATCHES_PER_SYNC_SHIFT = 4;
    private final int perBatchOverhead;
    private final LongConsumer batchesSyncConsumer;
    // use int to prevent word tearing as read is unprotected. Assumption is that we will never
    // reach MAX_INT
    // batches before running out of memory
    private int batches;
    private int syncedBatches;
    private int currentSync;
    // no need to make this volatile as eventual consistency is guaranteed due to periodic syncing
    // while
    // adding batches.
    private boolean victim;

    Participant(int perBatchOverhead, LongConsumer batchesSyncConsumer) {
      this.perBatchOverhead = perBatchOverhead;
      this.batches = 0;
      this.syncedBatches = 0;
      this.victim = false;
      this.batchesSyncConsumer = batchesSyncConsumer;
      this.currentSync = 1;
    }

    @Override
    public void addBatches(int numBatches) {
      this.batches += numBatches;
      if ((this.batches >> NUM_BATCHES_PER_SYNC_SHIFT) > currentSync) {
        // do sync only occasionally to reduce thread contention
        long diff = this.batches - this.syncedBatches;
        batchesSyncConsumer.accept(diff * perBatchOverhead);
        this.syncedBatches = this.batches;
        this.currentSync = 1 + (this.batches >> NUM_BATCHES_PER_SYNC_SHIFT);
      }
    }

    @Override
    public boolean isVictim() {
      return victim;
    }

    boolean isNotVictim() {
      return !victim;
    }

    void setAsVictim() {
      victim = true;
    }

    void done() {
      batchesSyncConsumer.accept((long) -syncedBatches * (long) perBatchOverhead);
    }

    long getOverhead() {
      // prevents word tearing as this is unprotected read
      return (long) this.batches * (long) this.perBatchOverhead;
    }

    @Override
    public String toString() {
      return "{Per Batch Overhead :"
          + perBatchOverhead
          + ", Batches :"
          + batches
          + ", Synced Batches :"
          + syncedBatches
          + ", Victim :"
          + victim
          + "}";
    }
  }
}
