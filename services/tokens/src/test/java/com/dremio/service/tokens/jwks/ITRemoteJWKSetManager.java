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
package com.dremio.service.tokens.jwks;

import static com.dremio.test.DremioTest.DEFAULT_DREMIO_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.util.Base64;
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
import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.jwt.ImmutableJWTClaims;
import com.dremio.service.tokens.jwt.JWTClaims;
import com.dremio.service.tokens.jwt.JWTProcessorFactory;
import com.dremio.service.tokens.proto.RemoteJWKSGrpc;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.threeten.extra.MutableClock;

public class ITRemoteJWKSetManager {
  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  @Rule public TemporaryFolder securityFolder = new TemporaryFolder();

  private static final String USERNAME = "testuser";
  private static final User TEST_USER =
      SimpleUser.newBuilder()
          .setUserName(USERNAME)
          .setUID(new UID(UUID.randomUUID().toString()))
          .build();
  private static final String CLUSTER_ID = "test-cluster-id";
  private static final String ISSUER_URL = "https://localhost:9047";
  private static final int JWT_EXPIRATION_MINUTES = 60;
  private static final int JWKS_ROTATION_PERIOD_DAYS = 15;

  private ManagedChannel channelToMaster;
  private LegacyKVStoreProvider kvStoreProvider;
  private MutableClock clock;
  private RemoteJWKSetManager remoteJWKSetManager;
  private SystemJWKSetManager systemJWKSetManager;

  @Before
  public void before() throws Exception {
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
    when(optionManager.getOption("token.access-token-v2.expiration.minutes"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.access-token-v2.expiration.minutes",
                "" + JWT_EXPIRATION_MINUTES));
    when(optionManager.getOption("token.jwks-key-rotation-period.days"))
        .thenReturn(
            OptionValue.createOption(
                OptionValue.Kind.LONG,
                OptionValue.OptionType.SYSTEM,
                "token.jwks-key-rotation-period.days",
                "" + JWKS_ROTATION_PERIOD_DAYS));
    final WebServerInfoProvider webServerInfoProvider =
        new WebServerInfoProvider() {
          @Override
          public String getClusterId() {
            return CLUSTER_ID;
          }

          @Override
          public URI getIssuer() {
            return URI.create(ISSUER_URL);
          }
        };
    final UserResolver userResolver = mock(UserResolver.class);
    when(userResolver.getUser(TEST_USER.getUID())).thenReturn(TEST_USER);
    when(userResolver.getUser(USERNAME)).thenReturn(TEST_USER);

    final DremioConfig dremioConfig =
        DEFAULT_DREMIO_CONFIG.withValue(
            DremioConfig.LOCAL_WRITE_PATH_STRING, securityFolder.newFolder().getAbsolutePath());

    this.clock = MutableClock.of(Instant.now(), ZoneOffset.UTC);

    final CredentialsService credentialsService =
        CredentialsServiceImpl.newInstance(
            dremioConfig, Set.of(SystemSecretCredentialsProvider.class));
    final Cipher cipher = new SystemCipher(dremioConfig, credentialsService);
    final SecretsCreator secretsCreator =
        new SecretsCreatorImpl(() -> cipher, () -> credentialsService);
    this.kvStoreProvider = LegacyKVStoreProviderAdapter.inMemory(DremioTest.CLASSPATH_SCAN_RESULT);
    kvStoreProvider.start();
    final ConfigurationStore configurationStore = new ConfigurationStore(kvStoreProvider);

    this.systemJWKSetManager =
        spy(
            new SystemJWKSetManager(
                clock,
                () -> webServerInfoProvider,
                () -> userResolver,
                () -> optionManager,
                () -> new LocalSchedulerService(1),
                () -> configurationStore,
                () -> secretsCreator,
                () -> credentialsService,
                dremioConfig));
    systemJWKSetManager.start();

    final String serverName = InProcessServerBuilder.generateName();
    final RemoteJWKSGrpc.RemoteJWKSImplBase jwksService =
        new RemoteJWKSGrpcImpl(() -> systemJWKSetManager);
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(jwksService)
            .build()
            .start());
    this.channelToMaster =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());

    final JWTProcessorFactory jwtProcessorFactory =
        new JWTProcessorFactory(() -> webServerInfoProvider);
    this.remoteJWKSetManager =
        new RemoteJWKSetManager(
            clock, () -> channelToMaster, () -> userResolver, () -> jwtProcessorFactory);
    remoteJWKSetManager.start();
  }

  @After
  public void afterEach() throws Exception {
    if (systemJWKSetManager != null) {
      systemJWKSetManager.close();
    }
    if (remoteJWKSetManager != null) {
      remoteJWKSetManager.close();
    }
  }

  @Test
  public void remoteValidateJwt() throws ParseException {
    final Date jwtExpiration =
        new Date(clock.instant().plus(Duration.ofMinutes(JWT_EXPIRATION_MINUTES)).toEpochMilli());
    final Date now = new Date(clock.instant().toEpochMilli());
    final JWTClaims jwtClaims =
        new ImmutableJWTClaims.Builder()
            .setJWTId(UUID.randomUUID().toString())
            .setIssuer(ISSUER_URL)
            .setSubject(TEST_USER.getUID().getId())
            .setAudience(CLUSTER_ID)
            .setExpirationTime(jwtExpiration)
            .setNotBeforeTime(now)
            .setIssueTime(now)
            .build();
    final String jwt = systemJWKSetManager.getSigner().sign(jwtClaims);

    final TokenDetails validatedToken = remoteJWKSetManager.getValidator().validate(jwt);
    assertEquals(USERNAME, validatedToken.username);
    // Nimbus truncates milliseconds in dates when parsing JWTs, only using the epoch seconds
    // value.
    assertTrue(Math.abs(jwtExpiration.getTime() - validatedToken.expiresAt) < 1000);
    assertEquals(List.of("dremio.all"), validatedToken.getScopes());
  }

  @Test
  public void remoteValidateJwtBustsCacheOnNewKeyId() throws Exception {
    final JWKS expectedOriginalJwks = systemJWKSetManager.getPublicJWKS();
    // Reset the number of calls to methods on the SystemJWKSManager spy so we only track calls to
    // getPublicJWKS made through RemoteJWKSetManager.
    reset(systemJWKSetManager);

    assertEquals(1, expectedOriginalJwks.getKeys().size());
    final String originalKeyId = expectedOriginalJwks.getKeys().get(0).getKeyId();

    final Date expiresAt =
        new Date(clock.instant().plus(Duration.ofMinutes(JWT_EXPIRATION_MINUTES)).toEpochMilli());
    final Date issuedAt = new Date(clock.instant().toEpochMilli());
    final JWTClaims jwtClaims =
        new ImmutableJWTClaims.Builder()
            .setJWTId(UUID.randomUUID().toString())
            .setIssuer(ISSUER_URL)
            .setSubject(TEST_USER.getUID().getId())
            .setAudience(CLUSTER_ID)
            .setExpirationTime(expiresAt)
            .setNotBeforeTime(issuedAt)
            .setIssueTime(issuedAt)
            .build();
    final String testToken1 = systemJWKSetManager.getSigner().sign(jwtClaims);
    assertEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(testToken1),
        "The JWT should have been signed with the original key");

    final TokenDetails validatedToken1 = remoteJWKSetManager.getValidator().validate(testToken1);
    assertEquals(USERNAME, validatedToken1.username);

    // Create a new token with a new JWT ID
    final String testToken2 =
        systemJWKSetManager
            .getSigner()
            .sign(
                new ImmutableJWTClaims.Builder()
                    .from(jwtClaims)
                    .setJWTId(UUID.randomUUID().toString())
                    .build());
    assertEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(testToken2),
        "The JWT should have been signed with the original key");

    final TokenDetails validatedToken2 = remoteJWKSetManager.getValidator().validate(testToken2);
    assertEquals(USERNAME, validatedToken2.username);

    // The RemoteJWKSetManager should only need to fetch the public JWKS from the master coordinator
    // once to validate two tokens that were signed with the same key.
    verify(systemJWKSetManager, times(1)).getPublicJWKS();

    // Advance time passed the rotation period for the original generated key so we rotate a new key
    // in when enforcing a consistent JWKS state.
    clock.add(Duration.ofDays(JWKS_ROTATION_PERIOD_DAYS).plusSeconds(1));
    systemJWKSetManager.enforceConsistentJwksState();

    // Create a new signed token that will be signed by the newly rotated in key.
    final Date newExpiresAt =
        new Date(clock.instant().plus(Duration.ofMinutes(JWT_EXPIRATION_MINUTES)).toEpochMilli());
    final JWTClaims newJwtClaims =
        new ImmutableJWTClaims.Builder()
            .setJWTId(UUID.randomUUID().toString())
            .setIssuer(ISSUER_URL)
            .setSubject(TEST_USER.getUID().getId())
            .setAudience(CLUSTER_ID)
            .setExpirationTime(newExpiresAt)
            // Use the original issueTime and notBeforeTime because the JWT validation library
            // (nimbus) uses the current system time to check these fields.
            .setIssueTime(issuedAt)
            .setNotBeforeTime(issuedAt)
            .build();
    final String testToken3 = systemJWKSetManager.getSigner().sign(newJwtClaims);
    assertNotEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(testToken3),
        "The JWT should have been signed with the new key");

    final TokenDetails validatedToken3 = remoteJWKSetManager.getValidator().validate(testToken3);
    assertEquals(USERNAME, validatedToken3.username);
    // RemoteJWKSetManager will need to retrieve the public JWKS from the master coordinator again
    // since the new token was signed with a key that is not cached.
    verify(systemJWKSetManager, times(2)).getPublicJWKS();

    final String testToken4 =
        systemJWKSetManager
            .getSigner()
            .sign(
                new ImmutableJWTClaims.Builder()
                    .from(newJwtClaims)
                    .setJWTId(UUID.randomUUID().toString())
                    .build());
    assertNotEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(testToken4),
        "The JWT should have been signed with the new key");

    final TokenDetails validatedToken4 = remoteJWKSetManager.getValidator().validate(testToken4);
    assertEquals(USERNAME, validatedToken4.username);
    // RemoteJWKSetManager shouldn't need to fetch the public JWKS from the master coordinator again
    // since the new token was signed with a key that is already cached.
    verify(systemJWKSetManager, times(2)).getPublicJWKS();
  }

  private static String getKeyIdFromSignedJwt(String signedJwt) throws IOException {
    final ObjectMapper mapper = new ObjectMapper();
    final String tokenHeader = signedJwt.split("\\.")[0];
    final Map<String, Object> testSignedToken1Claims =
        mapper.readValue(Base64.decode(tokenHeader), Map.class);
    return (String) testSignedToken1Claims.get("kid");
  }

  @Test
  public void remoteValidateJwtCachedJwksExpiration() throws Exception {
    final JWKS expectedOriginalJwks = systemJWKSetManager.getPublicJWKS();
    // Reset the number of calls to methods on the SystemJWKSManager spy so we only track calls to
    // getPublicJWKS made through RemoteJWKSetManager.
    reset(systemJWKSetManager);

    assertEquals(1, expectedOriginalJwks.getKeys().size());
    final String originalKeyId = expectedOriginalJwks.getKeys().get(0).getKeyId();

    final Date expiresAt =
        new Date(clock.instant().plus(Duration.ofMinutes(JWT_EXPIRATION_MINUTES)).toEpochMilli());
    final Date issuedAt = new Date(clock.instant().toEpochMilli());
    final JWTClaims jwtClaims =
        new ImmutableJWTClaims.Builder()
            .setJWTId(UUID.randomUUID().toString())
            .setIssuer(ISSUER_URL)
            .setSubject(TEST_USER.getUID().getId())
            .setAudience(CLUSTER_ID)
            .setExpirationTime(expiresAt)
            .setNotBeforeTime(issuedAt)
            .setIssueTime(issuedAt)
            .build();
    final String testToken = systemJWKSetManager.getSigner().sign(jwtClaims);
    assertEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(testToken),
        "The JWT should have been signed with the original key");

    final TokenDetails validatedToken = remoteJWKSetManager.getValidator().validate(testToken);
    assertEquals(USERNAME, validatedToken.username);
    verify(systemJWKSetManager, times(1)).getPublicJWKS();

    // Advance time to after the cached JWKS keys expire
    clock.add(Duration.ofHours(24).plusSeconds(1));

    // Create a new token with a new JWT ID
    final Date newExpiresAt =
        new Date(clock.instant().plus(Duration.ofMinutes(JWT_EXPIRATION_MINUTES)).toEpochMilli());
    final JWTClaims newJwtClaims =
        new ImmutableJWTClaims.Builder()
            .setJWTId(UUID.randomUUID().toString())
            .setIssuer(ISSUER_URL)
            .setSubject(TEST_USER.getUID().getId())
            .setAudience(CLUSTER_ID)
            .setExpirationTime(newExpiresAt)
            .setNotBeforeTime(issuedAt)
            .setIssueTime(issuedAt)
            .build();
    final String newTestToken = systemJWKSetManager.getSigner().sign(newJwtClaims);
    assertEquals(
        originalKeyId,
        getKeyIdFromSignedJwt(newTestToken),
        "The JWT should have been signed with the original key");

    final TokenDetails validatedToken2 = remoteJWKSetManager.getValidator().validate(newTestToken);
    assertEquals(USERNAME, validatedToken2.username);

    // The RemoteJWKSetManager should only need to fetch the public JWKS from the master coordinator
    // once to validate two tokens that were signed with the same key.
    verify(systemJWKSetManager, times(2)).getPublicJWKS();
  }

  @Test
  public void getPublicJWKS() {
    final JWKS jwks = remoteJWKSetManager.getPublicJWKS();
    assertNotNull(jwks);
    final JWKS expectedJwks = systemJWKSetManager.getPublicJWKS();
    assertEquals(expectedJwks, jwks);
  }
}
