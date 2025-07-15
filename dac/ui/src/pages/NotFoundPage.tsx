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

import { ErrorView } from "#oss/exports/components/ErrorViews/ErrorView";
import { SonarPage } from "#oss/exports/components/SonarPage";
import { SessionRequiredWrapper } from "#oss/SessionRequiredWrapper";
import { getIntlContext } from "dremio-ui-common/contexts/IntlContext.js";
import narwhal404 from "dremio-ui-lib/icons/dremio/narwhal/narwhal-404.svg";

export const NotFoundPage = (props: any) => {
  const { t } = getIntlContext();
  return (
    <SessionRequiredWrapper {...props}>
      <SonarPage>
        <div className="flex items-center justify-center h-full w-full">
          <ErrorView
            title={t("Common.Errors.404")}
            image={<img src={narwhal404} alt="" />}
          />
        </div>
      </SonarPage>
    </SessionRequiredWrapper>
  );
};
