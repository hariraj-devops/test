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

// @ts-check
import globals from "globals";
import pluginJs from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginReact from "eslint-plugin-react";
import pluginReactHooks from "eslint-plugin-react-hooks";
import pluginPromise from "eslint-plugin-promise";
import pluginJsxA11y from "eslint-plugin-jsx-a11y";
import eslintConfigPrettier from "eslint-config-prettier";
import * as regexpPlugin from "eslint-plugin-regexp";
import perfectionist from "eslint-plugin-perfectionist";
import reactCompiler from "eslint-plugin-react-compiler";

// https://eslint.org/docs/latest/use/configure/combine-configs#apply-a-config-array-to-a-subset-of-files
const srcConfigs = tseslint
  .config(
    pluginJs.configs.recommended,
    ...tseslint.configs.recommendedTypeChecked,
    {
      languageOptions: {
        parserOptions: {
          projectService: true,
          tsconfigRootDir: import.meta.dirname,
        },
      },
    },
    pluginReact.configs.flat["jsx-runtime"],
    {
      name: "react-compiler/recommended",
      plugins: {
        "react-compiler": reactCompiler,
      },
      rules: {
        "react-compiler/react-compiler": "error",
      },
    },
    pluginPromise.configs["flat/recommended"],
    pluginJsxA11y.flatConfigs.recommended,
    eslintConfigPrettier,
    {
      languageOptions: {
        globals: {
          ...globals.browser,
        },
      },
      plugins: {
        "react-hooks": pluginReactHooks,
        perfectionist,
      },
      rules: {
        ...pluginReactHooks.configs.recommended.rules,
        "perfectionist/sort-objects": [
          "warn",
          {
            type: "natural",
            order: "asc",
          },
        ],
      },
    },
  )
  .map((config) => ({
    ...config,
    files: ["components/**/*.{ts,tsx}"],
  }));

export default [
  ...srcConfigs,
  {
    ignores: [
      /**
       * eslint default includes all directories (eslint/defaults/files), but
       * we can disable that by excluding everything not in the src folder
       */
      "!components/**",
    ],
  },
];
