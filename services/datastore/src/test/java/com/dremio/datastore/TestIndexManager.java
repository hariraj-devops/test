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
package com.dremio.datastore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dremio.datastore.indexed.LuceneSearchIndex;
import com.google.common.cache.LoadingCache;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestIndexManager {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testCacheOnRemovalCallsClose() throws Exception {
    IndexManager indexManager = new IndexManager("baseDir", false, false, null);
    LoadingCache<String, LuceneSearchIndex> cache = indexManager.getIndexes();
    LuceneSearchIndex mockLuceneSearchIndex = mock(LuceneSearchIndex.class);
    cache.put("key", mockLuceneSearchIndex);
    cache.invalidate("key");
    verify(mockLuceneSearchIndex, times(1)).close();
  }
}
