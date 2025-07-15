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
import { Button } from "dremio-ui-lib/components";
import { FormattedMessage } from "react-intl";

import "./BranchButton.less";

function BranchButton({
  onClick,
  text,
  iconType = "vcs/branch",
}: {
  iconType?: string;
  text?: any;
  onClick: (arg?: any) => void;
}) {
  return (
    <span className="branch-button">
      <Button variant="secondary" onClick={onClick}>
        <dremio-icon name={iconType} />
        {text || <FormattedMessage id="RepoView.CreateBranch" />}
      </Button>
    </span>
  );
}

export default BranchButton;
