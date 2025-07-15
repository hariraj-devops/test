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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PropertyLoaderTest {
  private ConfigProvider configProvider;
  private PropertyLoader propertyLoader;

  @BeforeEach
  void setUp() {
    configProvider = mock(ConfigProvider.class);
    propertyLoader = new PropertyLoader(configProvider);
  }

  @Test
  void testGetConfigValue_EnvVariableTakesPrecedence() {
    when(configProvider.getEnv("TEST_KEY")).thenReturn("envValue");
    when(configProvider.getProperty("TEST_KEY")).thenReturn("sysValue");

    assertEquals("envValue", propertyLoader.getConfigValue("TEST_KEY", "defaultValue"));
  }

  @Test
  void testGetConfigValue_UsesSystemPropertyIfNoEnvVar() {
    when(configProvider.getEnv("TEST_KEY")).thenReturn(null);
    when(configProvider.getProperty("TEST_KEY")).thenReturn("sysValue");

    assertEquals("sysValue", propertyLoader.getConfigValue("TEST_KEY", "defaultValue"));
  }

  @Test
  void testGetConfigValue_UsesDefaultIfNoEnvOrSystemProperty() {
    when(configProvider.getEnv("TEST_KEY")).thenReturn(null);
    when(configProvider.getProperty("TEST_KEY")).thenReturn(null);

    assertEquals("defaultValue", propertyLoader.getConfigValue("TEST_KEY", "defaultValue"));
  }
}
