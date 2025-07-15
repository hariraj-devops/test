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
import * as React from "react";
import ReactSyntaxHighlighter from "react-syntax-highlighter";

const highlighterStyle = {
  hljs: {
    background: "inherit",
  },
};

type SyntaxHighlighterProps = {
  language: "sql" | "json";
  children: string;
  nowrap?: boolean;
  wrapLongLines?: boolean;
};

export const SyntaxHighlighter = (props: SyntaxHighlighterProps) => (
  <ReactSyntaxHighlighter
    language={props.language}
    style={highlighterStyle}
    wrapLongLines={props.wrapLongLines || false}
    className={clsx({ "syntax-highlighter--nowrap": props.nowrap })}
  >
    {props.children}
  </ReactSyntaxHighlighter>
);
