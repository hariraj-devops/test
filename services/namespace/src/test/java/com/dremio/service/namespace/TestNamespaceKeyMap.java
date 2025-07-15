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
package com.dremio.service.namespace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestNamespaceKeyMap {
  private NamespaceKeyMap<Integer> map;

  @BeforeEach
  void setUp() {
    map = new NamespaceKeyMap<>();
  }

  @Test
  void testSizeAndIsEmpty() {
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());

    map.put(new NamespaceKey(List.of("a", "b", "c")), 5);
    assertEquals(1, map.size());
    assertFalse(map.isEmpty());

    map.put(new NamespaceKey(List.of("a", "b", "d")), 8);
    assertEquals(2, map.size());
    assertFalse(map.isEmpty());

    map.put(new NamespaceKey(List.of("a", "b", "d")), 11); // update last entry
    assertEquals(2, map.size());
    assertFalse(map.isEmpty());

    map.put(new NamespaceKey(List.of("a")), 14);
    assertEquals(3, map.size());
    assertFalse(map.isEmpty());

    map.remove(new NamespaceKey(List.of("a")));
    assertEquals(2, map.size());
    assertFalse(map.isEmpty());

    map.remove(new NamespaceKey(List.of("a", "b", "c")));
    map.remove(new NamespaceKey(List.of("a", "b", "d")));
    assertEquals(0, map.size());
    assertTrue(map.isEmpty());
  }

  @Test
  void testContainsKey() {
    map.put(new NamespaceKey(List.of("x", "y", "z")), 10);
    assertFalse(map.containsKey(new NamespaceKey(List.of("x"))));
    assertFalse(map.containsKey(new NamespaceKey(List.of("x", "y"))));
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "y", "z"))));

    map.put(new NamespaceKey(List.of("x", "y")), 20);
    assertFalse(map.containsKey(new NamespaceKey(List.of("x"))));
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "y"))));
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "y", "z"))));

    map.put(new NamespaceKey(List.of("x")), 30);
    assertTrue(map.containsKey(new NamespaceKey(List.of("x"))));
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "y"))));
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "y", "z"))));

    map.put(new NamespaceKey(List.of("x", "abc")), 50);
    assertTrue(map.containsKey(new NamespaceKey(List.of("x", "abc"))));

    map.put(new NamespaceKey(List.of("abc")), 100);
    assertTrue(map.containsKey(new NamespaceKey(List.of("abc"))));

    map.put(new NamespaceKey(List.of("abc", "def")), 200);
    assertTrue(map.containsKey(new NamespaceKey(List.of("abc", "def"))));
  }

  @Test
  void testContainsValue() {
    map.put(new NamespaceKey(List.of("p", "q", "r")), 15);
    map.put(new NamespaceKey(List.of("a", "b", "c")), 20);
    map.put(new NamespaceKey(List.of("a", "d")), 25);
    assertTrue(map.containsValue(15));
    assertTrue(map.containsValue(20));
    assertTrue(map.containsValue(25));
    assertFalse(map.containsValue(0));
  }

  @Test
  void testPutAndGetAndRemove() {
    map.put(new NamespaceKey(List.of("a", "b", "c")), 5);
    assertEquals(5, map.get(new NamespaceKey(List.of("a", "b", "c"))));

    map.put(new NamespaceKey(List.of("a", "b", "d")), 8);
    assertEquals(8, map.get(new NamespaceKey(List.of("a", "b", "d"))));

    map.put(new NamespaceKey(List.of("a", "b", "d")), 11); // update last entry
    assertEquals(11, map.get(new NamespaceKey(List.of("a", "b", "d"))));

    map.put(new NamespaceKey(List.of("a")), 14);
    assertEquals(14, map.get(new NamespaceKey(List.of("a"))));

    map.remove(new NamespaceKey(List.of("a")));
    assertNull(map.get(new NamespaceKey(List.of("a"))));
    assertNotNull(map.get(new NamespaceKey(List.of("a", "b", "c"))));
    assertNotNull(map.get(new NamespaceKey(List.of("a", "b", "d"))));

    map.remove(new NamespaceKey(List.of("a", "b", "c")));
    map.remove(new NamespaceKey(List.of("a", "b", "d")));
    assertNull(map.get(new NamespaceKey(List.of("a", "b", "c"))));
    assertNull(map.get(new NamespaceKey(List.of("a", "b", "d"))));
  }

  @Test
  void testClear() {
    map.put(new NamespaceKey(List.of("a", "b")), 3);
    map.put(new NamespaceKey(List.of("c", "d")), 7);
    map.clear();
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());
  }

  @Test
  void testKeySet() {
    map.put(new NamespaceKey(List.of("a", "b")), 3);
    map.put(new NamespaceKey(List.of("a")), 4);
    map.put(new NamespaceKey(List.of("c", "d")), 7);
    Set<NamespaceKey> keys = map.keySet();
    assertEquals(3, keys.size());
    assertTrue(keys.contains(new NamespaceKey(List.of("a", "b"))));
    assertTrue(keys.contains(new NamespaceKey(List.of("a"))));
    assertTrue(keys.contains(new NamespaceKey(List.of("c", "d"))));
  }

  @Test
  void testValues() {
    map.put(new NamespaceKey(List.of("a", "b")), 3);
    map.put(new NamespaceKey(List.of("a")), 4);
    map.put(new NamespaceKey(List.of("c", "d")), 7);
    map.put(new NamespaceKey(List.of("e", "e")), 7);
    Collection<Integer> values = map.values();
    assertEquals(4, values.size());
    assertTrue(values.contains(3));
    assertTrue(values.contains(4));
    assertTrue(values.contains(7));
    assertEquals(2, values.stream().filter(value -> value == 7).count());
  }

  @Test
  void testEntrySet() {
    map.put(new NamespaceKey(List.of("a", "b")), 3);
    map.put(new NamespaceKey(List.of("a")), 4);
    map.put(new NamespaceKey(List.of("c", "d")), 7);
    Set<Map.Entry<NamespaceKey, Integer>> entrySet = map.entrySet();
    assertEquals(3, entrySet.size());
    assertTrue(
        entrySet.stream()
            .anyMatch(
                entry ->
                    entry.getKey().equals(new NamespaceKey(List.of("a", "b")))
                        && entry.getValue() == 3));
    assertTrue(
        entrySet.stream()
            .anyMatch(
                entry ->
                    entry.getKey().equals(new NamespaceKey(List.of("a")))
                        && entry.getValue() == 4));
    assertTrue(
        entrySet.stream()
            .anyMatch(
                entry ->
                    entry.getKey().equals(new NamespaceKey(List.of("c", "d")))
                        && entry.getValue() == 7));
  }
}
