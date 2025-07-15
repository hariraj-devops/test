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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.immutables.value.Value;

@Value.Immutable
public abstract class EmbeddedContentKey {
  private static final char DOT = '.';
  private static final char ZERO_BYTE = '\u0000';
  private static final char GROUP_SEPARATOR = '\u001D';

  public abstract List<String> getElements();

  public static EmbeddedContentKey of(String... elements) {
    Objects.requireNonNull(elements, "Elements array must not be null");
    return of(Arrays.asList(elements));
  }

  public static EmbeddedContentKey of(List<String> elements) {
    Objects.requireNonNull(elements, "elements argument is null");
    return new ImmutableEmbeddedContentKey.Builder().addAllElements(elements).build();
  }

  /** Parses the path encoded string to a {@code ContentKey} object. */
  public static EmbeddedContentKey fromPathString(String encoded) {
    return EmbeddedContentKey.of(decodePathString(encoded));
  }

  @Value.NonAttribute
  public String toPathString() {
    return toPathString(getElements());
  }

  private static List<String> decodePathString(String encoded) {
    List<String> elements = new ArrayList<>();
    int l = encoded.length();
    StringBuilder e = new StringBuilder();
    for (int i = 0; i < l; i++) {
      char c = encoded.charAt(i);
      switch (c) {
        case DOT:
          elements.add(e.toString());
          e.setLength(0);
          break;
        case GROUP_SEPARATOR:
        case ZERO_BYTE:
          e.append(DOT);
          break;
        default:
          e.append(c);
          break;
      }
    }
    if (e.length() > 0) {
      elements.add(e.toString());
    }
    return elements;
  }

  private static String toPathString(List<String> elements) {
    StringBuilder sb = new StringBuilder();
    for (String element : elements) {
      if (sb.length() > 0) {
        sb.append('.');
      }
      int l = element.length();
      for (int i = 0; i < l; i++) {
        char c = element.charAt(i);
        sb.append(c == DOT || c == ZERO_BYTE ? GROUP_SEPARATOR : c);
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return toPathString();
  }
}
