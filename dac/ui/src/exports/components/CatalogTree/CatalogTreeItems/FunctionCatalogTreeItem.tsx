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

import { FC } from "react";
import { useTreeItem } from "../../Tree";
import type {
  FunctionCatalogObject,
  FunctionCatalogReference,
} from "@dremio/dremio-js/oss";
import { useSuspenseQuery } from "@tanstack/react-query";
import { catalogByPath } from "@inject/queries/catalog";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { CatalogObjectDisplay } from "dremio-ui-common/catalog/CatalogObjectDisplay.js";

export const FunctionCatalogTreeItem: FC<{
  catalogReference: FunctionCatalogReference;
}> = (props) => {
  const catalogObject = useSuspenseQuery(
    catalogByPath(getSonarContext().getSelectedProjectId?.())(
      props.catalogReference.path,
    ),
  ).data.unwrap() as FunctionCatalogObject;

  const treeItemProps = useTreeItem(props.catalogReference.id);

  return (
    <>
      <div {...treeItemProps}>
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          <CatalogObjectDisplay catalogObject={catalogObject} />
        </div>
      </div>
    </>
  );
};
