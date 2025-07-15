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
import { cloneElement, type FC } from "react";
import { useIsScrolled } from "./utilities/useIsScrolled.js";
import { clsx } from "clsx";

export const ScrollShadow: FC<{
  children: JSX.Element;
  topShadow?: boolean;
  bottomShadow?: boolean;
}> = (props) => {
  const { isScrolledFromBottom, isScrolledFromTop, scrollRef } =
    useIsScrolled();

  return (
    <>
      {props.topShadow && (
        <div
          className={clsx("dremio-scroll-shadow dremio-scroll-shadow--top", {
            "--scrolled": isScrolledFromTop,
          })}
        />
      )}
      {cloneElement(props.children, { ref: scrollRef })}
      {props.bottomShadow && (
        <div
          className={clsx("dremio-scroll-shadow dremio-scroll-shadow--bottom", {
            "--scrolled": isScrolledFromBottom,
          })}
        />
      )}
    </>
  );
};
