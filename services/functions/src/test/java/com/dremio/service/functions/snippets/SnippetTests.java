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
package com.dremio.service.functions.snippets;

import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class SnippetTests {
  @Test
  public void tests() {
    Snippet testSnippet =
        Snippet.builder()
            .addChoice("this", "is", "a", "choice")
            .addPlaceholder("This is a placeholder")
            .addText("This is a text")
            .addTabstop()
            .addVariable(Variable.Type.CLIPBOARD)
            .build();

    String testSnippetString = testSnippet.toString();
    Optional<Snippet> optionalSnippet = Snippet.tryParse(testSnippetString);
    Assert.assertTrue(optionalSnippet.isPresent());

    Assert.assertEquals(testSnippetString, optionalSnippet.get().toString());
  }
}
