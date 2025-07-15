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
package com.dremio.plugins.icebergcatalog.dfs;

import static com.dremio.io.file.UriSchemes.DREMIO_AZURE_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_S3_SCHEME;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.store.hive.exec.FileSystemConfUtil;
import com.dremio.io.file.UriSchemes;
import com.dremio.options.OptionManager;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestDatasetFileSystemCache {

  private static OptionManager mockOptionManager;
  private static Function<List<String>, Configuration> fsProvider =
      dataset -> new Configuration(false);
  private DatasetFileSystemCache fsCache;

  static MockedStatic<UserGroupInformation> withUgiMock() throws IOException, InterruptedException {
    UserGroupInformation ugi = mock(UserGroupInformation.class);
    MockedStatic<UserGroupInformation> ugiMock = mockStatic(UserGroupInformation.class);
    ugiMock.when(UserGroupInformation::getLoginUser).thenReturn(ugi);
    ugiMock.when(UserGroupInformation::getCurrentUser).thenReturn(ugi);

    FileSystem fs = mock(FileSystem.class);
    when(ugi.doAs(any(PrivilegedExceptionAction.class))).thenReturn(fs);
    when(fs.getScheme()).thenReturn("not-interesting");
    return ugiMock;
  }

  @BeforeClass
  public static void setup() throws IOException {
    mockOptionManager = mock(OptionManager.class);
  }

  @Before
  public void before() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    fsCache.cache = spy(fsCache.cache);
  }

  @After
  public void teardown() {
    fsCache = null;
  }

  @Test
  public void testCacheRemovalListener() throws IOException, InterruptedException {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, FileSystem> cache = fsCache.cache;
    FileSystem mockFileSystem = mock(FileSystem.class);
    doThrow(new IOException()).when(mockFileSystem).close();
    URI uri =
        URI.create(
            String.format(
                "%s://%s%s_%s", UriSchemes.HDFS_SCHEME, "hostname", "path", "testCacheOnRemoval"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, "dremio", null);
    cache.put(datasetFileSystemCacheKey, mockFileSystem);
    cache.invalidate(datasetFileSystemCacheKey);
    // invalidation happens in an async way
    Thread.sleep(100L);
    verify(mockFileSystem, times(1)).close();
  }

  @Test
  public void testBuildCacheKeyWithInvalidStorageProviderCreds() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, FileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.S3_SCHEME, "bucket/path"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, SYSTEM_USERNAME, null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Credentials for the Storage Provider must be valid and have required access");
  }

  @Test
  public void testBuildCacheKeyWithSystemUsername() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, FileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.HDFS_SCHEME, "incomplete_hdfs_uri"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, SYSTEM_USERNAME, null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Incomplete HDFS URI, no host: hdfs://incomplete_hdfs_uri");
  }

  @Test
  public void testBuildCacheKeyWithLoginUsername() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, FileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.HDFS_SCHEME, "incomplete_hdfs_uri"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, "user", null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Incomplete HDFS URI, no host: hdfs://incomplete_hdfs_uri");
  }

  @Test
  public void testLoadFile() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    com.dremio.io.file.FileSystem fsNullScheme =
        fsCache.load("/path/to", SYSTEM_USERNAME, null, null, false);
    assertTrue(fsNullScheme.unwrap(LocalFileSystem.class) instanceof LocalFileSystem);
    com.dremio.io.file.FileSystem fsFileScheme =
        fsCache.load("file:///path/to", SYSTEM_USERNAME, null, null, false);
    assertTrue(fsFileScheme.unwrap(LocalFileSystem.class) instanceof LocalFileSystem);
  }

  @Test
  public void testLoadS3() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        fsCache.load("s3:///path/to", SYSTEM_USERNAME, null, null, false);
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_S3_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadAzure() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        fsCache.load("abfs://authority/path/to", SYSTEM_USERNAME, null, null, false);
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_AZURE_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadGCS() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        fsCache.load("gs://authority/path/to?query#fragment", SYSTEM_USERNAME, null, null, false);
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_GCS_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadHdfs() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        fsCache.load(
            "hdfs:///authority/path/to?query#fragment", SYSTEM_USERNAME, null, null, false);
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals("hdfs", argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadIOException() {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      mockFsConfUtil
          .when(() -> FileSystemConfUtil.initializeConfiguration(any(), any()))
          .thenThrow(IOException.class);
      assertThatThrownBy(
              () ->
                  fsCache.load(
                      "gs://authority/path/to?query#fragment", SYSTEM_USERNAME, null, null, false))
          .isInstanceOf(UserException.class);
    }
  }

  @Test
  public void testClose() throws Exception {
    fsCache.close();
    verify(fsCache.cache, times(1)).invalidateAll();
    verify(fsCache.cache, times(1)).cleanUp();
  }
}
