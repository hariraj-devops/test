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
import { createPortal } from "react-dom";
import { forwardRef, useCallback, useEffect, useRef, useState } from "react";
import mergeRefs from "react-merge-refs";
import clsx from "clsx";
import dialogPolyfill from "dialog-polyfill";

export type ModalContainerProps = {
  isOpen: boolean;
  open: () => void;
  close: () => void;
  children: JSX.Element | JSX.Element[];
  className?: string;
  "aria-label"?: string;

  /**
   * Close on outside click should only be used when a dialog does not
   * have a cancel/close button.
   */
  closeOnOutsideClick?: boolean;
};

export const ModalContainer = forwardRef<
  HTMLDialogElement,
  ModalContainerProps
>((props, ref) => {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { open, close, isOpen, className, closeOnOutsideClick, ...rest } =
    props;
  const dialogRef = useRef<HTMLDialogElement>(null);
  const triggerEl = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const el = dialogRef.current;

    if (!closeOnOutsideClick || !isOpen || !el) {
      return;
    }

    let pendingClickTarget: EventTarget | null = null;

    const handleMouseDown = (e: MouseEvent) => {
      if (e.target === el && e.button === 0) {
        pendingClickTarget = e.target;
      } else {
        pendingClickTarget = null;
      }
    };

    const handleMouseUp = (e: MouseEvent) => {
      if (e.target === el && e.button === 0 && pendingClickTarget === el) {
        close();
      }
      pendingClickTarget = null;
    };

    // https://www.w3.org/WAI/WCAG21/Understanding/pointer-cancellation.html
    el.addEventListener("mousedown", handleMouseDown);
    el.addEventListener("mouseup", handleMouseUp);

    return () => {
      el.removeEventListener("mousedown", handleMouseDown);
      el.removeEventListener("mouseup", handleMouseUp);
    };
  }, [close, closeOnOutsideClick, isOpen]);

  useEffect(() => {
    const dialogEl = dialogRef.current;
    if (!dialogEl) {
      return;
    }
    dialogPolyfill.registerDialog(dialogEl);
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) {
      dialogRef.current?.close();
      triggerEl.current?.focus?.();
      triggerEl.current = null;
    } else {
      triggerEl.current = document.activeElement as HTMLElement;
      dialogRef.current?.showModal();
    }
  }, [isOpen]);

  useEffect(() => {
    const dialogEl = dialogRef.current;

    if (!dialogEl) {
      return;
    }

    const closeHandler: EventListener = (event) => {
      event.preventDefault();
      close();
    };

    dialogEl.addEventListener("cancel", closeHandler);

    return () => {
      dialogEl.removeEventListener("cancel", closeHandler);
    };
  }, [close, isOpen]);

  return isOpen
    ? createPortal(
        <dialog
          className={clsx("dremio-modal-container", className)}
          ref={mergeRefs([ref, dialogRef])}
          style={{ overflow: "visible" }}
          onClick={(e) => {
            e.stopPropagation();
          }}
          {...rest}
        />,
        window.document.body,
      )
    : null;
});

export const useModalContainer = () => {
  const [isOpen, setIsOpen] = useState(false);

  return {
    open: useCallback(() => {
      setIsOpen(true);
    }, []),
    close: useCallback(() => {
      setIsOpen(false);
    }, []),
    isOpen,
  };
};
