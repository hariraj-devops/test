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
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** Describes constraints on a field. */
@Value.Immutable
public abstract class ProtoFieldConstraints {
  private static final String VALIDATION_CONSTRAINTS_PACKAGE = "javax.validation.constraints";
  private static final String MIN_CLASS = "Min";
  private static final String MAX_CLASS = "Max";
  private static final String EMAIL_CLASS = "Email";
  private static final String PATTERN_CLASS = "Pattern";
  private static final BigDecimal MAX_INTEGER = BigDecimal.valueOf(Integer.MAX_VALUE);
  private static final BigDecimal MIN_INTEGER = BigDecimal.valueOf(Integer.MIN_VALUE);

  public abstract Optional<BigDecimal> minimum();

  public abstract Optional<BigDecimal> maximum();

  public abstract Optional<String> pattern();

  public abstract Optional<Boolean> email();

  public ImmutableList<String> toValidationAnnotations() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    // Min/max.
    if (minimum().isPresent()) {
      builder.add(numberConstraint(MIN_CLASS, minimum().get()));
    }
    if (maximum().isPresent()) {
      builder.add(numberConstraint(MAX_CLASS, maximum().get()));
    }

    // Pattern.
    if (pattern().isPresent()) {
      builder.add(PATTERN_CLASS + String.format("(regexp = \"%s\")", pattern().get()));
    }

    // Email.
    if (email().isPresent() && email().get()) {
      builder.add(EMAIL_CLASS + "()");
    }

    return builder.build();
  }

  public void updateJavaImports(Set<String> imports) {
    if (minimum().isPresent()) {
      imports.add(VALIDATION_CONSTRAINTS_PACKAGE + "." + MIN_CLASS);
    }
    if (maximum().isPresent()) {
      imports.add(VALIDATION_CONSTRAINTS_PACKAGE + "." + MAX_CLASS);
    }
    if (email().isPresent()) {
      imports.add(VALIDATION_CONSTRAINTS_PACKAGE + "." + EMAIL_CLASS);
    }
    if (pattern().isPresent()) {
      imports.add(VALIDATION_CONSTRAINTS_PACKAGE + "." + PATTERN_CLASS);
    }
  }

  /** Immutables builder. */
  public static ImmutableProtoFieldConstraints.Builder builder() {
    return ImmutableProtoFieldConstraints.builder();
  }

  private static String numberConstraint(String name, BigDecimal value) {
    if (value.compareTo(MAX_INTEGER) < 0 && value.compareTo(MIN_INTEGER) > 0) {
      return String.format("%s(value = %d)", name, value.intValue());
    } else {
      if (value.scale() > 0) {
        throw new RuntimeException(
            String.format("%s constraint only supports integer types", name));
      } else {
        return String.format("%s(value = %dL)", name, value.longValue());
      }
    }
  }
}
