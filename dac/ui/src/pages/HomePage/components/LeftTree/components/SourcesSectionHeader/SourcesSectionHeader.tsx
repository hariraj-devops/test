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
import { useIntl } from "react-intl";
import { browserHistory } from "react-router";
import { IconButton } from "@dremio/design-system/components";
import { getAddSourceHref, useCanAddSource } from "../../LeftTreeUtils";
import * as classes from "./SourcesSectionHeader.module.less";

export const SourcesSectionHeader = () => {
  const { formatMessage } = useIntl();
  const canAddSource = useCanAddSource();
  return (
    <div className={classes["sources-title"]}>
      {formatMessage({ id: "Source.Sources" })}
      {canAddSource && (
        <IconButton
          className={classes["add-source-button"]}
          label={formatMessage({ id: "Source.AddSource" })}
          onClick={() => browserHistory.push(getAddSourceHref(true))}
          data-qa="add-sources"
          testid="add-sources"
        >
          <dremio-icon name="interface/add-small" />
        </IconButton>
      )}
    </div>
  );
};
