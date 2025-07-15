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

import React from "react";
import { useKeyboardListener } from "./useKeyboardListener";

export const useTrapFocus = (
  objectRef: React.MutableRefObject<HTMLElement>,
  shouldTrack: boolean,
  focusSelectors?: string,
) => {
  const actionElements = Array.from(
    objectRef.current?.querySelectorAll?.(focusSelectors || `[tabindex="0"]`) ||
      [],
  ) as HTMLElement[];
  const firstEl = actionElements?.[0];
  const lastEl = actionElements?.[actionElements.length - 1];

  const keepFocusWithin = (e: React.KeyboardEvent) => {
    if (e.code === "Tab") {
      if (document.activeElement === firstEl && e.shiftKey) {
        lastEl.focus();
        e.preventDefault();
      } else if (document.activeElement === lastEl && !e.shiftKey) {
        firstEl.focus();
        e.preventDefault();
      }
    }
  };

  React.useEffect(() => {
    if (firstEl) {
      firstEl.focus();
    }
  }, [firstEl]);

  useKeyboardListener(
    keepFocusWithin,
    shouldTrack ? objectRef.current : undefined,
  );
};
