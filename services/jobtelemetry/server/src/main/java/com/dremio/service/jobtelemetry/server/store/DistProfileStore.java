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
package com.dremio.service.jobtelemetry.server.store;

import com.dremio.common.utils.ProfilesPathUtils;
import com.dremio.common.utils.protos.ExternalIdHelper;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.proto.CoordExecRPC;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of profile store, keeps all profiles in dist store */
public class DistProfileStore implements ProfileStore {
  private static final Logger LOGGER = LoggerFactory.getLogger(DistProfileStore.class);

  private final Path profileStoreLocation;
  private final FileSystem dfs;

  public DistProfileStore(Provider<ProfileDistStoreConfig> profileDistStoreConfigProvider) {
    this.dfs = profileDistStoreConfigProvider.get().getFileSystem();
    this.profileStoreLocation = profileDistStoreConfigProvider.get().getStoragePath();
  }

  @Override
  public void start() throws Exception {
    LOGGER.info("Starting Profiles dist store");
    dfs.mkdirs(profileStoreLocation);
  }

  @Override
  public void putPlanningProfile(QueryId queryId, QueryProfile planningProfile) {
    LOGGER.debug("Put planning profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      putProfile(queryId, ProfilesPathUtils.buildPlanningProfilePath(queryId), planningProfile);
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error writing planning profile {}",
          QueryIdHelper.getQueryId(queryId),
          th.getMessage());
    }
  }

  @Override
  public Optional<QueryProfile> getPlanningProfile(QueryId queryId) {
    LOGGER.debug("Get planning profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      return Optional.of(
          getProfile(
              queryId, ProfilesPathUtils.buildPlanningProfilePath(queryId), QueryProfile.parser()));
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error getting planning profile {}",
          QueryIdHelper.getQueryId(queryId),
          th.getMessage());
    }
    return Optional.empty();
  }

  @Override
  public void putTailProfile(QueryId queryId, QueryProfile tailProfile) {
    LOGGER.debug("Put tail profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      putProfile(queryId, ProfilesPathUtils.buildTailProfilePath(queryId), tailProfile);
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error writing tail profile {}", QueryIdHelper.getQueryId(queryId), th.getMessage());
    }
  }

  @Override
  public Optional<QueryProfile> getTailProfile(QueryId queryId) {
    LOGGER.debug("Get tail profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      return Optional.of(
          getProfile(
              queryId, ProfilesPathUtils.buildTailProfilePath(queryId), QueryProfile.parser()));
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error getting tail profile {}", QueryIdHelper.getQueryId(queryId), th.getMessage());
    }
    return Optional.empty();
  }

  @Override
  public void putFullProfile(QueryId queryId, QueryProfile fullProfile) {
    LOGGER.debug("Put full profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      putProfile(queryId, ProfilesPathUtils.buildFullProfilePath(queryId), fullProfile);
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error writing full profile {}", QueryIdHelper.getQueryId(queryId), th.getMessage());
    }
  }

  @Override
  public Optional<QueryProfile> getFullProfile(QueryId queryId) {
    LOGGER.debug("Get full profile request {}", QueryIdHelper.getQueryId(queryId));
    try {
      return Optional.of(
          getProfile(
              queryId, ProfilesPathUtils.buildFullProfilePath(queryId), QueryProfile.parser()));
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error getting full profile {}", QueryIdHelper.getQueryId(queryId), th.getMessage());
    }
    return Optional.empty();
  }

  @Override
  public void putExecutorProfile(
      QueryId queryId,
      CoordinationProtos.NodeEndpoint endpoint,
      CoordExecRPC.ExecutorQueryProfile executorQueryProfile,
      boolean isFinal) {
    LOGGER.debug(
        "Put Executor profile request {} {}",
        QueryIdHelper.getQueryId(queryId),
        endpoint.getAddress());
    try {
      putProfile(
          queryId,
          ProfilesPathUtils.buildExecutorProfilePathWithEndpointAddress(queryId, endpoint, isFinal),
          executorQueryProfile);
    } catch (Throwable th) {
      LOGGER.error(
          "{} {} Error writing executor profile {}",
          QueryIdHelper.getQueryId(queryId),
          endpoint.getAddress(),
          th.getMessage());
    }
  }

  @Override
  public Stream<CoordExecRPC.ExecutorQueryProfile> getAllExecutorProfiles(QueryId queryId) {
    LOGGER.debug("Get executor profiles request {}", QueryIdHelper.getQueryId(queryId));
    try {
      Path path =
          dfs.canonicalizePath(
              Path.mergePaths(
                  profileStoreLocation,
                  Path.mergePaths(
                      Path.of(getDate(queryId)),
                      Path.of(ProfilesPathUtils.buildExecutorProfilePrefix(queryId)))));
      DirectoryStream<FileAttributes> dirStream = dfs.list(path);

      Set<String> allBlobs =
          StreamSupport.stream(dirStream.spliterator(), false)
              .map(e -> e.getPath().toString())
              .collect(Collectors.toSet());

      Set<String> blobsToFetch =
          allBlobs.stream()
              .filter(e -> e.endsWith(ProfilesPathUtils.FINAL_EXECUTOR_PROFILE_SUFFIX))
              .collect(Collectors.toSet());

      allBlobs.stream()
          .filter(e -> (!e.endsWith(ProfilesPathUtils.FINAL_EXECUTOR_PROFILE_SUFFIX)))
          .forEach(
              e -> {
                if (!blobsToFetch.contains(e + ProfilesPathUtils.FINAL_EXECUTOR_PROFILE_SUFFIX)) {
                  blobsToFetch.add(e);
                }
              });

      List<CoordExecRPC.ExecutorQueryProfile> executorQueryProfileList = new ArrayList<>();
      for (String executorPath : blobsToFetch) {
        try (final InputStream fsin = new BufferedInputStream(dfs.open(Path.of(executorPath)))) {
          executorQueryProfileList.add(CoordExecRPC.ExecutorQueryProfile.parser().parseFrom(fsin));
        }
      }
      return executorQueryProfileList.stream();

    } catch (Throwable th) {
      LOGGER.error(
          "{} Error getting executor profiles {}",
          QueryIdHelper.getQueryId(queryId),
          th.getMessage());
    }
    return Stream.empty();
  }

  @Override
  public void deleteSubProfiles(QueryId queryId) {
    try {
      deleteProfile(queryId, ProfilesPathUtils.buildIntermediatePrefix(queryId));
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error deleting intermediate profiles {}",
          QueryIdHelper.getQueryId(queryId),
          th.getMessage());
    }
  }

  @Override
  public void deleteProfile(QueryId queryId) {
    try {
      deleteProfile(queryId, ProfilesPathUtils.buildProfilePrefix(queryId));
    } catch (Throwable th) {
      LOGGER.error(
          "{} Error deleting profile {}", QueryIdHelper.getQueryId(queryId), th.getMessage());
    }
  }

  private void putProfile(QueryId queryId, String relativePath, Message profile)
      throws IOException {
    Path path =
        dfs.canonicalizePath(
            Path.mergePaths(
                profileStoreLocation,
                Path.mergePaths(Path.of(getDate(queryId)), Path.of(relativePath))));
    try (final OutputStream fsout = new BufferedOutputStream(dfs.create(path))) {
      profile.writeTo(fsout);
    }
  }

  private <T extends Message> T getProfile(QueryId queryId, String relativePath, Parser<T> parser)
      throws IOException {
    Path path =
        dfs.canonicalizePath(
            Path.mergePaths(
                profileStoreLocation,
                Path.mergePaths(Path.of(getDate(queryId)), Path.of(relativePath))));
    try (final InputStream fsin = new BufferedInputStream(dfs.open(path))) {
      return parser.parseFrom(fsin);
    }
  }

  private void deleteProfile(QueryId queryId, String prefix) throws IOException {
    Path path =
        dfs.canonicalizePath(
            Path.mergePaths(
                profileStoreLocation, Path.mergePaths(Path.of(getDate(queryId)), Path.of(prefix))));
    dfs.delete(path, true);
  }

  private String getDate(QueryId queryId) {
    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
    return simpleDateFormat.format(new Date(ExternalIdHelper.getTimeStamp(queryId)));
  }

  @Override
  public void close() throws Exception {}
}
