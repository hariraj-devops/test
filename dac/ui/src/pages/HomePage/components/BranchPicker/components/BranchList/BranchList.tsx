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
import { useCallback, useMemo, useRef, useState } from "react";
import { useIntl } from "react-intl";
import { usePromise } from "react-smart-promise";
import { AutoSizer, List } from "react-virtualized";
import { MenuItem } from "@mui/material";

import { useNessieContext } from "#oss/pages/NessieHomePage/utils/context";
import { Reference } from "#oss/types/nessie";
import { SearchField } from "components/Fields";
import RefIcon from "../RefIcon/RefIcon";
import { SelectSingleList } from "dremio-ui-lib/components";

import "./BranchList.less";

const LIST_ITEM_HEIGHT = 32;

function isHeader(el: any) {
  return el.isHeader === true;
}

type BranchListProps = {
  onClick?: (reference: Reference) => void;
  currentReference: Reference;
  defaultReference: Reference;
};

function BranchList({
  onClick,
  currentReference,
  defaultReference,
}: BranchListProps) {
  const intl = useIntl();
  const ref = useRef(null);
  const { apiV2 } = useNessieContext();

  const [, data] = usePromise(
    useCallback(
      () => apiV2.getAllReferencesV2({ maxRecords: 1000000 }),
      [apiV2],
    ),
  );
  const branchList = useMemo(() => {
    if (!data) return [];
    const branches = data.references;
    return [
      {
        isHeader: true,
        name: intl.formatMessage({ id: "Nessie.DefaultBranchHeader" }),
      },
      defaultReference,
      {
        isHeader: true,
        name: intl.formatMessage({ id: "Nessie.AllBranchesHeader" }),
      },
      ...(branches as Reference[]).filter(
        (b) => b.name !== defaultReference.name,
      ),
    ];
  }, [data, defaultReference, intl]);

  const [search, setSearch] = useState("");
  const filteredList = useMemo(() => {
    return !search
      ? branchList
      : (branchList as Reference[]).filter((cur) => {
          return (
            isHeader(cur) ||
            cur.name.toLowerCase().includes(search.toLowerCase().trim())
          );
        });
  }, [search, branchList]);

  function renderRow({ index, key, style }: any) {
    const cur = filteredList[index] as Reference;
    return (
      <div key={key} style={style}>
        {isHeader(cur) ? (
          <MenuItem disabled className="branchList-header-item">
            <span className="text-ellipsis">{cur.name}</span>
          </MenuItem>
        ) : (
          <MenuItem
            {...(onClick && {
              onClick: () => onClick(cur as Reference),
              onKeyDown: (e) => {
                if (e.code === "Enter") {
                  onClick(cur as Reference);
                }
              },
            })}
            data-testid={`branch-${cur.name}`}
            className="branchList-item"
            selected={cur.name === currentReference.name}
            title={cur.name}
            tabIndex={cur.name === currentReference.name ? 0 : -1}
          >
            <span className="branchList-item-icon">
              <RefIcon reference={cur} style={{ width: 20, height: 20 }} />
            </span>
            <span className="text-ellipsis">{cur.name}</span>
          </MenuItem>
        )}
      </div>
    );
  }

  return (
    <div className="branchList">
      <div className="branchList-search">
        <SearchField
          showIcon
          onChange={setSearch}
          placeholder={intl.formatMessage({
            id: "Nessie.BranchSearchPlaceholder",
          })}
        />
      </div>
      <div className="branchList-listContainer">
        <SelectSingleList
          listItems={filteredList}
          currentItemId={currentReference.name}
          aria-label="Branch list"
          itemRenderer={(item: any) =>
            isHeader(item) ? (
              <li
                className="branchList-header-item"
                style={{ height: LIST_ITEM_HEIGHT }}
              >
                <span className="text-ellipsis">{item.name}</span>
              </li>
            ) : (
              <li
                {...(onClick && {
                  onClick: () => onClick(item as Reference),
                })}
                id={item.name}
                data-testid={`branch-${item.name}`}
                className="branchList-item listbox-item"
                title={item.name}
                style={{ height: LIST_ITEM_HEIGHT }}
                role="option"
                aria-selected={item.name === currentReference.name}
              >
                <span className="branchList-item-icon">
                  <RefIcon
                    reference={item as Reference}
                    style={{ width: 20, height: 20 }}
                  />
                </span>
                <span className="text-ellipsis">{item.name}</span>
              </li>
            )
          }
        />
      </div>
    </div>
  );
}

export default BranchList;
