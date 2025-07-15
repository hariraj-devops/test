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
import type { SourceCatalogObject } from "@dremio/dremio-js/oss";
import NessieSourceBranchPicker from "#oss/pages/HomePage/components/SourceBranchPicker/SourceBranchPicker";
import { type VersionState } from "#oss/queries/catalog";

/**
 * Renders a branch picker tag for versioned sources
 */
export const BranchPickerButton: FC<{
  catalogObject: SourceCatalogObject;
  setVersion: (val: VersionState) => unknown;
  tabIndex: number;
}> = (props) => (
  <NessieSourceBranchPicker
    source={props.catalogObject}
    redirect={false}
    onApply={(_stateKey, { reference, hash }) => {
      if (reference) {
        if (hash) {
          props.setVersion({
            type: "COMMIT",
            name: reference.name,
            hash,
          });

          return;
        }

        props.setVersion({
          type: reference.type,
          name: reference.name,
          hash: hash || reference.hash,
        });
      }
    }}
    tabIndex={props.tabIndex}
  />
);
