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

import static com.dremio.telemetry.api.metrics.Metrics.MetricServletFactory.createMetricsServlet;

import com.dremio.common.liveness.LiveHealthMonitor;
import com.dremio.config.DremioConfig;
import com.dremio.exec.server.NodeRegistration;
import com.dremio.exec.work.protector.ForemenWorkManager;
import com.dremio.service.Service;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Provider;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * Responds to HTTP requests on a special 'liveness' port bound to the loopback interface In the
 * future, can be expanded to check on the health of the internal workings of the system
 */
public class LivenessService implements Service {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(LivenessService.class);

  private static final int ACCEPT_QUEUE_BACKLOG = 1;
  private static final int NUM_ACCEPTORS = 1;
  private static final int NUM_SELECTORS = 1;
  private static final int NUM_REQUEST_THREADS = 1;
  private static final int NUM_USER_THREADS = 1;
  private static final int MAX_THREADS =
      NUM_ACCEPTORS + NUM_SELECTORS + NUM_REQUEST_THREADS + NUM_USER_THREADS;

  private final DremioConfig config;
  private final boolean livenessEnabled;

  private final Server embeddedLivenessJetty = new Server(new QueuedThreadPool(MAX_THREADS));
  private final Provider<NodeRegistration> nodeRegistrationProvider;
  private final Provider<ForemenWorkManager> foremenWorkManagerProvider;
  private int livenessPort;
  private long pollCount;
  private final List<LiveHealthMonitor> healthMonitors = new ArrayList<>();

  public LivenessService(
      DremioConfig config,
      Provider<NodeRegistration> nodeRegistrationProvider,
      Provider<ForemenWorkManager> foremenWorkManagerProvider) {
    this.config = config;
    this.nodeRegistrationProvider = nodeRegistrationProvider;
    this.foremenWorkManagerProvider = foremenWorkManagerProvider;
    this.livenessEnabled = config.getBoolean(DremioConfig.LIVENESS_ENABLED);
    pollCount = 0;
  }

  /**
   * adds a health monitor check to the list of checks
   *
   * @param healthMonitor
   */
  public void addHealthMonitor(LiveHealthMonitor healthMonitor) {
    Preconditions.checkArgument(healthMonitor != null, "Health monitor cannot be null");
    this.healthMonitors.add(healthMonitor);
  }

  @Override
  public void start() throws Exception {
    if (!livenessEnabled) {
      logger.info("Liveness service disabled");
      return;
    }
    final ServerConnector serverConnector =
        new ServerConnector(embeddedLivenessJetty, NUM_ACCEPTORS, NUM_SELECTORS);
    serverConnector.setPort(config.getInt(DremioConfig.LIVENESS_PORT));
    serverConnector.setHost(config.getString(DremioConfig.LIVENESS_HOST));
    serverConnector.setAcceptQueueSize(ACCEPT_QUEUE_BACKLOG);
    embeddedLivenessJetty.addConnector(serverConnector);
    ServletContextHandler contextHandler =
        new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    contextHandler.setContextPath("/");
    embeddedLivenessJetty.setHandler(contextHandler);

    contextHandler.addServlet(new ServletHolder(new LivenessServlet()), "/live");
    contextHandler.addServlet(new ServletHolder(createMetricsServlet()), "/metrics");
    contextHandler.addServlet(new ServletHolder(new QuiesceServlet()), "/quiesce");

    contextHandler.addFilter(
        new FilterHolder(new DisableHttpMethodsFilter(ImmutableSet.of("TRACE", "OPTIONS"))),
        "/*",
        EnumSet.of(DispatcherType.REQUEST));
    embeddedLivenessJetty.start();
    livenessPort = serverConnector.getLocalPort();
    logger.info("Started liveness service on port {}", livenessPort);
  }

  @Override
  public void close() throws Exception {
    if (!livenessEnabled) {
      return;
    }
    embeddedLivenessJetty.stop();
  }

  public boolean isLivenessServiceEnabled() {
    return livenessEnabled;
  }

  public long getPollCount() {
    return pollCount;
  }

  public int getLivenessPort() {
    return livenessPort;
  }

  /** Servlet used to respond to liveness requests. Simply returns a 'yes, I'm alive' */
  public class LivenessServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      pollCount++;
      for (LiveHealthMonitor hm : healthMonitors) {
        if (!hm.isHealthy()) {
          // return error code 500
          response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
          return;
        }
      }
      response.setStatus(HttpServletResponse.SC_OK);
    }
  }

  public class QuiesceServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
      logger.info("Received request to quiesce node");
      try {
        foremenWorkManagerProvider.get().stopAcceptingQueries();
        nodeRegistrationProvider.get().close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

      var activeQueries = foremenWorkManagerProvider.get().getActiveQueryCount();
      response.setStatus(
          activeQueries == 0 ? HttpServletResponse.SC_OK : HttpServletResponse.SC_BAD_REQUEST);
      response.setContentType("application/json");
      response.getWriter().printf("{\"active_queries\": %d}", activeQueries);
    }
  }
}
