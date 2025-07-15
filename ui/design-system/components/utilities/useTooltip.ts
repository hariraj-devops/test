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
  arrow,
  flip,
  offset,
  safePolygon,
  shift,
  useFloating,
  useFocus,
  useHover,
  useInteractions,
  useRole,
  useTransitionStatus,
  type Placement,
  type UseRoleProps,
} from "@floating-ui/react";
import { useRef, useState } from "react";

const _internalUseRef = useRef;

export const useTooltip = (props: {
  delay: number;
  interactive: boolean;
  onOpen?: () => void;
  onClose?: () => void;
  placement: Placement;
  role: UseRoleProps["role"];
}) => {
  const arrowElRef = _internalUseRef(null);

  /**
   * Tracks whether the interaction hooks (hover, focus, etc.) are indicating
   * that the tooltip content should be rendered
   */
  const [open, setOpen] = useState(false);

  const floating = useFloating({
    middleware: [offset(8), flip(), shift(), arrow({ element: arrowElRef })],
    onOpenChange: (isOpen) => {
      setOpen(isOpen);

      if (!isOpen) {
        props.onClose?.();
      }

      if (isOpen) {
        props.onOpen?.();
      }
    },
    open,
    placement: props.placement,
  });

  return {
    arrowElRef,
    floating,
    interactions: useInteractions([
      useHover(floating.context, {
        handleClose: props.interactive
          ? safePolygon({
              buffer: 2,
            })
          : undefined,
        restMs: props.delay,
      }),
      useFocus(floating.context),
      useRole(floating.context, { role: props.role }),
    ]),
    open,
    setOpen,
    staticSide:
      placementSideMapping[
        floating.placement.split("-")[0] as keyof typeof placementSideMapping
      ],
    transitionStatus: useTransitionStatus(floating.context),
  } as const;
};

const placementSideMapping = {
  bottom: "top",
  left: "right",
  right: "left",
  top: "bottom",
} as const;
