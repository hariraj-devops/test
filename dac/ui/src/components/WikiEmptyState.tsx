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
import EmptyStateContainer from "#oss/pages/HomePage/components/EmptyStateContainer";
import additionalWikiControls from "@inject/shared/AdditionalWikiControls";
import { Button } from "dremio-ui-lib/components";

import * as classes from "./WikiEmptyState.module.less";

type WikiEmptyStateProps = {
  onAddWiki?: () => void;
  onAddSummary?: () => void;
};
const WikiEmptyState = (props: WikiEmptyStateProps) => {
  const { onAddWiki, onAddSummary } = props;
  const { formatMessage } = intl;

  return (
    <EmptyStateContainer icon="interface/edit-wiki" title="No.Wiki.Content">
      {onAddWiki && (
        <div className="flex">
          <Button
            className={classes["edit-wiki-content"]}
            onClick={onAddWiki}
            data-qa="edit-wiki-content"
            variant="tertiary"
          >
            {formatMessage({ id: "Edit.Wiki.Content" })}
          </Button>
          {additionalWikiControls?.()?.wikiEmptyStateExtra({
            onAddSummary,
            className: classes["edit-wiki-content"],
          })}
        </div>
      )}
    </EmptyStateContainer>
  );
};

export default WikiEmptyState;
