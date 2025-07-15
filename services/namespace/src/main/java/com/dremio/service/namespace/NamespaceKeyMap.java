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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * A memory-optimized mapping of NamespaceKey keys to objects of generic type. This class is
 * designed for storing large numbers of partially overlapping namespace keys.
 *
 * <p>Null values are not accepted.
 */
public class NamespaceKeyMap<V> implements Map<NamespaceKey, V> {
  private final TrieNode<V> root = new TrieNode<>();
  private int size = 0;

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  @Override
  public boolean containsValue(@NotNull Object value) {
    if (value == null) {
      return false;
    }
    for (V containedValue : values()) {
      if (containedValue.equals(value)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public V get(Object key) {
    if (!(key instanceof NamespaceKey)) {
      return null;
    }
    TrieNode<V> node = root;
    for (String part : ((NamespaceKey) key).getPathComponents()) {
      node = node.children.get(part);
      if (node == null) {
        return null;
      }
    }
    return node.value;
  }

  @Override
  public V put(NamespaceKey key, @NotNull V value) {
    if (value == null) {
      throw new NullPointerException();
    }
    TrieNode<V> node = root;
    for (String part : key.getPathComponents()) {
      node = node.children.computeIfAbsent(part, k -> new TrieNode<>());
    }
    V previousValue = node.value;
    if (previousValue == null) {
      size++;
    }
    node.value = value;
    return previousValue;
  }

  @Override
  public V remove(Object key) {
    if (!(key instanceof NamespaceKey)) {
      return null;
    }
    TrieNode<V> node = root;
    for (String part : ((NamespaceKey) key).getPathComponents()) {
      node = node.children.get(part);
      if (node == null) {
        return null;
      }
    }
    V previousValue = node.value;
    if (previousValue != null) {
      node.value = null;
      size--;
    }
    return previousValue;
  }

  @Override
  public void putAll(@NotNull Map<? extends NamespaceKey, ? extends V> m) {
    if (m == null) {
      throw new NullPointerException();
    }
    for (var entry : m.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void clear() {
    root.children.clear();
    size = 0;
  }

  @Override
  public @NotNull Set<NamespaceKey> keySet() {
    Set<NamespaceKey> keys = new HashSet<>();
    collectKeys(root, new ArrayList<>(), keys);
    return keys;
  }

  @Override
  public @NotNull Collection<V> values() {
    List<V> values = new ArrayList<>();
    collectValues(root, values);
    return values;
  }

  @Override
  public @NotNull Set<Entry<NamespaceKey, V>> entrySet() {
    Set<Entry<NamespaceKey, V>> entries = new HashSet<>();
    collectEntries(root, new ArrayList<>(), entries);
    return entries;
  }

  private void collectKeys(TrieNode<V> node, List<String> path, Set<NamespaceKey> outKeys) {
    if (node.value != null) {
      outKeys.add(new NamespaceKey(path));
    }
    for (Map.Entry<String, TrieNode<V>> entry : node.children.entrySet()) {
      path.add(entry.getKey());
      collectKeys(entry.getValue(), path, outKeys);
      path.remove(path.size() - 1);
    }
  }

  private void collectValues(TrieNode<V> node, List<V> outValues) {
    if (node.value != null) {
      outValues.add(node.value);
    }
    for (TrieNode<V> child : node.children.values()) {
      collectValues(child, outValues);
    }
  }

  private void collectEntries(
      TrieNode<V> node, List<String> path, Set<Entry<NamespaceKey, V>> outEntries) {
    if (node.value != null) {
      outEntries.add(new AbstractMap.SimpleEntry<>(new NamespaceKey(path), node.value));
    }
    for (Map.Entry<String, TrieNode<V>> entry : node.children.entrySet()) {
      path.add(entry.getKey());
      collectEntries(entry.getValue(), path, outEntries);
      path.remove(path.size() - 1);
    }
  }

  private static final class TrieNode<V> {
    private final Map<String, TrieNode<V>> children = new HashMap<>();
    private V value = null; // If non-null, there is a map entry for this node
  }
}
