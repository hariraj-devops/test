//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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

import { type FC } from "react";
import type {
  CatalogObject,
  SourceCatalogObject,
  VersionedDatasetCatalogObject,
} from "@dremio/dremio-js/oss";

const getIconForVersionedDataset = (
  versionedDataset: VersionedDatasetCatalogObject,
) => {
  switch (versionedDataset.catalogReference.type) {
    case "DATASET_VIRTUAL":
      return <dremio-icon name="entities/iceberg-view" class="h-3 w-3" />;
    default:
      return <dremio-icon name="entities/iceberg-table" class="h-3 w-3" />;
  }
};

const getIconForSource = (source: SourceCatalogObject) => {
  switch (source.type) {
    case "NESSIE":
      return <dremio-icon name="entities/nessie-source" class="h-3 w-3" />;
    default:
      return <dremio-icon name="entities/datalake-source" class="h-3 w-3" />;
  }
};

const getIconForBadSource = (source: SourceCatalogObject) => {
  switch (source.type) {
    case "NESSIE":
      return <dremio-icon name="entities/nessie-source-bad" class="h-3 w-3" />;
    default:
      return (
        <dremio-icon name="entities/datalake-source-bad" class="h-3 w-3" />
      );
  }
};

export const CatalogObjectIcon: FC<{
  catalogObject: CatalogObject;
  isFailedState?: boolean;
}> = (props) => {
  try {
    JSON.parse(props.catalogObject.catalogReference.id);

    // folders can have parseable IDs and need to be skipped here
    if (props.catalogObject.catalogReference.type !== "FOLDER") {
      return getIconForVersionedDataset(
        props.catalogObject as VersionedDatasetCatalogObject,
      );
    }
  } catch {
    // continue
  }

  switch (props.catalogObject.catalogReference.type) {
    case "DATASET_DIRECT":
    case "DATASET_PROMOTED":
      return <dremio-icon name="entities/dataset-table" class="h-3 w-3" />;
    case "DATASET_VIRTUAL":
      return <dremio-icon name="entities/dataset-view" class="h-3 w-3" />;
    case "FILE":
      return <dremio-icon name="entities/file" class="h-3 w-3" />;
    case "FOLDER":
      return <dremio-icon name="catalog/folder" class="h-3 w-3" />;
    case "FUNCTION":
      return (
        <dremio-icon name="catalog/function" class="h-3 w-3 icon-primary" />
      );
    case "HOME":
      return <dremio-icon name="entities/home" class="h-3 w-3 icon-primary" />;
    case "SPACE":
      return <dremio-icon name="entities/space" class="h-3 w-3" />;
    case "SOURCE":
      return props.isFailedState
        ? getIconForBadSource(props.catalogObject as SourceCatalogObject)
        : getIconForSource(props.catalogObject as SourceCatalogObject);
    default:
      return <dremio-icon name="data-types/TypeOther" class="h-3 w-3" />;
  }
};
