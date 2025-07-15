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
package com.dremio.service.grpc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Used to construct service configuration. Follows the configuration mentioned@
 * https://github.com/grpc/proposal/blob/master/A6-client-retries.md#retry-policy
 */
public class DefaultGrpcServiceConfigProvider {

  public static final List<String> SERVICE_NAMES =
      ImmutableList.of(
          "dremio.job.JobsService",
          "dremio.catalog.InformationSchemaService",
          "dremio.job.Chronicle",
          "dremio.maestroservice.MaestroService");

  public static Map<String, Object> getDefaultGrpcServiceConfig() {
    return getDefaultGrpcServiceConfig(SERVICE_NAMES);
  }

  /**
   * Gets the default service configuration for given list of service names
   *
   * @param serviceNames
   * @return
   */
  public static Map<String, Object> getDefaultGrpcServiceConfig(List<String> serviceNames) {
    return setGrpcServiceConfig(serviceNames, getDefaultRetryProperties());
  }

  /**
   * Gets the service configuration updated with given properties for given list of service names
   *
   * @param serviceNames
   * @param retryPropertiesMap
   * @return
   */
  public static Map<String, Object> getGrpcServiceConfig(
      List<String> serviceNames, Map<String, Object> retryPropertiesMap) {
    return setGrpcServiceConfig(serviceNames, retryPropertiesMap);
  }

  /**
   * Sets the given service configuration for given list of service names
   *
   * @param serviceNames
   * @param retryPropertiesMap
   * @return
   */
  private static Map<String, Object> setGrpcServiceConfig(
      List<String> serviceNames, Map<String, Object> retryPropertiesMap) {
    Map<String, Object> serviceConfig = Maps.newHashMap();
    List<Map<String, Object>> serviceConfigs = new ArrayList<>();
    for (String serviceName : serviceNames) {
      Map<String, Object> methodConfig = new HashMap<>();
      Map<String, Object> name = new HashMap<>();
      name.put("service", serviceName);
      methodConfig.put("name", Collections.<Object>singletonList(name));
      methodConfig.put("retryPolicy", retryPropertiesMap);
      serviceConfigs.add(methodConfig);
    }
    serviceConfig.put("methodConfig", serviceConfigs);
    return serviceConfig;
  }

  private static Map<String, Object> getDefaultRetryProperties() {
    Map<String, Object> retryPolicy = new HashMap<>();
    retryPolicy.put("maxAttempts", 10D);
    retryPolicy.put("initialBackoff", "1s");
    retryPolicy.put("maxBackoff", "30s");
    retryPolicy.put("backoffMultiplier", 2D);
    retryPolicy.put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"));
    return retryPolicy;
  }
}
