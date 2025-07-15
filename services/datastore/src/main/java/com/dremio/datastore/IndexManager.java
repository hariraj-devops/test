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

import com.dremio.common.DeferredException;
import com.dremio.datastore.indexed.CommitWrapper;
import com.dremio.datastore.indexed.LuceneSearchIndex;
import com.dremio.datastore.indexed.ReadOnlyLuceneSearchIndex;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import java.io.File;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Manages all existing indexes. */
class IndexManager implements AutoCloseable {

  private static final String INDEX_PATH_NAME = "search";

  private final DeferredException closeException = new DeferredException();

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<String, LuceneSearchIndex> indexes =
      CacheBuilder.newBuilder()
          .removalListener(
              new RemovalListener<String, LuceneSearchIndex>() {
                @Override
                public void onRemoval(RemovalNotification<String, LuceneSearchIndex> notification) {
                  try {
                    if (notification.getValue() != null) {
                      notification.getValue().close();
                    }
                  } catch (Exception ex) {
                    closeException.addException(ex);
                  }
                }
              })
          .build(
              new CacheLoader<String, LuceneSearchIndex>() {
                @Override
                public LuceneSearchIndex load(String name) throws IOException {
                  if (readOnly) {
                    return new ReadOnlyLuceneSearchIndex(indexDirectory, name, inMemory);
                  }
                  return new LuceneSearchIndex(indexDirectory, name, inMemory, commitWrapper);
                }
              });

  private final String baseDirectory;
  private final boolean inMemory;
  private final boolean readOnly;
  private final CommitWrapper commitWrapper;

  private File indexDirectory;

  IndexManager(
      String baseDirectory, boolean inMemory, boolean readOnly, CommitWrapper commitWrapper) {
    this.baseDirectory = baseDirectory;
    this.inMemory = inMemory;
    this.commitWrapper = commitWrapper;
    this.readOnly = readOnly;
  }

  public void start() throws Exception {

    this.indexDirectory = new File(baseDirectory, INDEX_PATH_NAME);
    if (indexDirectory.exists()) {
      if (!indexDirectory.isDirectory()) {
        throw new DatastoreException(
            String.format(
                "Invalid path %s for local search db, not a directory.",
                indexDirectory.getAbsolutePath()));
      }
    } else {
      if (!indexDirectory.mkdirs()) {
        throw new DatastoreException(
            String.format(
                "Failed to create directory %s for local search data.",
                indexDirectory.getAbsolutePath()));
      }
    }
  }

  LuceneSearchIndex getIndex(String name) {
    try {
      return indexes.get(name);
    } catch (ExecutionException ex) {
      throw Throwables.propagate(ex.getCause());
    }
  }

  void deleteEverything(Set<String> skipNames) throws IOException {
    final DeferredException deleteException = new DeferredException();
    for (Entry<String, LuceneSearchIndex> index : indexes.asMap().entrySet()) {
      if (!skipNames.contains(index.getKey())) {
        try {
          index.getValue().deleteEverything();
        } catch (IOException e) {
          deleteException.addException(e);
        }
      }
    }
    try {
      deleteException.close();
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException("Failure deleting indeices.", ex);
    }
  }

  @Override
  public void close() throws Exception {
    indexes.invalidateAll();
    indexes.cleanUp();
    closeException.close();
  }

  @VisibleForTesting
  protected LoadingCache<String, LuceneSearchIndex> getIndexes() {
    return indexes;
  }
}
