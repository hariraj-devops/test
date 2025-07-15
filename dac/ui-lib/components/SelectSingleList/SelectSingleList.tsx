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

import clsx from "clsx";
import * as React from "react";
import { useSelectListKeyboardListener } from "../utilities/useSelectListKeyboardListener";

type SingleListProps = {
  listItems: any[];
  ref?: any;
  selectedItemId?: string;
  listClassName?: string;
  listItemClassName?: string;
  listHeaderItemClassName?: string;
  itemRenderer?: (item: any, index?: number) => JSX.Element;
  "aria-label"?: string;
  disabled?: boolean;
};

export const SelectSingleList = (props: SingleListProps) => {
  const { listRef } = useSelectListKeyboardListener(props.selectedItemId);

  return (
    <ul
      className={clsx("listbox", props.listClassName)}
      aria-activedescendant={props.selectedItemId}
      tabIndex={props.disabled ? -1 : 0}
      ref={(r) => (listRef.current = r as HTMLUListElement)}
      role="listbox"
      aria-label={props["aria-label"]}
    >
      {props.listItems.map((item, index) => {
        return props.itemRenderer ? (
          props.itemRenderer(item, index)
        ) : (
          <li
            key={item?.id}
            id={item?.id}
            className={clsx("listbox-item", props.listItemClassName)}
            title={item?.name}
            role="option"
            aria-selected={item?.id === props.selectedItemId}
            onClick={item?.onClick}
          >
            <span className="text-ellipsis">{item?.name}</span>
          </li>
        );
      })}
    </ul>
  );
};
