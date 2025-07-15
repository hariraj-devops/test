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
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import Color from "colorjs.io";

export default {
  title: "Tokens/Colors",
};

const semanticTokens = [
  "info",
  "success",
  "warning",
  "danger",
  "brand",
  "neutral",
];

const hues = [
  "red",
  "red-cool",
  "orange-warm",
  "orange",
  "green",
  "mint",
  "cyan",
  "blue",
  "blue-warm",
  "indigo",
  "indigo-vivid",
  "violet-warm",
  "magenta",
  "gray",
];
const grades = [25, 50, 75, 100, 150, 200, 300, 400, 500, 600, 700, 800, 900];

const getColorProperties = (
  el: HTMLElement,
): { backgroundColor: string; color: string } => {
  const appliedStyle = window.getComputedStyle(el);
  return {
    backgroundColor: appliedStyle.backgroundColor,
    color: appliedStyle.color,
  };
};

const ColorCard = (props: { token: string }) => {
  return (
    <div
      className="inline-flex flex-row gap-1 border-thin p-05 items-center"
      style={{
        "--border--color": "var(--border--neutral)",
        borderRadius: "6px",
        fontWeight: 500,
      }}
    >
      <div
        className="border-thin overflow-hidden p-05"
        style={{
          "--border--color": "var(--border--neutral)",
          borderRadius: "5px",
        }}
      >
        <div
          style={{
            width: "1em",
            height: "1em",
            background: `var(--${props.token})`,
            borderRadius: "2px",
          }}
        />
      </div>
      <div>{props.token}</div>
    </div>
  );
};

const ColorChip = ({
  family,
  grade,
}: {
  family: string;
  grade?: number | string;
}) => {
  const chipRef = useRef<HTMLElement | null>(null);
  const [chipProperties, setChipProperties] =
    useState<ReturnType<typeof getColorProperties>>();
  useLayoutEffect(() => {
    setChipProperties(getColorProperties(chipRef.current!));
  }, []);
  if (chipProperties?.backgroundColor === "rgba(0, 0, 0, 0)") {
    return null;
  }
  return (
    <div style={{ textAlign: "center" }}>
      <div
        ref={chipRef}
        className={`bg-${family}${
          grade ? `-${grade}` : ""
        } flex items-center justify-center`}
        style={{
          width: "96px",
          height: "48px",
          fontSize: "14px",
          userSelect: "all",
        }}
      >
        {grade}
      </div>
      <code
        className="font-mono dremio-typography-less-important text-sm"
        style={{ userSelect: "all" }}
      >
        {chipProperties?.backgroundColor &&
          new Color(chipProperties.backgroundColor).toString({
            format: "hex",
          })}
      </code>
    </div>
  );
};

const Bg = (props: { className: string }) => (
  <div
    className={clsx(props.className, "hover border-thin p-05")}
    style={{ borderRadius: "2px" }}
  >
    {props.className}
  </div>
);

const renderFamily = (family) => (
  <div key={family} className="mb-4">
    <h3
      className="dremio-typography-bold mb-2"
      style={{ textTransform: "capitalize", fontSize: "14px" }}
    >
      {family}
    </h3>
    <div className="flex flex-row">
      {grades.map((grade) => (
        <ColorChip grade={grade} family={family} />
      ))}
    </div>
  </div>
);

export const Colors = () => (
  <div className="dremio-layout-stack" style={{ "--space": "2em" }}>
    <div className="flex flex-col gap-105">
      <h2></h2>
      <Bg className="bg-primary" />
      <Bg className="bg-secondary" />
      <Bg className="bg-popover" />
      <Bg className="bg-brand-solid" />
      <Bg className="bg-danger-solid" />
      <Bg className="bg-disabled" />

      <Bg className="bg-categorical-0" />
      <Bg className="bg-categorical-1" />
      <Bg className="bg-categorical-2" />
      <Bg className="bg-categorical-3" />
      <Bg className="bg-categorical-4" />
      <Bg className="bg-categorical-5" />
      <Bg className="bg-categorical-6" />
      <Bg className="bg-categorical-7" />
    </div>

    <div className="dremio-prose">
      <h2 className="dremio-typography-bold" style={{ fontSize: "24px" }}>
        Semantic Color Tokens
      </h2>
      <p>
        Semantic color tokens are aliases to a palette's primitive color tokens.
        For example, <code>color-danger-500</code> would likely map to{" "}
        <code>color-red-500</code>.
      </p>
    </div>

    {semanticTokens.map(renderFamily)}

    <div className="dremio-prose">
      <h2 className="dremio-typography-bold" style={{ fontSize: "24px" }}>
        Primitive Color Tokens
      </h2>
      <p>
        Primitive color tokens have no semantic meaning within the design
        system. They are named by rounding to the nearest hue name and to the
        nearest 50/100 for perceptual lightness (1000 - perceptual lightness
        percent * 10). For example, the color <code>#43b8c9</code> has a hue of
        212 and is closest to the hue family of "cyan", and its perceptual
        lightness of 72% is closest to the grade of 300 (1000 - 70 * 10).
        Therefore, according to this scheme <code>#43b8c9</code> would be named{" "}
        <code>color-cyan-300</code>.
      </p>
      <p>
        Perceptual lightness is based on the{" "}
        <a href="https://oklch.com/" target="_blank" rel="noreferrer">
          OKLCH color space
        </a>
        .
      </p>
    </div>

    {hues.map(renderFamily)}
  </div>
);
