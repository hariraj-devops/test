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
import Immutable from "immutable";
import { useCanAddSource, getAddSourceHref } from "../../LeftTreeUtils";
import EmptyStateContainer from "../../../EmptyStateContainer";
import {
  isLakehouseSourceType,
  isObjectStorageSourceType,
  isDatabaseType,
  isDataPlaneSourceType,
} from "@inject/constants/sourceTypes";

export const EmptySourceContainer = ({
  sources,
  isLoading,
}: {
  sources: Immutable.List<Record<string, any>>;
  isLoading: boolean;
}) => {
  const canAddSource = useCanAddSource();
  const lakehouseSource = sources.filter((source) =>
    isLakehouseSourceType(source?.get("type")),
  );
  const objectStorageSource = sources.filter((source) =>
    isObjectStorageSourceType(source?.get("type")),
  );
  const databases = sources.filter(
    (source) =>
      isDatabaseType(source?.get("type")) &&
      !isDataPlaneSourceType(source?.get("type")),
  );
  const showEmptySourceContainer =
    lakehouseSource.size < 1 &&
    objectStorageSource.size < 1 &&
    databases.size < 1;

  if (isLoading || !showEmptySourceContainer) return null;
  return (
    <EmptyStateContainer
      title="Sources.noSources"
      icon="interface/empty-add-data"
      linkInfo={
        canAddSource
          ? {
              href: getAddSourceHref(true),
              "data-qa": "add-sources",
              label: "Sources.AddSource.LowerCase",
            }
          : undefined
      }
    />
  );
};
