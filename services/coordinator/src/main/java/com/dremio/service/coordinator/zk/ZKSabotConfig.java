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

import static com.dremio.service.coordinator.ClusterCoordinator.Options.CLUSTER_ID;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_CONNECTION;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_CONNECTION_HANDLE_ENABLED;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_ELECTION_DELAY_FOR_LEADER_CALLBACK;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_ELECTION_POLLING;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_ELECTION_TIMEOUT;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_INITIAL_TIMEOUT_MS;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_RETRY_BASE_DELAY;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_RETRY_LIMIT;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_RETRY_MAX_DELAY;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_RETRY_UNLIMITED;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_ROOT;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_SESSION_TIMEOUT;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_SUPERVISOR_INTERVAL_MS;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_SUPERVISOR_MAX_FAILURES;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_SUPERVISOR_READ_TIMEOUT_MS;
import static com.dremio.service.coordinator.ClusterCoordinator.Options.ZK_TIMEOUT;
import static com.dremio.service.coordinator.zk.ZKClusterClient.ZK_LOST_HANDLER_MODULE_CLASS;

import com.dremio.common.config.SabotConfig;
import com.dremio.options.TypeValidators;
import com.dremio.service.coordinator.CoordinatorLostHandle;
import com.dremio.service.coordinator.ObservableConnectionLostHandler;
import java.util.function.Predicate;

/** ZKClusterConfig through SabotConfig */
public class ZKSabotConfig implements ZKClusterConfig {
  private final SabotConfig config;

  public ZKSabotConfig(SabotConfig sabotConfig) {
    this.config = sabotConfig;
  }

  @Override
  public int getConnectionTimeoutMilliSecs() {
    return config.getInt(ZK_TIMEOUT);
  }

  @Override
  public int getSessionTimeoutMilliSecs() {
    return config.getInt(ZK_SESSION_TIMEOUT);
  }

  @Override
  public String getRoot() {
    return config.getString(ZK_ROOT);
  }

  @Override
  public int getRetryBaseDelayMilliSecs() {
    return config.getMilliseconds(ZK_RETRY_BASE_DELAY).intValue();
  }

  @Override
  public long getInitialTimeoutMilliSecs() {
    return config.getLong(ZK_INITIAL_TIMEOUT_MS);
  }

  @Override
  public int getRetryMaxDelayMilliSecs() {
    return config.getMilliseconds(ZK_RETRY_MAX_DELAY).intValue();
  }

  @Override
  public boolean isRetryUnlimited() {
    return config.getBoolean(ZK_RETRY_UNLIMITED);
  }

  @Override
  public boolean isConnectionHandleEnabled() {
    return config.getBoolean(ZK_CONNECTION_HANDLE_ENABLED);
  }

  @Override
  public long getRetryLimit() {
    return config.getLong(ZK_RETRY_LIMIT);
  }

  @Override
  public long getElectionTimeoutMilliSecs() {
    return config.getMilliseconds(ZK_ELECTION_TIMEOUT);
  }

  @Override
  public long getElectionPollingMilliSecs() {
    return config.getMilliseconds(ZK_ELECTION_POLLING);
  }

  @Override
  public long getElectionDelayForLeaderCallbackMilliSecs() {
    return config.getMilliseconds(ZK_ELECTION_DELAY_FOR_LEADER_CALLBACK);
  }

  @Override
  public CoordinatorLostHandle getConnectionLostHandler() {
    return config.getInstance(
        ZK_LOST_HANDLER_MODULE_CLASS,
        CoordinatorLostHandle.class,
        ObservableConnectionLostHandler.OBSERVABLE_LOST_HANDLER.get());
  }

  @Override
  public String getClusterId() {
    return config.getString(CLUSTER_ID);
  }

  @Override
  public String getConnection() {
    return config.getString(ZK_CONNECTION);
  }

  @Override
  public Predicate<TypeValidators.BooleanValidator> getFeatureEvaluator() {
    return null;
  }

  @Override
  public int getZkSupervisorIntervalMilliSec() {
    return config.getInt(ZK_SUPERVISOR_INTERVAL_MS);
  }

  @Override
  public int getZkSupervisorReadTimeoutMilliSec() {
    return config.getInt(ZK_SUPERVISOR_READ_TIMEOUT_MS);
  }

  @Override
  public int getZkSupervisorMaxFailures() {
    return config.getInt(ZK_SUPERVISOR_MAX_FAILURES);
  }
}
