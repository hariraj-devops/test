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

import fs from "fs";
import path from "path";
import SVGSpriter from "svg-sprite";
import { generateIconManifest } from "./generateIconManifest";

const OUTPUT_PATH = "dist-icons";

const buildTheme = (theme: string) => {
  const icons = generateIconManifest().filter((icon) => icon.theme === theme);

  const spriter = new SVGSpriter({
    transform: false,
    shape: {
      id: {
        generator: (_name, file) => {
          return icons.find((icon) => icon.path === file.path).name;
        },
      },
    },
    mode: {
      symbol: {
        inline: true,
      },
    },
  });

  icons.forEach((icon) => {
    spriter.add(
      icon.path,
      icon.name + ".svg",
      fs.readFileSync(icon.path, "utf-8"),
    );
  });

  spriter.compile((_error, result) => {
    for (const mode in result) {
      for (const resource in result[mode]) {
        fs.mkdirSync(OUTPUT_PATH, {
          recursive: true,
        });
        fs.writeFileSync(
          path.join(OUTPUT_PATH, `${theme}.svg`),
          result[mode][resource].contents,
        );
      }
    }
  });
};

buildTheme("dremio");
buildTheme("dremio-dark");
