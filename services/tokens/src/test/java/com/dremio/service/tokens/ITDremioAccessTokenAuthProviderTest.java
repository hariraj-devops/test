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
package com.dremio.service.tokens;

import static com.dremio.test.DremioTest.DEFAULT_DREMIO_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.authenticator.AuthException;
import com.dremio.authenticator.AuthRequest;
import com.dremio.authenticator.AuthRequest.Resource;
import com.dremio.authenticator.AuthResult;
import com.dremio.common.server.WebServerInfoProvider;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.adapter.LegacyKVStoreProviderAdapter;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.options.OptionValue.Kind;
import com.dremio.options.impl.DefaultOptionManager;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.service.scheduler.LocalSchedulerService;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.tokens.jwks.SystemJWKSetManager;
import com.dremio.service.users.SimpleUser;
import com.dremio.service.users.User;
import com.dremio.service.users.UserResolver;
import com.dremio.service.users.proto.UID;
import com.dremio.services.configuration.ConfigurationStore;
import com.dremio.services.credentials.Cipher;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.services.credentials.CredentialsServiceImpl;
import com.dremio.services.credentials.SecretsCreator;
import com.dremio.services.credentials.SecretsCreatorImpl;
import com.dremio.services.credentials.SystemCipher;
import com.dremio.services.credentials.SystemSecretCredentialsProvider;
import com.dremio.test.DremioTest;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.threeten.extra.MutableClock;

class ITDremioAccessTokenAuthProviderTest {
  private static final String USERNAME = "testuser";
  private static final User TEST_USER =
      SimpleUser.newBuilder()
          .setUserName(USERNAME)
          .setUID(new UID(UUID.randomUUID().toString()))
          .build();

  @TempDir private Path securityFolder;

  private TokenManager legacyTokenManager;
  private DremioConfig dremioConfig;
  private CredentialsService credentialsService;
  private SystemJWKSetManager jwksManager;
  private TokenManagerImplV2 tokenManager;
  private MutableClock clock;
  private LegacyKVStoreProvider kvStoreProvider;
  private DremioAccessTokenAuthProvider authProvider;

  @BeforeEach
  public void setUp() throws Exception {
    final OptionManager optionManager =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionValidatorProvider(mock(OptionValidatorListingImpl.class))
            .withOptionManager(mock(DefaultOptionManager.class))
            .withOptionManager(mock(SystemOptionManager.class))
            .build();
    when(optionManager.getOption("token.jwt-access-token.enabled"))
        .thenReturn(
            OptionValue.createOption(
                Kind.BOOLEAN,
                OptionValue.OptionType.SYSTEM,
                "token.jwt-access-token.enabled",
                "true"));
    when(optionManager.getOption("token.release.leadership.ms"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.release.leadership.ms",
                "144000000"));
    when(optionManager.getOption("token.access-token-v2.expiration.minutes"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.access-token-v2.expiration.minutes",
                "60"));
    when(optionManager.getOption("token.jwks-key-rotation-period.days"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.jwks-key-rotation-period.days",
                "15"));

    this.kvStoreProvider = LegacyKVStoreProviderAdapter.inMemory(DremioTest.CLASSPATH_SCAN_RESULT);
    kvStoreProvider.start();

    this.dremioConfig =
        DEFAULT_DREMIO_CONFIG.withValue(
            DremioConfig.LOCAL_WRITE_PATH_STRING, securityFolder.toString());
    this.legacyTokenManager =
        new TokenManagerImpl(
            () -> kvStoreProvider,
            () -> mock(SchedulerService.class),
            () -> optionManager,
            true,
            dremioConfig);
    legacyTokenManager.start();

    final WebServerInfoProvider webServerInfoProvider =
        new WebServerInfoProvider() {
          @Override
          public String getClusterId() {
            return "test-cluster-id";
          }

          @Override
          public URI getIssuer() {
            return URI.create("https://localhost:9047");
          }
        };
    final UserResolver userResolver = mock(UserResolver.class);
    when(userResolver.getUser(TEST_USER.getUID())).thenReturn(TEST_USER);
    when(userResolver.getUser(USERNAME)).thenReturn(TEST_USER);

    this.clock = MutableClock.of(Instant.now(), ZoneOffset.UTC);

    this.credentialsService =
        CredentialsServiceImpl.newInstance(
            dremioConfig, Set.of(SystemSecretCredentialsProvider.class));
    final Cipher cipher = new SystemCipher(dremioConfig, credentialsService);
    final SecretsCreator secretsCreator =
        new SecretsCreatorImpl(() -> cipher, () -> credentialsService);
    final ConfigurationStore configurationStore = new ConfigurationStore(kvStoreProvider);

    this.jwksManager =
        new SystemJWKSetManager(
            clock,
            () -> webServerInfoProvider,
            () -> userResolver,
            () -> optionManager,
            () -> new LocalSchedulerService(1),
            () -> configurationStore,
            () -> secretsCreator,
            () -> credentialsService,
            dremioConfig);
    jwksManager.start();

    this.tokenManager =
        new TokenManagerImplV2(
            clock,
            () -> legacyTokenManager,
            () -> optionManager,
            () -> webServerInfoProvider,
            () -> userResolver,
            () -> jwksManager);
    tokenManager.start();

    this.authProvider = new DremioAccessTokenAuthProvider(() -> tokenManager);
    authProvider.start();
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (kvStoreProvider != null) {
      kvStoreProvider.close();
    }
    if (tokenManager != null) {
      tokenManager.close();
    }
    if (legacyTokenManager != null) {
      legacyTokenManager.close();
    }
    if (jwksManager != null) {
      jwksManager.close();
    }
    if (credentialsService != null) {
      credentialsService.close();
    }
    if (authProvider != null) {
      authProvider.close();
    }
  }

  @Test
  void validateJwt() throws AuthException {
    final TokenDetails createdToken = tokenManager.createJwt(USERNAME, "https://localhost:9047");
    final AuthResult authResult =
        authProvider.validate(
            AuthRequest.builder()
                .setTokenType(DremioAccessTokenAuthProvider.TOKEN_TYPE)
                .setUsername(USERNAME)
                .setToken(createdToken.token)
                .setResource(Resource.FLIGHT)
                .build());
    assertEquals(USERNAME, authResult.getUserName());
    // Validating the timestamp truncates the milliseconds from the timestamp, so divide by 1000 to
    // only compare seconds.
    assertEquals(createdToken.expiresAt / 1000, authResult.getExpiresAt().getTime() / 1000);
    assertEquals(DremioAccessTokenAuthProvider.TOKEN_TYPE, authResult.getTokenType());
  }
}
