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
package com.dremio.dac.homefiles;

import static java.lang.String.format;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.dac.service.errors.SourceBadStateException;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.file.FilePath;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import javax.inject.Inject;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import org.apache.commons.io.IOUtils;

/** Injectable tool for doing home file manipulation. */
public class HomeFileTool {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(HomeFileTool.class);

  private final HomeFileConf config;
  private final FileSystem fs;
  private final HostNameProvider hostNameProvider;
  private final SecurityContext securityContext;

  public interface HostNameProvider extends Supplier<String> {}

  @Inject
  public HomeFileTool(
      CatalogService catalog,
      HostNameProvider hostnameProvider,
      @Context SecurityContext securityContext)
      throws ExecutionSetupException {
    StoragePlugin plugin;
    try {
      plugin = catalog.getSource(HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME);
    } catch (Exception e) {
      // When sources have permission issues or unavailable in the backend,
      // we get '500 Internal Server Error' with no details in the exception. See Jira: DX-44078.
      if (e instanceof UserException
          && ((UserException) e).getErrorType()
              == UserBitShared.DremioPBError.ErrorType.SOURCE_BAD_STATE) {
        logger.error(
            "The source [{}] is in bad state. {}",
            HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME,
            e.getMessage(),
            e);
        throw new SourceBadStateException(
            HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME, e.getMessage(), e);
      }
      logger.error(
          "Exception while getting the plugin for the source [{}].",
          HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME,
          e);
      throw e;
    }
    Preconditions.checkNotNull(
        plugin, "Plugin [%s] not found.", HomeFileSystemStoragePlugin.HOME_PLUGIN_NAME);

    HomeFileSystemStoragePlugin homePlugin = (HomeFileSystemStoragePlugin) plugin;
    this.fs = homePlugin.getSystemUserFS();
    this.config = homePlugin.getConfig();
    this.hostNameProvider = hostnameProvider;
    this.securityContext = securityContext;
  }

  @VisibleForTesting
  public HomeFileTool(
      HomeFileConf config, FileSystem fs, String hostname, SecurityContext securityContext) {
    this.config = config;
    this.fs = fs;
    this.hostNameProvider = () -> hostname;
    this.securityContext = securityContext;
  }

  /**
   * Temporary location for file upload. Add uuid so that this location remains unique even across
   * file renames.
   *
   * @param filePath file path in under home space
   * @param extension file extension
   * @return location of staging dir where user file is uploaded.
   */
  @VisibleForTesting
  public Path getStagingLocation(FilePath filePath, String extension) {
    FilePath uniquePath =
        filePath.rename(
            format("%s_%s-%s", filePath.getFileName().toString(), extension, UUID.randomUUID()));
    Path stagingLocation =
        Path.mergePaths(
            config.getStagingPath(hostNameProvider.get()),
            PathUtils.toFSPath(uniquePath.toPathList()));
    if (!validStagingLocation(stagingLocation)) {
      throw new IllegalArgumentException("Invalid staging location provided");
    }
    return stagingLocation;
  }

  public HomeFileConf getConfForBackup() {
    return config;
  }

  /**
   * Some filesystem requires path to be resolved in order to be able to write
   *
   * @param parent parent directory
   * @param fileName file name
   * @return Returns the file path.
   */
  private Path filePath(Path parent, String fileName) throws IOException {
    return fs.canonicalizePath(parent.resolve(fileName));
  }

  private Path getUploadLocation(FilePath filePath, String extension) {
    FilePath filePathWithExtension =
        filePath.rename(format("%s_%s", filePath.getFileName().getName(), extension));
    Path uploadPath =
        Path.mergePaths(
            config.getInnerUploads(), PathUtils.toFSPath(filePathWithExtension.toPathList()));
    if (!validUploadLocation(uploadPath)) {
      throw new IllegalArgumentException("Invalid upload location provided");
    }
    return uploadPath;
  }

  /**
   * Upload and hold file in staging area.
   *
   * @param filePath file path in under home space
   * @param input input stream containing file's data
   * @return location where file is staged
   * @throws IOException - An exception that might occur if the file system cannot be written to.
   */
  @WithSpan
  public Path stageFile(FilePath filePath, String extension, InputStream input) throws IOException {
    final Path stagingLocation = getStagingLocation(filePath, extension);
    fs.mkdirs(stagingLocation, HomeFileSystemStoragePlugin.DEFAULT_PERMISSIONS);
    try (final InputStream is = input;
        final OutputStream output =
            fs.create(
                filePath(
                    stagingLocation, format("%s.%s", filePath.getFileName().getName(), extension)),
                true)) {
      IOUtils.copy(is, output);
    }
    return fs.makeQualified(stagingLocation);
  }

  @WithSpan
  public Path saveFile(String stagingLocation, FilePath filePath, FileType fileType)
      throws IOException {
    return saveFile(Path.of(stagingLocation), filePath, FileFormat.getExtension(fileType));
  }

  /**
   * Save staged file to final location
   *
   * @param stagingLocation staging directory where file is uploaded
   * @param filePath file path in under home space
   * @return final location of file
   * @throws IOException - An exception if the file system cannot be written to.
   */
  @VisibleForTesting
  public Path saveFile(Path stagingLocation, FilePath filePath, String extension)
      throws IOException {
    if (!validStagingLocation(stagingLocation)) {
      throw new IllegalArgumentException("Invalid save location provided");
    }

    final Path uploadLocation = getUploadLocation(filePath, extension);
    fs.mkdirs(uploadLocation.getParent());
    fs.rename(stagingLocation, uploadLocation);
    return uploadLocation;
  }

  private boolean validBaseLocation(Path location) {
    final Path pathToValidate = fs.makeQualified(location);

    final Path validBasePath = fs.makeQualified(config.getBaseUploadsPath());

    return pathToValidate.toURI().getScheme().equals(validBasePath.toURI().getScheme())
        && PathUtils.checkNoAccessOutsideBase(validBasePath, pathToValidate);
  }

  private boolean validUploadLocation(Path uploadLocation) {
    final Path pathToValidate = fs.makeQualified(uploadLocation);

    // the path to validate against should include the username
    final HomeName userHomePath =
        HomeName.getUserHomePath(securityContext.getUserPrincipal().getName());
    final Path validBasePath =
        fs.makeQualified(config.getInnerUploads().resolve(userHomePath.getName()));

    return pathToValidate.toURI().getScheme().equals(validBasePath.toURI().getScheme())
        && PathUtils.checkNoAccessOutsideBase(validBasePath, pathToValidate);
  }

  /**
   * Validates that the staging location is valid for the current user.
   *
   * @param stagingLocation staging directory where file is uploaded
   * @return if the location is valid or not
   */
  @WithSpan
  public boolean validStagingLocation(Path stagingLocation) {
    final Path pathToValidate = fs.makeQualified(stagingLocation);

    // the path to validate against should include the username
    final HomeName userHomePath =
        HomeName.getUserHomePath(securityContext.getUserPrincipal().getName());
    final Path validBasePath =
        fs.makeQualified(
            config.getStagingPath(hostNameProvider.get()).resolve(userHomePath.getName()));

    return pathToValidate.toURI().getScheme().equals(validBasePath.toURI().getScheme())
        && PathUtils.checkNoAccessOutsideBase(validBasePath, pathToValidate);
  }

  /**
   * Delete file uploaded by user
   *
   * @throws IllegalArgumentException - If the file location is invalid.
   * @throws IOException - An exception if the file system cannot be written to.
   */
  @WithSpan
  public void deleteUploadedFile(String fileLocation) throws IOException {
    Preconditions.checkNotNull(fileLocation);

    if (!validUploadLocation(Path.of(fileLocation))) {
      throw new IllegalArgumentException("Invalid delete location provided");
    }

    if (fs.exists(Path.of(fileLocation))) {
      fs.delete(Path.of(fileLocation), true);
    }
  }

  /**
   * Delete the contents in given user home.
   *
   * @param userHome - The location of a user's home space.
   * @return Whether successful or not.
   * @throws IOException - An exception if the file system cannot be written to.
   */
  @WithSpan
  public boolean deleteHomeAndContents(String userHome) throws IOException {
    final Path homePath = config.getInnerUploads().resolve(userHome);

    if (!validBaseLocation(homePath)) {
      throw new IllegalArgumentException("Invalid delete home location provided");
    }

    if (fs.exists(homePath)) {
      return fs.delete(homePath, true);
    }

    return true;
  }

  @VisibleForTesting
  public void clearUploads() throws IOException {
    fs.delete(config.getInnerUploads(), true);
    fs.mkdirs(config.getInnerUploads());
  }

  @VisibleForTesting
  public void clear() throws Exception {
    if (fs != null) {
      fs.delete(config.getBaseUploadsPath(), true);
    }
  }
}
