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
package com.dremio.exec.hadoop;

import static com.dremio.io.file.UriSchemes.FILE_SCHEME;
import static com.dremio.io.file.UriSchemes.HDFS_SCHEME;
import static com.dremio.io.file.UriSchemes.MAPRFS_SCHEME;
import static com.dremio.io.file.UriSchemes.WEBHDFS_SCHEME;

import com.dremio.common.VM;
import com.dremio.exec.store.LocalSyncableFileSystem;
import com.dremio.exec.store.dfs.OpenFileTracker;
import com.dremio.exec.store.dfs.SimpleFileBlockLocation;
import com.dremio.io.AsyncByteReader;
import com.dremio.io.FSInputStream;
import com.dremio.io.FSOutputStream;
import com.dremio.io.FilterFSInputStream;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileBlockLocation;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.context.OperatorStats.WaitRecorder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.ProviderMismatchException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;

/**
 * FileSystemWrapper is the wrapper around the actual FileSystem implementation.
 *
 * <p>If {@link com.dremio.sabot.exec.context.OperatorStats} are provided it returns an instrumented
 * FSDataInputStream to measure IO wait time and tracking file open/close operations.
 */
public class HadoopFileSystem implements FileSystem, OpenFileTracker {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HadoopFileSystem.class);

  // This provider path property is a comma separated list of one or more credential provider URIs
  // that is traversed while trying to resolve a credential alias.
  public static final String HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH =
      "hadoop.security.credential.provider.path";
  public static final String DREMIO_CREDENTIAL_PROVIDER_PATH = "dremio:///";

  private static final boolean TRACKING_ENABLED = VM.areAssertsEnabled();

  private static final String FORCE_REFRESH_LEVELS = "dremio.fs.force_refresh_levels";
  private static int FORCE_REFRESH_LEVELS_VALUE = Integer.getInteger(FORCE_REFRESH_LEVELS, 2);

  private final ConcurrentMap<FSInputStream, DebugStackTrace> openedFiles = Maps.newConcurrentMap();

  private final org.apache.hadoop.fs.FileSystem underlyingFs;
  final OperatorStats operatorStats;
  private final boolean isPdfs;
  private final boolean isMapRfs;
  private final boolean isNAS;
  private final boolean isHDFS;
  private final boolean enableAsync;

  public static FileSystem get(URI uri, Configuration fsConf, boolean enableAsync)
      throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(uri, fsConf);
    return get(fs, null, enableAsync);
  }

  public static FileSystem get(
      URI uri, Iterator<Map.Entry<String, String>> conf, boolean enableAsync) throws IOException {
    // we are passing the conf as map<string,string> to work around
    // Configuration objects being loaded by different class loaders.
    Configuration fsConf = new Configuration();
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    while (conf.hasNext()) {
      Map.Entry<String, String> property = conf.next();
      fsConf.set(property.getKey(), property.getValue());
    }
    org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(uri, fsConf);
    return get(fs, null, enableAsync);
  }

  public static FileSystem get(Path path, Configuration fsConf) throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.FileSystem fs = toHadoopPath(path).getFileSystem(fsConf);
    return get(fs);
  }

  public static FileSystem get(Path path, Configuration fsConf, OperatorStats stats)
      throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.FileSystem fs = toHadoopPath(path).getFileSystem(fsConf);
    return get(fs, stats, false);
  }

  public static FileSystem get(
      Path path, Configuration fsConf, OperatorStats stats, boolean enableAsync)
      throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.FileSystem fs = toHadoopPath(path).getFileSystem(fsConf);
    return get(fs, stats, enableAsync);
  }

  public static FileSystem getLocal(Configuration fsConf) throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.getLocal(fsConf);
    return get(fs);
  }

  /**
   * Get a raw local filesystem which doesn't produce checksum data
   *
   * @param fsConf the hadoop configuration
   * @return a local filesystem writing data without any checksum metadata
   * @throws IOException
   */
  public static FileSystem getRawLocal(Configuration fsConf) throws IOException {
    fsConf.set(HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH, DREMIO_CREDENTIAL_PROVIDER_PATH);
    org.apache.hadoop.fs.LocalFileSystem fs = org.apache.hadoop.fs.FileSystem.getLocal(fsConf);
    return get(fs.getRaw());
  }

  public static HadoopFileSystem get(org.apache.hadoop.fs.FileSystem fs) {
    return get(fs, null, false);
  }

  public static HadoopFileSystem get(
      org.apache.hadoop.fs.FileSystem fs, OperatorStats operatorStats) {
    return get(fs, operatorStats, false);
  }

  public static HadoopFileSystem get(
      org.apache.hadoop.fs.FileSystem fs, OperatorStats operatorStats, boolean enableAsync) {
    return new HadoopFileSystem(fs, operatorStats, enableAsync);
  }

  private HadoopFileSystem(
      org.apache.hadoop.fs.FileSystem fs, OperatorStats operatorStats, boolean enableAsync) {
    this.underlyingFs = fs;
    this.operatorStats = operatorStats;
    this.isPdfs =
        (underlyingFs instanceof PathCanonicalizer); // only pdfs implements PathCanonicalizer
    this.isMapRfs = isMapRfs(underlyingFs);
    this.isNAS = isNAS(underlyingFs);
    this.isHDFS = isHDFS(underlyingFs);
    this.enableAsync = enableAsync;
    if (operatorStats != null) {
      this.operatorStats.createMetadataReadIOStats();
    }
  }

  private static boolean isMapRfs(org.apache.hadoop.fs.FileSystem fs) {
    try {
      return MAPRFS_SCHEME.equals(fs.getScheme().toLowerCase(Locale.ROOT));
    } catch (UnsupportedOperationException e) {
    }
    return false;
  }

  private static boolean isNAS(org.apache.hadoop.fs.FileSystem fs) {
    try {
      return fs instanceof LocalSyncableFileSystem
          || FILE_SCHEME.equals(fs.getScheme().toLowerCase(Locale.ROOT));
    } catch (UnsupportedOperationException e) {
    }
    return false;
  }

  private static boolean isHDFS(org.apache.hadoop.fs.FileSystem fs) {
    try {
      final String scheme = fs.getScheme().toLowerCase(Locale.ROOT);
      return HDFS_SCHEME.equals(scheme) || WEBHDFS_SCHEME.equals(scheme);
    } catch (UnsupportedOperationException e) {
    }
    return false;
  }

  protected org.apache.hadoop.fs.FileSystem getUnderlyingFs() {
    return underlyingFs;
  }

  /**
   * If OperatorStats are provided return a instrumented {@link
   * org.apache.hadoop.fs.FSDataInputStream}.
   */
  @Override
  public FSInputStream open(Path f) throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      return newFSDataInputStreamWrapper(
          f, underlyingFs.open(toHadoopPath(f)), operatorStats, true);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public String getScheme() {
    return underlyingFs.getScheme();
  }

  @Override
  public FSOutputStream create(Path f) throws IOException {
    try (WaitRecorder recorder = OperatorStats.getWaitRecorder(operatorStats)) {
      return newFSDataOutputStreamWrapper(underlyingFs.create(toHadoopPath(f)), f.toString());
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSOutputStream create(Path f, boolean overwrite) throws IOException {
    try (WaitRecorder recorder = OperatorStats.getWaitRecorder(operatorStats)) {
      return newFSDataOutputStreamWrapper(
          underlyingFs.create(toHadoopPath(f), overwrite), f.toString());
    } catch (FSError e) {
      throw propagateFSError(e);
    } catch (FileAlreadyExistsException e) {
      throw new java.nio.file.FileAlreadyExistsException(e.getMessage());
    }
  }

  @Override
  public FileAttributes getFileAttributes(Path f) throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      final FileStatus result = underlyingFs.getFileStatus(toHadoopPath(f));
      // safe-guarding against misbehaving filesystems
      if (result == null) {
        throw new FileNotFoundException("File " + f + " does not exist");
      }
      return new FileStatusWrapper(result);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setPermission(Path p, Set<PosixFilePermission> permissions) throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, p)) {
      underlyingFs.setPermission(toHadoopPath(p), toFsPermission(permissions));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T unwrap(Class<T> clazz) {
    if (clazz.isAssignableFrom(underlyingFs.getClass())) {
      return (T) underlyingFs;
    }

    return null;
  }

  @Override
  public boolean mkdirs(Path f, Set<PosixFilePermission> permissions) throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      return underlyingFs.mkdirs(toHadoopPath(f), toFsPermission(permissions));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void close() throws IOException {
    if (TRACKING_ENABLED) {
      if (openedFiles.size() != 0) {
        final StringBuffer errMsgBuilder = new StringBuffer();

        errMsgBuilder.append(
            String.format(
                "Not all files opened using this FileSystem are closed. "
                    + "There are"
                    + " still [%d] files open.\n",
                openedFiles.size()));

        for (DebugStackTrace stackTrace : openedFiles.values()) {
          stackTrace.addToStringBuilder(errMsgBuilder);
        }

        final String errMsg = errMsgBuilder.toString();
        logger.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
    }
  }

  @Override
  public boolean mkdirs(Path folderPath) throws IOException {
    try (WaitRecorder metaDataRecorder =
        OperatorStats.getMetadataWaitRecorder(operatorStats, folderPath)) {
      org.apache.hadoop.fs.Path path = toHadoopPath(folderPath);
      if (!underlyingFs.exists(path)) {
        return underlyingFs.mkdirs(path);
      } else if (!underlyingFs.getFileStatus(path).isDirectory()) {
        throw new IOException("The specified folder path exists and is not a folder.");
      }
      return false;
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public DirectoryStream<FileAttributes> list(Path f) throws FileNotFoundException, IOException {
    try (WaitRecorder metaDataRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      return new ArrayDirectoryStream(underlyingFs.listStatus(toHadoopPath(f)));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public DirectoryStream<FileAttributes> listFiles(Path f, boolean recursive) throws IOException {
    try (WaitRecorder metaDataRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      return new FetchOnDemandDirectoryStream(
          underlyingFs.listFiles(toHadoopPath(f), recursive), f, operatorStats);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public DirectoryStream<FileAttributes> list(Path f, Predicate<Path> filter)
      throws FileNotFoundException, IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      return new ArrayDirectoryStream(
          underlyingFs.listStatus(toHadoopPath(f), toPathFilter(filter)));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public DirectoryStream<FileAttributes> glob(Path pattern, Predicate<Path> filter)
      throws FileNotFoundException, IOException {
    try (WaitRecorder metaRecorder =
        OperatorStats.getMetadataWaitRecorder(operatorStats, pattern)) {
      FileStatus[] fileStatuses =
          underlyingFs.globStatus(toHadoopPath(pattern), toPathFilter(filter));
      if (fileStatuses != null && logger.isTraceEnabled()) {
        for (FileStatus fileStatus : fileStatuses) {
          logger.trace("HFS glob file status: {}", fileStatus.toString());
        }
      }
      return new ArrayDirectoryStream(fileStatuses);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, dst)) {
      return underlyingFs.rename(toHadoopPath(src), toHadoopPath(dst));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean delete(Path f, boolean recursive) throws IOException {
    try (WaitRecorder recorder = OperatorStats.getWaitRecorder(operatorStats)) {
      return underlyingFs.delete(toHadoopPath(f), recursive);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean exists(Path f) throws IOException {
    final org.apache.hadoop.fs.Path p = toHadoopPath(f);
    boolean exists = false;
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      exists = underlyingFs.exists(p);
      if (!exists && isNAS) {
        forceRefresh(f);
        exists = underlyingFs.exists(p);
      }
    } catch (FSError e) {
      throw propagateFSError(e);
    } catch (IOException e) {
      if (e.getMessage().contains("FileSystem is closed")) {
        // Fix for S3AFileSystem - if the filesystem is closed (e.g. we are in the process of a JVM
        // shutdown and its
        // shutdown hook has already run), it throws an IOException. This is poor behavior - it
        // should be unchecked as
        // it is not a predictable scenario that callers might be expected to recover from.
        // Additionally, it being a raw
        // IOException makes it hard to differentiate from IOException's that reflect permission
        // errors and other
        // problems that callers might assume are due to a bad file path rather than source in a bad
        // state. For example,
        // MetadataSynchronizer needs to handle those cases differently: delete the dataset when
        // inaccessible due to bad
        // path or permissions, but preserve if inaccessible because JVM shutdown is in progress.
        // Ref:
        // https://github.com/apache/hadoop/blob/735e35d6484ca9908efeaa7c2f6f4d654b1fcf41/hadoop-tools/hadoop-aws/src/main/java/org/apache/hadoop/fs/s3a/S3AFileSystem.java#L4070

        // DX-91951: Below is likely unnecessary now - keeping unchecked as a precaution.
        throw new IllegalStateException(e);
      }
    }

    return exists;
  }

  @Override
  public boolean isDirectory(Path f) throws IOException {
    final org.apache.hadoop.fs.Path p = toHadoopPath(f);
    boolean exists = false;
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      exists = underlyingFs.isDirectory(p);
      if (!exists && isNAS) {
        forceRefresh(f);
        exists = underlyingFs.isDirectory(p);
      }
    } catch (FSError e) {
      throw propagateFSError(e);
    }
    return exists;
  }

  @Override
  public boolean isFile(Path f) throws IOException {
    final org.apache.hadoop.fs.Path p = toHadoopPath(f);
    boolean exists = false;
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, f)) {
      exists = underlyingFs.isFile(p);
      if (!exists && isNAS) {
        forceRefresh(f);
        exists = underlyingFs.isFile(p);
      }
    } catch (FSError e) {
      throw propagateFSError(e);
    }
    return exists;
  }

  @Override
  public URI getUri() {
    return underlyingFs.getUri();
  }

  @Override
  public Path makeQualified(Path path) {
    return fromHadoopPath(underlyingFs.makeQualified(toHadoopPath(path)));
  }

  @Override
  public Iterable<FileBlockLocation> getFileBlockLocations(
      FileAttributes file, long start, long len) throws IOException {
    if (!(file instanceof FileStatusWrapper)) {
      throw new ProviderMismatchException();
    }
    final FileStatus status = ((FileStatusWrapper) file).getFileStatus();
    Path p = status == null ? null : file.getPath();
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, p)) {
      return toFileBlockLocations(() -> underlyingFs.getFileBlockLocations(status, start, len));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Iterable<FileBlockLocation> getFileBlockLocations(Path p, long start, long len)
      throws IOException {
    try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(operatorStats, p)) {
      return toFileBlockLocations(
          () -> underlyingFs.getFileBlockLocations(toHadoopPath(p), start, len));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void access(final Path path, final Set<AccessMode> mode)
      throws AccessControlException, FileNotFoundException, IOException {
    try (WaitRecorder recorder = OperatorStats.getMetadataWaitRecorder(operatorStats, path)) {
      underlyingFs.access(toHadoopPath(path), toFsAction(mode));
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  private void forceRefresh(Path f) {
    /*
     In some cases, especially for NFS, the directory lookup is from the cache. So, a
     new file/directory created in another client may not be seen by this client.
     Now, NFS must adhere to close-to-open consistency.  Hence opening is a way to force
     a refresh of the attribute cache.

     This uses Java File APIs directly.
    */
    java.nio.file.Path p = Paths.get(f.toString());

    /*
     n level of directories may be created in the executor. To refresh, the base directory
     the coordinator is aware of needs to be refreshed. The default level is 2.
    */
    for (int i = 0; i < FORCE_REFRESH_LEVELS_VALUE; i++) {
      p = p.getParent();
      if (p == null) {
        return;
      }
      /*
        Need to use a call that would cause the trigger of a directory refresh.
        Checking isFile() or isDirectory() does not refresh the NFS directory cache.
        Opening the file/directory also does not also refresh the NFS diretory cache.
        Parent of a file or directory is always a directory. Attempting to open  a directory
        already known to the client refreshes the directory tree.
      */
      try (DirectoryStream<java.nio.file.Path> ignore = Files.newDirectoryStream(p)) {
        return; // return if there is no exception, i.e. it was found
      } catch (IOException e) {
        logger.trace("Refresh failed", e);
      }
    }
  }

  @Override
  public boolean isPdfs() {
    return isPdfs;
  }

  @Override
  public boolean isMapRfs() {
    return isMapRfs;
  }

  @Override
  public boolean supportsBlockAffinity() {
    return isHDFS;
  }

  @Override
  public boolean supportsPath(Path path) {
    try {
      underlyingFs.makeQualified(toHadoopPath(path));
      return true;
    } catch (IllegalArgumentException ie) {
      return false;
    }
  }

  @Override
  public long getDefaultBlockSize(Path path) {
    if (!isHDFS && !isMapRfs) {
      return -1;
    }
    return underlyingFs.getDefaultBlockSize(toHadoopPath(path));
  }

  /**
   * Canonicalizes a path if supported by the filesystem
   *
   * @param fs the filesystem to use
   * @param path the path to canonicalize
   * @return the canonicalized path, or the same path if not supported by the filesystem.
   * @throws IOException
   */
  public static Path canonicalizePath(org.apache.hadoop.fs.FileSystem fs, Path path)
      throws IOException {
    try {
      if (fs instanceof PathCanonicalizer) {
        final org.apache.hadoop.fs.Path hadoopPath = toHadoopPath(path);
        final org.apache.hadoop.fs.Path result =
            ((PathCanonicalizer) fs).canonicalizePath(hadoopPath);
        if (hadoopPath == result) {
          return path;
        }
        return fromHadoopPath(result);
      }
      return path;
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path canonicalizePath(Path p) throws IOException {
    try (WaitRecorder recorder = OperatorStats.getWaitRecorder(operatorStats)) {
      return canonicalizePath(underlyingFs, p);
    } catch (FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void fileOpened(Path path, FSInputStream fsInputStream) {
    openedFiles.put(
        fsInputStream, new DebugStackTrace(path, Thread.currentThread().getStackTrace()));
  }

  @Override
  public void fileClosed(FSInputStream fsInputStream) {
    openedFiles.remove(fsInputStream);
  }

  @Override
  public boolean supportsAsync() {
    if (!enableAsync) {
      return false;
    }
    if (underlyingFs instanceof MayProvideAsyncStream) {
      return ((MayProvideAsyncStream) underlyingFs).supportsAsync();
    }
    // will use wrapper to emulate async APIs.
    return isHDFS;
  }

  @Override
  public AsyncByteReader getAsyncByteReader(
      AsyncByteReader.FileKey fileKey, Map<String, String> options) throws IOException {
    return getAsyncByteReader(fileKey, operatorStats, options);
  }

  public AsyncByteReader getAsyncByteReader(
      AsyncByteReader.FileKey fileKey, OperatorStats operatorStats, Map<String, String> options)
      throws IOException {
    if (!enableAsync) {
      throw new UnsupportedOperationException();
    }

    final org.apache.hadoop.fs.Path path = toHadoopPath(fileKey.getPath());
    if (underlyingFs instanceof MayProvideAsyncStream) {
      return ((MayProvideAsyncStream) underlyingFs)
          .getAsyncByteReader(path, fileKey.getVersion(), options);
    } else {
      FSDataInputStream is;
      try (WaitRecorder recorder =
          OperatorStats.getMetadataWaitRecorder(operatorStats, fileKey.getPath())) {
        is = underlyingFs.open(path);
      }
      logger.debug("Opening new inputstream for {} ", path);
      FSInputStream inputStream =
          newFSDataInputStreamWrapper(fileKey.getPath(), is, operatorStats, false);
      return new HadoopAsyncByteReader(this, fileKey.getPath(), inputStream);
    }
  }

  @Override
  public boolean supportsPathsWithScheme() {
    return false;
  }

  private static final class ArrayDirectoryStream implements DirectoryStream<FileAttributes> {
    private final List<FileAttributes> delegate;

    private ArrayDirectoryStream(FileStatus[] statuses) {
      delegate =
          statuses != null
              ? ImmutableList.copyOf(
                  Iterables.transform(Arrays.asList(statuses), FileStatusWrapper::new))
              : Collections.emptyList();
    }

    @Override
    public Iterator<FileAttributes> iterator() {
      return delegate.iterator();
    }

    @Override
    public void close() throws IOException {}
  }

  public static final class FetchOnDemandDirectoryStream
      implements DirectoryStream<FileAttributes> {
    private final Iterator<FileAttributes> convertedIterator;

    public FetchOnDemandDirectoryStream(
        RemoteIterator<LocatedFileStatus> statuses, Path p, OperatorStats stats) {

      convertedIterator =
          new Iterator<FileAttributes>() {
            @Override
            public boolean hasNext() {
              try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(stats, p)) {
                return statuses.hasNext();
              } catch (IOException e) {
                logger.error(
                    "IO exception in FetchOnDemandDirectoryStream while performing hasNext on RemoteIterator",
                    e);
                throw new RuntimeException(e.getCause());
              }
            }

            @Override
            public FileAttributes next() {
              try (WaitRecorder metaRecorder = OperatorStats.getMetadataWaitRecorder(stats, p)) {
                return new FileStatusWrapper(statuses.next());
              } catch (IOException e) {
                logger.error(
                    "IO exception in FetchOnDemandDirectoryStream in fetching next fileAttribute ",
                    e);
                throw new RuntimeException(e.getCause());
              }
            }
          };
    }

    @Override
    public Iterator<FileAttributes> iterator() {
      return convertedIterator;
    }

    @Override
    public void close() throws IOException {}
  }

  public static class DebugStackTrace {
    private final StackTraceElement[] elements;
    private final Path path;

    public DebugStackTrace(Path path, StackTraceElement[] elements) {
      this.path = path;
      this.elements = elements;
    }

    public void addToStringBuilder(StringBuffer sb) {
      sb.append("File '");
      sb.append(path.toString());
      sb.append("' opened at callstack:\n");

      // add all stack elements except the top three as they point to FileSystemWrapper.open() and
      // inner stack elements.
      for (int i = 3; i < elements.length; i++) {
        sb.append("\t");
        sb.append(elements[i]);
        sb.append("\n");
      }
      sb.append("\n");
    }
  }

  FSInputStream newFSDataInputStreamWrapper(
      Path f, final FSDataInputStream is, OperatorStats stats, boolean recordWaitTimes)
      throws IOException {
    FSInputStream result;
    if (stats != null) {
      result = FSDataInputStreamWithStatsWrapper.of(is, stats, recordWaitTimes, f.toString());
    } else {
      result = FSDataInputStreamWrapper.of(is);
    }
    if (TRACKING_ENABLED) {
      result =
          new FilterFSInputStream(result) {
            @Override
            public void close() throws IOException {
              fileClosed(this);
              super.close();
            }
          };
      fileOpened(f, result);
    }
    return result;
  }

  FSOutputStream newFSDataOutputStreamWrapper(FSDataOutputStream os, String path)
      throws IOException {
    FSOutputStream result = new FSDataOutputStreamWrapper(os);
    if (operatorStats != null) {
      result = new FSDataOutputStreamWithStatsWrapper(result, operatorStats, path);
    }

    return result;
  }

  @VisibleForTesting
  static FsAction toFsAction(Set<AccessMode> mode) {
    final char[] perms = new char[] {'-', '-', '-'};
    for (AccessMode m : mode) {
      switch (m) {
        case READ:
          perms[0] = 'r';
          break;

        case WRITE:
          perms[1] = 'w';
          break;

        case EXECUTE:
          perms[2] = 'x';
          break;
      }
    }
    return FsAction.getFsAction(new String(perms));
  }

  static Path fromHadoopPath(org.apache.hadoop.fs.Path path) {
    return Path.of(path.toUri());
  }

  private static org.apache.hadoop.fs.Path toHadoopPath(Path path) {
    return new org.apache.hadoop.fs.Path(path.toURI());
  }

  private static PathFilter toPathFilter(Predicate<Path> predicate) {
    return p -> predicate.test(fromHadoopPath(p));
  }

  @VisibleForTesting
  static FsPermission toFsPermission(Set<PosixFilePermission> permissions) {
    return FsPermission.valueOf("-" + PosixFilePermissions.toString(permissions));
  }

  public static IOException propagateFSError(FSError e) throws IOException {
    Throwables.propagateIfPossible(e.getCause(), IOException.class);
    return new IOException("Unexpected FSError", e);
  }

  @FunctionalInterface
  private interface IOCallable<V> {
    V call() throws IOException;
  }

  private static Iterable<FileBlockLocation> toFileBlockLocations(IOCallable<BlockLocation[]> call)
      throws IOException {
    final BlockLocation[] blocks = call.call();
    final List<FileBlockLocation> results = new ArrayList<>(blocks.length);
    for (BlockLocation block : blocks) {
      results.add(
          new SimpleFileBlockLocation(
              block.getOffset(), block.getLength(), ImmutableList.copyOf(block.getHosts())));
    }
    return results;
  }
}
