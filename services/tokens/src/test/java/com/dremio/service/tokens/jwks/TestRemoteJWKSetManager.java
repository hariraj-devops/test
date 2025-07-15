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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.jwks.jwk.ImmutablePublicECKey;
import com.dremio.service.tokens.jwt.JWTProcessorFactory;
import com.dremio.service.users.SimpleUser;
import com.dremio.service.users.User;
import com.dremio.service.users.UserResolver;
import com.dremio.service.users.proto.UID;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.JWTProcessor;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.rules.TemporaryFolder;
import org.threeten.extra.MutableClock;

public class TestRemoteJWKSetManager {
  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  @Rule public TemporaryFolder securityFolder = new TemporaryFolder();

  private static final String ISSUER = "https://localhost:9047";
  private static final String CLUSTER_ID = "test-cluster-id";
  private static final String USERNAME = "testuser";
  private static final User TEST_USER =
      SimpleUser.newBuilder()
          .setUserName(USERNAME)
          .setUID(new UID(UUID.randomUUID().toString()))
          .build();

  private RemoteJWKSetManager remoteJWKSetManager;
  private SystemJWKSetManager mockSystemJWKSetManager;
  private JWTProcessorFactory mockJwtProcessorFactory;
  private MutableClock clock;

  @Before
  public void before() throws Exception {
    this.clock = MutableClock.of(Instant.now(), ZoneOffset.UTC);
    this.mockSystemJWKSetManager = mock(SystemJWKSetManager.class);

    final String serverName = InProcessServerBuilder.generateName();
    final RemoteJWKSGrpcImpl jwksGrpc = new RemoteJWKSGrpcImpl(() -> mockSystemJWKSetManager);
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(jwksGrpc)
            .build()
            .start());
    final ManagedChannel channelToMaster =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());

    final UserResolver userResolver = mock(UserResolver.class);
    when(userResolver.getUser(TEST_USER.getUID())).thenReturn(TEST_USER);
    when(userResolver.getUser(USERNAME)).thenReturn(TEST_USER);

    this.mockJwtProcessorFactory = mock(JWTProcessorFactory.class);
    this.remoteJWKSetManager =
        new RemoteJWKSetManager(
            clock, () -> channelToMaster, () -> userResolver, () -> mockJwtProcessorFactory);
    remoteJWKSetManager.start();
  }

  @After
  public void tearDown() throws Exception {
    if (remoteJWKSetManager != null) {
      remoteJWKSetManager.close();
    }
  }

  @Test
  public void getSignerThrows() {
    assertThrows(UnsupportedOperationException.class, () -> remoteJWKSetManager.getSigner());
  }

  @Test
  public void getPublicJWKSCachesKeys() {
    final JWKS expectedJwks = buildTestJWKS();
    when(mockSystemJWKSetManager.getPublicJWKS()).thenReturn(expectedJwks);

    JWKS givenJwks = remoteJWKSetManager.getPublicJWKS();
    assertEquals(expectedJwks, givenJwks);

    givenJwks = remoteJWKSetManager.getPublicJWKS();
    assertEquals(expectedJwks, givenJwks);

    verify(mockSystemJWKSetManager, times(1)).getPublicJWKS();

    // Advance time passed the cached JWKS entry's expiration
    clock.add(Duration.ofHours(24).plusSeconds(1));

    givenJwks = remoteJWKSetManager.getPublicJWKS();
    assertEquals(expectedJwks, givenJwks);
    // RemoteJWKSetManager should need to fetch the JWKS from the master coordinator again since the
    // original JWKS cache entry expired
    verify(mockSystemJWKSetManager, times(2)).getPublicJWKS();
  }

  @Test
  public void concurrentGetPublicJWKSFetchesJWKSOnce()
      throws ExecutionException, InterruptedException {
    final JWKS expectedJwks = buildTestJWKS();
    when(mockSystemJWKSetManager.getPublicJWKS())
        .then(
            invocation -> {
              Thread.sleep(100);
              return expectedJwks;
            });

    final int threadCount = 4;
    List<CompletableFuture<JWKS>> jwksFutures = new ArrayList<>(threadCount);

    for (int i = 0; i < threadCount; i++) {
      jwksFutures.add(CompletableFuture.supplyAsync(() -> remoteJWKSetManager.getPublicJWKS()));
    }

    CompletableFuture<Void> allFutures =
        CompletableFuture.allOf(jwksFutures.toArray(new CompletableFuture[threadCount]));
    allFutures.join();

    for (CompletableFuture<JWKS> jwksFuture : jwksFutures) {
      Assertions.assertEquals(expectedJwks, jwksFuture.get());
    }

    verify(mockSystemJWKSetManager, times(1)).getPublicJWKS();
  }

  private static JWKS buildTestJWKS() {
    return new ImmutableJWKS.Builder()
        .setKeys(
            List.of(
                new ImmutablePublicECKey.Builder()
                    .setKeyId(UUID.randomUUID().toString())
                    .setKeyType("EC")
                    .setUse("sig")
                    .setKeyOps(List.of("verify"))
                    .setAlgorithm("ES256")
                    .setX509CertChain(
                        List.of(
                            "MIICEzCCAbmgAwIBAgIRAIjUKClInNNUjHflCcpFsO8wCgYIKoZIzj0EAwIwgYoxJjAkBgNVBAsMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMSYwJAYDVQQKDB1EcmVtaW8gQ29ycC4gKGF1dG8tZ2VuZXJhdGVkKTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECAwKQ2FsaWZvcm5pYTELMAkGA1UEBhMCVVMwHhcNMjQxMjExMjI0NjUxWhcNMjUxMjEyMjI0NjUxWjCBijEmMCQGA1UECwwdRHJlbWlvIENvcnAuIChhdXRvLWdlbmVyYXRlZCkxJjAkBgNVBAoMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQIDApDYWxpZm9ybmlhMQswCQYDVQQGEwJVUzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABGofdRaiwntDgYQxmdMAiVTTNCxQAZgWAyAQfcqfHRQ4s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU/SgkwCgYIKoZIzj0EAwIDSAAwRQIhAJ3xm3HeaFAcbKwvdrMX89QMiDzwjKCt5kiWZtgQ7gRtAiA6SsJv5MmL5Vzx/T+Ib8xDJZniSuXesK1u3zVmYGyokw=="))
                    .setCurve("P-256")
                    .setX("ah91FqLCe0OBhDGZ0wCJVNM0LFABmBYDIBB9yp8dFDg")
                    .setY("s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU_Sgk")
                    .build()))
        .build();
  }

  @Test
  public void concurrentRemoteValidationGetsPublicJWKSOnce()
      throws ExecutionException, InterruptedException, BadJOSEException, JOSEException {
    final JWKS expectedJwks =
        new ImmutableJWKS.Builder()
            .setKeys(
                List.of(
                    new ImmutablePublicECKey.Builder()
                        .setKeyId("3ac43f8a-2165-4232-acb1-40c35f9e173a")
                        .setKeyType("EC")
                        .setUse("sig")
                        .setKeyOps(List.of("verify"))
                        .setAlgorithm("ES256")
                        .setX509CertChain(
                            List.of(
                                "MIICEzCCAbmgAwIBAgIRAIjUKClInNNUjHflCcpFsO8wCgYIKoZIzj0EAwIwgYoxJjAkBgNVBAsMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMSYwJAYDVQQKDB1EcmVtaW8gQ29ycC4gKGF1dG8tZ2VuZXJhdGVkKTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECAwKQ2FsaWZvcm5pYTELMAkGA1UEBhMCVVMwHhcNMjQxMjExMjI0NjUxWhcNMjUxMjEyMjI0NjUxWjCBijEmMCQGA1UECwwdRHJlbWlvIENvcnAuIChhdXRvLWdlbmVyYXRlZCkxJjAkBgNVBAoMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQIDApDYWxpZm9ybmlhMQswCQYDVQQGEwJVUzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABGofdRaiwntDgYQxmdMAiVTTNCxQAZgWAyAQfcqfHRQ4s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU/SgkwCgYIKoZIzj0EAwIDSAAwRQIhAJ3xm3HeaFAcbKwvdrMX89QMiDzwjKCt5kiWZtgQ7gRtAiA6SsJv5MmL5Vzx/T+Ib8xDJZniSuXesK1u3zVmYGyokw=="))
                        .setCurve("P-256")
                        .setX("ah91FqLCe0OBhDGZ0wCJVNM0LFABmBYDIBB9yp8dFDg")
                        .setY("s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU_Sgk")
                        .build()))
            .build();
    when(mockSystemJWKSetManager.getPublicJWKS())
        .then(
            invocation -> {
              Thread.sleep(100);
              return expectedJwks;
            });

    final JWTProcessor<SecurityContext> mockJwtProcessor = mock(JWTProcessor.class);
    when(mockJwtProcessorFactory.buildJWTProcessor(any())).thenReturn(mockJwtProcessor);
    final Date expiresAt = new Date(clock.instant().plus(Duration.ofMinutes(60)).toEpochMilli());
    final Date issuedAt = new Date(clock.instant().toEpochMilli());
    when(mockJwtProcessor.process(any(JWT.class), any()))
        .thenReturn(
            new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(CLUSTER_ID)
                .subject(TEST_USER.getUID().getId())
                .expirationTime(expiresAt)
                .issueTime(issuedAt)
                .notBeforeTime(issuedAt)
                .build());

    final int threadCount = 4;
    List<CompletableFuture<TokenDetails>> tokenDetailsFutures = new ArrayList<>(threadCount);
    for (int i = 0; i < threadCount; i++) {
      tokenDetailsFutures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return remoteJWKSetManager
                      .getValidator()
                      .validate(
                          "eyJraWQiOiIzYWM0M2Y4YS0yMTY1LTQyMzItYWNiMS00MGMzNWY5ZTE3M2EiLCJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJiODQ4OTcyZC0wMzljLTQxMmQtYmE0Mi1iNjY1ZTcxNGMyMGUiLCJhdWQiOiJ0ZXN0LWNsdXN0ZXItaWQiLCJuYmYiOjE3MzQwNDQ0OTIsImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0OjkwNDciLCJleHAiOjE3MzQwNDgwOTIsImlhdCI6MTczNDA0NDQ5MiwianRpIjoiZDQ4MTc4ZTYtY2M3Yy00NmMwLWI3NTQtMTdjN2ZhOWUwZWIwIn0.cFbGEhDdzKkWZX22bgduwk-awqmPUY8x-c9N870BK8RAh8shkSPWdXxQZDct9MNV9PTL-QJn3JjUiB4MQBm7bg");
                } catch (ParseException e) {
                  throw new RuntimeException(e);
                }
              }));
    }

    CompletableFuture<Void> allFutures =
        CompletableFuture.allOf(tokenDetailsFutures.toArray(new CompletableFuture[threadCount]));
    allFutures.join();

    for (CompletableFuture<TokenDetails> tokenDetailsFuture : tokenDetailsFutures) {
      Assertions.assertEquals(USERNAME, tokenDetailsFuture.get().username);
    }

    verify(mockSystemJWKSetManager, times(1)).getPublicJWKS();
  }

  @Test
  public void validateKeyIdDoesNotExist() throws Exception {
    final JWKS expectedJwks =
        new ImmutableJWKS.Builder()
            .setKeys(
                List.of(
                    new ImmutablePublicECKey.Builder()
                        .setKeyId("55f7f6e0-23c4-4538-a8b0-1a67ba097175")
                        .setKeyType("EC")
                        .setUse("sig")
                        .setKeyOps(List.of("verify"))
                        .setAlgorithm("ES256")
                        .setX509CertChain(
                            List.of(
                                "MIICEzCCAbmgAwIBAgIRAIjUKClInNNUjHflCcpFsO8wCgYIKoZIzj0EAwIwgYoxJjAkBgNVBAsMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMSYwJAYDVQQKDB1EcmVtaW8gQ29ycC4gKGF1dG8tZ2VuZXJhdGVkKTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECAwKQ2FsaWZvcm5pYTELMAkGA1UEBhMCVVMwHhcNMjQxMjExMjI0NjUxWhcNMjUxMjEyMjI0NjUxWjCBijEmMCQGA1UECwwdRHJlbWlvIENvcnAuIChhdXRvLWdlbmVyYXRlZCkxJjAkBgNVBAoMHURyZW1pbyBDb3JwLiAoYXV0by1nZW5lcmF0ZWQpMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQIDApDYWxpZm9ybmlhMQswCQYDVQQGEwJVUzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABGofdRaiwntDgYQxmdMAiVTTNCxQAZgWAyAQfcqfHRQ4s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU/SgkwCgYIKoZIzj0EAwIDSAAwRQIhAJ3xm3HeaFAcbKwvdrMX89QMiDzwjKCt5kiWZtgQ7gRtAiA6SsJv5MmL5Vzx/T+Ib8xDJZniSuXesK1u3zVmYGyokw=="))
                        .setCurve("P-256")
                        .setX("ah91FqLCe0OBhDGZ0wCJVNM0LFABmBYDIBB9yp8dFDg")
                        .setY("s0t70jLvjDqcVdoRJzPDqUYcwEZCwK5GNeOWIIU_Sgk")
                        .build()))
            .build();
    when(mockSystemJWKSetManager.getPublicJWKS()).thenReturn(expectedJwks);

    final JWTProcessor<SecurityContext> mockJwtProcessor = mock(JWTProcessor.class);
    when(mockJwtProcessorFactory.buildJWTProcessor(any())).thenReturn(mockJwtProcessor);
    when(mockJwtProcessor.process(any(JWT.class), any()))
        .then(
            invocation -> {
              final JWT givenJwt = invocation.getArgument(0, JWT.class);
              assertTrue(givenJwt.getHeader() instanceof JWSHeader);
              final JWSHeader header = (JWSHeader) givenJwt.getHeader();
              assertEquals("3ac43f8a-2165-4232-acb1-40c35f9e173a", header.getKeyID());
              throw new BadJOSEException("No key found");
            });

    // Try to validate a JWT that has a "kid" (key ID) that doesn't exist
    assertThrows(
        IllegalArgumentException.class,
        () ->
            remoteJWKSetManager
                .getValidator()
                .validate(
                    "eyJraWQiOiIzYWM0M2Y4YS0yMTY1LTQyMzItYWNiMS00MGMzNWY5ZTE3M2EiLCJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJiODQ4OTcyZC0wMzljLTQxMmQtYmE0Mi1iNjY1ZTcxNGMyMGUiLCJhdWQiOiJ0ZXN0LWNsdXN0ZXItaWQiLCJuYmYiOjE3MzQwNDQ0OTIsImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0OjkwNDciLCJleHAiOjE3MzQwNDgwOTIsImlhdCI6MTczNDA0NDQ5MiwianRpIjoiZDQ4MTc4ZTYtY2M3Yy00NmMwLWI3NTQtMTdjN2ZhOWUwZWIwIn0.cFbGEhDdzKkWZX22bgduwk-awqmPUY8x-c9N870BK8RAh8shkSPWdXxQZDct9MNV9PTL-QJn3JjUiB4MQBm7bg"));
  }
}
