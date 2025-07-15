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
import { CatalogReferenceIcon } from "./CatalogReferenceIcon";
import clsx from "clsx";
import Highlighter from "react-highlight-words";

export const CatalogReferenceDisplay: FC<{
  catalogReference: CatalogReference;
  className?: string;
  searchWords?: string[];
  searchWordsWithInFilter?: string[];
  showPath?: boolean;
}> = (props) => {
  const modifiedSearchWords =
    props.searchWordsWithInFilter || props.searchWords;
  return (
    <div
      className={clsx(
        "flex flex-row items-center gap-rel-1 overflow-hidden",
        props.className,
      )}
      draggable="true"
      onDragStart={(e) => {
        e.dataTransfer.setData(
          "text/plain",
          props.catalogReference.pathString(),
        );
        e.dataTransfer.setData(
          "text/json",
          JSON.stringify({
            type: "CatalogObject",
            data: {
              id: props.catalogReference.id,
              path: props.catalogReference.path,
              type: props.catalogReference.type,
            },
          }),
        );
      }}
    >
      <CatalogReferenceIcon
        catalogReference={props.catalogReference}
        style={{ width: "1.425em", height: "1.425em" }}
      />
      <div className="flex flex-col gap-rel-05 overflow-hidden">
        <div
          title={props.catalogReference.name}
          className={clsx({ "text-semibold": props.showPath }, "truncate")}
        >
          {props.searchWords ? (
            <Highlighter
              searchWords={props.searchWords}
              textToHighlight={props.catalogReference.name}
            />
          ) : (
            props.catalogReference.name
          )}
        </div>
        {props.showPath && (
          <div
            title={props.catalogReference.pathString(".")}
            className="text-rel-sm dremio-typography-less-important truncate"
          >
            {modifiedSearchWords ? (
              <Highlighter
                searchWords={modifiedSearchWords}
                textToHighlight={props.catalogReference.pathString(".")}
              />
            ) : (
              props.catalogReference.pathString(".")
            )}
          </div>
        )}
      </div>
    </div>
  );
};
