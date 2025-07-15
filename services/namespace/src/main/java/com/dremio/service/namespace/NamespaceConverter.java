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
package com.dremio.service.namespace;

import static com.dremio.common.utils.Protos.notEmpty;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_COLUMNS_NAMES;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_OWNER;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_PARENTS;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_SQL;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_UUID;
import static com.dremio.service.namespace.DatasetIndexKeys.UNQUOTED_LC_NAME;
import static com.dremio.service.namespace.DatasetIndexKeys.UNQUOTED_LC_SCHEMA;
import static com.dremio.service.namespace.DatasetIndexKeys.UNQUOTED_NAME;
import static com.dremio.service.namespace.DatasetIndexKeys.UNQUOTED_SCHEMA;

import com.dremio.common.utils.PathUtils;
import com.dremio.datastore.api.DocumentConverter;
import com.dremio.datastore.api.DocumentWriter;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import io.protostuff.ByteString;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.flatbuf.Schema;

/** Namespace search indexing. for now only support pds, vds, source and space indexing. */
public class NamespaceConverter implements DocumentConverter<String, NameSpaceContainer> {
  private Integer version = 2;

  @Override
  public Integer getVersion() {
    return version;
  }

  @Override
  public void convert(DocumentWriter writer, String key, NameSpaceContainer container) {
    writer.write(NamespaceIndexKeys.ENTITY_TYPE, container.getType().getNumber());

    final NamespaceKey nkey = new NamespaceKey(container.getFullPathList());
    NamespaceKey lkey = nkey.asLowerCase();
    writer.write(NamespaceIndexKeys.UNQUOTED_LC_PATH, lkey.toUnescapedString());

    // add standard  and lower case searches.
    writer.write(UNQUOTED_NAME, nkey.getName());
    writer.write(UNQUOTED_LC_NAME, lkey.getName());

    if (container.getType() == NameSpaceContainer.Type.DATASET) {
      writer.write(UNQUOTED_SCHEMA, nkey.getParent().toUnescapedString());
      writer.write(UNQUOTED_LC_SCHEMA, lkey.getParent().toUnescapedString());
    } else {
      writer.write(UNQUOTED_SCHEMA, nkey.toUnescapedString());
      writer.write(UNQUOTED_LC_SCHEMA, lkey.toUnescapedString());
    }

    EntityId entityId = null;
    switch (container.getType()) {
      case DATASET:
        {
          final DatasetConfig datasetConfig = container.getDataset();

          // last modified is a new field so support old entries which only have a createdAt.
          Long modified = Optional.ofNullable(datasetConfig.getCreatedAt()).orElse(0L);

          if (datasetConfig.getLastModified() != null && datasetConfig.getLastModified() > 0) {
            modified = datasetConfig.getLastModified();
          }
          writer.write(NamespaceIndexKeys.LAST_MODIFIED, modified);

          writer.write(
              DatasetIndexKeys.DATASET_ID,
              new NamespaceKey(container.getFullPathList()).getSchemaPath());

          writer.write(DATASET_UUID, datasetConfig.getId().getId());
          entityId = datasetConfig.getId();
          if (datasetConfig.getOwner() != null) {
            writer.write(DATASET_OWNER, datasetConfig.getOwner());
          }
          switch (datasetConfig.getType()) {
            case VIRTUAL_DATASET:
              {
                final VirtualDataset virtualDataset = datasetConfig.getVirtualDataset();
                writer.write(DATASET_SQL, virtualDataset.getSql());

                addParents(writer, virtualDataset.getParentsList());
                addColumns(writer, datasetConfig);
              }
              break;

            case PHYSICAL_DATASET:
            case PHYSICAL_DATASET_SOURCE_FILE:
            case PHYSICAL_DATASET_SOURCE_FOLDER:
              {
                addColumns(writer, datasetConfig);
                // TODO index physical dataset properties
              }
              break;

            default:
              break;
          }
          break;
        }

      case HOME:
        {
          HomeConfig homeConfig = container.getHome();
          writer.write(NamespaceIndexKeys.HOME_ID, homeConfig.getId().getId());
          entityId = homeConfig.getId();
          break;
        }

      case SOURCE:
        {
          final SourceConfig sourceConfig = container.getSource();
          writer.write(NamespaceIndexKeys.SOURCE_ID, sourceConfig.getId().getId());
          writer.write(NamespaceIndexKeys.LAST_MODIFIED, sourceConfig.getLastModifiedAt());
          entityId = sourceConfig.getId();
          break;
        }

      case SPACE:
        {
          final SpaceConfig spaceConfig = container.getSpace();
          writer.write(NamespaceIndexKeys.SPACE_ID, spaceConfig.getId().getId());
          writer.write(NamespaceIndexKeys.LAST_MODIFIED, spaceConfig.getCtime());
          entityId = spaceConfig.getId();
          break;
        }
      case FUNCTION:
        {
          final FunctionConfig udfConfig = container.getFunction();
          writer.write(NamespaceIndexKeys.UDF_ID, udfConfig.getId().getId());
          entityId = udfConfig.getId();
          break;
        }

      case FOLDER:
        {
          final FolderConfig folderConfig = container.getFolder();
          writer.write(NamespaceIndexKeys.FOLDER_ID, folderConfig.getId().getId());
          entityId = folderConfig.getId();
          break;
        }

      default:
        break;
    }
    writer.write(NamespaceIndexKeys.ENTITY_ID, entityId.getId());
  }

  private void addColumns(DocumentWriter writer, DatasetConfig datasetConfig) {
    String[] columns = getColumnsLowerCase(datasetConfig);

    if (columns.length > 0) {
      writer.write(DATASET_COLUMNS_NAMES, columns);
    }
  }

  public static String[] getColumnsLowerCase(DatasetConfig datasetConfig) {
    final ByteString schemaBytes = DatasetHelper.getSchemaBytes(datasetConfig);
    if (schemaBytes != null) {
      Schema schema = Schema.getRootAsSchema(schemaBytes.asReadOnlyByteBuffer());
      org.apache.arrow.vector.types.pojo.Schema s =
          org.apache.arrow.vector.types.pojo.Schema.convertSchema(schema);
      return s.getFields().stream()
          .map(input -> input.getName().toLowerCase())
          .toArray(String[]::new);
    } else {
      // If virtual dataset was created with view fields
      if (datasetConfig.getType() == DatasetType.VIRTUAL_DATASET) {
        final List<ViewFieldType> viewFieldTypes =
            datasetConfig.getVirtualDataset().getSqlFieldsList();
        if (notEmpty(viewFieldTypes)) {
          return viewFieldTypes.stream()
              .map(input -> input.getName().toLowerCase())
              .toArray(String[]::new);
        }
      }
    }

    return new String[0];
  }

  private void addParents(DocumentWriter writer, List<ParentDataset> parentDatasetList) {
    if (notEmpty(parentDatasetList)) {
      final String[] parents = new String[parentDatasetList.size()];
      int i = 0;
      for (ParentDataset parent : parentDatasetList) {
        parents[i++] = PathUtils.constructFullPath(parent.getDatasetPathList());
      }
      writer.write(DATASET_PARENTS, parents);
    }
  }
}
