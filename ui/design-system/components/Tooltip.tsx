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
import { cloneElement, type FC, type ReactNode } from "react";
import { type Placement } from "@floating-ui/react";
import { createPortal } from "react-dom";
import { useTooltip } from "./utilities/useTooltip.js";

const DEFAULT_DELAY = 240;
const DEFAULT_PLACEMENT = "bottom";

export type TooltipPlacement = Placement;

type TooltipProps = {
  children: JSX.Element;
  content: ReactNode;
  delay?: number;
  interactive?: boolean;
  onClose?: () => void;
  onOpen?: () => void;
  placement?: TooltipPlacement;
};

export const Tooltip: FC<TooltipProps> = (props) => {
  const { arrowElRef, floating, interactions, staticSide, transitionStatus } =
    useTooltip({
      delay: props.delay || DEFAULT_DELAY,
      interactive: !!props.interactive,
      onClose: props.onClose,
      onOpen: props.onOpen,
      placement: props.placement || DEFAULT_PLACEMENT,
      role: "tooltip",
    });

  return (
    <>
      {cloneElement(
        props.children,
        interactions.getReferenceProps({
          ...(props.children.props as Record<string, unknown>),
          ref: floating.refs.setReference,
        }),
      )}
      {transitionStatus.isMounted &&
        createPortal(
          <div
            ref={floating.refs.setFloating}
            style={floating.floatingStyles}
            {...interactions.getFloatingProps()}
          >
            <div
              className={clsx(
                "dremio-tooltip",
                `dremio-tooltip--${staticSide}`,
                `dremio-tooltip--${transitionStatus.status}`,
              )}
            >
              {props.content}
              <div
                className="dremio-tooltip__arrow"
                ref={arrowElRef}
                style={{
                  left: floating.middlewareData.arrow?.x,
                  top: floating.middlewareData.arrow?.y,
                  // eslint-disable-next-line perfectionist/sort-objects
                  [staticSide]:
                    "calc(var(--dremio-tooltip--arrow--size) * -0.5)",
                }}
              />
            </div>
          </div>,
          floating.refs.domReference.current?.closest("dialog") ||
            document.body,
        )}
    </>
  );
};
