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
package com.dremio.tools.openapigenerator.proto;

import java.util.Optional;
import org.apache.http.HttpStatus;
import org.immutables.value.Value;

/** Describes method response. */
@Value.Immutable
public interface ProtoMethodResponse {
  /** Http status, see {@link HttpStatus}. */
  int status();

  ProtoTypeRef typeRef();

  Optional<String> comment();

  static ImmutableProtoMethodResponse.Builder builder() {
    return ImmutableProtoMethodResponse.builder();
  }
}
