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
import type { CatalogReference } from "@dremio/dremio-js/oss";
import { DatasetCatalogTreeItem } from "./CatalogTreeItems/DatasetCatalogTreeItem";
// import { FileCatalogTreeItem } from "./CatalogTreeItems/FileCatalogTreeItem";
import { FolderCatalogTreeItem } from "./CatalogTreeItems/FolderCatalogTreeItem";
// import { FunctionCatalogTreeItem } from "./CatalogTreeItems/FunctionCatalogTreeItem";
import { HomeCatalogTreeItem } from "./CatalogTreeItems/HomeCatalogTreeItem";
import { SpaceCatalogTreeItem } from "dyn-load/exports/components/CatalogTree/CatalogTreeItems/SpaceCatalogTreeItem";
import { SourceCatalogTreeItem } from "@inject/exports/components/CatalogTree/CatalogTreeItems/SourceCatalogTreeItem";

export const CatalogReferenceChild: FC<{
  catalogReference: CatalogReference;
}> = (props) => {
  switch (props.catalogReference.type) {
    case "DATASET_DIRECT":
    case "DATASET_PROMOTED":
    case "DATASET_VIRTUAL":
      return (
        <DatasetCatalogTreeItem catalogReference={props.catalogReference} />
      );
    case "FILE":
      return null;
    case "FOLDER":
      return (
        <FolderCatalogTreeItem catalogReference={props.catalogReference} />
      );
    case "FUNCTION":
      return null;
    case "HOME":
      return <HomeCatalogTreeItem catalogReference={props.catalogReference} />;
    case "SPACE":
      return <SpaceCatalogTreeItem catalogReference={props.catalogReference} />;
    case "SOURCE":
      return (
        <SourceCatalogTreeItem catalogReference={props.catalogReference} />
      );
    default:
      return null;
  }
};
