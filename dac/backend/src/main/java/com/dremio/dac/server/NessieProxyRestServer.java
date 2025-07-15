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
package com.dremio.dac.server;

import com.dremio.common.perf.Timer;
import com.dremio.dac.annotations.RestApiServer;
import com.dremio.dac.resource.NessieSourceResource;
import com.dremio.dac.resource.NessieTestSourceResource;
import com.dremio.dac.service.errors.NotFoundExceptionMapper;
import com.dremio.services.nessie.proxy.ProxyExceptionMapper;
import com.dremio.services.nessie.proxy.ProxyRuntimeExceptionMapper;
import com.dremio.services.nessie.restjavax.converters.ContentKeyParamConverterProvider;
import com.dremio.services.nessie.restjavax.converters.NamespaceParamConverterProvider;
import com.dremio.services.nessie.restjavax.converters.ReferenceTypeParamConverterProvider;
import com.dremio.services.nessie.restjavax.exceptions.ConstraintViolationExceptionMapper;
import com.dremio.services.nessie.restjavax.exceptions.NessieExceptionMapper;
import javax.inject.Inject;
import org.glassfish.jersey.message.GZipEncoder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.EncodingFilter;

@RestApiServer(pathSpec = "/nessie-proxy/*", tags = "oss")
public class NessieProxyRestServer extends ResourceConfig {

  @Inject
  public NessieProxyRestServer() {
    try (Timer.TimedBlock b = Timer.time("new ProxyRestServer")) {
      init();
    }
  }

  protected void init() {
    // FILTERS //
    register(JSONPrettyPrintFilter.class);

    // Enable request contextualization.
    register(new AuthenticationBinder());

    // FEATURES
    register(DACAuthFilterFeature.class);
    register(DACJacksonJaxbJsonFeature.class);

    // LISTENERS //
    register(TimingApplicationEventListener.class);

    // Nessie
    if (Boolean.getBoolean("nessie.source.resource.testing.enabled")) {
      register(NessieTestSourceResource.class);
    } else {
      register(NessieSourceResource.class);
    }
    register(ContentKeyParamConverterProvider.class);
    register(NamespaceParamConverterProvider.class);
    register(ReferenceTypeParamConverterProvider.class);
    register(ConstraintViolationExceptionMapper.class, 10);
    register(NessieExceptionMapper.class, 10);
    register(NotFoundExceptionMapper.class);
    register(ProxyExceptionMapper.class, 10);
    register(ProxyRuntimeExceptionMapper.class, 10);
    register(EncodingFilter.class);
    register(GZipEncoder.class);
  }
}
