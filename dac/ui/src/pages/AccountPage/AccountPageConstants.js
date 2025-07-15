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
import { intl } from "#oss/utils/intl";
import * as adminPaths from "dremio-ui-common/paths/admin.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";

export const accountSection = () => ({
  title: "Account.Title", //todo loc
  icon: "settings/users",
  items: [
    {
      name: "Account.GeneralInformation",
      url: adminPaths.info.link({
        projectId: getSonarContext()?.getSelectedProjectId?.(),
      }),
    }, //todo loc
  ],
});

export const accountSectionNavItems = [
  [
    "General Information",
    intl.formatMessage({ id: "Account.GeneralInformation" }),
  ],
];
