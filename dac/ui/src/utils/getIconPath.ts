//
// Copyright (C) 2017-2019 Dremio Corporation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

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

import dremioLightSpritePath from "dremio-ui-lib/dist-icons/dremio.svg";
import dremioDarkSpritePath from "dremio-ui-lib/dist-icons/dremio-dark.svg";
import { getColorScheme } from "dremio-ui-common/appTheme";

export const getSpritePath = () => {
  const activeScheme = getColorScheme();
  switch (activeScheme) {
    case "dark":
      return dremioDarkSpritePath;
    default:
    case "light":
      return dremioLightSpritePath;
  }
};

export { dremioLightSpritePath, dremioDarkSpritePath };
export const iconBasePath = (colorScheme: "light" | "dark") =>
  colorScheme === "light"
    ? "/static/icons/dremio"
    : "/static/icons/dremio-dark";

type SourceTypes = "svg" | "png";
export const getSrcPath = (
  name: string,
  src: SourceTypes = "svg",
  colorScheme: "light" | "dark" = "light",
) => {
  if (src === "svg") return getIconPath(name, colorScheme);
  else return `${dremioLightSpritePath as unknown as string}/${name}.${src}`;
};

export const getIconPath = (
  name: string,
  colorScheme: "light" | "dark" = "light",
) => `${iconBasePath(colorScheme)}/${name}.svg` as const;
