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

import { useEffect } from "react";
import * as monaco from "monaco-editor";
import type {
  FunctionType,
  ModifiedFunction,
} from "../../../functions/Functions.type";
import {
  EXCLUDE_FROM_FUNCTIONS,
  NULL_VALUE,
  RESERVED_TYPES,
  RESERVED_WORDS,
} from "./monacoTokenUtils";
import {
  SQL_DARK_THEME,
  SQL_LIGHT_THEME,
  getSqlEditorOptions,
} from "./sqlEditorOptions";

const lang = getSqlEditorOptions().language;

export const useMonacoTokenProvider = (
  functions: FunctionType[] | ModifiedFunction[] = [],
  theme: typeof SQL_LIGHT_THEME | typeof SQL_DARK_THEME,
  getEditorInstance: () => monaco.editor.IStandaloneCodeEditor | null,
) => {
  useEffect(() => {
    if (getEditorInstance()) {
      const isLightTheme = theme == SQL_LIGHT_THEME;
      const sqlFunctionNames = functions
        .map((fn) => fn.name.toUpperCase())
        .filter(
          (fn) =>
            !EXCLUDE_FROM_FUNCTIONS.some((exclusions) => exclusions === fn),
        );
      const sqlKeywords = [...RESERVED_WORDS].filter(
        (word) =>
          !sqlFunctionNames.includes(word.toUpperCase()) &&
          ![...RESERVED_TYPES].includes(word.toUpperCase()),
      );
      DremioSQLTokenProvider.datatypes = [...RESERVED_TYPES];
      DremioSQLTokenProvider.functions = sqlFunctionNames;
      DremioSQLTokenProvider.keywords = sqlKeywords;
      DremioSQLTokenProvider.nullValue = [NULL_VALUE];

      monaco.languages.register({ id: lang });
      monaco.languages.setMonarchTokensProvider(lang, DremioSQLTokenProvider);
      monaco.languages.setLanguageConfiguration(lang, DremioSQLLanguageConfig);
      monaco.editor.defineTheme("sqlEditorTheme", {
        base: theme,
        inherit: false,
        rules: [
          ...Object.entries(DremioSQLColors[theme]).map(([key, val]: any) => ({
            token: key,
            foreground: val,
          })),
        ],
        colors: {
          "editor.background": isLightTheme ? "#FFFFFF" : "#202124",
          "editor.selectionBackground": isLightTheme ? "#B5D5FB" : "#304D6D",
          "editor.inactiveSelectionBackground": isLightTheme
            ? "#C6E9EF"
            : "#505862",
        },
      });
      monaco.editor.setTheme("sqlEditorTheme");
    }
  }, [functions, theme, getEditorInstance]);
};

// Unused -> dremio-sql language mimics Monaco's sql language. Use this to view original
export const getMonacoSql = async () => {
  const allLangs = monaco.languages.getLanguages();
  const object = await (
    allLangs.find(({ id }) => id === "sql") as any
  )?.loader?.();
  return object;
};

const DremioSQLTokenProvider: monaco.languages.IMonarchLanguage = {
  ignoreCase: true,
  defaultToken: "",
  tokenPostfix: ".dremio-sql",
  tokenizer: {
    root: [
      {
        include: "@comments",
      },
      {
        include: "@numbers",
      },
      {
        include: "@strings",
      },
      [
        /[\w@#$]+/,
        {
          cases: {
            "@keywords": { token: "keywords.$0" },
            "@datatypes": { token: "datatypes.$0" },
            "@functions": { token: "functions.$0" },
            "@nullValue": { token: "nullValue.$0" },
          },
        },
      ],
    ],
    comments: [
      [/--+.*/, "comment"],
      [
        /\/\*/,
        {
          token: "comment.quote",
          next: "@comment",
        },
      ],
      [/\/\/+.*/, "comment"],
    ],
    comment: [
      [/[^*/]+/, "comment"],
      [
        /\*\//,
        {
          token: "comment.quote",
          next: "@pop",
        },
      ],
      [/./, "comment"],
    ],
    numbers: [
      [/0[xX][0-9a-fA-F]*/, "number"],
      [/[$][+-]*\d*(\.\d*)?/, "number"],
      [/((\d+(\.\d*)?)|(\.\d+))([eE][\-+]?\d+)?/, "number"],
    ],
    strings: [
      [
        /N'/,
        {
          token: "string",
          next: "@string",
        },
      ],
      [
        /'/,
        {
          token: "string",
          next: "@string",
        },
      ],
    ],
    string: [
      [/[^']+/, "string"],
      [/''/, "string"],
      [
        /'/,
        {
          token: "string",
          next: "@pop",
        },
      ],
    ],
  },
};

const DremioSQLLanguageConfig: monaco.languages.LanguageConfiguration = {
  comments: {
    lineComment: "--",
    blockComment: ["/*", "*/"],
  },
  autoClosingPairs: [
    {
      open: "{",
      close: "}",
    },
    {
      open: "[",
      close: "]",
    },
    {
      open: "(",
      close: ")",
    },
    {
      open: '"',
      close: '"',
    },
    {
      open: "'",
      close: "'",
    },
  ],
  surroundingPairs: [
    {
      open: "{",
      close: "}",
    },
    {
      open: "[",
      close: "]",
    },
    {
      open: "(",
      close: ")",
    },
    {
      open: '"',
      close: '"',
    },
    {
      open: "'",
      close: "'",
    },
  ],
  colorizedBracketPairs: [
    ["{", "}"],
    ["[", "]"],
    ["(", ")"],
  ],
};

const DremioSQLColors = {
  vs: {
    "": "505862",
    delimiter: "505862",
    string: "077d82",
    datatypes: "850d8f",
    functions: "be4c20",
    comment: "8c6b1e",
    keywords: "0059ba",
    number: "4e50af",
    nullValue: "cf2d75",
  },
  "vs-dark": {
    "": "dfe2e7",
    delimiter: "dfe2e7",
    string: "7fdbdf",
    datatypes: "d38fcd",
    functions: "feb499",
    comment: "8c6b1e",
    keywords: "8ac3f6",
    number: "a3a8d7",
    nullValue: "cf2d75",
  },
};
