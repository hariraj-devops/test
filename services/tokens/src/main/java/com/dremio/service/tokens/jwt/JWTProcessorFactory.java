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
package com.dremio.service.tokens.jwt;

import com.dremio.common.server.WebServerInfoProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Provider;

/**
 * Creates Nimbus (JWT library) JWTProcessor objects given a Nimbus JWKSet. The returned
 * JWTProcessor can be used to validate Dremio JWTs. This class allows us to mock the JWT validation
 * mechanism so we don't need to create valid signed JWTs for unit tests that are testing behavior
 * unrelated to the validity of the given JWT.
 */
public class JWTProcessorFactory {
  private final Provider<WebServerInfoProvider> webServerInfoProvider;

  public JWTProcessorFactory(Provider<WebServerInfoProvider> webServerInfoProvider) {
    this.webServerInfoProvider = webServerInfoProvider;
  }

  public JWTProcessor<SecurityContext> buildJWTProcessor(JWKSet jwkSet) {
    final ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
    jwtProcessor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT));

    if (jwkSet.size() == 1) {
      try {
        jwtProcessor.setJWSKeySelector(
            new SingleKeyJWSKeySelector<>(
                JWSAlgorithm.ES256, jwkSet.getKeys().get(0).toECKey().toECPublicKey()));
      } catch (JOSEException e) {
        throw new RuntimeException("Failed to get JWK public key", e);
      }
    } else {
      jwtProcessor.setJWSKeySelector(
          new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, new ImmutableJWKSet<>(jwkSet)));
    }

    final WebServerInfoProvider serverInfo = webServerInfoProvider.get();
    final JWTClaimsSet exactMatchClaims =
        new JWTClaimsSet.Builder()
            .issuer(serverInfo.getIssuer().toString())
            .audience(serverInfo.getClusterId())
            .build();
    final Set<String> requiredClaims = new HashSet<>(JWTClaimsSet.getRegisteredNames());
    jwtProcessor.setJWTClaimsSetVerifier(
        new DefaultJWTClaimsVerifier<>(exactMatchClaims, requiredClaims));

    return jwtProcessor;
  }
}
