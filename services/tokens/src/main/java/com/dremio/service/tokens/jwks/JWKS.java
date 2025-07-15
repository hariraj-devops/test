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

import com.dremio.service.tokens.jwks.jwk.JWK;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import org.immutables.value.Value.Immutable;

/** Models a JSON Web Key Set: https://datatracker.ietf.org/doc/html/rfc7517#autoid-15. */
@Immutable
@JsonDeserialize(builder = ImmutableJWKS.Builder.class)
public interface JWKS {
  @JsonProperty("keys")
  List<JWK> getKeys();
}
