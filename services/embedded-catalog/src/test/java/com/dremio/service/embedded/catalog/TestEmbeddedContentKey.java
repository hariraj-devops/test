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
package com.dremio.service.embedded.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.projectnessie.model.ContentKey;

class TestEmbeddedContentKey {

  @ParameterizedTest
  @MethodSource
  void toPathStringRoundTrip(List<String> elements) {
    assertThat(
            EmbeddedContentKey.fromPathString(EmbeddedContentKey.of(elements).toPathString())
                .getElements())
        .isEqualTo(elements);

    // Check compatibility with Nessie OSS (previously used) encoders
    assertThat(
            EmbeddedContentKey.fromPathString(ContentKey.of(elements).toPathString()).getElements())
        .isEqualTo(elements);
  }

  public static Stream<Arguments> toPathStringRoundTrip() {
    return Stream.of(
        arguments(List.of("a", "b", "c")),
        arguments(List.of("a", "/b", "c/")),
        arguments(List.of("a", "b/c")),
        arguments(List.of("a.", ".b")),
        arguments(List.of("a\u1234", "b")));
  }
}
