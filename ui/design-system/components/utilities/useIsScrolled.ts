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
  useLayoutEffect,
  useRef,
  useState,
  type MutableRefObject,
} from "react";

export const useIsScrolled = () => {
  const scrollRef = useRef<HTMLElement>();
  const [isScrolledFromTop, setIsScrolledFromTop] = useState(false);
  const [isScrolledFromBottom, setIsScrolledFromBottom] = useState(false);
  const [isScrolledFromLeft, setIsScrolledFromLeft] = useState(false);
  const [isScrolledFromRight, setIsScrolledFromRight] = useState(false);

  useLayoutEffect(() => {
    const scrollEl = scrollRef.current;

    if (!scrollEl) {
      throw new Error("scrollRef is not defined");
    }

    const handleChange = () => {
      setIsScrolledFromTop(scrollEl.scrollTop !== 0);
      setIsScrolledFromBottom(
        scrollEl.scrollHeight - scrollEl.scrollTop - scrollEl.clientHeight > 1,
      );
      setIsScrolledFromLeft(scrollEl.scrollLeft !== 0);
      setIsScrolledFromRight(
        scrollEl.scrollWidth - scrollEl.scrollLeft - scrollEl.clientWidth > 1,
      );
    };

    // Run the change handler immediately on the initial render
    handleChange();

    /**
     * Create a MutationObserver to re-run handleChange any time children are modified
     * (which may cause clientWidth / clientHeight to change)
     */
    const observer = new MutationObserver(() => {
      handleChange();
    });
    observer.observe(scrollEl, {
      childList: true,
      subtree: true,
    });

    /**
     * Listen for scroll events to re-run handleChange
     */
    scrollEl.addEventListener("scroll", handleChange, {
      passive: true,
    });

    return () => {
      observer.disconnect();
      scrollEl.removeEventListener("scroll", handleChange);
    };
  }, []);

  return {
    isScrolledFromBottom,
    isScrolledFromLeft,
    isScrolledFromRight,
    isScrolledFromTop,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    scrollRef: scrollRef as MutableRefObject<any>,
  };
};
