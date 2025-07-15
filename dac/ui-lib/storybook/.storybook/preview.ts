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

import "../../themes/utilities/reset.scss";
import "../assets/fonts/inter-ui/inter.css";
import "../assets/fonts/FiraCode/FiraCode.css";
import "../../themes/dremio/index.scss";
import {
  configureDremioIcon,
  loadSvgSprite,
} from "../../components/icon/configureDremioIcon";
import "../../themes/dremio/components/table.scss";
import dremioSpritePath from "../../dist-icons/dremio.svg";
import "./index.scss";

loadSvgSprite(dremioSpritePath)
  .then(() => configureDremioIcon())
  .catch((e) => {
    console.error(e);
  });

export const parameters = {
  darkMode: {
    classTarget: "html",
    lightClass: "dremio-light",
    darkClass: "dremio-dark",
    stylePreview: true,
  },
};
