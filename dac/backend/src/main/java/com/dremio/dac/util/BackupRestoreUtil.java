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
package com.dremio.dac.util;

import static com.dremio.datastore.BackupSupport.backupDestinationDirName;
import static java.lang.String.format;

import com.dremio.common.VM;
import com.dremio.common.concurrent.CloseableSchedulerThreadPool;
import com.dremio.common.concurrent.CloseableThreadPool;
import com.dremio.common.concurrent.NamedThreadFactory;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.common.utils.ProtostuffUtil;
import com.dremio.config.DremioConfig;
import com.dremio.dac.daemon.KVStoreProviderHelper;
import com.dremio.dac.homefiles.HomeFileConf;
import com.dremio.dac.homefiles.HomeFileTool;
import com.dremio.dac.proto.model.backup.BackupFileInfo;
import com.dremio.dac.server.DACConfig;
import com.dremio.datastore.ByteSerializerFactory;
import com.dremio.datastore.CheckpointInfo;
import com.dremio.datastore.KVFormatInfo;
import com.dremio.datastore.KVStoreInfo;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.Serializer;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.KVFormatter;
import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStore.PutOption;
import com.dremio.datastore.api.KVStoreProvider;
import com.dremio.datastore.format.Format;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.server.BootStrapContext;
import com.dremio.exec.store.dfs.PseudoDistributedFileSystem;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.FileSystemUtils;
import com.dremio.io.file.Path;
import com.dremio.io.file.PathFilters;
import com.dremio.security.SecurityFolder;
import com.dremio.service.jobtelemetry.server.store.LocalProfileKVStoreCreator;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.tokens.TokenStoreCreator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.opentracing.noop.NoopTracerFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.AccessControlException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.ws.rs.core.UriBuilder;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.jetbrains.annotations.NotNull;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

/** Backup Service running only on master. */
public final class BackupRestoreUtil {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(BackupRestoreUtil.class);

  private static final Set<PosixFilePermission> DEFAULT_PERMISSIONS =
      Sets.immutableEnumSet(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
  private static final String BACKUP_FILE_SUFFIX_JSON = "_backup.json";
  private static final String BACKUP_FILE_SUFFIX_BINARY = "_backup.pb";
  private static final String BACKUP_INFO_FILE_SUFFIX = "_info.json";

  private static final String[] SUPPORTED_COMPRESSION_METHODS = {"lz4", "snappy", "none"};

  private static final Predicate<Path> BACKUP_FILES_FILTER_JSON =
      PathFilters.endsWith(BACKUP_FILE_SUFFIX_JSON);
  private static final Predicate<Path> BACKUP_FILES_FILTER_BINARY =
      PathFilters.endsWith(BACKUP_FILE_SUFFIX_BINARY);
  private static final Predicate<Path> BACKUP_INFO_FILES_FILTER =
      PathFilters.endsWith(BACKUP_INFO_FILE_SUFFIX);
  public static final String SECURITY_FOLDER = "security";

  private static String getTableName(String fileName, String suffix) {
    return fileName.substring(0, fileName.length() - suffix.length());
  }

  private static final class BackupRecord {
    private final String key;
    private final String value;

    @JsonCreator
    public BackupRecord(@JsonProperty("key") String key, @JsonProperty("value") String value) {
      this.key = key;
      this.value = value;
    }

    public String getKey() {
      return key;
    }

    public String getValue() {
      return value;
    }
  }

  public static CheckpointInfo createCheckpoint(
      com.dremio.io.file.Path backupDirPath, KVStoreProvider kvStoreProvider) throws IOException {
    final FileSystem fs = HadoopFileSystem.get(backupDirPath, new Configuration());
    // Checking if directory already exists and that the daemon can access it
    BackupRestoreUtil.checkOrCreateDirectory(fs, backupDirPath);
    return BackupRestoreUtil.createCheckpoint(backupDirPath, fs, kvStoreProvider);
  }

  public static CheckpointInfo createCheckpoint(
      final Path backupDirAsPath, FileSystem fs, final KVStoreProvider kvStoreProvider)
      throws IOException {
    logger.info("Backup Checkpoint has started");
    final LocalKVStoreProvider localKVStoreProvider =
        kvStoreProvider.unwrap(LocalKVStoreProvider.class);
    if (localKVStoreProvider == null) {
      throw new IllegalArgumentException(
          "Only LocalKVStoreProvider is supported for checkpoint backups. Current provider is "
              + kvStoreProvider.getClass().getName());
    }

    try {
      final LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
      final String backupDirName = backupDestinationDirName(now);
      final Path backupDestinationDir = backupDirAsPath.resolve(backupDirName);
      fs.mkdirs(backupDestinationDir, DEFAULT_PERMISSIONS);

      return localKVStoreProvider.newCheckpoint(Paths.get(backupDestinationDir.toURI()));
    } finally {
      logger.info("Backup Checkpoint has finished");
    }
  }

  private static <K, V> void dumpTable(
      FileSystem fs,
      Path backupRootDir,
      BackupFileInfo backupFileInfo,
      KVStore<K, V> kvStore,
      boolean binary,
      Compression compression,
      String tblKey)
      throws IOException {
    final Path backupFile =
        backupRootDir.resolve(
            format(
                "%s%s",
                kvStore.getName(), binary ? BACKUP_FILE_SUFFIX_BINARY : BACKUP_FILE_SUFFIX_JSON));
    final KVFormatter<K, V> kvFormatter = kvStore.getKVFormatter();
    final Serializer<K, byte[]> keySerializer = serializerForBackup(kvFormatter.getKeyFormat());
    final Serializer<V, byte[]> valueSerializer = serializerForBackup(kvFormatter.getValueFormat());
    final Iterator<Document<K, V>> iterator = kvStore.find().iterator();
    long records = 0;

    if (binary) {
      OutputStream fsout = compression.getOutputStream(fs.create(backupFile, true));
      try (final DataOutputStream bos = new DataOutputStream(fsout); ) {
        while (iterator.hasNext()) {
          Document<K, V> document = iterator.next();
          {
            byte[] key = toBytes(document.getKey(), keySerializer);
            bos.writeInt(key.length);
            bos.write(key);
          }
          {
            byte[] value = toBytes(document.getValue(), valueSerializer);
            bos.writeInt(value.length);
            bos.write(value);
          }
          ++records;
        }
        backupFileInfo.setChecksum(0L);
        backupFileInfo.setRecords(records);
        backupFileInfo.setBinary(true);
      }
    } else {
      OutputStream fsout = compression.getOutputStream(fs.create(backupFile, true));
      final ObjectMapper objectMapper = new ObjectMapper();
      try (final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fsout))) {
        while (iterator.hasNext()) {
          Document<K, V> document = iterator.next();
          if (!tblKey.isEmpty() && !tblKey.equals(toJson(document.getKey(), keySerializer))) {
            continue;
          }
          writer.write(
              objectMapper.writeValueAsString(
                  new BackupRecord(
                      toJson(document.getKey(), keySerializer),
                      toJson(document.getValue(), valueSerializer))));
          writer.newLine();
          ++records;
        }
        backupFileInfo.setChecksum(0L);
        backupFileInfo.setRecords(records);
        backupFileInfo.setBinary(false);
      }
    }

    // write info file after backup file was successfully created and closed.
    final Path backupInfoFile =
        backupRootDir.resolve(
            format(
                "%s%s", backupFileInfo.getKvstoreInfo().getTablename(), BACKUP_INFO_FILE_SUFFIX));
    try (OutputStream backupInfoOut = fs.create(backupInfoFile, true)) {
      ProtostuffUtil.toJSON(backupInfoOut, backupFileInfo, BackupFileInfo.getSchema(), false);
    }
  }

  private static <K, V> void restoreTable(
      FileSystem fs,
      KVStore<K, V> kvStore,
      Path filePath,
      boolean binary,
      long records,
      BackupFileInfo.Compression compressionValue)
      throws IOException {
    final KVFormatter<K, V> kvFormatter = kvStore.getKVFormatter();
    final Serializer<K, byte[]> keySerializer = serializerForBackup(kvFormatter.getKeyFormat());
    final Serializer<V, byte[]> valueSerializer = serializerForBackup(kvFormatter.getValueFormat());
    if (binary) {
      Compression compression = Compression.valueOf(compressionValue.toString().toUpperCase());
      InputStream in = compression.getInputStream(fs.open(filePath));
      try (DataInputStream dis = new DataInputStream(in)) {
        for (long i = 0; i < records; i++) {
          K key = null;
          V value = null;
          {
            int keyLength = dis.readInt();
            byte[] keyBytes = new byte[keyLength];
            dis.readFully(keyBytes);
            key = fromBytes(keyBytes, keySerializer);
          }
          {
            int valueLength = dis.readInt();
            byte[] valueBytes = new byte[valueLength];
            dis.readFully(valueBytes);
            value = fromBytes(valueBytes, valueSerializer);
          }
          // Use the create flag to ensure OCC-enabled KVStore tables can retrieve an initial
          // version.
          // For non-OCC tables, this start version will get ignored and overwritten.
          kvStore.put(key, value, PutOption.CREATE);
        }
      }
      return;
    }
    Compression compression = Compression.valueOf(compressionValue.toString().toUpperCase());
    InputStream in = compression.getInputStream(fs.open(filePath));
    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
      final ObjectMapper objectMapper = new ObjectMapper();
      String line;
      while ((line = reader.readLine()) != null) {
        final BackupRecord rec = objectMapper.readValue(line, BackupRecord.class);
        K key = fromJson(rec.getKey(), keySerializer);
        V value = fromJson(rec.getValue(), valueSerializer);

        // Use the create flag to ensure OCC-enabled KVStore tables can retrieve an initial version.
        // For non-OCC tables, this start version will get ignored and overwritten.
        kvStore.put(key, value, PutOption.CREATE);
      }
    }
  }

  private static boolean isGoodRestoreLocation(File path) {
    if (!path.isDirectory()) {
      return false;
    }
    String[] pathList = path.list();
    // The directory must be empty or contain just the invisible ".DS_Store" file on a Mac.
    return pathList.length == 0 || pathList.length == 1 && pathList[0].equals(".DS_Store");
  }

  private static Map<String, BackupFileInfo> scanInfoFiles(FileSystem fs, Path backupDir)
      throws IOException {
    final Map<String, BackupFileInfo> tableToInfo = Maps.newHashMap();
    try (final DirectoryStream<FileAttributes> backupFiles =
        fs.list(backupDir, BACKUP_INFO_FILES_FILTER)) {
      for (FileAttributes backupFile : backupFiles) {
        final String tableName =
            getTableName(backupFile.getPath().getName(), BACKUP_INFO_FILE_SUFFIX);
        // read backup info file
        final byte[] headerBytes = new byte[(int) backupFile.size()];
        IOUtils.readFully(fs.open(backupFile.getPath()), headerBytes, 0, headerBytes.length);
        final BackupFileInfo backupFileInfo = new BackupFileInfo();
        ProtostuffUtil.fromJSON(headerBytes, backupFileInfo, BackupFileInfo.getSchema(), false);
        tableToInfo.put(tableName, backupFileInfo);
      }
    }
    return tableToInfo;
  }

  private static Map<String, Path> scanBackupFiles(
      FileSystem fs, Path backupDir, Map<String, BackupFileInfo> tableToInfo) throws IOException {
    final Map<String, Path> tableToBackupData = Maps.newHashMap();
    boolean binary = tableToInfo.values().stream().findFirst().get().getBinary();
    try (final DirectoryStream<FileAttributes> backupDataFiles =
        fs.list(backupDir, binary ? BACKUP_FILES_FILTER_BINARY : BACKUP_FILES_FILTER_JSON)) {
      for (FileAttributes backupDataFile : backupDataFiles) {
        final String tableName =
            getTableName(
                backupDataFile.getPath().getName(),
                binary ? BACKUP_FILE_SUFFIX_BINARY : BACKUP_FILE_SUFFIX_JSON);
        if (tableToInfo.containsKey(tableName)) {
          tableToBackupData.put(tableName, backupDataFile.getPath());
        } else {
          throw new IOException("Missing metadata file for table " + tableName);
        }
      }
    }

    // make sure we found backup files for all metadata files
    final List<String> missing = Lists.newArrayList();
    for (String tableName : tableToInfo.keySet()) {
      if (!tableToBackupData.containsKey(tableName)) {
        missing.add(tableName);
      }
    }
    if (!missing.isEmpty()) {
      throw new IOException("Backup files missing " + missing);
    }
    return tableToBackupData;
  }

  /** Backup all non KVStore files. */
  public static BackupStats backupFiles(
      DremioConfig dremioConfig, String backupDestination, HomeFileTool fileStore)
      throws IOException, GeneralSecurityException {

    final com.dremio.io.file.Path backupDestinationDir =
        com.dremio.io.file.Path.of(backupDestination);
    final com.dremio.io.file.Path backupRootDirPath = backupDestinationDir.getParent();
    final FileSystem bkpFs = HadoopFileSystem.get(backupRootDirPath, new Configuration());
    final BackupStats backupStats = new BackupStats(backupDestination, 0, 0);
    BackupRestoreUtil.backupFiles(
        dremioConfig, bkpFs, backupDestinationDir, fileStore.getConfForBackup(), backupStats);
    return backupStats;
  }

  /** Backup all non KVStore files. */
  public static void backupFiles(
      DremioConfig dremioConfig,
      FileSystem bkpFs,
      Path backupDir,
      HomeFileConf homeFileStore,
      BackupStats backupStats)
      throws IOException, GeneralSecurityException {
    backupUploadedFiles(bkpFs, backupDir, homeFileStore, backupStats);
    if (SecurityFolder.securityFolderExists(dremioConfig)) {
      backupSecurity(bkpFs, backupDir, SecurityFolder.of(dremioConfig), backupStats);
    }
  }

  private static void backupUploadedFiles(
      FileSystem fs, Path backupDir, HomeFileConf homeFileStore, BackupStats backupStats)
      throws IOException {
    final Path uploadsBackupDir = Path.withoutSchemeAndAuthority(backupDir).resolve("uploads");
    fs.mkdirs(uploadsBackupDir);
    final Path uploadsDir = homeFileStore.getInnerUploads();
    copyFiles(
        homeFileStore.getFilesystemAndCreatePaths(null),
        uploadsDir,
        fs,
        uploadsBackupDir,
        homeFileStore.isPdfsBased(),
        backupStats);
  }

  private static void backupSecurity(
      FileSystem dstFs, Path backupDir, SecurityFolder securityFolder, BackupStats backupStats)
      throws IOException {
    java.nio.file.Path securityDirectoryPath = securityFolder.getSecurityDirectory();
    FileSystem srcFs =
        HadoopFileSystem.get(
            Path.of(securityDirectoryPath.getParent().toUri()), new Configuration());
    Path securitySrcDir = Path.of(securityDirectoryPath.toUri());

    Path securityDestDir = Path.withoutSchemeAndAuthority(backupDir).resolve(SECURITY_FOLDER);
    dstFs.mkdirs(securityDestDir);
    copyFiles(srcFs, securitySrcDir, dstFs, securityDestDir, false, backupStats);
  }

  private static void copyFiles(
      FileSystem srcFs,
      Path srcPath,
      FileSystem dstFs,
      Path dstPath,
      boolean isPdfs,
      BackupStats backupStats)
      throws IOException {
    for (FileAttributes fileAttributes : srcFs.list(srcPath)) {
      if (fileAttributes.isDirectory()) {
        final Path dstDir = dstPath.resolve(fileAttributes.getPath().getName());
        dstFs.mkdirs(dstPath);
        copyFiles(srcFs, fileAttributes.getPath(), dstFs, dstDir, isPdfs, backupStats);
      } else {
        final Path dstFile;
        if (!isPdfs) {
          dstFile = dstPath.resolve(fileAttributes.getPath().getName());
        } else {
          // strip off {host}@ from file name
          dstFile =
              dstPath.resolve(
                  PseudoDistributedFileSystem.getRemoteFileName(
                      fileAttributes.getPath().getName()));
        }
        FileSystemUtils.copy(srcFs, fileAttributes.getPath(), dstFs, dstFile, false);
        backupStats.incrementFiles();
      }
    }
  }

  public static void restoreUploadedFiles(
      FileSystem fs,
      Path backupDir,
      HomeFileConf homeFileStore,
      BackupStats backupStats,
      String hostname)
      throws IOException {
    // restore uploaded files
    final Path uploadsBackupDir = Path.withoutSchemeAndAuthority(backupDir).resolve("uploads");
    FileSystem fs2 = homeFileStore.getFilesystemAndCreatePaths(hostname);
    fs2.delete(homeFileStore.getPath(), true);
    FileSystemUtils.copy(fs, uploadsBackupDir, fs2, homeFileStore.getInnerUploads(), false, false);
    try (final DirectoryStream<FileAttributes> directoryStream =
        FileSystemUtils.listRecursive(fs, uploadsBackupDir, PathFilters.ALL_FILES)) {
      for (FileAttributes attributes : directoryStream) {
        if (attributes.isRegularFile()) {
          backupStats.incrementFiles();
        }
      }
    }
  }

  public static void restoreSecurityFiles(
      FileSystem bkpFs, Path backupDir, SecurityFolder securityFolder, BackupStats backupStats)
      throws IOException {
    Path securityBackupDir = Path.withoutSchemeAndAuthority(backupDir).resolve(SECURITY_FOLDER);
    java.nio.file.Path securityDirectory = securityFolder.getSecurityDirectory();
    Path rootRestoreDir = Path.of(securityDirectory.getParent().toUri());

    try (FileSystem restoreFs = HadoopFileSystem.get(rootRestoreDir, new Configuration())) {
      Path restoredSecurityFolder = Path.of(securityDirectory.toUri());
      // When restoring the files it is expected that the destination folder does not exist.
      // As SecureFolder creates the folder on disk when it is instantiated, it is deleted here.
      // Its permission is restored later.
      restoreFs.delete(restoredSecurityFolder, true);
      FileSystemUtils.copy(bkpFs, securityBackupDir, restoreFs, rootRestoreDir, false, true);

      try (DirectoryStream<FileAttributes> directoryStream =
          FileSystemUtils.listRecursive(restoreFs, restoredSecurityFolder, PathFilters.ALL_FILES)) {
        for (FileAttributes fileAttributes : directoryStream) {
          if (fileAttributes.isRegularFile()) {
            // For all files restored to the `security` folder need to have the correct permission.
            // As SecurityFolder owns the permission logic, the permissions statically defined for
            // files are applied to each file restored.
            restoreFs.setPermission(
                fileAttributes.getPath(), SecurityFolder.SECURITY_FILE_PERMISSIONS);
            backupStats.incrementFiles();
          }
        }
      }

      restoreFs.setPermission(
          restoredSecurityFolder, SecurityFolder.SECURITY_DIRECTORY_PERMISSIONS);
    }
  }

  /** Options for doing backup/restore. */
  public static class BackupOptions {
    /**
     * Backup root dir. Backup subdirectories (with timestamp names) are stored inside the backup
     * root dir.
     */
    private final String backupDir;

    private final boolean binary;
    private final boolean includeProfiles;

    private String compression;
    private String table;
    private String key;

    @JsonCreator
    public BackupOptions(
        @JsonProperty("backupDir") String backupDir,
        @JsonProperty("binary") boolean binary,
        @JsonProperty("includeProfiles") boolean includeProfiles,
        @JsonProperty("compression") String compression,
        @JsonProperty("table") String table,
        @JsonProperty("key") String key) {
      super();
      this.backupDir = backupDir;
      this.binary = binary;
      this.includeProfiles = includeProfiles;
      this.compression = compression;
      this.table = table;
      this.key = key;
    }

    public String getBackupDir() {
      return backupDir;
    }

    @JsonIgnore
    public Path getBackupDirAsPath() {
      return com.dremio.io.file.Path.of(backupDir);
    }

    public boolean isBinary() {
      return binary;
    }

    public boolean isIncludeProfiles() {
      return includeProfiles;
    }

    public String getCompression() {
      return this.compression;
    }

    public String getTable() {
      return this.table;
    }

    public String getKey() {
      return this.key;
    }
  }

  public static BackupStats createBackup(
      FileSystem fs,
      BackupOptions options,
      KVStoreProvider kvStoreProvider,
      @Nullable HomeFileConf homeFileStore,
      DremioConfig dremioConfig,
      @Nullable CheckpointInfo checkpointInfo,
      boolean shouldBackupFiles)
      throws IOException, NamespaceException {
    String msg = checkpointInfo == null ? "Tables and uploads" : "Tables";
    if (options.getCompression() == (null) || options.getCompression().equals("")) {
      logger.info("{} Backup started.", msg);
    } else {
      logger.info("{} Backup started with {} compression.", msg, options.getCompression());
    }

    final BackupStats backupStats = new BackupStats();

    final LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
    final Path backupDir =
        checkpointInfo == null
            ? options.getBackupDirAsPath().resolve(backupDestinationDirName(now))
            : Path.of(checkpointInfo.getBackupDestinationDir());
    fs.mkdirs(backupDir, DEFAULT_PERMISSIONS);
    backupStats.backupPath = backupDir.toURI().getPath();

    final String backupThreadsString = System.getProperty("dremio.backup.threads");
    final int backupThreads =
        backupThreadsString != null
            ? Integer.parseInt(backupThreadsString.trim())
            : VM.availableProcessors() / 2;
    final ExecutorService svc =
        Executors.newFixedThreadPool(Math.max(1, backupThreads), new NamedThreadFactory("Backup-"));

    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      kvStoreProvider.stores().stream()
          .map(store -> asFuture(svc, store, fs, backupDir, options, backupStats))
          .forEach(futures::add);

      // Files should not be backed up if the caller is performing an out-of-server process backup.
      // In this scenario the backup is done in two states to not consume over consume Dremio server
      // memory that could lead to Zookeeper disconnections and later Dremio server restart.
      // (this usecase is done by `-s` flag on dremio-admin CLI and is going to change after
      // DX-91211)
      if (shouldBackupFiles) {
        if (homeFileStore != null) {
          futures.add(
              runAsync(() -> backupUploadedFiles(fs, backupDir, homeFileStore, backupStats), svc));
        }
        if (SecurityFolder.securityFolderExists(dremioConfig)) {
          futures.add(
              runAsync(
                  () -> backupSecurity(fs, backupDir, SecurityFolder.of(dremioConfig), backupStats),
                  svc));
        }
      }
      checkFutures(futures);
    } finally {
      CloseableSchedulerThreadPool.close(svc, logger);
      logger.info(
          "{} Backup finished. Backup of {} tables and {} uploads",
          msg,
          backupStats.getTables(),
          backupStats.getFiles());
    }

    return backupStats;
  }

  private static void checkFutures(List<CompletableFuture<Void>> futures)
      throws IOException, NamespaceException {
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (CompletionException | ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(), IOException.class, NamespaceException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  private static CompletableFuture<Void> asFuture(
      Executor e,
      KVStore<?, ?> kvStore,
      FileSystem fs,
      Path backupDir,
      BackupOptions options,
      BackupStats backupStats) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            if (TokenStoreCreator.TOKENS_TABLE_NAME.equals(kvStore.getName())) {
              // Skip creating a backup of tokens table
              // TODO: In the future, if there are other tables that should not be backed up, this
              // could be part of
              // StoreBuilderConfig interface
              return;
            }

            if (LocalProfileKVStoreCreator.PROFILES_NAME.equals(kvStore.getName())
                && !options.isIncludeProfiles()) {
              return;
            }

            if (!options.table.isEmpty() && !options.table.equals(kvStore.getName())) {
              return;
            }

            final BackupFileInfo backupFileInfo =
                new BackupFileInfo().setKvstoreInfo(createDefaultKVStoreInfo(kvStore));
            Compression compression = validateSupportedCompression(options);
            backupFileInfo.setCompression(
                BackupFileInfo.Compression.valueOf(options.getCompression().toUpperCase()));
            dumpTable(
                fs,
                backupDir,
                backupFileInfo,
                kvStore,
                options.isBinary(),
                compression,
                options.key);
            backupStats.incrementTables();
          } catch (IOException ex) {
            throw new CompletionException(ex);
          }
        },
        e);
  }

  /** Stats and exceptions thrown during the restore process. */
  public static class RestorationResults {
    public RestorationResults(BackupStats stats, List<Exception> exceptions) {
      this.stats = stats;
      this.exceptions = exceptions;
    }

    public List<Exception> getExceptions() {
      return exceptions;
    }

    public BackupStats getStats() {
      return stats;
    }

    private BackupStats stats;
    private List<Exception> exceptions;
  }

  public static RestorationResults restore(FileSystem bkpFs, Path backupDir, DACConfig dacConfig)
      throws Exception {
    final String dbDir = dacConfig.getConfig().getString(DremioConfig.DB_PATH_STRING);
    URI uploads = dacConfig.getConfig().getURI(DremioConfig.UPLOADS_PATH_STRING);
    File dbPath = new File(dbDir);

    try (final KVStoreProvider kvStoreProvider = getKvStoreProvider(dacConfig, dbDir)) {
      boolean localKVStore = isLocalKVStore(kvStoreProvider);
      logger.info("Restoring backup to {} store", localKVStore ? "local" : "distributed");
      if (localKVStore && !isGoodRestoreLocation(dbPath)) {
        throw new IllegalArgumentException(format("Path %s must be an empty directory.", dbDir));
      }
      kvStoreProvider.start();

      // TODO after we add home file store type to configuration make sure we change homefile store
      // construction.
      if (uploads.getScheme().equals("pdfs")) {
        uploads = UriBuilder.fromUri(uploads).scheme("file").build();
      }

      final HomeFileConf homeFileConf = new HomeFileConf(uploads.toString());
      homeFileConf.getFilesystemAndCreatePaths(null);
      Map<String, BackupFileInfo> tableToInfo = scanInfoFiles(bkpFs, backupDir);
      Map<String, Path> tableToBackupFiles = scanBackupFiles(bkpFs, backupDir, tableToInfo);
      final BackupStats backupStats = new BackupStats();
      backupStats.backupPath = backupDir.toURI().getPath();
      List<Exception> restoreTableExceptions = new ArrayList<>();

      try (CloseableThreadPool ctp = new CloseableThreadPool("restore")) {
        Map<String, CompletableFuture<Void>> futureMap = new HashMap<>();
        for (String tableName : tableToInfo.keySet()) {
          CompletableFuture<Void> future =
              CompletableFuture.runAsync(
                  () -> {
                    try {
                      BackupFileInfo info = tableToInfo.get(tableName);
                      final KVStore<?, ?> store = getStore(kvStoreProvider, info);
                      try {
                        restoreTable(
                            bkpFs,
                            store,
                            tableToBackupFiles.get(tableName),
                            info.getBinary(),
                            info.getRecords(),
                            info.getCompression());
                        backupStats.incrementTables();
                      } catch (Exception e) {
                        throw new CompletionException(
                            String.format("Restore failed for the '%s' table backup", tableName),
                            e);
                      }
                    } catch (Exception e) {
                      restoreTableExceptions.add(e);
                    }
                  },
                  ctp);
          futureMap.put(tableName, future);
        }
        futureMap.put(
            "restore uploads",
            runAsync(
                () ->
                    restoreUploadedFiles(
                        bkpFs,
                        backupDir,
                        homeFileConf,
                        backupStats,
                        dacConfig.getConfig().getThisNode()),
                e -> restoreTableExceptions.add(new CompletionException(e)),
                ctp));

        // Only try to restore the security folder if the folder exists in the backup source that
        // is being restored.
        if (bkpFs.exists(backupDir.resolve(SECURITY_FOLDER))) {
          futureMap.put(
              "restore security",
              runAsync(
                  () -> {
                    SecurityFolder securityFolder = SecurityFolder.of(dacConfig.getConfig());
                    restoreSecurityFiles(bkpFs, backupDir, securityFolder, backupStats);
                  },
                  e -> restoreTableExceptions.add(new CompletionException(e)),
                  ctp));
        }

        checkFutures(futureMap.values().stream().collect(Collectors.toList()));
      }
      return new RestorationResults(backupStats, restoreTableExceptions);
    }
  }

  private static KVStore<?, ?> getStore(KVStoreProvider kvStoreProvider, BackupFileInfo info) {
    return kvStoreProvider.stores().stream()
        .filter(store -> store.getName().equals(info.getKvstoreInfo().getTablename()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("KVStore not found"));
  }

  private static @NotNull KVStoreProvider getKvStoreProvider(DACConfig dacConfig, String dbDir) {
    ScanResult scanResult = ClassPathScanner.fromPrescan(dacConfig.getConfig().getSabotConfig());
    // TODO: do we need BootStrapContext here for its startTelemetry() and registerMetrics() ?
    BootStrapContext bootStrapContext = new BootStrapContext(dacConfig.getConfig(), scanResult);
    return KVStoreProviderHelper.newKVStoreProvider(
        dacConfig,
        scanResult,
        bootStrapContext.getAllocator(),
        null,
        null,
        NoopTracerFactory.create());
  }

  /**
   * Checks that directory exists and write permission is granted.
   *
   * @param fs - file system
   * @param directory - directory to check
   * @throws IOException
   */
  public static void checkOrCreateDirectory(FileSystem fs, Path directory) throws IOException {
    // Checking if directory already exists and that the daemon can access it
    if (!fs.exists(directory)) {
      // Checking if parent already exists and has the right permissions
      Path parent = directory.getParent();
      if (!fs.exists(parent)) {
        throw new IllegalArgumentException(format("Parent directory %s does not exist.", parent));
      }
      if (!fs.isDirectory(parent)) {
        throw new IllegalArgumentException(format("Path %s is not a directory.", parent));
      }
      try {
        fs.access(parent, EnumSet.of(AccessMode.WRITE, AccessMode.EXECUTE));
      } catch (AccessControlException e) {
        throw new IllegalArgumentException(
            format("Cannot create directory %s: check parent directory permissions.", directory),
            e);
      }
      fs.mkdirs(directory);
    }
    try {
      fs.access(directory, EnumSet.allOf(AccessMode.class));
    } catch (org.apache.hadoop.security.AccessControlException e) {
      throw new IllegalArgumentException(
          format("Path %s is not accessible/writeable.", directory), e);
    }
  }

  /** Stats for backup/restore. */
  public static final class BackupStats {
    private String backupPath = null;
    private AtomicLong tables = new AtomicLong(0);
    private AtomicLong files = new AtomicLong(0);

    public BackupStats() {}

    @JsonCreator
    public BackupStats(
        @JsonProperty("backupPath") String backupPath,
        @JsonProperty("tables") long tables,
        @JsonProperty("files") long files) {
      this.backupPath = backupPath;
      this.tables = new AtomicLong(tables);
      this.files = new AtomicLong(files);
    }

    public String getBackupPath() {
      return backupPath;
    }

    public long getTables() {
      return tables.get();
    }

    public void incrementFiles() {
      files.incrementAndGet();
    }

    public void incrementTables() {
      tables.incrementAndGet();
    }

    public long getFiles() {
      return files.get();
    }
  }

  private static Compression validateSupportedCompression(BackupOptions options) {
    if (options.getCompression() == null || options.getCompression().equals("")) {
      options.compression = "none";
    }
    Compression compression;
    if (Arrays.stream(SUPPORTED_COMPRESSION_METHODS).anyMatch(options.getCompression()::equals)) {
      compression = Compression.valueOf(options.getCompression().toUpperCase());
    } else {
      logger.warn("Compression value should be a string and can either be empty or snappy or lz4.");
      throw new RuntimeException(
          "Compression value should be a string and can either be empty or snappy or lz4.");
    }
    return compression;
  }

  enum Compression {
    NONE(outputStream -> outputStream, inputStream -> inputStream),
    SNAPPY(
        outputStream -> new SnappyOutputStream(outputStream),
        inputStream -> {
          try {
            return new SnappyInputStream(inputStream);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }),
    LZ4(
        outputStream -> new LZ4BlockOutputStream(outputStream),
        inputStream -> new LZ4BlockInputStream(inputStream));

    private final Function<OutputStream, OutputStream> outputStreamFunction;
    private final Function<InputStream, InputStream> inputStreamFunction;

    Compression(
        Function<OutputStream, OutputStream> outputStreamFunction,
        Function<InputStream, InputStream> inputStreamFunction) {
      this.outputStreamFunction = outputStreamFunction;
      this.inputStreamFunction = inputStreamFunction;
    }

    public InputStream getInputStream(InputStream in) {
      return this.inputStreamFunction.apply(in);
    }

    public OutputStream getOutputStream(OutputStream out) {
      return this.outputStreamFunction.apply(out);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static CompletableFuture<Void> runAsync(
      ThrowingRunnable task, Consumer<Throwable> exceptionHandler, Executor executor) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            task.run();
          } catch (Exception e) {
            if (exceptionHandler != null) {
              exceptionHandler.accept(e);
            } else {
              throw new CompletionException(e);
            }
          }
        },
        executor);
  }

  private static CompletableFuture<Void> runAsync(ThrowingRunnable task, Executor executor) {
    return runAsync(task, null, executor);
  }

  private static boolean isLocalKVStore(KVStoreProvider kvStoreProvider) {
    return kvStoreProvider instanceof LocalKVStoreProvider;
  }

  private static KVStoreInfo createDefaultKVStoreInfo(KVStore<?, ?> kvStore) {
    // Although the info is serialized to JSON, this information is never used.
    // In the future this must be removed from the backup serialization.

    final KVFormatInfo defaultFormat = new KVFormatInfo(KVFormatInfo.Type.BYTES);
    return new KVStoreInfo(kvStore.getName(), defaultFormat, defaultFormat);
  }

  @SuppressWarnings("unchecked")
  private static <T> Serializer<T, byte[]> serializerForBackup(Format<?> format) {
    return (Serializer<T, byte[]>) format.apply(ByteSerializerFactory.INSTANCE);
  }

  private static <T> byte[] toBytes(T data, Serializer<T, byte[]> serializer) {
    return serializer.serialize(data);
  }

  private static <T> String toJson(T data, Serializer<T, byte[]> serializer) {
    try {
      return serializer.toJson(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static <T> T fromBytes(byte[] bytes, Serializer<T, byte[]> serializer) {
    return serializer.deserialize(bytes);
  }

  private static <T> T fromJson(String json, Serializer<T, byte[]> serializer) {
    try {
      return serializer.fromJson(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
