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
package com.dremio.services.pubsub.nats;

import io.nats.client.Options;

public class NatsConnectionOptionsProvider {

  public static Options getOptions(String natsServerUrl) {
    return new Options.Builder()
        .server(natsServerUrl)
        // by default NATS tries to reconnect up to 60 times
        // the default timeout between reconnections is 2 seconds
        // The total time it waits would be 2 minutes * 60 = 120 seconds
        // We set it to have unlimited reconnect attempts (-1 for infinite retries)
        .maxReconnects(-1)
        .build();
  }
}
