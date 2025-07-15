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

import * as React from "react";
import { useKeyboardListener } from "./useKeyboardListener";

// This assumes the list is structured as: ul > li, and li has ID
export const useSelectListKeyboardListener = (selectedItemId?: string) => {
  const listRef = React.useRef<HTMLUListElement | undefined>(undefined);

  const focusItem = React.useCallback((el: HTMLElement) => {
    el?.scrollIntoView({
      block: "nearest",
      inline: "nearest",
    });
    el?.click();
  }, []);

  useKeyboardListener((e: React.KeyboardEvent) => {
    const listItems = Array.from(listRef.current?.children || []);
    if (e.code === "ArrowUp") {
      e.preventDefault();
      const curItemIdx = listItems.findIndex(
        (item) => item.id === selectedItemId,
      );
      const prevEl = listItems
        .slice(0, curItemIdx)
        .reverse()
        .find((item) => item.getAttribute("role") === "option");
      focusItem(prevEl as HTMLElement);
    } else if (e.code === "ArrowDown") {
      e.preventDefault();
      const curItemIdx = listItems.findIndex(
        (item) => item.id === selectedItemId,
      );
      const nextEl = listItems
        .slice(curItemIdx + 1, listItems.length)
        .find((item) => item.getAttribute("role") === "option");
      focusItem(nextEl as HTMLElement);
    } else if (e.key === "Home") {
      const firstItem = listItems.find(
        (item) => item.getAttribute("role") === "option",
      );
      focusItem(firstItem as HTMLElement);
    } else if (e.key === "End") {
      const lastItem = listItems
        .reverse()
        .find((item) => item.getAttribute("role") === "option");
      focusItem(lastItem as HTMLElement);
    }
  }, listRef.current);

  return {
    listRef,
  };
};
