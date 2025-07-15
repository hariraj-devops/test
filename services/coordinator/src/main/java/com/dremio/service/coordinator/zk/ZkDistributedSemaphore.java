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
package com.dremio.service.coordinator.zk;

import com.dremio.common.AutoCloseables;
import com.dremio.service.coordinator.DistributedSemaphore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2;
import org.apache.curator.framework.recipes.locks.Lease;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

class ZkDistributedSemaphore implements DistributedSemaphore {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ZkDistributedSemaphore.class);
  private static final int WATCHER_CHECK_GAP_MILLIS = 60 * 1000;

  private final InterProcessSemaphoreV2 semaphore;
  private final Map<UpdateListener, Void> listeners =
      Collections.synchronizedMap(new WeakHashMap<UpdateListener, Void>());
  private final String path;
  private final CuratorFramework client;
  private final AtomicBoolean childWatchEnabled;
  private volatile long lastWatcherCheckedAtMillis;

  ZkDistributedSemaphore(CuratorFramework client, String path, int numberOfLeases)
      throws Exception {
    this.semaphore = new InterProcessSemaphoreV2(client, path, numberOfLeases);
    this.path = ZKPaths.makePath(path, "leases");
    this.client = client;
    this.childWatchEnabled = new AtomicBoolean(false);
    this.lastWatcherCheckedAtMillis = 0;
  }

  private boolean setWatcher() throws Exception {
    if (client.checkExists().forPath(path) != null) {
      if (childWatchEnabled.compareAndSet(false, true)) {
        client.getChildren().usingWatcher((Watcher) this::onEvent).forPath(path);
        logger.debug("watcher set for path: {}", path);
      } else {
        logger.debug("watcher already set for path: {}", path);
      }
      return true;
    } else {
      logger.debug("path {} not found", path);
      return false;
    }
  }

  private void onEvent(WatchedEvent event) {
    // typically watcher is triggered only once. Let us make sure watcher is set again
    childWatchEnabled.set(false);
    if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
      Collection<UpdateListener> col = new ArrayList<>(listeners.keySet());
      for (UpdateListener l : col) {
        l.updated();
      }
    }
  }

  @Override
  public boolean hasOutstandingPermits() {
    try {
      return !semaphore.getParticipantNodes().isEmpty();
    } catch (Exception e) {
      if (e instanceof KeeperException.NoNodeException) {
        logger.debug("No node exception.", e);
        return false;
      } else {
        logger.warn("exception when semaphore trying to get participant nodes.", e);
        return true;
      }
    }
  }

  /**
   * This is a workaround for a rare curator leak of a lease path if it is interrupted due to a
   * cancellation, while it is waiting inside the acquire call.
   *
   * @param expectedNodeData identifies the node to be deleted
   */
  @Override
  public void forceDeleteParticipantNode(String expectedNodeData) {
    try {
      for (String participantNode : semaphore.getParticipantNodes()) {
        final String leasePath = ZKPaths.makePath(path, participantNode);
        try {
          final byte[] nodeBytes = client.getData().forPath(leasePath);
          if (nodeBytes != null && new String(nodeBytes).equals(expectedNodeData)) {
            logger.warn(
                "Forcefully deleting a leaked permit for path {} that contains {}",
                leasePath,
                expectedNodeData);
            client.delete().guaranteed().forPath(leasePath);
          }
        } catch (Exception e) {
          if (e instanceof KeeperException.NoNodeException) {
            logger.debug("Lease path {} no longer exists", leasePath, e);
          } else {
            logger.warn("Unable to read or delete lease path {}", leasePath, e);
          }
        }
      }
    } catch (Exception e) {
      if (e instanceof KeeperException.NoNodeException) {
        logger.debug("Semaphore path no longer exists for {}", expectedNodeData, e);
      } else {
        logger.warn(
            "Exception occurred while trying to get semaphore participant node for {}.",
            expectedNodeData,
            e);
      }
    }
  }

  @Override
  public DistributedLease acquire(long time, TimeUnit unit, byte[] nodeData) throws Exception {
    if (nodeData != null) {
      semaphore.setNodeData(nodeData);
    }
    return acquire(1, time, unit);
  }

  @Override
  public DistributedLease acquire(int numPermits, long time, TimeUnit unit) throws Exception {
    Collection<Lease> leases = semaphore.acquire(numPermits, time, unit);
    if (leases != null) {
      return new LeasesHolder(leases);
    }
    return null;
  }

  @Override
  public boolean registerUpdateListener(UpdateListener listener) {

    // if this is the first add, add it here.
    boolean set = true;
    try {
      var currentTimeMillis = System.currentTimeMillis();
      if (currentTimeMillis > lastWatcherCheckedAtMillis + WATCHER_CHECK_GAP_MILLIS) {
        set = setWatcher();
        lastWatcherCheckedAtMillis = currentTimeMillis;
      }
    } catch (Exception e) {
      logger.warn("Exception occurred while registering listener", e);
    }
    listeners.put(
        () -> {
          try {
            listener.updated();
          } catch (Exception e) {
            logger.warn("Exception occurred while notifying listener.", e);
          }
        },
        null);
    return set;
  }

  private class LeasesHolder implements DistributedLease {
    private Collection<Lease> leases;

    LeasesHolder(Collection<Lease> leases) {
      this.leases = leases;
    }

    @Override
    public void close() throws Exception {
      AutoCloseables.close(leases);
    }
  }
}
