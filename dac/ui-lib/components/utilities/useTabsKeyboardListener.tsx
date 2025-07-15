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

const horizontalArrows = { nextArrow: "ArrowRight", prevArrow: "ArrowLeft" };
const verticalArrows = { nextArrow: "ArrowDown", prevArrow: "ArrowUp" };

export const tabsListener = (
  e: React.KeyboardEvent,
  orientation: "vertical" | "horizontal",
) => {
  const { nextArrow, prevArrow } =
    orientation === "horizontal" ? horizontalArrows : verticalArrows;
  if (e.code === prevArrow) {
    e.preventDefault();
    const previousEl = document.activeElement
      ?.previousElementSibling as HTMLElement;
    if (previousEl) {
      previousEl.focus();
    } else {
      const siblings = document.activeElement?.parentElement?.children;
      (siblings?.[siblings?.length - 1] as HTMLElement).focus();
    }
  } else if (e.code === nextArrow) {
    e.preventDefault();
    const nextEl = document.activeElement?.nextElementSibling as HTMLElement;
    if (nextEl) {
      nextEl.focus();
    } else {
      const siblings = document.activeElement?.parentElement?.children;
      (siblings?.[0] as HTMLElement).focus();
    }
  }
};

export const useTabsKeyboardListener = (
  orientation: "vertical" | "horizontal" = "horizontal",
) => {
  const [tabsEl, setTabsEl] = React.useState<HTMLElement | null>();

  const getTabsListener = React.useCallback(
    (e: React.KeyboardEvent) => tabsListener(e, orientation),
    [orientation],
  );

  useKeyboardListener(getTabsListener, tabsEl as HTMLElement);

  return {
    setTabsEl,
  };
};

export const withTabsKeyboardListener =
  <T,>(WrappedComponent: React.ComponentClass) =>
  (props: T) => {
    const { setTabsEl } = useTabsKeyboardListener((props as any)?.orientation);
    return <WrappedComponent {...props} setTabsEl={setTabsEl} />;
  };
