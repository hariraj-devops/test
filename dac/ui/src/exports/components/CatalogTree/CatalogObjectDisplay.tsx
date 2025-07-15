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
import type { CatalogObject } from "@dremio/dremio-js/oss";
import { Popover } from "dremio-ui-lib/components";
import { CatalogObjectIcon } from "dyn-load/exports/components/CatalogTree/CatalogObjectIcon";

const withSummaryOverlay =
  (summaryOverlay?: React.ComponentProps<typeof Popover>["content"]) =>
  (target: JSX.Element) =>
    summaryOverlay ? (
      <Popover
        role="tooltip"
        showArrow
        delay={750}
        placement="right"
        mode="hover"
        portal
        content={summaryOverlay}
      >
        {target}
      </Popover>
    ) : (
      target
    );

export const CatalogObjectDisplay: FC<{
  catalogObject: CatalogObject;
  customIcon?: JSX.Element;
  summaryOverlay?: React.ComponentProps<typeof Popover>["content"];
}> = (props) => {
  const wrapOverlay = withSummaryOverlay(props.summaryOverlay);

  return wrapOverlay(
    <div
      className="flex flex-row items-center gap-05 overflow-hidden"
      draggable="true"
      onDragStart={(e) => {
        e.dataTransfer.setData("text/plain", props.catalogObject.pathString());
        e.dataTransfer.setData(
          "text/json",
          JSON.stringify({
            type: "CatalogObject",
            data: {
              id: props.catalogObject.id,
              path: props.catalogObject.path,
              type: props.catalogObject.catalogReference.type,
            },
          }),
        );
      }}
    >
      <div>
        {props.customIcon ?? (
          <CatalogObjectIcon catalogObject={props.catalogObject} />
        )}
      </div>
      <div className="truncate">{props.catalogObject.name}</div>
    </div>,
  );
};
