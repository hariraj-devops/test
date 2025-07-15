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
import { browserHistory } from "react-router";
import { useIntl } from "react-intl";
import { clsx } from "clsx";
import { Button } from "dremio-ui-lib/components";
import { getAddSourceHref, useCanAddSource } from "../../LeftTreeUtils";
import * as classes from "./AddSourceButton.module.less";
export const AddSourceButton = ({
  addBotShadow,
}: {
  addBotShadow: boolean;
}) => {
  const { formatMessage } = useIntl();
  const canAddSource = useCanAddSource();
  if (!canAddSource) return null;
  return (
    <div
      className={clsx(
        classes["add-source-container"],
        addBotShadow ? classes["add-shadow-bot"] : "",
      )}
    >
      <Button
        variant="tertiary"
        data-qa="add-source"
        onClick={() => browserHistory.push(getAddSourceHref(true))}
      >
        <dremio-icon
          name="interface/add-small"
          class={classes["add-source-icon"]}
        />
        {formatMessage({
          id: "Source.AddSource",
        })}
      </Button>
    </div>
  );
};
