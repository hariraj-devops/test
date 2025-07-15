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
package com.dremio.services.pubsub.nats.integration;

import com.dremio.testcontainers.nats.NatsContainer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.io.IOException;
import org.testcontainers.containers.GenericContainer;

public class NatsTestStarter {
  private NatsContainer natsContainer;
  private Connection natsConnection;
  private String natsUrl;

  public NatsTestStarter() {}

  public void setUp() throws IOException, InterruptedException {
    natsContainer = new NatsContainer();
    natsContainer.start();

    natsUrl =
        String.format("nats://%s:%d", natsContainer.getHost(), natsContainer.getMappedPort(4222));
    natsConnection = Nats.connect(natsUrl);
  }

  public void tearDown() {
    if (natsConnection != null) {
      try {
        natsConnection.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (natsContainer != null) {
      natsContainer.stop();
    }
  }

  public GenericContainer<?> getNatsContainer() {
    return natsContainer;
  }

  public Connection getNatsConnection() {
    return natsConnection;
  }

  public String getNatsUrl() {
    return natsUrl;
  }
}
