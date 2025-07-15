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
import { type ComponentProps, type FC, type RefObject } from "react";
import clsx from "clsx";
import { Tooltip, type TooltipPlacement } from "./Tooltip.js";

type IconButtonProps = {
  className?: string;
  label: string;
  testid: string;
};

export const getIconButtonProps = (props: IconButtonProps) => ({
  "aria-label": props.label,
  className: clsx("dremio-icon-button", props.className),
  "data-testid": props.testid,
  tabIndex: 0,
});

export const IconButton: FC<
  IconButtonProps & {
    children: JSX.Element;
    ref?: RefObject<HTMLButtonElement>;
    tooltipPlacement?: TooltipPlacement;
  } & ComponentProps<"button">
> = (props) => {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { className, label, testid, tooltipPlacement, ...rest } = props;
  return (
    <Tooltip content={label} placement={tooltipPlacement || "top"}>
      <button type="button" {...rest} {...getIconButtonProps(props)} />
    </Tooltip>
  );
};
