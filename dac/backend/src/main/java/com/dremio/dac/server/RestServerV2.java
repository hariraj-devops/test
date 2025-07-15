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
import com.dremio.common.perf.Timer.TimedBlock;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.dac.annotations.RestApiServer;
import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.explore.bi.PowerBIMessageBodyGenerator;
import com.dremio.dac.explore.bi.QlikAppMessageBodyGenerator;
import com.dremio.dac.explore.bi.TableauMessageBodyGenerator;
import com.dremio.services.nessie.restjavax.converters.ContentKeyParamConverterProvider;
import com.dremio.services.nessie.restjavax.converters.NamespaceParamConverterProvider;
import com.dremio.services.nessie.restjavax.converters.ReferenceTypeParamConverterProvider;
import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import javax.inject.Inject;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.mvc.freemarker.FreemarkerMvcFeature;

/** Dremio Rest Server. */
@RestApiServer(pathSpec = "/apiv2/*", tags = "oss")
public class RestServerV2 extends ResourceConfig {
  public static final String FIRST_TIME_API_ENABLE = "dac.rest.config.first-time.enable";
  public static final String TEST_API_ENABLE = "dac.rest.config.test-resources.enable";
  public static final String ERROR_STACKTRACE_ENABLE = "dac.rest.config.stacktrace.enable";
  public static final String DAC_AUTH_FILTER_DISABLE = "dac.rest.config.auth.disable";
  public static final String EE_DAC_AUTH_FILTER_DISABLE = "dac.rest.config.ee-auth.disable";
  public static final String JSON_PRETTYPRINT_ENABLE = "dac.rest.config.json-prettyprint.enable";

  @Inject
  public RestServerV2(DACConfig dacConfig, ScanResult result) {
    try (TimedBlock b = Timer.time("new RestServer")) {
      init(dacConfig, result);
    }
  }

  protected void init(DACConfig dacConfig, ScanResult result) {
    // PROVIDERS //
    // We manually registered provider needed for nessie-as-a-source
    register(ContentKeyParamConverterProvider.class);
    register(NamespaceParamConverterProvider.class);
    register(ReferenceTypeParamConverterProvider.class);

    // FILTERS //
    register(JSONPrettyPrintFilter.class);
    register(MediaTypeFilter.class);

    // RESOURCES //
    for (Class<?> resource : result.getAnnotatedClasses(RestResource.class)) {
      register(resource);
    }

    // Enable request contextualization.
    register(new AuthenticationBinder());

    // FEATURES
    property(FreemarkerMvcFeature.TEMPLATE_OBJECT_FACTORY, getFreemarkerConfiguration());
    register(FreemarkerMvcFeature.class);
    register(MultiPartFeature.class);
    register(FirstTimeFeature.class);
    register(DACAuthFilterFeature.class);
    register(DACExceptionMapperFeature.class);
    register(DACJacksonJaxbJsonFeature.class);
    register(JSONJobDataFilter.class);
    register(TestResourcesFeature.class);

    // LISTENERS //
    register(TimingApplicationEventListener.class);

    // EXCEPTION MAPPERS //
    register(RestApiJsonParseExceptionMapper.class);
    register(RestApiJsonMappingExceptionMapper.class);

    // BODY WRITERS //
    registerBIToolMessageBodyGenerators();

    // PROPERTIES //
    property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, "true");
    property(RestServerV2.ERROR_STACKTRACE_ENABLE, dacConfig.sendStackTraceToClient);
    property(RestServerV2.TEST_API_ENABLE, dacConfig.allowTestApis);
    property(RestServerV2.FIRST_TIME_API_ENABLE, dacConfig.isInternalUserAuth());

    final String disableMoxy =
        PropertiesHelper.getPropertyNameForRuntime(
            CommonProperties.MOXY_JSON_FEATURE_DISABLE, getConfiguration().getRuntimeType());
    property(disableMoxy, true);
  }

  protected void registerBIToolMessageBodyGenerators() {
    //  BODY WRITERS //
    register(QlikAppMessageBodyGenerator.class);
    register(PowerBIMessageBodyGenerator.class);

    registerTableauMessageBodyGenerator();
  }

  protected void registerTableauMessageBodyGenerator() {
    register(TableauMessageBodyGenerator.class);
    property(TableauMessageBodyGenerator.CUSTOMIZATION_ENABLED, false);
  }

  private Configuration getFreemarkerConfiguration() {
    Configuration configuration = new Configuration(Configuration.VERSION_2_3_26);
    configuration.setOutputFormat(HTMLOutputFormat.INSTANCE);
    configuration.setClassForTemplateLoading(getClass(), "/");
    return configuration;
  }
}
