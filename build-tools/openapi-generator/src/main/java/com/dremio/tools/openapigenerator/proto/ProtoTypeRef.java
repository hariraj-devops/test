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
import org.immutables.value.Value;

/** Reference to a type. */
@Value.Immutable
public interface ProtoTypeRef {
  /** File where type is defined if it's not a built-in or well-known type. */
  Optional<ProtoFileOptions> fileOptions();

  /** Whether it's a repeated type. */
  boolean isRepeated();

  /** Type name for built-in and well-known types is their string representation. */
  String typeName();

  static ImmutableProtoTypeRef.Builder builder() {
    return ImmutableProtoTypeRef.builder();
  }
}
