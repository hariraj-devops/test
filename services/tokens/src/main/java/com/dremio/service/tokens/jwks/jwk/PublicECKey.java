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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.immutables.value.Value.Immutable;

/**
 * Models the public key parameters for a JSON Web Key (JWK) that was generated from an elliptic
 * curve (EC) algorithm.
 */
@Immutable
@JsonDeserialize(builder = ImmutablePublicECKey.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public interface PublicECKey extends JWK {
  /** The elliptic curve that was used to generate this key. */
  @JsonProperty(value = "crv", required = true)
  String getCurve();

  /** Public X coordinate of the key. */
  @JsonProperty(value = "x", required = true)
  String getX();

  /** Public Y coordinate of the key. */
  @JsonProperty(value = "y", required = true)
  String getY();
}
