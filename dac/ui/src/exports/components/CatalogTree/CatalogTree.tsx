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

import { memo, useState, type FC } from "react";
import { useSelector } from "react-redux";
import { useCatalogRoot } from "@inject/exports/providers/useCatalogRoot";
import { getHomeSource, getSources } from "#oss/selectors/home";
import {
  ExpandedContextProvider,
  FocusContext,
  useTree,
  useTreeKeyboardListener,
} from "../Tree";
import { CatalogTreeChildren } from "./CatalogTreeChildren";
import { PathPrefixContext } from "./prefixContext";

export const CatalogTree: FC<
  Partial<{ sort: "asc" | "desc"; starsOnly: boolean }>
> = memo(function CatalogTree(props) {
  const catalogReferences = useCatalogRoot(props);
  const [focusedEl, setFocusedEl] = useState<HTMLElement>();

  const { treeRef, onFocus, onBlur } = useTreeKeyboardListener({
    handleFocus: (el: HTMLElement) => setFocusedEl(el),
  });
  const treeProps = useTree({
    childrenIds: catalogReferences.map((ref) => ref.id),
  });

  const expandedSource: string | undefined = useSelector((state) =>
    getHomeSource(getSources(state)),
  )?.get("id");

  return (
    // @ts-expect-error `split()` expects a separator but accepts undefined
    <ExpandedContextProvider initialExpansionState={expandedSource?.split()}>
      <PathPrefixContext.Provider value={null}>
        <FocusContext.Provider value={focusedEl}>
          <div
            ref={(r) => (treeRef.current = r ? r : undefined)}
            className="tree px-105 pt-05 overflow-y-auto"
            onFocus={onFocus}
            onBlur={onBlur}
            tabIndex={0}
            {...treeProps}
          >
            {props.starsOnly && !catalogReferences.length ? (
              <p className="flex flex-row items-center justify-center h-10 dremio-typography-large">
                No entities starred
              </p>
            ) : (
              <CatalogTreeChildren catalogReferences={catalogReferences} />
            )}
          </div>
        </FocusContext.Provider>
      </PathPrefixContext.Provider>
    </ExpandedContextProvider>
  );
});
