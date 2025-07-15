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

import com.dremio.service.tokens.jwks.jwk.PublicECKey;
import com.dremio.service.tokens.proto.RemoteJWKSGrpc;
import com.dremio.service.tokens.proto.RemoteJwksRpc.GetPublicJWKSResponse;
import com.dremio.service.tokens.proto.RemoteJwksRpc.JWK;
import com.dremio.service.tokens.proto.RemoteJwksRpc.JWK.Builder;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.stream.Collectors;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This gRPC server runs on the master coordinator and allows secondary coordinators to make a gRPC
 * request to the master coordinator to retrieve the JSON Web Key Set (JWKS) that only exists on the
 * master coordinator. This allows secondary coordinators to independently validate Dremio JWT
 * signatures with the retrieved public keys.
 */
public class RemoteJWKSGrpcImpl extends RemoteJWKSGrpc.RemoteJWKSImplBase {
  private static final Logger logger = LoggerFactory.getLogger(RemoteJWKSGrpcImpl.class);

  private final Provider<JWKSetManager> jwkSetManager;

  public RemoteJWKSGrpcImpl(Provider<JWKSetManager> jwkSetManager) {
    this.jwkSetManager = jwkSetManager;
  }

  @Override
  public void getPublicJWKS(Empty request, StreamObserver<GetPublicJWKSResponse> responseObserver) {
    try {
      final JWKS jwks = jwkSetManager.get().getPublicJWKS();
      responseObserver.onNext(
          GetPublicJWKSResponse.newBuilder()
              .addAllKeys(
                  jwks.getKeys().stream()
                      .map(RemoteJWKSGrpcImpl::convertJWKToProto)
                      .collect(Collectors.toList()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      logger.error("Failed to retrieve public JWKS", e);
      responseObserver.onError(
          Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  private static JWK convertJWKToProto(com.dremio.service.tokens.jwks.jwk.JWK jwk) {
    final Builder builder =
        JWK.newBuilder()
            .setKid(jwk.getKeyId())
            .setKty(jwk.getKeyType())
            .setUse(jwk.getUse())
            .addAllKeyOps(jwk.getKeyOps())
            .setAlg(jwk.getAlgorithm())
            .addAllX5C(jwk.getX509CertChain());

    if (jwk.getX509Url() != null) {
      builder.setX5U(jwk.getX509Url());
    }
    if (jwk.getX509Thumbprint() != null) {
      builder.setX5T(jwk.getX509Thumbprint());
    }
    if (jwk.getX509Sha256Thumbprint() != null) {
      builder.setX5TS256(jwk.getX509Sha256Thumbprint());
    }

    if (!(jwk instanceof PublicECKey)) {
      throw new RuntimeException(
          String.format(
              "JWK with kid='%s' is not a supported key type. Given key type: %s",
              jwk.getKeyId(), jwk.getKeyType()));
    }

    final PublicECKey ecKey = (PublicECKey) jwk;
    builder.setEcKey(
        com.dremio.service.tokens.proto.RemoteJwksRpc.PublicECKey.newBuilder()
            .setCrv(ecKey.getCurve())
            .setX(ecKey.getX())
            .setY(ecKey.getY())
            .build());

    return builder.build();
  }
}
