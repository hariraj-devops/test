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
package com.dremio.exec.store.iceberg.viewdepoc;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.util.Tasks;

class PropertiesUpdate implements UpdateProperties {
  private final ViewOperations ops;
  private final Map<String, String> updates = Maps.newHashMap();
  private final Set<String> removals = Sets.newHashSet();
  private ViewVersionMetadata base;

  PropertiesUpdate(ViewOperations ops) {
    this.ops = ops;
    this.base = ops.current();
  }

  @Override
  public UpdateProperties set(String key, String value) {
    Preconditions.checkNotNull(key, "Key cannot be null");
    Preconditions.checkNotNull(value, "Value cannot be null");
    Preconditions.checkArgument(
        !removals.contains(key), "Cannot remove and update the same key: %s", key);

    updates.put(key, value);

    return this;
  }

  @Override
  public UpdateProperties remove(String key) {
    Preconditions.checkNotNull(key, "Key cannot be null");
    Preconditions.checkArgument(
        !updates.keySet().contains(key), "Cannot remove and update the same key: %s", key);

    removals.add(key);

    return this;
  }

  @Override
  public Map<String, String> apply() {
    this.base = ops.refresh();

    Map<String, String> newProperties = Maps.newHashMap();
    for (Map.Entry<String, String> entry : base.properties().entrySet()) {
      if (!removals.contains(entry.getKey())) {
        newProperties.put(entry.getKey(), entry.getValue());
      }
    }

    newProperties.putAll(updates);

    return newProperties;
  }

  @Override
  public void commit() {
    Tasks.foreach(ops)
        .retry(
            base.propertyAsInt(
                ViewProperties.COMMIT_NUM_RETRIES, ViewProperties.COMMIT_NUM_RETRIES_DEFAULT))
        .exponentialBackoff(
            base.propertyAsInt(
                ViewProperties.COMMIT_MIN_RETRY_WAIT_MS,
                ViewProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
            base.propertyAsInt(
                ViewProperties.COMMIT_MAX_RETRY_WAIT_MS,
                ViewProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
            base.propertyAsInt(
                ViewProperties.COMMIT_TOTAL_RETRY_TIME_MS,
                ViewProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
            2.0 /* exponential */)
        .onlyRetryOn(CommitFailedException.class)
        .run(
            taskOps -> {
              Map<String, String> newProperties = apply();
              ViewVersionMetadata updated = base.replaceProperties(newProperties);
              taskOps.commit(base, updated, new HashMap<>());
            });
  }
}
