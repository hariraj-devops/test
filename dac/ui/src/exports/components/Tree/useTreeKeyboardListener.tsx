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

import {
  useRef,
  useState,
  useCallback,
  createContext,
  useContext,
} from "react";
import { useKeyboardListener } from "dremio-ui-lib/components";

export const useTreeKeyboardListener = ({
  handleFocus,
}: {
  handleFocus?: (el: HTMLElement) => void;
}) => {
  const treeRef = useRef<HTMLDivElement>();
  const isBlur = useRef(false);
  const [nodeIdx, setNodeIdx] = useState(0);

  const getNodes = () =>
    Array.from(treeRef.current?.querySelectorAll('[role="treeitem"]') || []);

  const focusFirstNode = useCallback(() => {
    if (treeRef.current) {
      (treeRef.current.firstElementChild as HTMLElement)?.focus();
      handleFocus?.(treeRef.current.firstElementChild as HTMLElement);
    }
  }, [handleFocus]);

  const focusLastNode = useCallback(() => {
    if (treeRef.current) {
      (treeRef.current.lastElementChild as HTMLElement)?.focus();
      handleFocus?.(treeRef.current.lastElementChild as HTMLElement);
    }
  }, [handleFocus]);

  const onFocus = useCallback(() => {
    const nodes = getNodes();
    if (
      treeRef.current &&
      !isBlur.current &&
      document.activeElement === treeRef.current
    ) {
      if (nodeIdx < 0 || nodeIdx >= (nodes || []).length - 1 || nodeIdx === 0) {
        focusFirstNode();
      } else {
        const node = treeRef.current.querySelectorAll('[role="treeitem"]')[
          nodeIdx
        ] as HTMLElement;
        node?.focus();
        handleFocus?.(node);
      }
    } else {
      isBlur.current = false;
    }
  }, [nodeIdx, handleFocus, focusFirstNode]);

  const onBlur = (e?: any) => {
    if (e.relatedTarget === treeRef.current) {
      isBlur.current = true;
    }
  };

  const focusNextNode = useCallback(() => {
    const activeEl = document?.activeElement as HTMLElement;
    (activeEl.nextElementSibling as HTMLElement)?.focus();
    handleFocus?.(activeEl.nextElementSibling as HTMLElement);
    setNodeIdx((idx) => idx + 1);
  }, [setNodeIdx, handleFocus]);

  const focusPreviousNode = useCallback(() => {
    const activeEl = document?.activeElement as HTMLElement;
    (activeEl.previousElementSibling as HTMLElement)?.focus();
    handleFocus?.(activeEl.previousElementSibling as HTMLElement);
    if (nodeIdx > 0) setNodeIdx((idx) => idx - 1);
  }, [handleFocus, nodeIdx]);

  const focusOnParentNode = useCallback(() => {
    const activeEl = document?.activeElement as HTMLElement;
    if (treeRef.current) {
      const nodes = getNodes().slice(0, nodeIdx).reverse();
      const node = nodes.find((el) => {
        if (!el.getAttribute("aria-owns")) return false;

        const ids = el.getAttribute("aria-owns")?.split(" ");
        return ids?.includes(activeEl.getAttribute("id") as string);
      });
      if (node) {
        (node as HTMLElement).focus?.();
        handleFocus?.(node as HTMLElement);
        setNodeIdx(
          getNodes().findIndex(
            (n) => node.getAttribute("id") === n.getAttribute("id"),
          ),
        );
      }
    }
  }, [nodeIdx, handleFocus]);

  const trackFocus = useCallback(
    (e: React.KeyboardEvent) => {
      const activeEl = document?.activeElement as HTMLElement;
      if (e.code === "ArrowLeft") {
        if (activeEl.getAttribute("aria-expanded") === "true") {
          activeEl?.click();
        } else {
          focusOnParentNode();
        }
      } else if (e.code === "ArrowRight") {
        if (activeEl.getAttribute("aria-expanded") === "true") {
          focusNextNode();
        } else {
          if (activeEl.getAttribute("role") === "treeitem") {
            activeEl?.click();
          }
        }
      } else if (e.code === "Enter") {
        activeEl?.click();
      } else if (e.code === "ArrowUp") {
        e.preventDefault();
        focusPreviousNode();
      } else if (e.code === "ArrowDown") {
        e.preventDefault();
        if (activeEl === treeRef.current) {
          onFocus();
        } else {
          focusNextNode();
        }
      } else if (e.key === "Home") {
        focusFirstNode();
      } else if (e.key === "End") {
        focusLastNode();
      }
    },
    [
      onFocus,
      focusNextNode,
      focusOnParentNode,
      focusPreviousNode,
      focusFirstNode,
      focusLastNode,
    ],
  );

  useKeyboardListener(trackFocus, treeRef.current);

  return {
    treeRef,
    onFocus,
    onBlur,
  };
};

export const FocusContext = createContext<HTMLElement | undefined>(undefined);

export const useFocusContext = () => useContext(FocusContext);
