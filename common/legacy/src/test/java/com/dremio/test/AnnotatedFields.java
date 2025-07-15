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
package com.dremio.test;

import static org.junit.platform.commons.util.AnnotationUtils.findAnnotatedFields;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.ReflectionUtils.isStatic;

import com.dremio.common.SuppressForbidden;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * originally taken from:
 * https://github.com/projectnessie/nessie/blob/d8280d075bbc8621954a9d68e0d11305e64affd2/compatibility/common/src/main/java/org/projectnessie/tools/compatibility/internal/AnnotatedFields.java
 */
public final class AnnotatedFields {
  private AnnotatedFields() {}

  public static <A extends Annotation> void populateAnnotatedFields(
      ExtensionContext context,
      Object instance,
      Class<A> annotationType,
      Predicate<A> annotationFilter,
      Function<Field, Object> fieldValue) {
    Class<?> clazz = context.getRequiredTestClass();
    boolean isStatic = instance == null;

    Predicate<Field> staticPredicate = field -> isStatic == isStatic(field);
    Predicate<Field> annotationPredicate =
        field -> findAnnotation(field, annotationType).map(annotationFilter::test).orElse(true);

    findAnnotatedFields(clazz, annotationType, staticPredicate.and(annotationPredicate))
        .forEach(f -> setField(f, instance, fieldValue.apply(f)));
  }

  @SuppressForbidden
  private static void setField(Field field, Object instance, Object value) {
    field.setAccessible(true);
    try {
      if (Modifier.isStatic(field.getModifiers())) {
        if (instance != null) {
          return;
        }
      } else {
        if (instance == null) {
          return;
        }
      }
      field.set(instance, value);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
