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
package com.dremio.service.flight;

import static com.dremio.config.DremioConfig.FLIGHT_USE_SESSION_SERVICE;

import com.dremio.common.AutoCloseables;
import com.dremio.config.DremioConfig;
import com.dremio.exec.rpc.ssl.SSLConfigurator;
import com.dremio.exec.work.protector.UserWorker;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.service.Service;
import com.dremio.service.flight.impl.FlightWorkManager.RunQueryResponseHandlerFactory;
import com.dremio.service.tokens.TokenManager;
import com.dremio.service.users.UserService;
import com.dremio.service.usersessions.UserSessionService;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.ssl.SSLConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.Optional;
import javax.inject.Provider;
import org.apache.arrow.flight.DremioFlightServer;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;

/** Service which manages a Flight endpoint. */
public class DremioFlightService implements Service {
  // Flight SSL configuration
  public static final String FLIGHT_SSL_PREFIX = "services.flight.ssl.";
  public static final String FLIGHT_SSL_ENABLED = FLIGHT_SSL_PREFIX + DremioConfig.SSL_ENABLED;

  // Flight authentication modes
  // Backwards compatible auth with ServerAuthHandler.
  public static final String FLIGHT_LEGACY_AUTH_MODE = "legacy.arrow.flight.auth";
  // New basic token auth with FlightServer middleware CallHeaderAuthenticator.
  public static final String FLIGHT_AUTH2_AUTH_MODE = "arrow.flight.auth2";

  public static final String FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE = "client-properties-middleware";
  public static final FlightServerMiddleware.Key<ServerCookieMiddleware>
      FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY =
          FlightServerMiddleware.Key.of(FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE);

  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(DremioFlightService.class);

  private final Provider<DremioConfig> configProvider;
  private final Provider<BufferAllocator> bufferAllocator;
  private final Provider<UserWorker> userWorkerProvider;
  private final Provider<TokenManager> tokenManagerProvider;
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<UserSessionService> userSessionServiceProvider;
  private final Provider<DremioFlightAuthProvider> authProvider;
  private final Provider<FlightRequestContextDecorator> requestContextDecoratorProvider;
  private final Provider<CredentialsService> credentialsServiceProvider;
  private final RunQueryResponseHandlerFactory runQueryResponseHandlerFactory;
  private final Provider<OptionValidatorListing> optionValidatorListingProvider;
  private final Provider<UserService> userServiceProvider;

  private DremioFlightSessionsManager dremioFlightSessionsManager;

  private volatile DremioFlightServer server;
  private BufferAllocator allocator;

  public DremioFlightService(
      Provider<DremioConfig> configProvider,
      Provider<BufferAllocator> bufferAllocator,
      Provider<UserWorker> userWorkerProvider,
      Provider<TokenManager> tokenManagerProvider,
      Provider<OptionManager> optionManagerProvider,
      Provider<UserSessionService> userSessionServiceProvider,
      Provider<DremioFlightAuthProvider> authProvider,
      Provider<FlightRequestContextDecorator> requestContextDecoratorProvider,
      Provider<CredentialsService> credentialsServiceProvider,
      Provider<OptionValidatorListing> optionValidatorListingProvider,
      Provider<UserService> userServiceProvider) {
    this(
        configProvider,
        bufferAllocator,
        userWorkerProvider,
        tokenManagerProvider,
        optionManagerProvider,
        userSessionServiceProvider,
        authProvider,
        requestContextDecoratorProvider,
        credentialsServiceProvider,
        RunQueryResponseHandlerFactory.DEFAULT,
        optionValidatorListingProvider,
        userServiceProvider);
  }

  @VisibleForTesting
  DremioFlightService(
      Provider<DremioConfig> configProvider,
      Provider<BufferAllocator> bufferAllocator,
      Provider<UserWorker> userWorkerProvider,
      Provider<TokenManager> tokenManagerProvider,
      Provider<OptionManager> optionManagerProvider,
      Provider<UserSessionService> userSessionServiceProvider,
      Provider<DremioFlightAuthProvider> authProvider,
      Provider<FlightRequestContextDecorator> requestContextDecoratorProvider,
      Provider<CredentialsService> credentialsServiceProvider,
      RunQueryResponseHandlerFactory runQueryResponseHandlerFactory,
      Provider<OptionValidatorListing> optionValidatorListingProvider,
      Provider<UserService> userServiceProvider) {
    this.configProvider = configProvider;
    this.bufferAllocator = bufferAllocator;
    this.tokenManagerProvider = tokenManagerProvider;
    this.userWorkerProvider = userWorkerProvider;
    this.optionManagerProvider = optionManagerProvider;
    this.runQueryResponseHandlerFactory = runQueryResponseHandlerFactory;
    this.userSessionServiceProvider = userSessionServiceProvider;
    this.authProvider = authProvider;
    this.requestContextDecoratorProvider = requestContextDecoratorProvider;
    this.credentialsServiceProvider = credentialsServiceProvider;
    this.optionValidatorListingProvider = optionValidatorListingProvider;
    this.userServiceProvider = userServiceProvider;
  }

  @Override
  public void start() throws Exception {
    Preconditions.checkArgument(
        server == null, "Flight Service should not be started more than once.");
    logger.info("Starting Flight Service");

    final DremioConfig config = configProvider.get();

    allocator =
        bufferAllocator.get().newChildAllocator("flight-service-allocator", 0, Long.MAX_VALUE);
    if (config.hasPath(FLIGHT_USE_SESSION_SERVICE)
        && config.getBoolean(FLIGHT_USE_SESSION_SERVICE)) {
      dremioFlightSessionsManager =
          new SessionServiceFlightSessionsManager(
              optionValidatorListingProvider.get(),
              optionManagerProvider.get(),
              tokenManagerProvider,
              userSessionServiceProvider);
    } else {
      dremioFlightSessionsManager =
          new TokenCacheFlightSessionManager(
              tokenManagerProvider,
              optionManagerProvider.get(),
              optionValidatorListingProvider.get());
    }

    final int port = config.getInt(DremioConfig.FLIGHT_SERVICE_PORT_INT);
    // Get the wildcard address which is usually 0.0.0.0.
    final String wildcardAddress = new InetSocketAddress(port).getHostName();
    final Location location = getLocation(wildcardAddress, port);

    DremioFlightServer.Builder builder =
        DremioFlightServer.builder()
            .location(location)
            .allocator(allocator)
            .automaticFlowControl(
                Boolean.getBoolean("dremio.services.arrow-flight.automatic-flow-control"))
            .maxInboundMessageSize(
                Integer.getInteger(
                    "dremio.services.arrow-flight.max-message-size", Integer.MAX_VALUE))
            .maxInboundMetadataSize(
                Integer.getInteger("dremio.services.arrow-flight.max-metadata-size", 0))
            .keepAliveTime(Integer.getInteger("dremio.services.arrow-flight.keep-alive-time", 240))
            .clientKeepAliveInterval(
                Integer.getInteger("dremio.services.arrow-flight.client-keep-alive-interval", 120))
            .idleTimeout(Integer.getInteger("dremio.services.arrow-flight.idle-timeout", 30))
            .maxConnectionAgeGrace(
                Integer.getInteger("dremio.services.arrow-flight.max-connection-age-grace", 0))
            .maxConnectionAge(
                Integer.getInteger("dremio.services.arrow-flight.max-connection-age", 0))
            .producer(
                new DremioFlightProducer(
                    Optional.empty(),
                    dremioFlightSessionsManager,
                    userWorkerProvider,
                    optionManagerProvider,
                    allocator,
                    requestContextDecoratorProvider,
                    runQueryResponseHandlerFactory,
                    userServiceProvider));

    builder.middleware(
        FLIGHT_CLIENT_PROPERTIES_MIDDLEWARE_KEY, new ServerCookieMiddleware.Factory());

    authProvider.get().addAuthHandler(builder, dremioFlightSessionsManager);

    if (config.getBoolean(FLIGHT_SSL_ENABLED)) {
      final SSLConfig sslConfig =
          getSSLConfig(
              config,
              new SSLConfigurator(config, credentialsServiceProvider, FLIGHT_SSL_PREFIX, "flight"));
      addTlsProperties(builder, sslConfig);
    }

    server = builder.build();
    server.start();

    logger.info("Flight Service started at {} on port {}.", config.getThisNode(), port);
  }

  @Override
  public void close() throws Exception {
    logger.info("Stopping Flight Service");
    AutoCloseables.close(server, allocator, dremioFlightSessionsManager);
    logger.info("Flight Service stopped");
  }

  /**
   * Create the Flight Location to be used in the server builder.
   *
   * @param address The address.
   * @param port The port.
   * @return The Location
   */
  protected Location getLocation(String address, int port) {
    if (!configProvider.get().getBoolean(FLIGHT_SSL_ENABLED)) {
      return Location.forGrpcInsecure(address, port);
    }
    return Location.forGrpcTls(address, port);
  }

  @VisibleForTesting
  DremioFlightServer getFlightServer() {
    return server;
  }

  /**
   * Create an SSL Configuration based on Dremio configuration settings.
   *
   * @param config The Dremio configuration.
   * @param sslConfigurator The SSL Configurator used to build the SSL configuration from.
   * @return an SSLConfig
   */
  @VisibleForTesting
  protected SSLConfig getSSLConfig(DremioConfig config, SSLConfigurator sslConfigurator) {
    try {
      // Disable peer validation for user-facing services, as we do with the web and client RPC SSL
      // configurations.
      return sslConfigurator
          .getSSLConfig(
              true, config.getThisNode(), InetAddress.getLocalHost().getCanonicalHostName())
          .get();
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Add TLS properties to the given builder and return the SSL Configuration used.
   *
   * @param builder The server builder.
   * @param sslConfig The SSL configuration.
   * @return The SSL configuration. This is returned to make the SSLConfig accessible by unit tests
   *     while avoiding storing the SSLConfig on the service.
   */
  private void addTlsProperties(DremioFlightServer.Builder builder, SSLConfig sslConfig) {
    try {
      final KeyStore keyStore = KeyStore.getInstance(sslConfig.getKeyStoreType());
      try (final InputStream keyStoreStream =
          Files.newInputStream(Paths.get(sslConfig.getKeyStorePath()))) {
        keyStore.load(keyStoreStream, sslConfig.getKeyStorePassword().toCharArray());
      }

      final Enumeration<String> aliases = keyStore.aliases();
      while (aliases.hasMoreElements()) {
        final String alias = aliases.nextElement();
        // TODO: DX-25342: We are assuming that the first alias representing a private key
        // is what the user wants to use for server encryption. This may not be how
        // other Dremio user-facing services behave w.r.t encryption. Standardize the
        // behavior.
        if (keyStore.isKeyEntry(alias)) {
          final Key key = keyStore.getKey(alias, sslConfig.getKeyPassword().toCharArray());
          final Certificate[] certificates = keyStore.getCertificateChain(alias);
          builder.useTls(toInputStream(certificates), toInputStream(key));
          return;
        }
      }
      throw new RuntimeException("Keystore did not have a private key.");
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static InputStream toInputStream(Key key) throws IOException {
    try (final StringWriter writer = new StringWriter();
        final JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
      pemWriter.writeObject(new PemObject("PRIVATE KEY", key.getEncoded()));
      pemWriter.flush();
      return new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static InputStream toInputStream(Certificate[] certificates) throws IOException {
    try (final StringWriter writer = new StringWriter();
        final JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
      for (Certificate certificate : certificates) {
        pemWriter.writeObject(certificate);
      }
      pemWriter.flush();
      return new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8));
    }
  }
}
