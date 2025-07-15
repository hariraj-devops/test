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
package com.dremio.errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

public class DremioRestrictedTestcontainersUsageTest {
  private final CompilationTestHelper helper =
      CompilationTestHelper.newInstance(DremioRestrictedTestcontainersUsage.class, getClass());

  @Test
  public void testOfficialTestcontainersConstructor() {
    helper
        .addSourceLines(
            "Test.java",
            "import org.testcontainers.containers.NginxContainer;",
            "",
            "class Test {",
            "  @SuppressWarnings(\"deprecation\")",
            "  // BUG: Diagnostic contains: DremioRestrictedTestcontainersUsage",
            "  NginxContainer<?> container = new NginxContainer<>();",
            "}")
        .doTest();
  }

  @Test
  public void testOfficialTestcontainersMethodReference() {
    helper
        .addSourceLines(
            "Test.java",
            "import java.util.function.Supplier;",
            "import org.testcontainers.containers.NginxContainer;",
            "",
            "class Test {",
            "  // BUG: Diagnostic contains: DremioRestrictedTestcontainersUsage",
            "  Supplier<NginxContainer<?>> supplier = NginxContainer::new;",
            "}")
        .doTest();
  }

  @Test
  public void testDremioContainerConstructor() {
    helper
        .addSourceLines("DremioContainer.java", dremioContainerSource())
        .addSourceLines("ExampleDremioContainer.java", exampleDremioContainerSource())
        .addSourceLines(
            "Test.java",
            "import com.dremio.testcontainers.ExampleDremioContainer;",
            "",
            "class Test {",
            "  ExampleDremioContainer container = new ExampleDremioContainer();",
            "}")
        .doTest();
  }

  @Test
  public void testDremioContainerMethodReference() {
    helper
        .addSourceLines("DremioContainer.java", dremioContainerSource())
        .addSourceLines("ExampleDremioContainer.java", exampleDremioContainerSource())
        .addSourceLines(
            "Test.java",
            "import com.dremio.testcontainers.ExampleDremioContainer;",
            "import java.util.function.Supplier;",
            "",
            "class Test {",
            "  Supplier<ExampleDremioContainer> supplier = ExampleDremioContainer::new;",
            "}")
        .doTest();
  }

  @Test
  public void testGenericContainerConstructor() {
    helper
        .addSourceLines("ExampleGenericContainer.java", exampleGenericContainerSource())
        .addSourceLines(
            "Test.java",
            "import com.dremio.testcontainers.ExampleGenericContainer;",
            "",
            "class Test {",
            "  // BUG: Diagnostic contains: DremioRestrictedTestcontainersUsage",
            "  ExampleGenericContainer container = new ExampleGenericContainer();",
            "}")
        .doTest();
  }

  @Test
  public void testGenericContainerMethodReference() {
    helper
        .addSourceLines("ExampleGenericContainer.java", exampleGenericContainerSource())
        .addSourceLines(
            "Test.java",
            "import com.dremio.testcontainers.ExampleGenericContainer;",
            "import java.util.function.Supplier;",
            "",
            "class Test {",
            "  // BUG: Diagnostic contains: DremioRestrictedTestcontainersUsage",
            "  Supplier<ExampleGenericContainer> supplier = ExampleGenericContainer::new;",
            "}")
        .doTest();
  }

  private String[] dremioContainerSource() {
    return new String[] {
      "package com.dremio.testcontainers;", "", "public interface DremioContainer {}"
    };
  }

  private String[] exampleDremioContainerSource() {
    return new String[] {
      "package com.dremio.testcontainers;",
      "",
      "import org.testcontainers.containers.GenericContainer;",
      "",
      "public class ExampleDremioContainer extends GenericContainer<ExampleDremioContainer> implements DremioContainer {}"
    };
  }

  private String[] exampleGenericContainerSource() {
    return new String[] {
      "package com.dremio.testcontainers;",
      "",
      "import org.testcontainers.containers.GenericContainer;",
      "",
      "@SuppressWarnings(\"deprecation\")",
      "public class ExampleGenericContainer extends GenericContainer<ExampleGenericContainer> {}"
    };
  }
}
