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

import { useContext, type FC } from "react";
import type {
  CatalogReference,
  Problem,
  SourceCatalogObject,
} from "@dremio/dremio-js/oss";
import { useQuery } from "@tanstack/react-query";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import { catalogByPath } from "@inject/queries/catalog";
import { OpenDetailsButton } from "./CatalogTreeItems/components/OpenDetailsButton";
import { AddToQueryButton } from "./CatalogTreeItems/components/AddToQueryButton";
import { StarredButton } from "@inject/exports/components/CatalogTree/CatalogTreeItems/components/StarredButton";
import { SectionMessageErrorFromProblem } from "../SectionMessageErrorFromProblem";
import { renderExpandedIcon, useTreeItem } from "../Tree";
import { CatalogObjectBadState } from "./CatalogObjectBadState";
import { PathPrefixContext } from "./prefixContext";

export const FailedCatalogTreeItem: FC<{
  catalogReference: CatalogReference;
  error?: Error | Problem;
}> = (props) => {
  // catalogObject is only needed to get the correct Source type (e.g. Nessie)
  const catalogObject = useQuery({
    ...catalogByPath(getSonarContext().getSelectedProjectId?.())(
      props.catalogReference.path,
    ),
    enabled: props.catalogReference.type === "SOURCE",
  }).data?.unwrapOr(undefined) as SourceCatalogObject | undefined;

  const pathPrefix = useContext(PathPrefixContext);

  const treeItemProps = useTreeItem(
    (pathPrefix ? `${pathPrefix}-` : "") + props.catalogReference.id,
  );

  return (
    <>
      <div {...treeItemProps} aria-label={props.catalogReference.name}>
        <div className="flex flex-row items-center h-full position-relative overflow-hidden">
          {props.error ? (
            renderExpandedIcon(treeItemProps.isExpanded)
          ) : (
            <div className="ml-3" />
          )}
          <CatalogObjectBadState
            catalogObject={
              catalogObject ?? {
                name: props.catalogReference.name,
                catalogReference: { ...props.catalogReference },
              }
            }
          />
        </div>
        <div className="ml-auto catalog-treeitem__actions">
          <OpenDetailsButton catalogReference={props.catalogReference} />
          <AddToQueryButton catalogReference={props.catalogReference} />
          <StarredButton catalogReference={props.catalogReference} />
        </div>
      </div>
      {treeItemProps.isExpanded && !!props.error && (
        <SectionMessageErrorFromProblem
          problem={
            "title" in props.error
              ? props.error
              : ((props.error as Error).cause as Problem)
          }
        />
      )}
    </>
  );
};
