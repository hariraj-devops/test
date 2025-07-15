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

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

/** Describes a REST path with method. */
@Value.Immutable
public interface ProtoMethod {
  ProtoFileOptions fileOptions();

  /**
   * Method name derived from the path and http method, it is made unique within one spec file. The
   * method is in PascalCase, by proto style guide.
   */
  String name();

  /** Method path. */
  String path();

  /** Method or verb. */
  String httpMethod();

  Optional<String> comment();

  /** Method parameters. */
  ImmutableList<ProtoMethodParameter> parameters();

  /** Possible responses. */
  ImmutableList<ProtoMethodResponse> responses();

  static ImmutableProtoMethod.Builder builder() {
    return ImmutableProtoMethod.builder();
  }
}
