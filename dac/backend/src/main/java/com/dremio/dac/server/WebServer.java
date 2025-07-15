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

import com.dremio.dac.annotations.RestApiServer;
import com.dremio.dac.daemon.DremioBinder;
import com.dremio.dac.server.socket.SocketServlet;
import com.dremio.options.OptionManager;
import com.dremio.service.Service;
import com.dremio.service.SingletonRegistry;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.tokens.TokenManager;
import com.dremio.services.credentials.CredentialsService;
import com.google.common.annotations.VisibleForTesting;
import java.security.KeyStore;
import java.util.EnumSet;
import javax.inject.Provider;
import javax.servlet.DispatcherType;
import javax.ws.rs.container.DynamicFeature;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

/** Dremio web server. */
public class WebServer implements Service {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WebServer.class);

  /** Specific media types used by Dremio */
  public static final class MediaType extends javax.ws.rs.core.MediaType {
    public MediaType(String type, String subType) {
      super(type, subType);
    }

    /** Qlik Sense app media type string */
    public static final String TEXT_PLAIN_QLIK_APP = "text/plain+qlik-app";

    /** Qlik Sense app media type */
    public static final MediaType TEXT_PLAIN_QLIK_APP_TYPE =
        new MediaType("text", "plain+qlik-app");

    /** Tableau TDS media type string */
    public static final String APPLICATION_TDS = "application/tds";

    /** Tableau TDS media type */
    public static final MediaType APPLICATION_TDS_TYPE = new MediaType("application", "tds");

    /** Tableau TDS media type string (for Native Drill Connector) */
    public static final String APPLICATION_TDS_DRILL = "application/tds+drill";

    /** Tableau TDS media type (for Native Drill Connector) */
    public static final MediaType APPLICATION_TDS_DRILL_TYPE =
        new MediaType("application", "tds+drill");

    /** Power BI DS media type */
    public static final String APPLICATION_PBIDS = "application/pbids";

    /** CSV media type string */
    public static final String TEXT_CSV = "text/csv";
  }

  /** Dremio hostname to use for response (usually match the Host header field). */
  public static final String X_DREMIO_HOSTNAME = "x-dremio-hostname";

  /**
   * Dremio header that forces numbers to be returned as strings for job data
   *
   * <p>That header is also hardcoded in {@link /dac/ui/src/utils/apiUtils/apiUtils.js} for client
   * side
   */
  public static final String X_DREMIO_JOB_DATA_NUMBERS_AS_STRINGS =
      "x-dremio-job-data-number-format";

  public static final String X_DREMIO_JOB_DATA_NUMBERS_AS_STRINGS_SUPPORTED_VALUE =
      "number-as-string";

  // After being created in the start() method a reference to this
  // ResourceConfig for the rest server is maintained so that
  // resources from the SabotNode can be directly injected into it
  // after the start up completes
  private final SingletonRegistry registry;
  private final Provider<RestApiServerFactory> restApiServerFactoryProvider;
  private final DremioServer server;
  private final DACConfig config;
  private final Provider<CredentialsService> credentialsServiceProvider;
  private final DremioBinder dremioBinder;
  private final String uiType;

  public WebServer(
      SingletonRegistry registry,
      DACConfig config,
      Provider<CredentialsService> credentialsServiceProvider,
      Provider<RestApiServerFactory> restApiServerFactoryProvider,
      Provider<DremioServer> server,
      DremioBinder dremioBinder,
      String uiType) {
    this.registry = registry;
    this.restApiServerFactoryProvider = restApiServerFactoryProvider;
    this.config = config;
    this.credentialsServiceProvider = credentialsServiceProvider;
    this.dremioBinder = dremioBinder;
    this.uiType = uiType;
    this.server = server.get();
  }

  public AccessLogFilter getAccessLogFilter() {
    return server.getAccessLogFilter();
  }

  @Override
  public void start() throws Exception {
    server.startDremioServer(
        registry, config, credentialsServiceProvider, uiType, this::registerEndpoints);
  }

  protected void registerEndpoints(ServletContextHandler servletContextHandler) {
    // security header filters
    SecurityHeadersFilter securityHeadersFilter =
        new SecurityHeadersFilter(registry.provider(OptionManager.class));
    servletContextHandler.addFilter(
        new FilterHolder(securityHeadersFilter), "/*", EnumSet.of(DispatcherType.REQUEST));

    // Generic Response Headers filter for api responses
    servletContextHandler.addFilter(
        GenericResponseHeadersFilter.class.getName(),
        "/apiv2/*",
        EnumSet.of(DispatcherType.REQUEST));
    servletContextHandler.addFilter(
        GenericResponseHeadersFilter.class.getName(), "/api/*", EnumSet.of(DispatcherType.REQUEST));

    // WebSocket API
    final SocketServlet servlet =
        new SocketServlet(
            registry.lookup(JobsService.class),
            registry.lookup(TokenManager.class),
            registry.provider(OptionManager.class));
    final ServletHolder wsHolder = new ServletHolder(servlet);
    wsHolder.setInitOrder(1);
    servletContextHandler.addServlet(wsHolder, "/apiv2/socket");

    // Rest APIs.
    int initOrder = 2;
    for (ResourceConfig restServer : restApiServerFactoryProvider.get().load()) {
      RestApiServer annotation = restServer.getClass().getAnnotation(RestApiServer.class);
      if (annotation == null) {
        throw new RuntimeException(
            String.format(
                "Server class %s is not annotated with %s",
                restServer.getClass(), RestApiServer.class.getName()));
      }
      restServer.register(dremioBinder);
      restServer.register(
          (DynamicFeature)
              (resourceInfo, context) -> context.register(DremioServer.TracingFilter.class));

      // Add servlet.
      ServletHolder restHolder = new ServletHolder(new ServletContainer(restServer));
      restHolder.setInitOrder(initOrder++);
      servletContextHandler.addServlet(restHolder, annotation.pathSpec());
      logger.info(
          "Added servlet for server class {} at pathSpec {}",
          restServer.getClass(),
          annotation.pathSpec());
    }
  }

  public int getPort() {
    return server.getPort();
  }

  /**
   * Get the trust store containing the self-signed certificate (not the private key).
   *
   * @return trust store, null if the cert is not generated by the server
   */
  @VisibleForTesting
  KeyStore getTrustStore() {
    return server.getTrustStore();
  }

  @Override
  public void close() throws Exception {
    server.close();
  }
}
