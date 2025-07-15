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

package com.dremio.exec.store.deltalake;

import static org.junit.Assert.assertEquals;

import com.dremio.io.file.Path;
import com.dremio.service.namespace.file.proto.FileType;
import org.junit.Test;

/** Tests for {@link DeltaFilePathResolver} */
public class TestDeltaFilePathResolver {

  @Test
  public void testFilePathResolver() {
    Path metaDir = Path.of(".");

    Path path = DeltaFilePathResolver.resolve(metaDir, 0L, 1, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000000.json"));

    path = DeltaFilePathResolver.resolve(metaDir, 1L, 1, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000001.json"));

    path = DeltaFilePathResolver.resolve(metaDir, 1L, 1, FileType.PARQUET).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000001.checkpoint.parquet"));

    path = DeltaFilePathResolver.resolve(metaDir, 10L, 1, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000010.json"));

    path = DeltaFilePathResolver.resolve(metaDir, 22L, 1, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000022.json"));

    path = DeltaFilePathResolver.resolve(metaDir, 10L, 1, FileType.PARQUET).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000010.checkpoint.parquet"));

    path = DeltaFilePathResolver.resolve(metaDir, 100L, 1, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000100.json"));

    path = DeltaFilePathResolver.resolve(metaDir, 20L, 1, FileType.PARQUET).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000020.checkpoint.parquet"));
  }
}
