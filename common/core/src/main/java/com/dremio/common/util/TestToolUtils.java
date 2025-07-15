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
package com.dremio.common.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Resources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;

public class TestToolUtils {

  public static String readTestResourceAsString(String resPath) {
    try {
      if (resPath.startsWith("/")) {
        // for compatibilty with existing callers
        resPath = resPath.substring(1);
      }
      URL resUrl = Resources.getResource(resPath);
      return Resources.toString(resUrl, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
