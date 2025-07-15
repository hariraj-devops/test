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
package com.dremio.service.conduit.server;

import com.dremio.context.RequestContext;
import com.dremio.service.Service;
import com.dremio.service.grpc.ContextualizedClientInterceptor;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import javax.inject.Inject;
import javax.inject.Provider;

/** A channel to the in-process conduit server */
public class ConduitInProcessChannelProvider implements Service {
  private ManagedChannel inProcessChannel;
  private final String inProcessServerName;
  private final Provider<RequestContext> requestContextProvider;

  @Inject
  public ConduitInProcessChannelProvider(
      String inProcessServerName, Provider<RequestContext> requestContextProvider) {
    this.inProcessServerName = inProcessServerName;
    this.requestContextProvider = requestContextProvider;
  }

  @Override
  public void start() throws Exception {
    inProcessChannel =
        InProcessChannelBuilder.forName(inProcessServerName)
            .usePlaintext()
            .intercept(
                ContextualizedClientInterceptor.buildSingleTenantClientInterceptorWithDefaults(
                    requestContextProvider))
            .build();
  }

  public Channel getInProcessChannelToConduit() {
    return inProcessChannel;
  }

  @Override
  public void close() throws Exception {
    if (inProcessChannel != null) {
      inProcessChannel.shutdown();
    }
  }
}
