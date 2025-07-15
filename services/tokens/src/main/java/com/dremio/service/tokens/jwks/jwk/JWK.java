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
package com.dremio.service.tokens.jwks.jwk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import org.immutables.value.Value.Immutable;

/** Models a JSON Web Key: https://datatracker.ietf.org/doc/html/rfc7517#section-4. */
@Immutable
@JsonDeserialize(builder = ImmutableJWK.Builder.class)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = As.EXISTING_PROPERTY,
    property = "kty",
    visible = true)
@JsonSubTypes({@JsonSubTypes.Type(value = PublicECKey.class, name = "EC")})
@JsonIgnoreProperties(ignoreUnknown = true)
public interface JWK {
  /** Unique ID of this key. */
  @JsonProperty(value = "kid", required = true)
  String getKeyId();

  /** The key type identifies the crytographic algorithm family used to generate the key. */
  @JsonProperty(value = "kty", required = true)
  String getKeyType();

  /**
   * Identifies the intended use for the public key. The formal specification only defines "sig"
   * (signature) and "enc" (encryption). Note that the formal spec makes this field optional, but we
   * mark it as required because we always want the field to be set.
   */
  @JsonProperty(value = "use", required = true)
  String getUse();

  /**
   * Identifies the operations for which the key is intended to be used. Note that the formal spec
   * makes this field optional, but we mark it as required because we always want the field to be
   * set.
   */
  @JsonProperty(value = "key_ops", required = true)
  List<String> getKeyOps();

  /**
   * Identifies the cryptographic algorithm intended for use with the key. Note that the JWK spec
   * marks this field as optional, but it is required for our use case to give other resource
   * servers the necessary information to validate signatures in Dremio JWTs.
   */
  @JsonProperty(value = "alg", required = true)
  String getAlgorithm();

  /**
   * An optional URI that refers to a resource for the X.509 certificate or certificate chain used
   * to verify this key.
   */
  @JsonProperty(value = "x5u")
  String getX509Url();

  /** An optional list that comprises the X.509 certificate chain used to verify this key. */
  @JsonProperty("x5c")
  List<String> getX509CertChain();

  /** Base64-URL encoded SHA-1 digest of the X.509 certificate used to verify this key. */
  @JsonProperty("x5t")
  String getX509Thumbprint();

  /** Base64-URL encoded SHA-256 digest of the X.509 certificate used to verify this key. */
  @JsonProperty("x5t#S256")
  String getX509Sha256Thumbprint();
}
