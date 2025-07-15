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

import static com.google.common.base.Preconditions.checkArgument;

import com.dremio.service.tokens.TokenDetails;
import com.dremio.service.tokens.jwks.jwk.ImmutablePublicECKey;
import com.dremio.service.tokens.jwks.jwk.ImmutablePublicECKey.Builder;
import com.dremio.service.tokens.jwks.jwk.JWK;
import com.dremio.service.tokens.jwks.jwk.PublicECKey;
import com.dremio.service.tokens.jwt.JWTProcessorFactory;
import com.dremio.service.tokens.jwt.JWTSigner;
import com.dremio.service.tokens.jwt.JWTValidator;
import com.dremio.service.tokens.jwt.JWTValidatorImpl;
import com.dremio.service.tokens.proto.RemoteJWKSGrpc;
import com.dremio.service.tokens.proto.RemoteJwksRpc;
import com.dremio.service.tokens.proto.RemoteJwksRpc.GetPublicJWKSResponse;
import com.dremio.service.users.UserResolver;
import com.google.common.base.Strings;
import com.google.protobuf.Empty;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.JWTProcessor;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Provider;

/**
 * RemoteJWKSetManager is used by secondary coordinators to retrieve the JWKS from the master
 * coordinator, and use those to validate Dremio JWTs.
 */
public class RemoteJWKSetManager implements JWKSetManager {
  private static final int JWKS_CACHE_EXPIRATION_HOURS = 24;

  private final Clock clock;
  private final Provider<ManagedChannel> channelToMaster;
  private final Provider<UserResolver> userResolverProvider;
  private final Provider<JWTProcessorFactory> jwtProcessorFactoryProvider;
  private final Object jwksCacheMutex;

  private RemoteJWKSGrpc.RemoteJWKSBlockingStub remoteJwksStub;
  private CachedJWKSValidator cachedJwksValidator;
  private Set<String> cachedValidatorKeyIds;

  public RemoteJWKSetManager(
      Clock clock,
      Provider<ManagedChannel> channelToMaster,
      Provider<UserResolver> userResolverProvider,
      Provider<JWTProcessorFactory> jwtProcessorFactoryProvider) {
    this.clock = clock;
    this.channelToMaster = channelToMaster;
    this.userResolverProvider = userResolverProvider;
    this.jwtProcessorFactoryProvider = jwtProcessorFactoryProvider;
    this.jwksCacheMutex = new Object();
  }

  @Override
  public void start() throws Exception {
    this.remoteJwksStub = RemoteJWKSGrpc.newBlockingStub(channelToMaster.get());
  }

  @Override
  public void close() throws Exception {}

  @Override
  public JWTSigner getSigner() {
    // RemoteJWKSetManager is only used by secondary coordinators, which cannot sign JWTs themselves
    // because only the master possesses the private signing key, and secondary coordinators don't
    // need to be able to sign JWTs.
    throw new UnsupportedOperationException("RemoteJWKSetManager is not capable of signing tokens");
  }

  @Override
  public JWTValidator getValidator() {
    if (cachedJwksValidator == null || cachedJwksValidator.hasExpired(clock)) {
      synchronized (jwksCacheMutex) {
        if (cachedJwksValidator == null || cachedJwksValidator.hasExpired(clock)) {
          return refreshCachedJWKSValidator().delegatingJwtValidator;
        } else {
          return cachedJwksValidator.delegatingJwtValidator;
        }
      }
    }

    return cachedJwksValidator.delegatingJwtValidator;
  }

  @Override
  public JWKS getPublicJWKS() {
    if (cachedJwksValidator == null || cachedJwksValidator.hasExpired(clock)) {
      synchronized (jwksCacheMutex) {
        if (cachedJwksValidator == null || cachedJwksValidator.hasExpired(clock)) {
          return refreshCachedJWKSValidator().jwks;
        } else {
          return cachedJwksValidator.jwks;
        }
      }
    }

    return this.cachedJwksValidator.jwks;
  }

  /** Note: this method is not thread safe. Calls to it must be protected by a mutex. */
  private CachedJWKSValidator refreshCachedJWKSValidator() {
    try {
      final GetPublicJWKSResponse response =
          remoteJwksStub.getPublicJWKS(Empty.getDefaultInstance());
      final JWKS jwks = convertJWKSResponseToJWKS(response);
      this.cachedValidatorKeyIds =
          jwks.getKeys().stream().map(JWK::getKeyId).collect(Collectors.toSet());
      final JWKSet nimbusJwkSet = convertToNimbusJWKSet(jwks);
      final JWTProcessor<SecurityContext> jwtProcessor =
          jwtProcessorFactoryProvider.get().buildJWTProcessor(nimbusJwkSet);

      final DelegatingJWTValidator delegatingValidator =
          new DelegatingJWTValidator(
              keyId -> {
                if (cachedValidatorKeyIds.contains(keyId)) {
                  return this.cachedJwksValidator.jwtValidator;
                } else {
                  synchronized (jwksCacheMutex) {
                    if (cachedValidatorKeyIds.contains(keyId)) {
                      return this.cachedJwksValidator.jwtValidator;
                    } else {
                      return refreshCachedJWKSValidator().jwtValidator;
                    }
                  }
                }
              });
      this.cachedJwksValidator =
          new CachedJWKSValidator(
              jwks,
              delegatingValidator,
              new JWTValidatorImpl(userResolverProvider, jwtProcessor),
              clock.instant().plus(Duration.ofHours(JWKS_CACHE_EXPIRATION_HOURS)));
      return this.cachedJwksValidator;
    } catch (StatusRuntimeException sre) {
      throw new RuntimeException(
          String.format(
              "Secondary coordinator failed to retrieve JWKS from the master coordinator: %s",
              sre.getMessage()));
    }
  }

  private static JWKS convertJWKSResponseToJWKS(GetPublicJWKSResponse jwksResponse) {
    return new ImmutableJWKS.Builder()
        .setKeys(
            jwksResponse.getKeysList().stream()
                .map(
                    protoJwk -> {
                      if (!protoJwk.hasEcKey()) {
                        throw new IllegalStateException(
                            String.format(
                                "JWK with kid='%s' is unsupported key type: '%s",
                                protoJwk.getKid(), protoJwk.getKty()));
                      }

                      final ImmutablePublicECKey.Builder builder = new Builder();
                      final RemoteJwksRpc.PublicECKey ecKey = protoJwk.getEcKey();

                      builder
                          .setKeyId(protoJwk.getKid())
                          .setKeyType(protoJwk.getKty())
                          .setUse(protoJwk.getUse())
                          .setKeyOps(protoJwk.getKeyOpsList())
                          .setAlgorithm(protoJwk.getAlg())
                          .setX509CertChain(protoJwk.getX5CList())
                          .setCurve(ecKey.getCrv())
                          .setX(ecKey.getX())
                          .setY(ecKey.getY());

                      if (protoJwk.hasX5U()) {
                        builder.setX509Url(protoJwk.getX5U());
                      }
                      if (protoJwk.hasX5T()) {
                        builder.setX509Thumbprint(protoJwk.getX5T());
                      }
                      if (protoJwk.hasX5TS256()) {
                        builder.setX509Sha256Thumbprint(protoJwk.getX5TS256());
                      }

                      return builder.build();
                    })
                .collect(Collectors.toList()))
        .build();
  }

  private static JWKSet convertToNimbusJWKSet(JWKS jwks) {
    return new JWKSet(
        jwks.getKeys().stream()
            .map(RemoteJWKSetManager::convertToNimbusJWK)
            .collect(Collectors.toList()));
  }

  private static com.nimbusds.jose.jwk.JWK convertToNimbusJWK(JWK jwk) {
    if (!(jwk instanceof PublicECKey)) {
      throw new IllegalStateException(
          String.format(
              "JWK with kid='%s' is not a supported key type. Given key type is: '%s'",
              jwk.getKeyId(), jwk.getKeyType()));
    }

    final PublicECKey currentEcKey = (PublicECKey) jwk;

    try {
      final ECKey.Builder builder =
          new com.nimbusds.jose.jwk.ECKey.Builder(
                  Curve.parse((currentEcKey).getCurve()),
                  Base64URL.from(currentEcKey.getX()),
                  Base64URL.from(currentEcKey.getY()))
              .keyID(currentEcKey.getKeyId())
              .keyUse(KeyUse.parse(currentEcKey.getUse()))
              .keyOperations(KeyOperation.parse(currentEcKey.getKeyOps()))
              .algorithm(Algorithm.parse(currentEcKey.getAlgorithm()));

      if (currentEcKey.getX509CertChain() != null && !currentEcKey.getX509CertChain().isEmpty()) {
        builder.x509CertChain(
            currentEcKey.getX509CertChain().stream()
                .map(Base64::from)
                .collect(Collectors.toList()));
      }
      if (!Strings.isNullOrEmpty(currentEcKey.getX509Url())) {
        builder.x509CertURL(URI.create(currentEcKey.getX509Url()));
      }
      if (!Strings.isNullOrEmpty(currentEcKey.getX509Thumbprint())) {
        builder.x509CertThumbprint(Base64URL.from(currentEcKey.getX509Thumbprint()));
      }
      if (!Strings.isNullOrEmpty(currentEcKey.getX509Sha256Thumbprint())) {
        builder.x509CertSHA256Thumbprint(Base64URL.from(currentEcKey.getX509Sha256Thumbprint()));
      }

      return builder.build();
    } catch (ParseException e) {
      throw new RuntimeException("Failed to parse public ECKey from master coordinator's JWKS", e);
    }
  }

  private static class CachedJWKSValidator {
    private final JWKS jwks;
    private final DelegatingJWTValidator delegatingJwtValidator;
    private final JWTValidator jwtValidator;
    private final Instant expirationTime;

    public CachedJWKSValidator(
        JWKS jwks,
        DelegatingJWTValidator delegatingJwtValidator,
        JWTValidator jwtValidator,
        Instant expirationTime) {
      this.jwks = jwks;
      this.delegatingJwtValidator = delegatingJwtValidator;
      this.jwtValidator = jwtValidator;
      this.expirationTime = expirationTime;
    }

    public boolean hasExpired(Clock clock) {
      return clock.instant().isAfter(expirationTime);
    }
  }

  private static class DelegatingJWTValidator implements JWTValidator {
    private final Function<String, JWTValidator> getDelegateByKeyId;

    public DelegatingJWTValidator(Function<String, JWTValidator> getDelegateByKeyId) {
      this.getDelegateByKeyId = getDelegateByKeyId;
    }

    @Override
    public TokenDetails validate(String jwtString) throws ParseException {
      checkArgument(jwtString != null, "invalid token");
      final JWT jwt = JWTParser.parse(jwtString);

      if (!(jwt.getHeader() instanceof JWSHeader)) {
        throw new RuntimeException("Parsed JWT is not signed. Expected a signed JWT.");
      }

      final JWSHeader jwsHeader = (JWSHeader) jwt.getHeader();
      final JWTValidator delegateValidator = getDelegateByKeyId.apply(jwsHeader.getKeyID());
      return delegateValidator.validate(jwtString);
    }
  }
}
