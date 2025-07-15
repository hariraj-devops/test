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

package com.dremio.exec.store;

import java.util.Objects;

/** Table info upon executing 'SHOW TABLES'. */
public final class TableInfo {
  public final String source;
  public final String path;
  public final String name;
  public final String type;

  public TableInfo(String source, String path, String name, String type) {
    this.source = source;
    this.path = path;
    this.name = name;
    this.type = type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TableInfo tableInfo = (TableInfo) o;
    return Objects.equals(source, tableInfo.source)
        && Objects.equals(path, tableInfo.path)
        && Objects.equals(name, tableInfo.name)
        && Objects.equals(type, tableInfo.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(source, path, name, type);
  }

  @Override
  public String toString() {
    return "TableInfo{"
        + "source='"
        + source
        + '\''
        + ", path='"
        + path
        + '\''
        + ", name='"
        + name
        + '\''
        + ", type='"
        + type
        + '\''
        + '}';
  }
}
