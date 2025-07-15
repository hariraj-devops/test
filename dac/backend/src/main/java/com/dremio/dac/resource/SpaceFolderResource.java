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
package com.dremio.dac.resource;

import static com.dremio.service.namespace.proto.NameSpaceContainer.Type.FOLDER;

import com.dremio.common.utils.PathUtils;
import com.dremio.dac.annotations.RestResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.model.folder.FolderModel;
import com.dremio.dac.model.folder.FolderName;
import com.dremio.dac.model.folder.FolderPath;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.spaces.SpaceName;
import com.dremio.dac.server.GenericErrorMessage;
import com.dremio.dac.service.collaboration.CollaborationHelper;
import com.dremio.dac.service.datasets.DatasetVersionMutator;
import com.dremio.dac.service.errors.ClientErrorException;
import com.dremio.dac.service.errors.DatasetNotFoundException;
import com.dremio.dac.service.errors.FolderNotFoundException;
import com.dremio.dac.util.ResourceUtil;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.space.proto.FolderConfig;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

/** Rest resource for spaces. */
@RestResource
@Secured
@RolesAllowed({"admin", "user"})
@Path("/space/{space}")
public class SpaceFolderResource {
  private final DatasetVersionMutator datasetService;
  private final NamespaceService namespaceService;
  private final CollaborationHelper collaborationHelper;
  private final SpaceName spaceName;

  @Inject
  public SpaceFolderResource(
      DatasetVersionMutator datasetService,
      NamespaceService namespaceService,
      CollaborationHelper collaborationHelper,
      @PathParam("space") SpaceName spaceName) {
    this.datasetService = datasetService;
    this.namespaceService = namespaceService;
    this.collaborationHelper = collaborationHelper;
    this.spaceName = spaceName;
  }

  // TODO(DX-98540): Refactor to call into CatalogFolder interface instead of NamespaceService
  @GET
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public FolderModel getFolder(
      @PathParam("path") String path,
      @QueryParam("includeContents") @DefaultValue("true") boolean includeContents)
      throws NamespaceException, FolderNotFoundException, DatasetNotFoundException {
    FolderPath folderPath = FolderPath.fromURLPath(spaceName, path);
    try {
      FolderConfig folderConfig = namespaceService.getFolder(folderPath.toNamespaceKey());
      NamespaceTree contents = null;
      if (includeContents) {
        contents =
            newNamespaceTree(
                namespaceService.list(folderPath.toNamespaceKey(), null, Integer.MAX_VALUE));
      }
      return newSpaceFolder(folderPath, folderConfig, contents);
    } catch (NamespaceNotFoundException nfe) {
      throw new FolderNotFoundException(folderPath, nfe);
    }
  }

  @DELETE
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  public void deleteFolder(@PathParam("path") String path, @QueryParam("version") String version)
      throws NamespaceException, FolderNotFoundException {
    FolderPath folderPath = FolderPath.fromURLPath(spaceName, path);
    if (version == null) {
      throw new ClientErrorException(GenericErrorMessage.MISSING_VERSION_PARAM_MSG);
    }

    try {
      namespaceService.deleteFolder(folderPath.toNamespaceKey(), version);
    } catch (NamespaceNotFoundException nfe) {
      throw new FolderNotFoundException(folderPath, nfe);
    } catch (ConcurrentModificationException e) {
      throw ResourceUtil.correctBadVersionErrorMessage(e, "folder", path);
    }
  }

  @POST
  @Path("/folder/{path: .*}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public FolderModel createFolder(FolderName name, @PathParam("path") String path)
      throws NamespaceException {
    String fullPath = PathUtils.toFSPathString(Arrays.asList(path, name.toString()));
    FolderPath folderPath = FolderPath.fromURLPath(spaceName, fullPath);

    final FolderConfig folderConfig = new FolderConfig();
    folderConfig.setFullPathList(folderPath.toPathList());
    folderConfig.setName(folderPath.getFolderName().getName());
    try {
      namespaceService.addOrUpdateFolder(folderPath.toNamespaceKey(), folderConfig);
    } catch (NamespaceNotFoundException nfe) {
      throw new ClientErrorException("Parent folder doesn't exist", nfe);
    }

    return newSpaceFolder(folderPath, folderConfig, null);
  }

  protected FolderModel newSpaceFolder(
      FolderPath folderPath, FolderConfig folderConfig, NamespaceTree contents)
      throws NamespaceNotFoundException {
    String id =
        folderConfig.getId() == null ? folderPath.toUrlPath() : folderConfig.getId().getId();
    return new FolderModel(
        id,
        folderConfig.getName(),
        folderPath.toUrlPath(),
        folderConfig.getIsPhysicalDataset(),
        false,
        false,
        folderConfig.getExtendedConfig(),
        folderConfig.getTag(),
        null,
        contents,
        null,
        0,
        folderConfig.getStorageUri(),
        folderConfig.getTag());
  }

  protected NamespaceTree newNamespaceTree(List<NameSpaceContainer> children)
      throws DatasetNotFoundException, NamespaceException {
    return NamespaceTree.newInstance(datasetService, children, FOLDER, collaborationHelper);
  }
}
