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
package com.dremio.exec.rpc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

class TestRpcExceptionHandler extends TestChannelExceptionHandler {

  private RemoteConnection connection;

  @Override
  @BeforeEach
  void setup() {
    super.setup();
    connection = mock(RemoteConnection.class);
    when(connection.getName()).thenReturn("test-connection");
  }

  @Override
  @AfterEach
  void tearDown() {
    super.tearDown();
    Mockito.reset(connection);
  }

  @Override
  protected ChannelExceptionHandler createChannelExceptionHandler() {
    return new RpcExceptionHandler<>(connection);
  }
}
