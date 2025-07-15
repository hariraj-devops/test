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
package com.dremio.services.pubsub.nats.utils;

public class PropertyLoader {

  private final ConfigProvider configProvider;

  public PropertyLoader(ConfigProvider configProvider) {
    this.configProvider = configProvider;
  }

  public String getConfigValue(String key, String defaultValue) {
    String envValue = configProvider.getEnv(key);
    if (envValue != null && !envValue.isEmpty()) {
      return envValue;
    }

    String systemValue = configProvider.getProperty(key);
    if (systemValue != null && !systemValue.isEmpty()) {
      return systemValue;
    }

    return defaultValue;
  }
}
