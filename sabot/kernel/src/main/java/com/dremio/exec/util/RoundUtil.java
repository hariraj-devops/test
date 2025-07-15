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
package com.dremio.exec.util;

public interface RoundUtil {
  static int round8up(int val) {
    int rem = val % 8;
    if (rem == 0) {
      return val;
    } else {
      return val - rem + 8;
    }
  }

  static int round64up(int val) {
    int rem = val % 64;
    if (rem == 0) {
      return val;
    } else {
      return val - rem + 64;
    }
  }

  static int nextPower2(int value) {
    if (value <= 0) {
      return 1;
    }
    if (Integer.bitCount(value) == 1) {
      return value;
    }
    return Integer.highestOneBit(value) << 1;
  }
}
