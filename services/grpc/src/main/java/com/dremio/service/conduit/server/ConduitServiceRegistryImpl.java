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

import com.dremio.service.grpc.CloseableBindableService;
import com.google.common.base.Preconditions;
import io.grpc.BindableService;
import io.grpc.HandlerRegistry;
import io.grpc.ServerServiceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Implementation of {@link ConduitServiceRegistry}. */
public class ConduitServiceRegistryImpl implements ConduitServiceRegistry {

  private final List<BindableService> serviceList;
  private final List<CloseableBindableService> closeableServiceList;
  private final List<ServerServiceDefinition> serverServiceDefinitionList;
  private Optional<HandlerRegistry> fallbackHandler = Optional.empty();

  public ConduitServiceRegistryImpl() {
    this.serviceList = new ArrayList<>();
    this.closeableServiceList = new ArrayList<>();
    this.serverServiceDefinitionList = new ArrayList<>();
  }

  @Override
  public void registerService(BindableService bindableService) {
    serviceList.add(bindableService);
  }

  @Override
  public void registerService(CloseableBindableService bindableService) {
    closeableServiceList.add(bindableService);
  }

  @Override
  public void registerServerService(ServerServiceDefinition serverServiceDefinition) {
    serverServiceDefinitionList.add(serverServiceDefinition);
  }

  @Override
  public void registerFallbackHandler(HandlerRegistry handlerRegistry) {
    Preconditions.checkState(
        !fallbackHandler.isPresent(), "Fallback handler is already registered.");
    fallbackHandler = Optional.of(handlerRegistry);
  }

  List<BindableService> getServiceList() {
    return serviceList;
  }

  List<CloseableBindableService> getCloseableServiceList() {
    return closeableServiceList;
  }

  List<ServerServiceDefinition> getServerServiceDefinitionList() {
    return serverServiceDefinitionList;
  }

  public Optional<HandlerRegistry> getFallbackHandler() {
    return fallbackHandler;
  }
}
